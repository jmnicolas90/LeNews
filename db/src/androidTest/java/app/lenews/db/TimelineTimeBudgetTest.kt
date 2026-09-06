/*
 * Copyright (C) 2026 Jean-Michel Nicolas
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package app.lenews.db

import android.content.Context
import android.database.Cursor
import android.util.Log
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import app.lenews.db.benchmark.ArticleStoreSeeder
import app.lenews.db.filters.MainFilter
import app.lenews.db.filters.QueryFilters
import app.lenews.db.filters.SubFilter
import app.lenews.db.queries.FeedUnreadCountQueryBuilder
import app.lenews.db.queries.ItemsQueryBuilder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The time budget of `docs/article-store.md` §6, on a seeded store, on a real
 * device: the first page of each timeline, the first page of the history and
 * the two drawer counts under 10 ms on a hundred thousand articles, and Paging's
 * `COUNT(*)` under 20 ms on the twenty thousand retention leaves.
 *
 * The folder timeline is measured on a folder full of articles and on a folder
 * with none: a filter written on a column of `Feed` cannot be tested before an
 * article row has been read, so the empty folder is the case that reads the
 * whole store to return nothing, and it is the one that goes red first.
 *
 * The store is seeded and then measured in both the states it can be in: with
 * the indexes the schema creates and no statistics, which is what a phone has
 * until a sync runs `PRAGMA optimize`, and again after that optimize. Both,
 * because statistics are not reliably an improvement: they made the drawer's
 * per-feed count sixty times slower until the query named the index it walks.
 * Ticket 11 found that an index changes nothing until the planner has
 * statistics; the answer here is not to hope for statistics but to shape the
 * query so the right plan is the only one. If that ever stops being true, this
 * test goes red before the app gets slow.
 *
 * Every measurement is the median of [REPEATS] warm executions, so one unlucky
 * scheduling hiccup does not fail the gate. Every number is reported when one
 * of them is over budget, because knowing which query and by how much is the
 * whole point.
 */
@LargeTest
class TimelineTimeBudgetTest {

    @Test
    fun theFirstPagesAndTheDrawerCountsAreInsideTheirBudget() {
        val store = SeededStore(FULL_STORE_ARTICLES)

        try {
            // a budget met by a page that comes back empty, or in the wrong
            // order, is a budget met by accident
            store.checkTheFirstPagesReturnWhatTheyShould()

            // Both states the store can be in: as the sync leaves it, and after
            // the `PRAGMA optimize` the model puts at the end of every sync.
            // Statistics can make the planner change its mind for the worse,
            // which is why the budget is asserted on both.
            assertInsideBudget(store.firstPagesAndDrawerCounts(), PAGE_BUDGET_MILLIS, FULL_STORE_ARTICLES)

            store.optimize()

            assertInsideBudget(store.firstPagesAndDrawerCounts(), PAGE_BUDGET_MILLIS, FULL_STORE_ARTICLES)
        } finally {
            store.close()
        }
    }

    @Test
    fun theCountPagingRunsOnEveryLoadIsInsideItsBudget() {
        val store = SeededStore(RETAINED_STORE_ARTICLES)

        try {
            val measurements = listOf(
                store.measureCount("timeline, all articles — Paging COUNT(*)", QueryFilters())
            )
            assertInsideBudget(measurements, COUNT_BUDGET_MILLIS, RETAINED_STORE_ARTICLES)

            store.optimize()

            val afterOptimize = listOf(
                store.measureCount("timeline, all articles — Paging COUNT(*)", QueryFilters())
            )
            assertInsideBudget(afterOptimize, COUNT_BUDGET_MILLIS, RETAINED_STORE_ARTICLES)
        } finally {
            store.close()
        }
    }

    /**
     * `docs/article-store.md` §4 on the store it was written for: one retention
     * pass against a server that has dropped nothing, so what goes is what the
     * horizon says goes.
     *
     * The pass is the one the sync runs, with the server's whole id list — a
     * hundred thousand ids through the temporary table — and what it leaves is
     * checked twice over: against the survivors counted before it ran, by a
     * query written as what is kept rather than as what goes, and against the
     * rules themselves, which nothing left in the store may break. Then the
     * pages and Paging's `COUNT(*)` are measured again on the store that is
     * left, because the size retention leaves is the size the app really runs
     * at and the only thing that bounds that count.
     */
    @Test
    fun oneRetentionPassLeavesWhatTheRulesSayAndTheCountIsInsideItsBudget() {
        val store = SeededStore(FULL_STORE_ARTICLES)

        try {
            val now = System.currentTimeMillis()
            val horizon = now - HORIZON_IN_MILLISECONDS

            val before = store.count("Select count(*) From Article")
            val starredBefore = store.count("Select count(*) From Article Where starred = 1")
            val unreadBefore = store.count("Select count(*) From Article Where read = 0")
            val survivorsExpected = store.count(
                "Select count(*) From Article " +
                        "Where starred = 1 Or read = 0 Or read_at >= $horizon"
            )

            // a server that still holds everything: only the horizon can drop a row
            val serverIds = store.everyArticleId()
            val (dropped, passMillis) = store.retentionPass(serverIds, now)
            val left = store.count("Select count(*) From Article")

            Log.i(
                TAG,
                "retention: %,d articles and %,d server ids in, %,d dropped in %.0f ms, %,d left"
                    .format(before, serverIds.size, dropped, passMillis, left)
            )

            assertEquals(before - dropped, left, "the delete removed rows it did not report")
            assertEquals(survivorsExpected, left, "what was left is not what the rules keep")
            assertTrue(dropped > 0, "a year of articles and the pass dropped none of them")

            // nothing the rules drop is still there
            assertEquals(
                0L,
                store.count(
                    "Select count(*) From Article " +
                            "Where starred = 0 And read = 1 And read_at < $horizon"
                ),
                "an article read past the horizon is still in the store"
            )
            assertEquals(
                starredBefore,
                store.count("Select count(*) From Article Where starred = 1"),
                "starred articles survive both rules"
            )
            assertEquals(
                unreadBefore,
                store.count("Select count(*) From Article Where read = 0"),
                "the server still holds every article, so no unread one may be dropped"
            )

            // The second pass is what every later sync pays: the same server
            // list into the temporary table, and nothing left to drop. Invariant
            // 5 at the size the store really runs at.
            val (droppedAgain, secondPassMillis) = store.retentionPass(serverIds, now)
            Log.i(
                TAG,
                "retention, second pass over the same answer: %,d dropped in %.0f ms"
                    .format(droppedAgain, secondPassMillis)
            )
            assertEquals(0L, droppedAgain, "repeating a sync dropped an article the first kept")

            val leftAsInt = left.toInt()
            store.checkTheFirstPagesReturnWhatTheyShould()
            assertInsideBudget(store.firstPagesAndDrawerCounts(), PAGE_BUDGET_MILLIS, leftAsInt)
            assertInsideBudget(
                listOf(store.measureCount("timeline, all articles — Paging COUNT(*)", QueryFilters())),
                COUNT_BUDGET_MILLIS,
                leftAsInt
            )

            // what the sync itself does next, at 4g
            store.optimize()

            assertInsideBudget(store.firstPagesAndDrawerCounts(), PAGE_BUDGET_MILLIS, leftAsInt)
            assertInsideBudget(
                listOf(store.measureCount("timeline, all articles — Paging COUNT(*)", QueryFilters())),
                COUNT_BUDGET_MILLIS,
                leftAsInt
            )
        } finally {
            store.close()
        }
    }

    private fun assertInsideBudget(
        measurements: List<Measurement>,
        budgetMillis: Double,
        articleCount: Int
    ) {
        // logged whether the budget holds or not: a number nobody can read is a
        // number nobody checks
        measurements.forEach {
            Log.i(TAG, "%,d articles | %-45s %8.2f ms (budget %.0f) | %s"
                .format(articleCount, it.name, it.millis, budgetMillis, it.plan))
        }

        val overBudget = measurements.filter { it.millis > budgetMillis }
        if (overBudget.isEmpty()) {
            return
        }

        val report = measurements.joinToString("\n") {
            "  %-45s %8.2f ms   %s".format(it.name, it.millis, it.plan)
        }
        fail(
            "On $articleCount articles, ${overBudget.size} of ${measurements.size} queries are " +
                    "over the ${budgetMillis.toInt()} ms budget:\n$report"
        )
    }

    private companion object {
        const val TAG = "LeNewsBudget"

        const val FULL_STORE_ARTICLES = 100_000
        const val RETAINED_STORE_ARTICLES = 20_000

        const val PAGE_BUDGET_MILLIS = 10.0
        const val COUNT_BUDGET_MILLIS = 20.0
    }
}

private data class Measurement(val name: String, val millis: Double, val plan: String)

/** A seeded database on disk, deleted when it is closed. */
private class SeededStore(articleCount: Int) {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = File(context.filesDir, "timeline-budget-$articleCount.db")
    private val database: Database

    init {
        deleteFiles()
        database = Room.databaseBuilder(context, Database::class.java, file.absolutePath)
            .addCallback(AnalyzeOnCreate)
            .build()

        ArticleStoreSeeder(writable(), articleCount).seed()

        // a budget met on an empty table would mean nothing
        check(count("Select count(*) From Article") == articleCount.toLong()) {
            "the store was meant to hold $articleCount articles"
        }
        check(count("Select count(*) From Article Where read = 0") > 0) {
            "the store holds no unread article, so the unread timeline measures nothing"
        }
        check(count("Select count(*) From Article Where starred = 1") > 0) {
            "the store holds no starred article, so the stars timeline measures nothing"
        }
        check(count("Select count(*) From Article Where read_at Is Not Null") > 0) {
            "the store holds no read article, so the history measures nothing"
        }

        addTheEmptyFolder()
    }

    /**
     * A folder the seeder does not fill: one feed, no article. It is the folder
     * the timeline is worst at — there is nothing to return, so nothing stops a
     * query written the wrong way from reading every article in the store to
     * find that out.
     */
    private fun addTheEmptyFolder() {
        val folderId = EMPTY_FOLDER.folderId
        val feedId = ArticleStoreSeeder.FEED_COUNT + 1

        writable().execSQL(
            "Insert Into Folder(id, name, remote_id) " +
                    "Values ($folderId, 'Empty folder', 'user/-/label/Empty folder')"
        )
        writable().execSQL(
            "Insert Into Feed(id, name, description, url, siteUrl, last_updated, color, " +
                    "icon_url, folder_id, remote_id, notification_enabled, open_in, " +
                    "open_in_ask) Values ($feedId, 'Feed $feedId', 'A feed with no article', " +
                    "'https://feed$feedId.example/rss', 'https://feed$feedId.example', '', 0, " +
                    "'https://feed$feedId.example/icon.png', $folderId, " +
                    "'feed/https://feed$feedId.example/rss', 1, 'LOCAL_VIEW', 1)"
        )
    }

    fun count(sql: String): Long {
        writable().query(sql).use { cursor ->
            cursor.moveToFirst()
            return cursor.getLong(0)
        }
    }

    /** Every id the store holds: what a server that has dropped nothing sends. */
    fun everyArticleId(): List<Long> {
        val ids = ArrayList<Long>()
        writable().query("Select id From Article").use { cursor ->
            while (cursor.moveToNext()) ids += cursor.getLong(0)
        }
        return ids
    }

    /**
     * One retention pass, run the way step 4e runs it — inside a transaction —
     * and how long it took, which is time the sync pays with the transaction
     * open.
     */
    fun retentionPass(serverIds: Collection<Long>, now: Long): Pair<Long, Double> {
        var dropped = 0
        val millis = millis {
            database.runInTransaction { dropped = database.deleteWhatRetentionDrops(serverIds, now) }
        }
        return dropped.toLong() to millis
    }

    fun close() {
        database.close()
        deleteFiles()
    }

    /** What the sync will run at the end of its transaction. */
    fun optimize() {
        writable().execSQL("PRAGMA optimize")
    }

    fun firstPagesAndDrawerCounts(): List<Measurement> = listOf(
        measurePage("timeline, all articles", QueryFilters()),
        measurePage("timeline, unread only", QueryFilters(showReadItems = false)),
        measurePage("timeline, one feed", QueryFilters(subFilter = SubFilter.FEED, feedId = 1)),
        measurePage("timeline, one folder", POPULATED_FOLDER),
        measurePage("timeline, a folder with no article", EMPTY_FOLDER),
        measurePage("timeline, starred", QueryFilters(mainFilter = MainFilter.STARS)),
        measurePage("history, first page", HISTORY),
        measure(
            "drawer, unread count per feed",
            FeedUnreadCountQueryBuilder.build(MainFilter.ALL).sql
        ),
        measureUnreadCountOfTheLast24Hours(),
    )

    /**
     * The pages the budget covers return what they are supposed to return: a
     * full page where there are articles to show, nothing at all for the empty
     * folder, and a history that really is ordered by the moment each article
     * became read, newest first.
     */
    fun checkTheFirstPagesReturnWhatTheyShould() {
        check(rowsOfFirstPage(ItemsQueryBuilder.buildItemsQuery(QueryFilters()).sql) == PAGE_SIZE) {
            "the all-articles timeline gives no full first page"
        }
        check(rowsOfFirstPage(ItemsQueryBuilder.buildItemsQuery(POPULATED_FOLDER).sql) == PAGE_SIZE) {
            "folder ${POPULATED_FOLDER.folderId} gives no full first page, so it measures nothing"
        }
        check(rowsOfFirstPage(ItemsQueryBuilder.buildItemsQuery(EMPTY_FOLDER).sql) == 0) {
            "folder ${EMPTY_FOLDER.folderId} was meant to hold no article"
        }

        val becameReadAt = ArrayList<Long>()
        writable().query(firstPageOf(ItemsQueryBuilder.buildItemsQuery(HISTORY).sql)).use { cursor ->
            val column = cursor.getColumnIndexOrThrow("read_at")
            while (cursor.moveToNext()) becameReadAt += cursor.getLong(column)
        }
        check(becameReadAt.size == PAGE_SIZE) {
            "the history gives no full first page, so its budget measures nothing"
        }
        check(becameReadAt == becameReadAt.sortedDescending()) {
            "the history is not ordered by the moment the article became read, newest first"
        }
    }

    private fun rowsOfFirstPage(sql: String): Int {
        writable().query(firstPageOf(sql)).use { cursor -> return cursor.count }
    }

    /** The first page of a timeline, wrapped the way Room's Paging source wraps it. */
    fun measurePage(name: String, filters: QueryFilters): Measurement =
        measure(name, firstPageOf(ItemsQueryBuilder.buildItemsQuery(filters).sql))

    private fun firstPageOf(sql: String) = "SELECT * FROM ( $sql ) LIMIT $PAGE_SIZE OFFSET 0"

    /** The count Room's Paging source runs on the initial load of every page source. */
    fun measureCount(name: String, filters: QueryFilters): Measurement {
        val sql = ItemsQueryBuilder.buildItemsQuery(filters).sql
        return measure(name, "SELECT COUNT(*) FROM ( $sql )")
    }

    /**
     * The drawer's 24-hour count, through the real DAO and its flow, which is
     * how the timeline screen model reads it.
     */
    fun measureUnreadCountOfTheLast24Hours(): Measurement {
        val name = "drawer, unread count of the last 24 hours"
        val read = { runBlocking { database.itemDao().selectUnreadNewItemsCount().first() } }

        read()
        val timings = List(REPEATS) { millis { read() } }.sorted()

        return Measurement(name, timings[REPEATS / 2], "ItemDao.selectUnreadNewItemsCount()")
    }

    fun measure(name: String, sql: String): Measurement {
        drain(sql)
        val timings = List(REPEATS) { millis { drain(sql) } }.sorted()

        return Measurement(name, timings[REPEATS / 2], explain(sql))
    }

    /** Reads every column of every row, which is where the work actually happens. */
    private fun drain(sql: String) {
        writable().query(sql).use { cursor ->
            val columns = cursor.columnCount
            while (cursor.moveToNext()) {
                for (column in 0 until columns) {
                    when (cursor.getType(column)) {
                        Cursor.FIELD_TYPE_STRING -> cursor.getString(column)
                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
                        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column)
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun explain(sql: String): String {
        val lines = ArrayList<String>()
        writable().query("EXPLAIN QUERY PLAN $sql").use { cursor ->
            val detail = cursor.getColumnIndexOrThrow("detail")
            while (cursor.moveToNext()) lines += cursor.getString(detail)
        }
        return lines.joinToString(" / ")
    }

    private inline fun millis(block: () -> Unit): Double {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000.0
    }

    private fun writable(): SupportSQLiteDatabase = database.openHelper.writableDatabase

    private fun deleteFiles() {
        listOf("", "-wal", "-shm").forEach { suffix -> File(file.absolutePath + suffix).delete() }
    }

    private companion object {
        const val PAGE_SIZE = 50
        const val REPEATS = 7

        /** Folder 1 holds a tenth of the feeds, so a tenth of the articles. */
        val POPULATED_FOLDER = QueryFilters(subFilter = SubFilter.FOLDER, folderId = 1)

        /** The history list: every article that became read, newest first. */
        val HISTORY = QueryFilters(mainFilter = MainFilter.HISTORY)

        /** The folder [SeededStore.addTheEmptyFolder] creates. */
        val EMPTY_FOLDER = QueryFilters(
            subFilter = SubFilter.FOLDER,
            folderId = ArticleStoreSeeder.FOLDER_COUNT + 1
        )
    }
}
