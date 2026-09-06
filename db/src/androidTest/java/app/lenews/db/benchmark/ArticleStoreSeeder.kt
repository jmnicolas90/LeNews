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

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Fills a database with a store the size a heavy reader ends up with: one
 * account, ten folders, a hundred feeds and a year of articles, the newest
 * [UNREAD_ARTICLES] of them unread and [STARRED_ARTICLES] starred, spread over
 * the whole year.
 *
 * Adapted from ticket 11's benchmark seeder to the article store of ticket 13:
 * read and starred state are columns of the article row now, so there is no
 * state table to fill and no tags to join. A read article carries a `read_at`,
 * which is the invariant the model states — `read = 1` if and only if `read_at`
 * is not null.
 *
 * Rows go in through compiled statements in transactions of
 * [SEED_TRANSACTION_ROWS], which is why a year of articles seeds in a couple of
 * seconds. This is fixture setup, not a measured write.
 */
class ArticleStoreSeeder(
    private val database: SupportSQLiteDatabase,
    private val articleCount: Int
) {

    fun seed() {
        seedAccountFoldersAndFeeds()
        seedArticles()
    }

    private fun seedAccountFoldersAndFeeds() {
        database.execSQL(
            "Insert Into Account(id, url, name, displayed_name, cursor, token, " +
                    "write_token, notifications_enabled) " +
                    "Values (1, 'https://rss.example', 'FreshRSS', 'Bench', 0, 't', 'w', 0)"
        )

        database.beginTransaction()
        try {
            repeat(FOLDER_COUNT) { index ->
                database.execSQL(
                    "Insert Into Folder(id, name, remote_id) " +
                            "Values (${index + 1}, 'Folder ${index + 1}', " +
                            "'user/-/label/Folder ${index + 1}')"
                )
            }
            repeat(FEED_COUNT) { index ->
                database.execSQL(
                    "Insert Into Feed(id, name, description, url, siteUrl, last_updated, color, " +
                            "icon_url, folder_id, remote_id, notification_enabled, " +
                            "open_in, open_in_ask) Values (${index + 1}, 'Feed ${index + 1}', " +
                            "'A feed', 'https://feed${index + 1}.example/rss', " +
                            "'https://feed${index + 1}.example', '', 0, " +
                            "'https://feed${index + 1}.example/icon.png', " +
                            "${index % FOLDER_COUNT + 1}, 'feed/https://feed${index + 1}.example/rss', " +
                            "1, 'LOCAL_VIEW', 1)"
                )
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    /**
     * Articles are inserted oldest first, which is the order a year of daily
     * syncs leaves them in, with a few days of jitter on the publication date so
     * the row order is not a perfect stand-in for the date order. The newest
     * [UNREAD_ARTICLES] are unread; everything older is read, and became read a
     * day after it was published. One article in [starredStride] is starred.
     */
    private fun seedArticles() {
        val content = filler('c', CONTENT_CHARS)
        val description = filler('d', DESCRIPTION_CHARS)
        val cleanDescription = filler('t', CLEAN_DESCRIPTION_CHARS)
        val oldest = System.currentTimeMillis() - YEAR_DAYS * DAY_MILLIS
        val step = (YEAR_DAYS * DAY_MILLIS) / articleCount
        val jitter = kotlin.random.Random(20260906)

        val unreadFrom = maxOf(0, articleCount - UNREAD_ARTICLES)
        val starredStride = maxOf(1, articleCount / STARRED_ARTICLES)

        var inserted = 0
        while (inserted < articleCount) {
            val last = minOf(inserted + SEED_TRANSACTION_ROWS, articleCount)
            database.beginTransaction()
            try {
                database.compileStatement(
                    "Insert Into Article(id, title, description, clean_description, link, " +
                            "image_link, author, pub_date, content, feed_id, read_time, read, " +
                            "starred, read_at) Values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                ).use { statement ->
                    for (index in inserted until last) {
                        val publishedAt =
                            oldest + index * step + jitter.nextLong(-3 * DAY_MILLIS, 3 * DAY_MILLIS)
                        val read = index < unreadFrom
                        val starred = index % starredStride == 0

                        statement.bindLong(1, articleId(index, publishedAt))
                        statement.bindString(2, "Article $index about something that happened")
                        statement.bindString(3, description)
                        statement.bindString(4, cleanDescription)
                        statement.bindString(5, "https://feed${index % FEED_COUNT + 1}.example/$index")
                        statement.bindString(6, "https://feed${index % FEED_COUNT + 1}.example/$index.jpg")
                        statement.bindString(7, "Author ${index % 40}")
                        statement.bindLong(8, publishedAt)
                        statement.bindString(9, content)
                        statement.bindLong(10, (index % FEED_COUNT + 1).toLong())
                        statement.bindDouble(11, 1.5)
                        statement.bindLong(12, if (read) 1 else 0)
                        statement.bindLong(13, if (starred) 1 else 0)
                        if (read) {
                            statement.bindLong(14, publishedAt + DAY_MILLIS)
                        } else {
                            statement.bindNull(14)
                        }
                        statement.executeInsert()
                    }
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            inserted = last
        }
    }

    /**
     * A FreshRSS id is the Unix second the server first saw the article,
     * multiplied by a million, plus a counter within that second. The seeded ids
     * have the same shape and the same order of magnitude, so the store is keyed
     * on numbers of the size it really holds.
     */
    private fun articleId(index: Int, publishedAt: Long): Long =
        (publishedAt / 1000L) * 1_000_000L + (index % 1_000_000)

    private fun filler(letter: Char, length: Int): String = buildString(length) {
        while (this.length < length) {
            append(letter).append("orem ipsum dolor sit amet consectetur adipiscing elit ")
        }
        setLength(length)
    }

    companion object {
        /** ~100 feeds in ~10 folders, as the map describes the shape. */
        const val FOLDER_COUNT = 10
        const val FEED_COUNT = 100

        /** What a heavy reader leaves unread and starred, whatever the store's size. */
        const val UNREAD_ARTICLES = 2_500
        const val STARRED_ARTICLES = 1_000

        /** Article body sizes, so a row weighs what a real article weighs. */
        const val CONTENT_CHARS = 1_000
        const val DESCRIPTION_CHARS = 300
        const val CLEAN_DESCRIPTION_CHARS = 250

        const val SEED_TRANSACTION_ROWS = 10_000
        const val DAY_MILLIS = 86_400_000L
        const val YEAR_DAYS = 365
    }
}
