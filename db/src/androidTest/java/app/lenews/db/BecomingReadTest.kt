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
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lenews.db.entities.Feed
import app.lenews.db.entities.Folder
import app.lenews.db.entities.Item
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `docs/article-store.md` §5, statement by statement: every route by which an
 * article becomes read stamps `read_at` **once**, on the articles that were
 * unread and in the route's scope, and leaves everything else exactly as it
 * was — an article already read keeps the date it already had, because the
 * history shows when an article became read and not when someone last swept
 * over it.
 *
 * Each route is two statements, and this test runs them in the order
 * `BaseRepository` runs them: the queue first, while `read = 0` still names the
 * articles about to change, then the update. Run the other way round the queue
 * would come out empty and the server would never be told.
 */
@RunWith(AndroidJUnit4::class)
class BecomingReadTest {

    private lateinit var database: Database

    @Before
    fun before() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, Database::class.java).build()

        database.folderDao().insert(Folder(name = "News", remoteId = "user/-/label/News"))
        database.folderDao().insert(Folder(name = "Other", remoteId = "user/-/label/Other"))

        database.feedDao().insert(feed("First", FOLDER_WITH_ARTICLES))
        database.feedDao().insert(feed("Second", FOLDER_WITH_ARTICLES))
        database.feedDao().insert(feed("Third", OTHER_FOLDER))
    }

    @After
    fun after() {
        database.close()
    }

    /**
     * The single-article route: opening an article, swiping it, or the timeline
     * marking it read as it scrolls past. Reading it again while it is already
     * read changes nothing; marking it unread takes it out of the history, and
     * reading it after that is a new date.
     */
    @Test
    fun oneArticleIsStampedOnceUnstampedByUnreadAndStampedAgainOnTheNextRead() = runTest {
        store(unread(ARTICLE_A, FEED_ONE))

        becomesRead(ARTICLE_A, NOW)
        assertRead(ARTICLE_A, NOW)
        assertEquals(true, queued(ARTICLE_A), "the server was not told")

        becomesRead(ARTICLE_A, NOW + A_MINUTE)
        assertRead(ARTICLE_A, NOW, "an already read article was stamped a second time")

        becomesUnread(ARTICLE_A)
        assertUnread(ARTICLE_A)
        assertEquals(false, queued(ARTICLE_A), "the server was not told about the unread")

        becomesRead(ARTICLE_A, NOW + A_DAY)
        assertRead(ARTICLE_A, NOW + A_DAY, "reading it again did not date it again")
    }

    /** Starring and unstarring say nothing about reading. */
    @Test
    fun starringDoesNotTouchReadOrTheDate() = runTest {
        store(unread(ARTICLE_A, FEED_ONE), unread(ARTICLE_B, FEED_ONE))
        becomesRead(ARTICLE_B, NOW)

        database.itemDao().setStarred(ARTICLE_A, true)
        database.itemDao().setStarred(ARTICLE_B, true)
        database.itemDao().setStarred(ARTICLE_B, false)

        assertUnread(ARTICLE_A)
        assertRead(ARTICLE_B, NOW)
    }

    @Test
    fun markingTheWholeListReadStampsOnlyTheUnreadOnes() = runTest {
        val alreadyRead = storeAndReadOneOfThem()

        database.pendingChangeDao().queueReadForAllUnread()
        database.itemDao().markAllRead(NOW)

        assertRead(alreadyRead, LONG_AGO, "an article already read was stamped again")
        assertNull(database.pendingChangeDao().select(alreadyRead), "nothing to tell the server")

        assertRead(ARTICLE_B, NOW)
        assertRead(ARTICLE_C, NOW)
        assertEquals(true, queued(ARTICLE_B))
        assertEquals(true, queued(ARTICLE_C))
    }

    @Test
    fun markingOneFeedReadLeavesTheOtherFeedsAlone() = runTest {
        store(
            unread(ARTICLE_A, FEED_ONE),
            unread(ARTICLE_B, FEED_ONE),
            unread(ARTICLE_C, FEED_TWO)
        )

        database.pendingChangeDao().queueReadForUnreadInFeed(FEED_ONE)
        database.itemDao().markAllReadByFeed(FEED_ONE, NOW)

        assertRead(ARTICLE_A, NOW)
        assertRead(ARTICLE_B, NOW)
        assertUnread(ARTICLE_C)
        assertNull(database.pendingChangeDao().select(ARTICLE_C))
    }

    @Test
    fun markingOneFolderReadTakesTheArticlesOfItsFeedsAndNoOthers() = runTest {
        store(
            unread(ARTICLE_A, FEED_ONE),
            unread(ARTICLE_B, FEED_TWO),
            unread(ARTICLE_C, FEED_THREE)
        )

        database.pendingChangeDao().queueReadForUnreadInFolder(FOLDER_WITH_ARTICLES)
        database.itemDao().markAllReadByFolder(FOLDER_WITH_ARTICLES, NOW)

        assertRead(ARTICLE_A, NOW)
        assertRead(ARTICLE_B, NOW)
        assertUnread(ARTICLE_C, "an article of another folder was marked read")
        assertNull(database.pendingChangeDao().select(ARTICLE_C))
    }

    /**
     * Upstream issue #341: marking the starred list read did nothing at all,
     * because the statement selected the articles on a column that FreshRSS
     * accounts never wrote — the star lived in a table of its own and
     * `Item.starred` stayed 0 forever. There is one `starred` column now, on the
     * article, and the sync writes it, so the statement selects what the screen
     * shows. This test is what keeps that true.
     */
    @Test
    fun markingTheStarredListReadMarksTheStarredUnreadArticles() = runTest {
        store(
            unread(ARTICLE_A, FEED_ONE),
            unread(ARTICLE_B, FEED_ONE),
            unread(ARTICLE_C, FEED_TWO)
        )
        database.itemDao().setStarred(ARTICLE_A, true)
        database.itemDao().setStarred(ARTICLE_B, true)
        // read long ago and already uploaded: nothing about it waits in the queue
        database.itemDao().markRead(ARTICLE_B, LONG_AGO)

        database.pendingChangeDao().queueReadForUnreadStarred()
        database.itemDao().markAllStarredRead(NOW)

        assertRead(ARTICLE_A, NOW, "marking the starred list read did nothing")
        assertEquals(true, queued(ARTICLE_A), "the server was not told")

        assertRead(ARTICLE_B, LONG_AGO, "a starred article already read was stamped again")
        assertUnread(ARTICLE_C, "an article that is not starred was marked read")
        assertNull(database.pendingChangeDao().select(ARTICLE_C))
    }

    @Test
    fun markingTheLastDayReadLeavesTheOlderArticlesUnread() = runTest {
        // the window the screen uses is the last day of the real clock, so the
        // publication dates below are relative to it and not to [NOW], which is
        // only the moment the articles became read
        val since = System.currentTimeMillis() - A_DAY
        val yesterday = LocalDateTime.now().minusHours(2)
        val lastWeek = LocalDateTime.now().minusDays(7)

        store(
            unread(ARTICLE_A, FEED_ONE, yesterday),
            unread(ARTICLE_B, FEED_ONE, lastWeek)
        )

        database.pendingChangeDao().queueReadForUnreadSince(since)
        database.itemDao().markAllReadSince(since, NOW)

        assertRead(ARTICLE_A, NOW)
        assertUnread(ARTICLE_B, "an article older than a day was marked read")
        assertNull(database.pendingChangeDao().select(ARTICLE_B))
    }

    /**
     * Three articles, the first of them read long ago and its change already
     * uploaded, so nothing about it waits in the queue.
     */
    private suspend fun storeAndReadOneOfThem(): Long {
        store(
            unread(ARTICLE_A, FEED_ONE),
            unread(ARTICLE_B, FEED_ONE),
            unread(ARTICLE_C, FEED_TWO)
        )
        database.itemDao().markRead(ARTICLE_A, LONG_AGO)

        return ARTICLE_A
    }

    private suspend fun becomesRead(articleId: Long, now: Long) {
        database.pendingChangeDao().queueRead(articleId, true)
        database.itemDao().markRead(articleId, now)
    }

    private suspend fun becomesUnread(articleId: Long) {
        database.pendingChangeDao().queueRead(articleId, false)
        database.itemDao().markUnread(articleId)
    }

    private suspend fun queued(articleId: Long): Boolean? =
        database.pendingChangeDao().select(articleId)?.read

    private suspend fun assertRead(articleId: Long, readAt: Long, message: String? = null) {
        val article = database.itemDao().select(articleId)!!
        assertTrue(article.isRead, message ?: "article $articleId is not read")
        assertEquals(readAt, article.readAt, message ?: "article $articleId carries the wrong date")
    }

    private suspend fun assertUnread(articleId: Long, message: String? = null) {
        val article = database.itemDao().select(articleId)!!
        assertFalse(article.isRead, message ?: "article $articleId is read")
        assertNull(article.readAt, message ?: "an unread article carries a date")
    }

    private suspend fun store(vararg articles: Item) {
        database.itemDao().upsertArticles(articles.toList())
    }

    private fun unread(
        id: Long,
        feedId: Int,
        pubDate: LocalDateTime = LocalDateTime.now().minusHours(1)
    ) = Item(id = id, title = "article $id", feedId = feedId, pubDate = pubDate)

    private fun feed(name: String, folderId: Int) =
        Feed(name = name, remoteId = "feed/$name", folderId = folderId)

    private companion object {
        const val FOLDER_WITH_ARTICLES = 1
        const val OTHER_FOLDER = 2

        const val FEED_ONE = 1
        const val FEED_TWO = 2
        const val FEED_THREE = 3

        const val ARTICLE_A = 1_625_234_531_559_678L
        const val ARTICLE_B = 1_625_234_531_559_679L
        const val ARTICLE_C = 1_625_234_531_559_680L

        const val A_MINUTE = 60_000L
        const val A_DAY = 24 * 60 * A_MINUTE

        const val NOW = 1_757_160_000_000L
        const val LONG_AGO = NOW - 90 * A_DAY
    }
}
