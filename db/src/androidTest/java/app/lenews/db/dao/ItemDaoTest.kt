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
package app.lenews.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lenews.db.Database
import app.lenews.db.entities.Feed
import app.lenews.db.entities.Item
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The identity rule of `docs/article-store.md` §2: the FreshRSS id is the
 * article's primary key, so the same id can never make two rows, and an article
 * FreshRSS sends again is an update of its content and nothing else.
 */
@RunWith(AndroidJUnit4::class)
class ItemDaoTest {

    private lateinit var database: Database

    @Before
    fun before() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, Database::class.java).build()

        database.feedDao().insert(Feed(name = "Feed", remoteId = "feed/one"))
    }

    @After
    fun after() {
        database.close()
    }

    @Test
    fun theSameIdInsertedTwiceLeavesOneRowWithTheSecondContentAndTheFirstState() = runTest {
        val itemDao = database.itemDao()
        val pendingChangeDao = database.pendingChangeDao()

        val inserted = itemDao.upsertArticles(listOf(article(title = "first", content = "one")))
        assertEquals(1, inserted.size)

        // the article is read on the phone and the decision is queued
        itemDao.markRead(ARTICLE_ID, READ_AT)
        itemDao.setStarred(ARTICLE_ID, true)
        pendingChangeDao.queueRead(ARTICLE_ID, true)

        val redelivered = itemDao.upsertArticles(
            listOf(article(title = "second", content = "two"))
        )
        assertTrue(redelivered.isEmpty(), "a re-delivered article is not a new one")

        assertEquals(1, itemDao.count())

        with(itemDao.select(ARTICLE_ID)) {
            assertEquals("second", title, "the content is the second one")
            assertEquals("two", content)

            assertTrue(isRead, "the state the phone holds survives the re-delivery")
            assertTrue(isStarred)
            assertEquals(READ_AT, readAt)
        }

        val pendingChange = assertNotNull(pendingChangeDao.select(ARTICLE_ID))
        assertEquals(true, pendingChange.read)
        assertNull(pendingChange.starred)
    }

    @Test
    fun theSameIdTwiceInOneResponseLeavesOneRowWithTheLastOfThem() = runTest {
        val itemDao = database.itemDao()

        val inserted = itemDao.upsertArticles(
            listOf(
                article(title = "first", content = "one"),
                article(title = "second", content = "two")
            )
        )

        assertEquals(1, inserted.size)
        assertEquals(1, itemDao.count())
        assertEquals("second", itemDao.select(ARTICLE_ID).title)
    }

    /**
     * §3 step 4b: a new article enters the store unread, unstarred and with no
     * `read_at`, whatever the content said. The read and starred flags an article
     * arrives with are not state — the id lists of the sync are — and storing
     * them here would write `read = 1` with no `read_at`, which invariant 2
     * forbids and nothing repairs afterwards, since marking read only touches
     * rows that are unread.
     */
    @Test
    fun anArticleThatArrivesReadAndStarredIsInsertedInTheNeutralState() = runTest {
        val itemDao = database.itemDao()

        val inserted = itemDao.upsertArticles(
            listOf(
                article(title = "read on the web").apply {
                    isRead = true
                    isStarred = true
                    readAt = null
                }
            )
        )

        with(itemDao.select(ARTICLE_ID)) {
            assertFalse(isRead, "the article is stored unread")
            assertFalse(isStarred)
            assertNull(readAt)
        }

        // and what the caller is handed back is what the store holds
        with(inserted.single()) {
            assertFalse(isRead)
            assertFalse(isStarred)
            assertNull(readAt)
        }
    }

    /**
     * The sequel to the neutral insert: the state the sync learned is applied on
     * top of it, and a read learned at sync is stamped with the sync's clock.
     */
    @Test
    fun theStateAppliedAfterTheInsertStampsAReadLearnedAtSync() = runTest {
        val itemDao = database.itemDao()
        itemDao.upsertArticles(listOf(article(title = "read on the web").apply { isRead = true }))

        itemDao.markReadFromSync(listOf(ARTICLE_ID), READ_AT)
        itemDao.starFromSync(listOf(ARTICLE_ID))

        with(itemDao.select(ARTICLE_ID)) {
            assertTrue(isRead)
            assertEquals(READ_AT, readAt, "read = 1 goes with a read_at, never without")
            assertTrue(isStarred)
        }
    }

    /**
     * §3, steps 4c and 4d: the phone's decision wins over the server's answer
     * until it has been uploaded, so the state a sync learned skips any article
     * with a pending value for the half it writes — and only that half.
     */
    @Test
    fun theStateASyncLearnedSkipsAnArticleWithAPendingValue() = runTest {
        val itemDao = database.itemDao()
        itemDao.upsertArticles(listOf(article(title = "read on the phone")))

        itemDao.markRead(ARTICLE_ID, READ_AT)
        database.pendingChangeDao().queueRead(ARTICLE_ID, true)

        // the server still calls it unread, and is not listened to
        itemDao.markUnreadFromSync(listOf(ARTICLE_ID))
        with(itemDao.select(ARTICLE_ID)) {
            assertTrue(isRead, "a pending read decision was overwritten by the server")
            assertEquals(READ_AT, readAt)
        }

        // the starred half has no pending value, so the server decides it
        itemDao.starFromSync(listOf(ARTICLE_ID))
        assertTrue(itemDao.select(ARTICLE_ID).isStarred)

        database.pendingChangeDao().queueStarred(ARTICLE_ID, true)
        itemDao.unstarFromSync(listOf(ARTICLE_ID))
        assertTrue(
            itemDao.select(ARTICLE_ID).isStarred,
            "a pending starred decision was overwritten by the server"
        )

        // once the queue is empty the server decides both halves again
        database.pendingChangeDao().deleteAll()
        itemDao.markUnreadFromSync(listOf(ARTICLE_ID))
        itemDao.unstarFromSync(listOf(ARTICLE_ID))
        with(itemDao.select(ARTICLE_ID)) {
            assertFalse(isRead)
            assertNull(readAt)
            assertFalse(isStarred)
        }
    }

    @Test
    fun readingAnArticleStampsItAndUnreadingItClearsTheStamp() = runTest {
        val itemDao = database.itemDao()
        itemDao.upsertArticles(listOf(article(title = "one")))

        itemDao.markRead(ARTICLE_ID, READ_AT)
        with(itemDao.select(ARTICLE_ID)) {
            assertTrue(isRead)
            assertEquals(READ_AT, readAt)
        }

        // reading an already read article changes nothing and stamps nothing
        itemDao.markRead(ARTICLE_ID, READ_AT + 1000)
        assertEquals(READ_AT, itemDao.select(ARTICLE_ID).readAt)

        itemDao.markUnread(ARTICLE_ID)
        with(itemDao.select(ARTICLE_ID)) {
            assertFalse(isRead)
            assertNull(readAt)
        }
    }

    private fun article(title: String, content: String? = null) = Item(
        id = ARTICLE_ID,
        title = title,
        content = content,
        feedId = 1,
        pubDate = LocalDateTime.of(2026, 9, 6, 12, 0)
    )

    private companion object {
        /** A FreshRSS id: the discovery second times a million. */
        const val ARTICLE_ID = 1_625_234_531_559_678L
        const val READ_AT = 1_757_160_000_000L
    }
}
