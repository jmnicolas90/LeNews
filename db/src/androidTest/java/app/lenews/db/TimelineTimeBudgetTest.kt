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
import kotlin.test.fail

/**
 * The time budget of `docs/article-store.md` §6, on a seeded store, on a real
 * device: the first page of each timeline and the two drawer counts under 10 ms
 * on a hundred thousand articles, and Paging's `COUNT(*)` under 20 ms on the
 * twenty thousand retention leaves.
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
    }

    private fun count(sql: String): Long {
        writable().query(sql).use { cursor ->
            cursor.moveToFirst()
            return cursor.getLong(0)
        }
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
        measurePage("timeline, starred", QueryFilters(mainFilter = MainFilter.STARS)),
        measure(
            "drawer, unread count per feed",
            FeedUnreadCountQueryBuilder.build(MainFilter.ALL).sql
        ),
        measureUnreadCountOfTheLast24Hours(),
    )

    /** The first page of a timeline, wrapped the way Room's Paging source wraps it. */
    fun measurePage(name: String, filters: QueryFilters): Measurement {
        val sql = ItemsQueryBuilder.buildItemsQuery(filters).sql
        return measure(name, "SELECT * FROM ( $sql ) LIMIT $PAGE_SIZE OFFSET 0")
    }

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
    }
}
