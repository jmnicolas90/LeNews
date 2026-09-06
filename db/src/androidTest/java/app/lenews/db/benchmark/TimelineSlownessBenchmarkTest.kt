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
package app.lenews.db.benchmark

import android.content.Context
import android.database.Cursor
import android.util.Log
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import app.lenews.db.Database
import app.lenews.db.entities.ItemState
import app.lenews.db.filters.MainFilter
import app.lenews.db.filters.OrderField
import app.lenews.db.filters.OrderType
import app.lenews.db.filters.QueryFilters
import app.lenews.db.filters.SubFilter
import app.lenews.db.queries.FeedUnreadCountQueryBuilder
import app.lenews.db.queries.ItemsQueryBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Test
import java.io.File
import kotlin.math.max

/**
 * Measures how the timeline, the drawer counts, one sync's state rewrite and the
 * per-article tag query behave as articles accumulate. Ticket 11.
 *
 * This is a measurement, not a test with an assertion: nothing here fails on a
 * slow number. It seeds a real on-disk Room database (the point is disk I/O and
 * the SQLite planner, so no in-memory database), times every query cold and
 * warm and captures `EXPLAIN QUERY PLAN` for each, in five passes, all of them
 * against indexes created **on the test database only**: as the schema stands;
 * with an index on `Item.remote_id` alone, without and then with `ANALYZE`,
 * which isolates the one index the drawer needs from the four that follow; then
 * with all five indexes and no statistics; then with `ANALYZE` on top. The
 * no-statistics passes are there because they change almost nothing: without
 * statistics the planner keeps the join order it already had. The app's
 * entities and schema are untouched; the fix lands with ticket 13's schema
 * reset.
 *
 * The sync, mark-all-read and tag rows run the production code rather than a
 * stand-in: the real `ItemStateDao` and `TagDao` suspend methods, called from a
 * coroutine, and a faithful copy of `GReaderRepository.insertItemsIds` (the
 * `db` module cannot depend on `app`, so the algorithm is copied). Raw
 * `executeInsert` statements are used for fixture setup only, plus one clearly
 * labelled comparison row.
 *
 * Two runs:
 *
 *  - `controlDatabaseOf10000Articles` — the control, always runs, about
 *    twenty-two seconds end to end. This is what the gate's G7 stage pays, and
 *    most of it is the sync's id mapping, which costs over a second a call
 *    whatever the size of the database.
 *  - `yearSizedDatabaseOf110000Articles` — a year of one FreshRSS account at
 *    about 300 articles a day, 219 MB on disk. Skipped unless asked for; about
 *    fifty-five seconds on this emulator.
 *
 * The full run, from the repository root, with the emulator already booted:
 *
 * ```
 * ANDROID_SERIAL=emulator-5554 ./gradlew :db:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=app.lenews.db.benchmark.TimelineSlownessBenchmarkTest \
 *     -Pandroid.testInstrumentationRunnerArguments.lenewsBenchmark=full
 * ```
 *
 * Results go to logcat under the tag `LeNewsBench`
 * (`adb -s emulator-5554 logcat -d -s LeNewsBench`) and to a Markdown file per
 * run under the test app's external files directory:
 *
 * ```
 * adb -s emulator-5554 pull \
 *     /sdcard/Android/data/app.lenews.db.test/files/timeline-slowness-110000.md
 * ```
 */
@LargeTest
class TimelineSlownessBenchmarkTest {

    @Test
    fun controlDatabaseOf10000Articles() {
        Benchmark(articleCount = 10_000).run()
    }

    @Test
    fun yearSizedDatabaseOf110000Articles() {
        Assume.assumeTrue(
            "A year-sized database is only seeded when asked for: pass " +
                    "-Pandroid.testInstrumentationRunnerArguments.lenewsBenchmark=full",
            InstrumentationRegistry.getArguments().getString(FULL_RUN_ARGUMENT) == "full"
        )

        Benchmark(articleCount = 110_000).run()
    }

    private companion object {
        const val FULL_RUN_ARGUMENT = "lenewsBenchmark"
    }
}

private const val TAG = "LeNewsBench"

/** ~100 feeds in ~10 folders, one account, as the map describes the shape. */
private const val FOLDER_COUNT = 10
private const val FEED_COUNT = 100
private const val TAG_COUNT = 10

/**
 * What one classic sync leaves in `ItemState`. The Google Reader data source
 * caps `stream/items/ids` at `MAX_ITEMS` (2500) for the reading list and
 * `MAX_STARRED_ITEMS` (1000) for the starred stream, so `ItemState` holds at
 * most 2500 unread + 2500 read + the starred ids that fall outside both —
 * about six thousand rows whatever the size of `Item`. These counts are
 * therefore the same in the control and in the year-sized run: the only thing
 * that grows between the two is the article table, which is the variable under
 * test.
 */
private const val UNREAD_STATE_ROWS = 2500
private const val READ_STATE_ROWS = 2500
private const val STARRED_STATE_ROWS = 1000

/** Article body sizes, so a row weighs what a real article weighs. */
private const val CONTENT_CHARS = 1000
private const val DESCRIPTION_CHARS = 300
private const val CLEAN_DESCRIPTION_CHARS = 250

private const val ACCOUNT_ID = 1
private const val PAGE_SIZE = 50
private const val DEEP_OFFSET = 5_000

private const val SEED_TRANSACTION_ROWS = 10_000
private const val DAY_MILLIS = 86_400_000L
private const val YEAR_DAYS = 365

/** Base of the 64-bit article id FreshRSS hands out. */
private const val REMOTE_ID_BASE = 0x0EA9B4C700000000L

private class Benchmark(private val articleCount: Int) {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseFile = File(context.filesDir, "timeline-slowness-$articleCount.db")
    private val report = StringBuilder()

    private var database: Database? = null

    private val allArticles = QueryFilters(
        accountId = ACCOUNT_ID,
        showReadItems = true,
        mainFilter = MainFilter.ALL,
        subFilter = SubFilter.ALL,
        orderField = OrderField.DATE,
        orderType = OrderType.DESC,
    )
    private val unreadOnly = allArticles.copy(showReadItems = false)

    private val timelineAllSql = ItemsQueryBuilder
        .buildItemsQuery(allArticles, separateState = true).sql
    private val timelineUnreadSql = ItemsQueryBuilder
        .buildItemsQuery(unreadOnly, separateState = true).sql
    private val feedUnreadCountSql = FeedUnreadCountQueryBuilder
        .build(ACCOUNT_ID, MainFilter.ALL, useSeparateState = true).sql

    /**
     * Copied from `ItemDao.selectUnreadNewItemsCountByItemState`, with the bound
     * account id spelled out. Room compiles that method into exactly this text;
     * if the DAO changes, change it here too.
     */
    private val unreadNewCountSql = """Select count(*) From ItemState Inner Join Item On Item.remote_id = ItemState.remote_id
        Where ItemState.read = 0 and account_id = $ACCOUNT_ID And DateTime(Round(Item.pub_date / 1000), 'unixepoch')
        Between DateTime(DateTime("now"), "-24 hour") And DateTime("now")"""

    private val tagsOfArticleSql =
        "Select Tag.* From Tag Inner Join TagJoin on Tag.id = TagJoin.tag_id Where item_id = "

    /**
     * Copied from `ItemStateDao.setAllItemsReadByUpdate` and
     * `setAllItemsReadByInsert`, with the bound account id spelled out, so their
     * plans can be captured. Room compiles those two methods into exactly this
     * text; if the DAO changes, change it here too.
     */
    private val markAllReadUpdateSql =
        """Update ItemState set read = 1 Where remote_id In (Select Item.remote_id From Item 
        Inner Join Feed On Feed.id = Item.feed_id Where account_id = $ACCOUNT_ID)"""

    private val markAllReadInsertSql =
        """Insert Or Ignore Into ItemState(read, starred, remote_id, account_id) Select 1 as read, 0 as starred, 
        Item.remote_id as remote_id, account_id From Item Inner Join Feed On Feed.id = Item.feed_id Where Feed.account_id = $ACCOUNT_ID"""

    /** How many `ItemState` rows mark-all-read left behind, set by each round. */
    private var markAllReadStateRows = 0

    fun run() {
        line("# Timeline slowness — $articleCount articles")
        line("")
        line("Device: ${android.os.Build.MODEL} (${android.os.Build.SUPPORTED_ABIS.first()}), " +
                "API ${android.os.Build.VERSION.SDK_INT}")
        line("")

        deleteDatabaseFiles()
        open()

        val seedMillis = measureMillis { seed() }
        line("Seeding: ${format(seedMillis)} for $articleCount articles, " +
                "$FEED_COUNT feeds, $FOLDER_COUNT folders, " +
                "${count("Select count(*) From ItemState")} ItemState rows, " +
                "${count("Select count(*) From TagJoin")} TagJoin rows.")
        line("Database file after seeding: ${megabytes(databaseFile.length())} MB.")
        line("")

        val pageOneIds = firstPageArticleIds()

        val passes = ArrayList<Pass>()

        line("## Pass 1 — as the schema stands, no added index")
        line("")
        passes += Pass(
            "No index",
            measureEverything(pageOneIds, hasPubDateIndex = false, includeSyncPath = true)
        )
        table(passes.last().measurements)

        line("")
        val remoteIdMillis = measureMillis { createRemoteIdIndex() }
        line("Creating Item(remote_id) took ${format(remoteIdMillis)}.")
        line("")

        line("## Pass 2 — Item(remote_id) alone, no ANALYZE")
        line("")
        passes += Pass("remote_id", measureEverything(pageOneIds, hasPubDateIndex = false))
        table(passes.last().measurements)

        line("")
        var analyzeMillis = measureMillis { writable().execSQL("ANALYZE") }
        line("Running ANALYZE took ${format(analyzeMillis)}.")
        line("")

        line("## Pass 3 — Item(remote_id) alone, after ANALYZE")
        line("")
        passes += Pass("remote_id + ANALYZE", measureEverything(pageOneIds, hasPubDateIndex = false))
        table(passes.last().measurements)

        line("")
        val forgetMillis = measureMillis { forgetStatistics() }
        val remainingMillis = measureMillis { createRemainingIndexes() }
        line("Forgetting the statistics took ${format(forgetMillis)} and creating the other " +
                "four indexes ${format(remainingMillis)}.")
        line("")

        line("## Pass 4 — all five indexes, no statistics")
        line("")
        passes += Pass("All five", measureEverything(pageOneIds, hasPubDateIndex = true))
        table(passes.last().measurements)

        line("")
        analyzeMillis = measureMillis { writable().execSQL("ANALYZE") }
        line("Running ANALYZE took ${format(analyzeMillis)}.")
        line("")

        line("## Pass 5 — all five indexes and ANALYZE")
        line("")
        passes += Pass(
            "All five + ANALYZE",
            measureEverything(pageOneIds, hasPubDateIndex = true, includeSyncPath = true)
        )
        table(passes.last().measurements)

        line("")
        line("## Side by side (warm median, milliseconds)")
        line("")
        line("| Query | Rows | " + passes.joinToString(" | ") { it.name } + " |")
        line("| --- | ---: |" + " ---: |".repeat(passes.size))
        passes.last().measurements.forEach { row ->
            val cells = passes.joinToString(" | ") { pass ->
                pass.measurements.firstOrNull { it.name == row.name }
                    ?.let { format(it.warmMillis) } ?: "-"
            }
            line("| ${row.name} | ${row.rows} | $cells |")
        }

        line("")
        line("Database file at the end: ${megabytes(databaseFile.length())} MB.")

        close()
        // A year-sized database is 219 MB; leaving it on the device after an
        // opt-in run would fill the emulator's disk over a few runs.
        deleteDatabaseFiles()
        writeReport()
    }

    // region measurement

    /**
     * [includeSyncPath] adds the three rows that cost seconds rather than
     * milliseconds — the id mapping, the mapping with the three inserts, and
     * mark-all-read with the sync that follows it. None of them is sensitive to
     * the index arrangement in the way the timeline is (the mapping is pure
     * Kotlin, and the two writes only pay index maintenance), so they run in the
     * first pass and the last one and are left out of the three in between.
     * That is what keeps the control, which the gate pays for, cheap.
     */
    private fun measureEverything(
        pageOneIds: List<Int>,
        hasPubDateIndex: Boolean,
        includeSyncPath: Boolean = false,
    ): List<Measurement> = buildList {
        add(measure("timeline all — Paging COUNT(*)", pagingCount(timelineAllSql)))
        add(measure("timeline all — page 1 (LIMIT 50 OFFSET 0)", pagingPage(timelineAllSql, 0)))
        add(measure("timeline all — page 2 (LIMIT 50 OFFSET 50)", pagingPage(timelineAllSql, PAGE_SIZE)))
        add(measure("timeline all — deep page (LIMIT 50 OFFSET 5000)", pagingPage(timelineAllSql, DEEP_OFFSET)))
        add(measure("timeline unread — Paging COUNT(*)", pagingCount(timelineUnreadSql)))
        add(measure("timeline unread — page 1 (LIMIT 50 OFFSET 0)", pagingPage(timelineUnreadSql, 0)))
        add(measure("timeline unread — page 2 (LIMIT 50 OFFSET 50)", pagingPage(timelineUnreadSql, PAGE_SIZE)))
        add(measure("timeline unread — deep page (LIMIT 50 OFFSET 5000)", pagingPage(timelineUnreadSql, DEEP_OFFSET)))
        add(measure("drawer — unread count per feed", feedUnreadCountSql))
        add(measure("drawer — unread count of the last 24 hours", unreadNewCountSql))
        add(measureTagsByDao(pageOneIds))
        add(measureTagsByRawSql(pageOneIds))
        add(measureStateDelete())
        add(measureRawStateReinsert())

        if (includeSyncPath) {
            add(measureIdMapping())
            add(measureStateInsert())
            addAll(measureMarkAllRead())
        }

        if (hasPubDateIndex) {
            // What the same page costs when the planner is made to walk the
            // pub_date index instead of sorting everything it joined. Not a
            // query the app runs: a ceiling for what a design could reach.
            add(
                measure(
                    "timeline all — page 1, forced onto the pub_date index",
                    pagingPage(forcedOntoPubDateIndex(timelineAllSql), 0)
                )
            )
            add(
                measure(
                    "timeline all — deep page, forced onto the pub_date index",
                    pagingPage(forcedOntoPubDateIndex(timelineAllSql), DEEP_OFFSET)
                )
            )
        }
    }

    /**
     * `CROSS JOIN` is how SQLite is told not to reorder the loops and
     * `INDEXED BY` names the index to walk, so `Item` becomes the outer loop,
     * read in publication order, and the top fifty come out without a sort.
     */
    private fun forcedOntoPubDateIndex(sql: String) = sql.replace(
        "FROM Item INNER JOIN Feed",
        "FROM Item INDEXED BY bench_Item_pub_date CROSS JOIN Feed"
    )

    /**
     * Cold is the first execution after the database is reopened, which drops
     * SQLite's page cache and every prepared statement. Warm is the median of
     * repeated executions on the open database. The host's file cache still
     * holds the pages either way, so cold here is optimistic against a phone.
     */
    private fun measure(name: String, sql: String): Measurement {
        reopen()

        var rows = 0
        val cold = measureMillis { rows = drain(sql) }
        val repeats = if (cold > 500) 3 else 7
        val warm = List(repeats) { measureMillis { drain(sql) } }.sorted()[repeats / 2]

        return Measurement(name, rows, cold, warm, explain(sql), sql)
    }

    /**
     * The per-article tag query as the timeline actually runs it.
     * `TimelineScreenModel.buildPager()` maps every page of `PagingData` through
     * `database.tagDao().selectAllByItem(item.id)` — a suspend Room call per
     * article, from a coroutine, which means Room's transaction executor, a
     * generated `Tag` materialisation and a coroutine resumption each time. That
     * is what is measured here: fifty real DAO calls, not fifty cursors.
     */
    private fun measureTagsByDao(pageOneIds: List<Int>): Measurement {
        val name = "tags — TagDao.selectAllByItem × ${pageOneIds.size} (suspend, as the screen model)"
        reopen()

        var rows = 0
        val cold = measureMillis {
            rows = runBlocking { pageOneIds.sumOf { database!!.tagDao().selectAllByItem(it).size } }
        }
        val warm = List(7) {
            measureMillis {
                runBlocking { pageOneIds.forEach { id -> database!!.tagDao().selectAllByItem(id) } }
            }
        }.sorted()[3]

        return Measurement(
            name, rows, cold, warm,
            explain(tagsOfArticleSql + pageOneIds.first()),
            "TagDao.selectAllByItem(id) × ${pageOneIds.size}, awaited one after another"
        )
    }

    /**
     * The same fifty queries as raw SQL on the open connection, with no Room and
     * no coroutine around them. The gap between this row and the one above is
     * what Room costs on this pattern, and it is why the two are kept apart.
     */
    private fun measureTagsByRawSql(pageOneIds: List<Int>): Measurement {
        val name = "tags — the same ${pageOneIds.size} queries as raw SQL"
        reopen()

        var rows = 0
        val cold = measureMillis { rows = pageOneIds.sumOf { drain(tagsOfArticleSql + it) } }
        val warm = List(7) {
            measureMillis { pageOneIds.forEach { id -> drain(tagsOfArticleSql + id) } }
        }.sorted()[3]

        return Measurement(
            name, rows, cold, warm,
            explain(tagsOfArticleSql + pageOneIds.first()),
            tagsOfArticleSql + "<article id>"
        )
    }

    /**
     * The first statement of `GReaderRepository.insertItemsIds`: every state row
     * of the account is thrown away. `ItemStateDao.deleteItemStates` is a real
     * suspend Room call here, one transaction of its own, exactly as the sync
     * issues it.
     */
    private fun measureStateDelete(): Measurement {
        val sql = "Delete From ItemState Where account_id = $ACCOUNT_ID"
        val plan = explain("Select * From ItemState Where account_id = $ACCOUNT_ID")

        reopen()
        val deleted = count("Select count(*) From ItemState Where account_id = $ACCOUNT_ID")
        val cold = measureMillis { runBlocking { database!!.itemStateDao().deleteItemStates(ACCOUNT_ID) } }
        val warm = List(3) {
            seedItemStates()
            measureMillis { runBlocking { database!!.itemStateDao().deleteItemStates(ACCOUNT_ID) } }
        }.sorted()[1]
        seedItemStates()

        return Measurement(
            "sync — deleteItemStates", deleted.toInt(), cold, warm, plan,
            "ItemStateDao.deleteItemStates(accountId) — $sql"
        )
    }

    /**
     * The id mapping alone, with no database in it: the `starredIds.any` scan
     * and the `starredIds.remove` that `insertItemsIds` runs over every unread
     * and every read id. It is a linear scan of the starred list per id, so it
     * is quadratic in the two capped lists, and it is production code rather
     * than a detail of this benchmark — hence a row of its own.
     */
    private fun measureIdMapping(): Measurement {
        reopen()
        val cold = measureMillis { mapItemStatesAsTheRepositoryDoes(syncIdLists()) }
        val warm = repeated(cold) { measureMillis { mapItemStatesAsTheRepositoryDoes(syncIdLists()) } }
        val mapped = mapItemStatesAsTheRepositoryDoes(syncIdLists()).sumOf { it.size }

        return Measurement(
            "sync — insertItemsIds id mapping, no database", mapped, cold, warm,
            "no plan (Kotlin, not SQL)",
            "unreadIds.map { starredIds.any { … }; starredIds.remove(it) } and the same over readIds"
        )
    }

    /**
     * The rest of `insertItemsIds`: the mapping above followed by the three
     * `ItemStateDao.insert(List)` calls — unread ids, read ids, leftover starred
     * ids — with no transaction around the three. Room's generated insert
     * adapter binds and executes one statement per row inside a transaction per
     * call, and that per-row work is the thing the previous round's raw
     * `executeInsert` row left out.
     */
    private fun measureStateInsert(): Measurement {
        reopen()
        val rows = itemStateRows().size

        runBlocking { database!!.itemStateDao().deleteItemStates(ACCOUNT_ID) }
        val cold = measureMillis { runBlocking { insertStateRowsAsTheRepositoryDoes(syncIdLists()) } }
        val warm = repeated(cold) {
            runBlocking { database!!.itemStateDao().deleteItemStates(ACCOUNT_ID) }
            measureMillis { runBlocking { insertStateRowsAsTheRepositoryDoes(syncIdLists()) } }
        }

        return Measurement(
            "sync — insertItemsIds mapping and the three ItemStateDao.insert calls", rows, cold, warm,
            explain("Insert Into ItemState(read, starred, remote_id, account_id) Values (0, 0, 'x', $ACCOUNT_ID)"),
            "ItemStateDao.insert(List<ItemState>) × 3, one Room transaction each, " +
                    "over the ids mapped as GReaderRepository.insertItemsIds maps them"
        )
    }

    /**
     * The same rows put back with compiled statements and `executeInsert`, which
     * is what the fixture seeding uses. **Not the production path** — it is kept
     * as one labelled row because it is what the first round of this ticket
     * measured, and the difference between the two is what Room costs.
     */
    private fun measureRawStateReinsert(): Measurement {
        val rows = itemStateRows()

        reopen()
        writable().execSQL("Delete From ItemState Where account_id = $ACCOUNT_ID")
        val cold = measureMillis { insertItemStatesAsTheSyncDoes(rows) }
        val warm = List(3) {
            writable().execSQL("Delete From ItemState Where account_id = $ACCOUNT_ID")
            measureMillis { insertItemStatesAsTheSyncDoes(rows) }
        }.sorted()[1]

        return Measurement(
            "sync — the same rows by raw executeInsert (not the production path)",
            rows.size, cold, warm,
            explain("Insert Into ItemState(read, starred, remote_id, account_id) Values (0, 0, 'x', $ACCOUNT_ID)"),
            "Insert Into ItemState(read, starred, remote_id, account_id) Values (?, ?, ?, ?) — " +
                    "compiled statement, three transactions"
        )
    }

    /**
     * Mark-all-read, and the sync that follows it. This is where the API caps
     * stop bounding `ItemState`.
     *
     * The FAB in `TimelineTab` calls `TimelineScreenModel.setAllItemsRead()`,
     * which for a FreshRSS account reaches `Repository.setAllItemsRead()` and so
     * `ItemStateDao.setAllItemsRead(accountId)`: an update of every state row
     * that exists, then `setAllItemsReadByInsert`, an
     * `Insert Or Ignore … Select` that writes a state row for **every stored
     * article**. `ItemState` stops being a few thousand rows and becomes as
     * large as `Item`; the next sync's `deleteItemStates` then deletes all of
     * them and puts the few thousand capped ones back.
     *
     * Each round restores the fixture, so the pass that follows sees the same
     * database it would have seen without this measurement.
     */
    private fun measureMarkAllRead(): List<Measurement> {
        reopen()
        val before = count("Select count(*) From ItemState Where account_id = $ACCOUNT_ID").toInt()

        val cold = markAllReadRound()
        val rounds = List(if (cold.sum() > 500) 1 else 3) { markAllReadRound() }
        val after = markAllReadStateRows

        val markPlan = explain(markAllReadUpdateSql) + " // " + explain(markAllReadInsertSql)
        val warm = { position: Int -> rounds.map { it[position] }.sorted()[rounds.size / 2] }

        return listOf(
            Measurement(
                "mark all read — ItemStateDao.setAllItemsRead ($before rows before, $after after)",
                after, cold[0], warm(0), markPlan,
                "ItemStateDao.setAllItemsRead(accountId) — setAllItemsReadByUpdate then " +
                        "setAllItemsReadByInsert, one Room transaction"
            ),
            Measurement(
                "after mark all read — sync deleteItemStates", after, cold[1], warm(1),
                explain("Select * From ItemState Where account_id = $ACCOUNT_ID"),
                "ItemStateDao.deleteItemStates(accountId) over $after rows"
            ),
            Measurement(
                "after mark all read — sync insertItemsIds inserts", before, cold[2], warm(2),
                explain("Insert Into ItemState(read, starred, remote_id, account_id) Values (0, 0, 'x', $ACCOUNT_ID)"),
                "ItemStateDao.insert(List<ItemState>) × 3 — back to the capped $before rows"
            ),
        )
    }

    /** Milliseconds for mark-all-read, the delete that follows, and the reinsert. */
    private fun markAllReadRound(): DoubleArray {
        val dao = database!!.itemStateDao()
        val ids = syncIdLists()

        val mark = measureMillis { runBlocking { dao.setAllItemsRead(ACCOUNT_ID) } }
        markAllReadStateRows = count("Select count(*) From ItemState Where account_id = $ACCOUNT_ID").toInt()
        val delete = measureMillis { runBlocking { dao.deleteItemStates(ACCOUNT_ID) } }
        val insert = measureMillis { runBlocking { insertStateRowsAsTheRepositoryDoes(ids) } }

        return doubleArrayOf(mark, delete, insert)
    }

    /**
     * The three id lists `insertItemsIds` is called with, in the shapes the
     * Google Reader data source hands them over: the capped unread ids, the
     * capped read ids, and every starred id as a `MutableList` the mapping is
     * free to remove from.
     */
    private fun syncIdLists(): SyncIds {
        val rows = itemStateRows()
        return SyncIds(
            unreadIds = rows.filter { !it.read }.map { it.remoteId },
            readIds = rows.filter { it.read && !it.starredOnly }.map { it.remoteId },
            starredIds = rows.filter { it.starred }.map { it.remoteId }.toMutableList(),
        )
    }

    /**
     * The mapping half of `GReaderRepository.insertItemsIds`, copied faithfully
     * from `app/src/main/java/app/lenews/repositories/GReaderRepository.kt`
     * (lines 203-253) at commit `96f4c9e0`. The `db` module cannot depend on
     * `app`, so the algorithm is copied rather than called; the linear
     * `starredIds.any` scan and the `starredIds.remove` are the production code,
     * not a shortcut taken here. **If that method changes, change this.**
     */
    private fun mapItemStatesAsTheRepositoryDoes(ids: SyncIds): List<List<ItemState>> {
        val starredIds = ids.starredIds

        val unread = ids.unreadIds.map { id ->
            val starred = starredIds.any { starredId -> starredId == id }

            if (starred) {
                starredIds.remove(id)
            }

            ItemState(id = 0, read = false, starred = starred, remoteId = id, accountId = ACCOUNT_ID)
        }

        val read = ids.readIds.map { id ->
            val starred = starredIds.any { starredId -> starredId == id }
            if (starred) {
                starredIds.remove(id)
            }

            ItemState(id = 0, read = true, starred = starred, remoteId = id, accountId = ACCOUNT_ID)
        }

        // insert starred items ids which are read
        val starredOnly = starredIds.map { id ->
            ItemState(0, read = true, starred = true, remoteId = id, accountId = ACCOUNT_ID)
        }

        return listOf(unread, read, starredOnly)
    }

    /**
     * `insertItemsIds` without its opening `deleteItemStates`, which is measured
     * as a row of its own so the two halves are visible. The three
     * `insert(List)` calls are the real DAO, with no transaction around them —
     * the repository has none either.
     */
    private suspend fun insertStateRowsAsTheRepositoryDoes(ids: SyncIds) {
        val dao = database!!.itemStateDao()
        val (unread, read, starredOnly) = mapItemStatesAsTheRepositoryDoes(ids)

        dao.insert(unread)
        dao.insert(read)
        if (starredOnly.isNotEmpty()) {
            dao.insert(starredOnly)
        }
    }

    private fun insertItemStatesAsTheSyncDoes(rows: List<StateRow>) {
        val unread = rows.filter { !it.read }
        val read = rows.filter { it.read && !it.starredOnly }
        val starredOnly = rows.filter { it.starredOnly }

        listOf(unread, read, starredOnly).forEach { batch ->
            if (batch.isNotEmpty()) insertItemStateBatch(batch)
        }
    }

    private fun insertItemStateBatch(batch: List<StateRow>) {
        val db = writable()
        db.beginTransaction()
        try {
            db.compileStatement(
                "Insert Into ItemState(read, starred, remote_id, account_id) Values (?, ?, ?, ?)"
            ).use { statement ->
                batch.forEach { row ->
                    statement.bindLong(1, if (row.read) 1 else 0)
                    statement.bindLong(2, if (row.starred) 1 else 0)
                    statement.bindString(3, row.remoteId)
                    statement.bindLong(4, ACCOUNT_ID.toLong())
                    statement.executeInsert()
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // endregion

    // region seeding

    private fun seed() {
        val db = writable()

        db.execSQL(
            "Insert Into Account(id, url, name, displayed_name, type, last_modified, " +
                    "current_account, token, write_token, notifications_enabled) " +
                    "Values ($ACCOUNT_ID, 'https://rss.example', 'FreshRSS', 'Bench', " +
                    "'FRESHRSS', 0, 1, 't', 'w', 0)"
        )

        db.beginTransaction()
        try {
            repeat(FOLDER_COUNT) { index ->
                db.execSQL(
                    "Insert Into Folder(id, name, remoteId, account_id) " +
                            "Values (${index + 1}, 'Folder ${index + 1}', " +
                            "'user/-/label/Folder ${index + 1}', $ACCOUNT_ID)"
                )
            }
            repeat(FEED_COUNT) { index ->
                db.execSQL(
                    "Insert Into Feed(id, name, description, url, siteUrl, last_updated, color, " +
                            "icon_url, folder_id, remote_id, account_id, notification_enabled, " +
                            "open_in, open_in_ask) Values (${index + 1}, 'Feed ${index + 1}', " +
                            "'A feed', 'https://feed${index + 1}.example/rss', " +
                            "'https://feed${index + 1}.example', '', 0, " +
                            "'https://feed${index + 1}.example/icon.png', " +
                            "${index % FOLDER_COUNT + 1}, 'feed/https://feed${index + 1}.example/rss', " +
                            "$ACCOUNT_ID, 1, 'LOCAL_VIEW', 1)"
                )
            }
            repeat(TAG_COUNT) { index ->
                db.execSQL(
                    "Insert Into Tag(id, name, remote_id, account_id) " +
                            "Values (${index + 1}, 'Tag ${index + 1}', " +
                            "'user/-/label/Tag ${index + 1}', $ACCOUNT_ID)"
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        seedArticles()
        seedTagJoins()
        seedItemStates()
    }

    /**
     * Articles are inserted oldest first, which is the order a year of daily
     * syncs leaves them in, with a few days of jitter on the publication date so
     * the row order is not a perfect stand-in for the date order.
     */
    private fun seedArticles() {
        val db = writable()
        val content = filler('c', CONTENT_CHARS)
        val description = filler('d', DESCRIPTION_CHARS)
        val cleanDescription = filler('t', CLEAN_DESCRIPTION_CHARS)
        val oldest = System.currentTimeMillis() - YEAR_DAYS * DAY_MILLIS
        val step = (YEAR_DAYS * DAY_MILLIS) / articleCount
        val jitter = kotlin.random.Random(20260906)

        var inserted = 0
        while (inserted < articleCount) {
            val last = minOf(inserted + SEED_TRANSACTION_ROWS, articleCount)
            db.beginTransaction()
            try {
                db.compileStatement(
                    "Insert Into Item(id, title, description, clean_description, link, image_link, " +
                            "author, pub_date, content, feed_id, read_time, read, starred, remote_id) " +
                            "Values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?)"
                ).use { statement ->
                    for (index in inserted until last) {
                        val id = (index + 1).toLong()
                        statement.bindLong(1, id)
                        statement.bindString(2, "Article $index about something that happened")
                        statement.bindString(3, description)
                        statement.bindString(4, cleanDescription)
                        statement.bindString(5, "https://feed${index % FEED_COUNT + 1}.example/$index")
                        statement.bindString(6, "https://feed${index % FEED_COUNT + 1}.example/$index.jpg")
                        statement.bindString(7, "Author ${index % 40}")
                        statement.bindLong(
                            8,
                            oldest + index * step + jitter.nextLong(-3 * DAY_MILLIS, 3 * DAY_MILLIS)
                        )
                        statement.bindString(9, content)
                        statement.bindLong(10, (index % FEED_COUNT + 1).toLong())
                        statement.bindDouble(11, 1.5)
                        statement.bindString(12, remoteId(index))
                        statement.executeInsert()
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            inserted = last
        }
    }

    /** One article in five carries two tags. */
    private fun seedTagJoins() {
        val db = writable()
        var index = 0
        while (index < articleCount) {
            val last = minOf(index + SEED_TRANSACTION_ROWS, articleCount)
            db.beginTransaction()
            try {
                db.compileStatement("Insert Into TagJoin(tag_id, item_id) Values (?, ?)")
                    .use { statement ->
                        for (article in index until last) {
                            if (article % 5 != 0) continue
                            val first = (article / 5) % TAG_COUNT + 1
                            val second = (article / 5 + 3) % TAG_COUNT + 1
                            listOf(first, second).forEach { tag ->
                                statement.bindLong(1, tag.toLong())
                                statement.bindLong(2, (article + 1).toLong())
                                statement.executeInsert()
                            }
                        }
                    }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            index = last
        }
    }

    private fun seedItemStates() {
        writable().execSQL("Delete From ItemState Where account_id = $ACCOUNT_ID")
        insertItemStatesAsTheSyncDoes(itemStateRows())
    }

    /**
     * The rows one classic sync leaves behind: the newest [UNREAD_STATE_ROWS]
     * articles unread, the [READ_STATE_ROWS] just before them read, and
     * [STARRED_STATE_ROWS] starred articles spread over the whole year, the ones
     * outside both windows arriving as read-and-starred rows exactly as
     * `insertItemsIds` writes them.
     */
    private fun itemStateRows(): List<StateRow> {
        val unreadFrom = max(0, articleCount - UNREAD_STATE_ROWS)
        val readFrom = max(0, articleCount - UNREAD_STATE_ROWS - READ_STATE_ROWS)
        val starredStride = max(1, articleCount / STARRED_STATE_ROWS)
        val starred = (0 until articleCount step starredStride).toHashSet()

        val rows = ArrayList<StateRow>()
        for (index in unreadFrom until articleCount) {
            rows += StateRow(remoteId(index), read = false, starred = index in starred, starredOnly = false)
        }
        for (index in readFrom until unreadFrom) {
            rows += StateRow(remoteId(index), read = true, starred = index in starred, starredOnly = false)
        }
        for (index in starred) {
            if (index < readFrom) {
                rows += StateRow(remoteId(index), read = true, starred = true, starredOnly = true)
            }
        }
        return rows
    }

    /** `tag:google.com,2005:reader/item/<16 hex digits>`, the form the tree stores. */
    private fun remoteId(index: Int): String =
        "tag:google.com,2005:reader/item/%016x".format(REMOTE_ID_BASE + index)

    private fun filler(letter: Char, length: Int): String = buildString(length) {
        while (this.length < length) {
            append(letter).append("orem ipsum dolor sit amet consectetur adipiscing elit ")
        }
        setLength(length)
    }

    // endregion

    // region indexes

    /**
     * The one index the two drawer counts need, on the test database only, and
     * nothing else. It is created on its own because the first round of this
     * ticket created all five at once and then credited the unread timeline's
     * `SEARCH Item USING bench_Item_remote_id` to the whole set: this index has
     * to be measured alone before that claim can be made.
     */
    private fun createRemoteIdIndex() {
        writable().execSQL("Create Index If Not Exists bench_Item_remote_id On Item(remote_id)")
    }

    /** The other four obvious missing indexes, on the test database only. */
    private fun createRemainingIndexes() {
        val db = writable()
        listOf(
            "Create Index If Not Exists bench_Item_pub_date On Item(pub_date)",
            "Create Index If Not Exists bench_Item_read On Item(read)",
            "Create Index If Not Exists bench_Item_feed_id_pub_date On Item(feed_id, pub_date)",
            "Create Index If Not Exists bench_ItemState_account_id On ItemState(account_id)",
        ).forEach { db.execSQL(it) }
    }

    /**
     * `ANALYZE` writes `sqlite_stat1`, and the planner keeps reading it for as
     * long as it has rows. Emptying it puts the planner back on its default
     * estimates, which is the state a phone is in: the app never runs `ANALYZE`.
     * Without this, the "all five indexes, no statistics" pass would silently
     * inherit the statistics of the pass before it.
     */
    private fun forgetStatistics() {
        val db = writable()
        db.execSQL("Delete From sqlite_stat1")
        db.execSQL("Analyze sqlite_master")
    }

    // endregion

    // region plumbing

    private fun pagingPage(sql: String, offset: Int) =
        "SELECT * FROM ( $sql ) LIMIT $PAGE_SIZE OFFSET $offset"

    private fun pagingCount(sql: String) = "SELECT COUNT(*) FROM ( $sql )"

    private fun firstPageArticleIds(): List<Int> {
        val ids = ArrayList<Int>()
        writable().query(pagingPage(timelineAllSql, 0)).use { cursor ->
            val column = cursor.getColumnIndexOrThrow("id")
            while (cursor.moveToNext()) ids += cursor.getInt(column)
        }
        return ids
    }

    /** Reads every column of every row, which is where the work actually happens. */
    private fun drain(sql: String): Int {
        var rows = 0
        writable().query(sql).use { cursor ->
            val columns = cursor.columnCount
            while (cursor.moveToNext()) {
                rows++
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
        return rows
    }

    private fun count(sql: String): Long {
        writable().query(sql).use { cursor ->
            cursor.moveToFirst()
            return cursor.getLong(0)
        }
    }

    private fun explain(sql: String): String {
        val lines = ArrayList<String>()
        try {
            writable().query("EXPLAIN QUERY PLAN $sql").use { cursor ->
                val detail = cursor.getColumnIndexOrThrow("detail")
                while (cursor.moveToNext()) lines += cursor.getString(detail)
            }
        } catch (e: RuntimeException) {
            return "no plan (${e.javaClass.simpleName})"
        }
        return if (lines.isEmpty()) "no plan" else lines.joinToString(" / ")
    }

    private fun open() {
        database = Room.databaseBuilder(context, Database::class.java, databaseFile.absolutePath)
            .build()
        writable()
    }

    private fun close() {
        database?.close()
        database = null
    }

    private fun reopen() {
        close()
        open()
    }

    private fun writable(): SupportSQLiteDatabase = database!!.openHelper.writableDatabase

    private fun deleteDatabaseFiles() {
        listOf("", "-wal", "-shm").forEach { suffix ->
            File(databaseFile.absolutePath + suffix).delete()
        }
    }

    private inline fun measureMillis(block: () -> Unit): Double {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000.0
    }

    private fun table(measurements: List<Measurement>) {
        line("| Query | Rows | Cold (ms) | Warm median (ms) | Plan |")
        line("| --- | ---: | ---: | ---: | --- |")
        measurements.forEach {
            line("| ${it.name} | ${it.rows} | ${format(it.coldMillis)} | ${format(it.warmMillis)} | ${it.plan} |")
        }
        line("")
        line("<details><summary>SQL</summary>")
        line("")
        measurements.forEach {
            line("- **${it.name}**")
            line("  ```")
            it.sql.lines().forEach { sqlLine -> line("  $sqlLine") }
            line("  ```")
        }
        line("</details>")
    }

    /**
     * The warm median. A measurement that already took more than half a second
     * cold is repeated once rather than three times: the expensive rows here are
     * stable to well under a percent, and repeating them seven times would put
     * the control over a minute for no extra information.
     */
    private inline fun repeated(coldMillis: Double, block: () -> Double): Double {
        val repeats = if (coldMillis > 500) 1 else 3
        return List(repeats) { block() }.sorted()[repeats / 2]
    }

    private fun format(millis: Double) = "%.1f".format(millis)

    private fun megabytes(bytes: Long) = "%.0f".format(bytes / 1024.0 / 1024.0)

    private fun line(text: String) {
        Log.i(TAG, text)
        report.append(text).append('\n')
    }

    private fun writeReport() {
        val directory = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(directory, "timeline-slowness-$articleCount.md")
        file.writeText(report.toString())
        Log.i(TAG, "Report written to ${file.absolutePath}")
    }

    // endregion
}

private data class Measurement(
    val name: String,
    val rows: Int,
    val coldMillis: Double,
    val warmMillis: Double,
    val plan: String,
    val sql: String,
)

/** One pass of every measurement, under one index and statistics arrangement. */
private data class Pass(
    val name: String,
    val measurements: List<Measurement>,
)

/** The three lists `GReaderRepository.insertItemsIds` takes. */
private data class SyncIds(
    val unreadIds: List<String>,
    val readIds: List<String>,
    val starredIds: MutableList<String>,
)

private data class StateRow(
    val remoteId: String,
    val read: Boolean,
    val starred: Boolean,
    /** A starred article outside both id windows, which the sync writes last. */
    val starredOnly: Boolean,
)
