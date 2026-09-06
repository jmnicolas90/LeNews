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
import app.lenews.db.AnalyzeOnCreate
import app.lenews.db.Database
import app.lenews.db.entities.Item
import app.lenews.db.filters.MainFilter
import app.lenews.db.filters.QueryFilters
import app.lenews.db.filters.SubFilter
import app.lenews.db.queries.FeedUnreadCountQueryBuilder
import app.lenews.db.queries.ItemsQueryBuilder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

/**
 * How the timeline, the drawer counts and one sync's writes behave as articles
 * accumulate, on the article store of ticket 13. This is ticket 11's benchmark
 * re-run on the new schema.
 *
 * It is a measurement, not a test with an assertion: nothing here fails on a
 * slow number. `TimelineTimeBudgetTest` is what holds the budget. This one says
 * where the time goes, and captures `EXPLAIN QUERY PLAN` for every query.
 *
 * Two passes on the same seeded database:
 *
 *  - **as the schema stands** — the indexes Room creates and no statistics,
 *    which is what a phone has: nothing runs `ANALYZE` on a store the sync
 *    filled;
 *  - **after `PRAGMA optimize`** — what the model says the sync will run at the
 *    end of its transaction.
 *
 * Ticket 11's headline was that an index changes nothing until the planner has
 * statistics. The two passes are here to say whether that is still true now the
 * timeline query names the join order itself.
 *
 * Two runs:
 *
 *  - `controlDatabaseOf10000Articles` — the control, always runs.
 *  - `yearSizedDatabaseOf100000Articles` — the size the budget is set against.
 *    Skipped unless asked for.
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
 *     /sdcard/Android/data/app.lenews.db.test/files/timeline-slowness-100000.md
 * ```
 */
@LargeTest
class TimelineSlownessBenchmarkTest {

    @Test
    fun controlDatabaseOf10000Articles() {
        Benchmark(articleCount = 10_000).run()
    }

    @Test
    fun yearSizedDatabaseOf100000Articles() {
        Assume.assumeTrue(
            "A year-sized database is only seeded when asked for: pass " +
                    "-Pandroid.testInstrumentationRunnerArguments.lenewsBenchmark=full",
            InstrumentationRegistry.getArguments().getString(FULL_RUN_ARGUMENT) == "full"
        )

        Benchmark(articleCount = 100_000).run()
    }

    private companion object {
        const val FULL_RUN_ARGUMENT = "lenewsBenchmark"
    }
}

private const val TAG = "LeNewsBench"

private const val PAGE_SIZE = 50
private const val DEEP_OFFSET = 5_000

/** How many articles one sync brings back, new and re-delivered. */
private const val SYNC_BATCH = 1_000

private class Benchmark(private val articleCount: Int) {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseFile = File(context.filesDir, "timeline-slowness-$articleCount.db")
    private val report = StringBuilder()

    private var database: Database? = null

    private val allArticles = QueryFilters()
    private val unreadOnly = QueryFilters(showReadItems = false)
    private val oneFeed = QueryFilters(subFilter = SubFilter.FEED, feedId = 1)
    private val starred = QueryFilters(mainFilter = MainFilter.STARS)

    private val timelineAllSql = ItemsQueryBuilder.buildItemsQuery(allArticles).sql
    private val timelineUnreadSql = ItemsQueryBuilder.buildItemsQuery(unreadOnly).sql
    private val timelineFeedSql = ItemsQueryBuilder.buildItemsQuery(oneFeed).sql
    private val timelineStarredSql = ItemsQueryBuilder.buildItemsQuery(starred).sql
    private val feedUnreadCountSql = FeedUnreadCountQueryBuilder.build(MainFilter.ALL).sql

    /**
     * The history list of `docs/article-store.md` §5, as ticket 16 built it:
     * a main filter of the timeline, so it is the timeline's own query with
     * `read_at` for the filter and the order.
     */
    private val history = QueryFilters(mainFilter = MainFilter.HISTORY)

    private val historySql = ItemsQueryBuilder.buildItemsQuery(history).sql

    fun run() {
        line("# Timeline slowness — $articleCount articles, article store schema")
        line("")
        line(
            "Device: ${android.os.Build.MODEL} (${android.os.Build.SUPPORTED_ABIS.first()}), " +
                    "API ${android.os.Build.VERSION.SDK_INT}"
        )
        line("")

        deleteDatabaseFiles()
        open()

        val seedMillis = measureMillis { ArticleStoreSeeder(writable(), articleCount).seed() }
        line(
            "Seeding: ${format(seedMillis)} ms for $articleCount articles, " +
                    "${ArticleStoreSeeder.FEED_COUNT} feeds, " +
                    "${ArticleStoreSeeder.FOLDER_COUNT} folders, " +
                    "${count("Select count(*) From Article Where read = 0")} unread, " +
                    "${count("Select count(*) From Article Where starred = 1")} starred."
        )
        line("Database file after seeding: ${megabytes(databaseFile.length())} MB.")
        line("")

        line("## Pass 1 — as the schema stands, no statistics")
        line("")
        val withoutStatistics = readMeasurements()
        table(withoutStatistics)

        line("")
        val optimizeMillis = measureMillis { writable().execSQL("PRAGMA optimize") }
        line("`PRAGMA optimize` took ${format(optimizeMillis)} ms.")
        line("")

        line("## Pass 2 — after PRAGMA optimize")
        line("")
        val withStatistics = readMeasurements()
        table(withStatistics)

        line("")
        line("## Side by side (warm median, milliseconds)")
        line("")
        line("| Query | Rows | No statistics | After PRAGMA optimize |")
        line("| --- | ---: | ---: | ---: |")
        withoutStatistics.forEach { row ->
            val after = withStatistics.firstOrNull { it.name == row.name }
            line(
                "| ${row.name} | ${row.rows} | ${format(row.warmMillis)} | " +
                        "${after?.let { format(it.warmMillis) } ?: "-"} |"
            )
        }

        line("")
        line("## What a sync and a mark-all-read cost")
        line("")
        line("Measured after the two passes, because both of them change the store.")
        line("")
        table(writeMeasurements())

        line("")
        line("Database file at the end: ${megabytes(databaseFile.length())} MB.")

        close()
        // A year-sized database is a couple of hundred megabytes; leaving it on
        // the device after an opt-in run would fill the emulator's disk.
        deleteDatabaseFiles()
        writeReport()
    }

    // region measurement

    private fun readMeasurements(): List<Measurement> = buildList {
        add(measure("timeline all — Paging COUNT(*)", pagingCount(timelineAllSql)))
        add(measure("timeline all — page 1", pagingPage(timelineAllSql, 0)))
        add(measure("timeline all — page 2", pagingPage(timelineAllSql, PAGE_SIZE)))
        add(measure("timeline all — deep page (OFFSET $DEEP_OFFSET)", pagingPage(timelineAllSql, DEEP_OFFSET)))
        add(measure("timeline unread — Paging COUNT(*)", pagingCount(timelineUnreadSql)))
        add(measure("timeline unread — page 1", pagingPage(timelineUnreadSql, 0)))
        add(measure("timeline one feed — page 1", pagingPage(timelineFeedSql, 0)))
        add(measure("timeline starred — page 1", pagingPage(timelineStarredSql, 0)))
        add(measure("history — page 1", pagingPage(historySql, 0)))
        add(measure("drawer — unread count per feed", feedUnreadCountSql))
        add(measureUnreadNewCount())
    }

    private fun writeMeasurements(): List<Measurement> = buildList {
        add(measureUpsert("sync — upsert of $SYNC_BATCH re-delivered articles", newArticles = false))
        add(measureUpsert("sync — upsert of $SYNC_BATCH new articles", newArticles = true))
        addAll(measureMarkAllRead())
    }

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
        val warm = List(REPEATS) { measureMillis { drain(sql) } }.sorted()[REPEATS / 2]

        return Measurement(name, rows, cold, warm, explain(sql), sql)
    }

    /** The drawer's 24-hour count, through the real DAO, as the screen model reads it. */
    private fun measureUnreadNewCount(): Measurement {
        reopen()

        val read = { runBlocking { database!!.itemDao().selectUnreadNewItemsCount().first() } }
        val cold = measureMillis { read() }
        val warm = List(REPEATS) { measureMillis { read() } }.sorted()[REPEATS / 2]

        return Measurement(
            "drawer — unread count of the last 24 hours", 1, cold, warm,
            explain("Select count(*) From Article Where read = 0 And pub_date >= 0"),
            "ItemDao.selectUnreadNewItemsCount()"
        )
    }

    /**
     * One sync's article write, through the real DAO: `upsertArticles` with a
     * batch of ids the store already holds (which is what `ot` re-delivers on
     * every sync) and then with a batch of new ones.
     */
    private fun measureUpsert(name: String, newArticles: Boolean): Measurement {
        val dao = database!!.itemDao()
        val heldIds = firstPageOfIds(SYNC_BATCH)

        var newId = System.currentTimeMillis() / 1000L * 1_000_000L
        val batch = List(SYNC_BATCH) { index ->
            Item(
                id = if (newArticles) newId++ else heldIds[index % heldIds.size],
                title = "Article from a sync $index",
                content = "Some content",
                feedId = index % ArticleStoreSeeder.FEED_COUNT + 1,
                pubDate = LocalDateTime.now(),
                readTime = 1.5
            )
        }

        val millis = measureMillis { runBlocking { dao.upsertArticles(batch) } }

        return Measurement(
            name, batch.size, millis, millis,
            explain("Insert Or Ignore Into Article(id, title) Values (1, 'x')"),
            "ItemDao.upsertArticles(List<Item>) — insert or ignore, then update the content columns"
        )
    }

    /**
     * Mark-all-read, as `BaseRepository.setAllItemsRead` runs it: the pending
     * changes are queued for the unread articles first, then the articles are
     * marked read and stamped. The `db` module cannot depend on `app`, so the
     * two calls are made here in the same order the repository makes them.
     */
    private fun measureMarkAllRead(): List<Measurement> {
        val itemDao = database!!.itemDao()
        val pendingChangeDao = database!!.pendingChangeDao()
        val now = System.currentTimeMillis()

        val unread = count("Select count(*) From Article Where read = 0").toInt()
        val queueMillis = measureMillis { runBlocking { pendingChangeDao.queueReadForAllUnread() } }
        val markMillis = measureMillis { runBlocking { itemDao.markAllRead(now) } }
        val queued = count("Select count(*) From PendingChange").toInt()

        return listOf(
            Measurement(
                "mark all read — queue the pending changes ($unread unread)",
                queued, queueMillis, queueMillis,
                explain("Select id From Article Where read = 0"),
                "PendingChangeDao.queueReadForAllUnread()"
            ),
            Measurement(
                "mark all read — mark and stamp the articles",
                unread, markMillis, markMillis,
                explain("Select id From Article Where read = 0"),
                "ItemDao.markAllRead(now)"
            ),
        )
    }

    // endregion

    // region plumbing

    private fun pagingPage(sql: String, offset: Int) =
        "SELECT * FROM ( $sql ) LIMIT $PAGE_SIZE OFFSET $offset"

    private fun pagingCount(sql: String) = "SELECT COUNT(*) FROM ( $sql )"

    private fun firstPageOfIds(limit: Int): List<Long> {
        val ids = ArrayList<Long>(limit)
        writable().query("Select id From Article Order By pub_date DESC Limit $limit")
            .use { cursor ->
                while (cursor.moveToNext()) ids += cursor.getLong(0)
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
            .addCallback(AnalyzeOnCreate)
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

    private companion object {
        const val REPEATS = 7
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
