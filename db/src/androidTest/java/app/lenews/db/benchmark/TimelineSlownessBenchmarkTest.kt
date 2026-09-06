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
import app.lenews.db.filters.MainFilter
import app.lenews.db.filters.OrderField
import app.lenews.db.filters.OrderType
import app.lenews.db.filters.QueryFilters
import app.lenews.db.filters.SubFilter
import app.lenews.db.queries.FeedUnreadCountQueryBuilder
import app.lenews.db.queries.ItemsQueryBuilder
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
 * warm and captures `EXPLAIN QUERY PLAN` for each, in three passes: as the
 * schema stands, then with the obvious missing indexes created **on the test
 * database only**, then with `ANALYZE` run on top of them. The third pass is
 * there because the second one changes almost nothing: without statistics the
 * planner keeps the join order it already had. The app's entities and schema
 * are untouched; the fix lands with ticket 13's schema reset.
 *
 * Two runs:
 *
 *  - `controlDatabaseOf10000Articles` — the control, always runs, about three
 *    seconds end to end. This is what the gate's G7 stage pays.
 *  - `yearSizedDatabaseOf110000Articles` — a year of one FreshRSS account at
 *    about 300 articles a day, 219 MB on disk. Skipped unless asked for; about
 *    eighteen seconds on this emulator.
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

        line("## Before the added indexes")
        line("")
        val before = measureEverything(pageOneIds, withIndexes = false)
        table(before)

        line("")
        val indexMillis = measureMillis { createIndexes() }
        line("Creating the five indexes took ${format(indexMillis)}.")
        line("")

        line("## After the added indexes")
        line("")
        val after = measureEverything(pageOneIds, withIndexes = true)
        table(after)

        line("")
        val analyzeMillis = measureMillis { writable().execSQL("ANALYZE") }
        line("Running ANALYZE took ${format(analyzeMillis)}.")
        line("")

        line("## After the added indexes and ANALYZE")
        line("")
        val analyzed = measureEverything(pageOneIds, withIndexes = true)
        table(analyzed)

        line("")
        line("## Side by side (warm median, milliseconds)")
        line("")
        line("| Query | Rows | No index | Indexes | Indexes + ANALYZE |")
        line("| --- | ---: | ---: | ---: | ---: |")
        after.indices.forEach { position ->
            val indexed = after[position]
            val plain = before.getOrNull(position)
            val plainMillis = if (plain != null && plain.name == indexed.name) {
                format(plain.warmMillis)
            } else {
                "-"
            }
            line("| ${indexed.name} | ${indexed.rows} | $plainMillis | " +
                    "${format(indexed.warmMillis)} | ${format(analyzed[position].warmMillis)} |")
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

    private fun measureEverything(pageOneIds: List<Int>, withIndexes: Boolean): List<Measurement> = buildList {
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
        add(measureTagsOfPage(pageOneIds))
        add(measureStateDelete())
        add(measureStateReinsert())

        if (withIndexes) {
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

    /** The per-article tag query the timeline runs once for every visible article. */
    private fun measureTagsOfPage(pageOneIds: List<Int>): Measurement {
        val name = "tags — selectAllByItem × ${pageOneIds.size} (one page)"
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
     * `GReaderRepository.insertItemsIds` starts by throwing away every state row
     * of the account. `ItemStateDao.deleteItemStates` is one Room call, so one
     * transaction of its own.
     */
    private fun measureStateDelete(): Measurement {
        val sql = "Delete From ItemState Where account_id = $ACCOUNT_ID"
        val plan = explain("Select * From ItemState Where account_id = $ACCOUNT_ID")

        reopen()
        val deleted = count("Select count(*) From ItemState Where account_id = $ACCOUNT_ID")
        val cold = measureMillis { writable().execSQL(sql) }
        val warm = List(3) {
            seedItemStates()
            measureMillis { writable().execSQL(sql) }
        }.sorted()[1]
        seedItemStates()

        return Measurement("sync — deleteItemStates", deleted.toInt(), cold, warm, plan, sql)
    }

    /**
     * And then puts them all back. The repository issues three separate
     * `insert(List)` calls — unread ids, read ids, leftover starred ids — with
     * no transaction around the three, so this reproduces that shape rather
     * than one tidy transaction.
     */
    private fun measureStateReinsert(): Measurement {
        val rows = itemStateRows()

        reopen()
        writable().execSQL("Delete From ItemState Where account_id = $ACCOUNT_ID")
        val cold = measureMillis { insertItemStatesAsTheSyncDoes(rows) }
        val warm = List(3) {
            writable().execSQL("Delete From ItemState Where account_id = $ACCOUNT_ID")
            measureMillis { insertItemStatesAsTheSyncDoes(rows) }
        }.sorted()[1]

        return Measurement(
            "sync — reinsert every ItemState row", rows.size, cold, warm,
            explain("Insert Into ItemState(read, starred, remote_id, account_id) Values (0, 0, 'x', $ACCOUNT_ID)"),
            "Insert Into ItemState(read, starred, remote_id, account_id) Values (?, ?, ?, ?) — " +
                    "three Room insert(List) calls, one transaction each"
        )
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
     * The obvious missing indexes, on the test database only. No `ANALYZE` is
     * run: the app never runs one either, so the planner sees the same default
     * cost estimates it sees on a phone.
     */
    private fun createIndexes() {
        val db = writable()
        listOf(
            "Create Index If Not Exists bench_Item_remote_id On Item(remote_id)",
            "Create Index If Not Exists bench_Item_pub_date On Item(pub_date)",
            "Create Index If Not Exists bench_Item_read On Item(read)",
            "Create Index If Not Exists bench_Item_feed_id_pub_date On Item(feed_id, pub_date)",
            "Create Index If Not Exists bench_ItemState_account_id On ItemState(account_id)",
        ).forEach { db.execSQL(it) }
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

private data class StateRow(
    val remoteId: String,
    val read: Boolean,
    val starred: Boolean,
    /** A starred article outside both id windows, which the sync writes last. */
    val starredOnly: Boolean,
)
