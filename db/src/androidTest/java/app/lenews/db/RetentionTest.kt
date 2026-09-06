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
import app.lenews.db.entities.Item
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The mirror and horizon rules of `docs/article-store.md` §4, as one delete:
 * the phone holds what FreshRSS holds, nothing read more than thirty days ago
 * is kept, and a starred article survives both.
 *
 * The store here is a handful of rows, so that each case says which article
 * went and which stayed. The size the rules are really about — a hundred
 * thousand articles and the whole server id list — is `TimelineTimeBudgetTest`.
 */
@RunWith(AndroidJUnit4::class)
class RetentionTest {

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

    /** Mirror: the server no longer returns it, so the phone drops it. */
    @Test
    fun anUnreadArticleTheServerNoLongerHoldsIsGone() = runTest {
        store(article(ARTICLE_A))
        store(article(ARTICLE_B))

        val deleted = retentionPass(serverIds = setOf(ARTICLE_A))

        assertEquals(1, deleted)
        assertEquals(listOf(ARTICLE_A), heldIds())
    }

    /** Starred articles survive both rules. */
    @Test
    fun aStarredArticleTheServerNoLongerHoldsStays() = runTest {
        store(article(ARTICLE_A, starred = true))
        store(article(ARTICLE_B, starred = true, read = true, readAt = NOW - THIRTY_ONE_DAYS))

        val deleted = retentionPass(serverIds = emptySet())

        assertEquals(0, deleted, "a starred article is dropped by neither rule")
        assertEquals(listOf(ARTICLE_A, ARTICLE_B), heldIds())
    }

    /**
     * The mirror branch is about unread articles only: a read article the
     * server has dropped is still within the horizon, so the phone keeps it
     * until the thirty days are up. That is what "unless it is starred, or
     * still within the horizon" means, and it is the way back to an article
     * that was swiped away.
     */
    @Test
    fun anArticleReadWithinTheHorizonAndAbsentFromTheServerStays() = runTest {
        store(article(ARTICLE_A, read = true, readAt = NOW - TWENTY_NINE_DAYS))

        val deleted = retentionPass(serverIds = emptySet())

        assertEquals(0, deleted)
        assertEquals(listOf(ARTICLE_A), heldIds())
    }

    /** Horizon: past thirty days a read article goes, whatever the server has. */
    @Test
    fun anArticleReadPastTheHorizonIsGoneEvenThoughTheServerStillHoldsIt() = runTest {
        store(article(ARTICLE_A, read = true, readAt = NOW - THIRTY_ONE_DAYS))

        val deleted = retentionPass(serverIds = setOf(ARTICLE_A))

        assertEquals(1, deleted)
        assertEquals(emptyList(), heldIds())
    }

    @Test
    fun anArticleReadInsideTheHorizonStays() = runTest {
        store(article(ARTICLE_A, read = true, readAt = NOW - TWENTY_NINE_DAYS))

        val deleted = retentionPass(serverIds = setOf(ARTICLE_A))

        assertEquals(0, deleted)
        assertEquals(listOf(ARTICLE_A), heldIds())
    }

    @Test
    fun anArticleReadPastTheHorizonAndStarredStays() = runTest {
        store(article(ARTICLE_A, read = true, readAt = NOW - THIRTY_ONE_DAYS, starred = true))

        val deleted = retentionPass(serverIds = setOf(ARTICLE_A))

        assertEquals(0, deleted, "starred articles survive the horizon")
        assertEquals(listOf(ARTICLE_A), heldIds())
    }

    /**
     * The article that became read exactly thirty days ago to the millisecond
     * is **kept**: the horizon is the age past which an article is no longer
     * kept, so the last millisecond of the thirtieth day is still inside it and
     * the comparison is a strict `<`. The next sync, a moment later, drops it.
     * Which side the boundary falls on cannot matter in practice — syncs do not
     * land on a millisecond — but the test says which side was chosen, so that
     * nobody has to read the SQL to find out.
     */
    @Test
    fun anArticleReadExactlyOnTheHorizonStaysUntilTheNextSync() = runTest {
        store(article(ARTICLE_A, read = true, readAt = NOW - THIRTY_DAYS))

        assertEquals(0, retentionPass(serverIds = setOf(ARTICLE_A)))
        assertEquals(listOf(ARTICLE_A), heldIds())

        assertEquals(1, retentionPass(serverIds = setOf(ARTICLE_A), now = NOW + 1))
        assertEquals(emptyList(), heldIds())
    }

    /** Nothing the server still holds and nobody has read is touched. */
    @Test
    fun theUnreadArticlesTheServerStillHoldsStay() = runTest {
        store(article(ARTICLE_A))
        store(article(ARTICLE_B))

        val deleted = retentionPass(serverIds = setOf(ARTICLE_A, ARTICLE_B))

        assertEquals(0, deleted)
        assertEquals(listOf(ARTICLE_A, ARTICLE_B), heldIds())
    }

    /**
     * A deleted article takes its queued decision with it, through the foreign
     * key. Without that, the next sync would upload a decision about an article
     * the store no longer holds.
     */
    @Test
    fun thePendingChangeOfADeletedArticleGoesWithIt() = runTest {
        store(article(ARTICLE_A, read = true, readAt = NOW - THIRTY_ONE_DAYS))
        store(article(ARTICLE_B))
        database.pendingChangeDao().queueRead(ARTICLE_A, true)
        database.pendingChangeDao().queueStarred(ARTICLE_B, true)

        retentionPass(serverIds = setOf(ARTICLE_A, ARTICLE_B))

        assertNull(
            database.pendingChangeDao().select(ARTICLE_A),
            "the queued decision of a deleted article stayed behind"
        )
        assertNotNull(
            database.pendingChangeDao().select(ARTICLE_B),
            "the queue of an article that stays was emptied"
        )
    }

    /**
     * A full account sends tens of thousands of ids, far past the 999 values
     * SQLite will bind to one statement. They go into a temporary table in
     * chunks and the delete asks that table, so the size of the list changes
     * nothing.
     */
    @Test
    fun fiftyThousandServerIdsGoThroughWithoutHittingTheBindLimit() = runTest {
        store(article(ARTICLE_A))
        store(article(ARTICLE_B))

        // ARTICLE_A is in the list, ARTICLE_B is not
        val serverIds = buildList {
            add(ARTICLE_A)
            repeat(50_000) { add(ARTICLE_A + 2 + it) }
        }

        val deleted = retentionPass(serverIds = serverIds)

        assertEquals(1, deleted)
        assertEquals(listOf(ARTICLE_A), heldIds())
    }

    /**
     * The temporary table holds one sync's answer and no other: the ids of a
     * pass are gone before the next one runs, or an article the server dropped
     * would be kept for ever by a list it was once in.
     */
    @Test
    fun theServerIdsOfOnePassDoNotLeakIntoTheNext() = runTest {
        store(article(ARTICLE_A))
        store(article(ARTICLE_B))

        assertEquals(0, retentionPass(serverIds = setOf(ARTICLE_A, ARTICLE_B)))

        store(article(ARTICLE_C))
        val deleted = retentionPass(serverIds = setOf(ARTICLE_C))

        assertEquals(2, deleted, "the ids of the first pass were still in the table")
        assertEquals(listOf(ARTICLE_C), heldIds())
    }

    /** Invariant 5: repeating a sync changes nothing, retention included. */
    @Test
    fun runningTheSamePassTwiceDeletesNothingMore() = runTest {
        store(article(ARTICLE_A))
        store(article(ARTICLE_B, read = true, readAt = NOW - THIRTY_ONE_DAYS))

        assertEquals(1, retentionPass(serverIds = setOf(ARTICLE_A)))
        assertEquals(0, retentionPass(serverIds = setOf(ARTICLE_A)))
        assertEquals(listOf(ARTICLE_A), heldIds())
    }

    /**
     * The delete belongs inside the sync transaction and nowhere else, so that
     * a failed sync deletes nothing. It also needs the one connection a
     * transaction pins, since the temporary table it fills lives on that
     * connection. Called outside one it refuses rather than silently working
     * against a connection the next statement may not get.
     */
    @Test
    fun theDeleteRefusesToRunOutsideATransaction() = runTest {
        store(article(ARTICLE_A))

        assertFailsWith<IllegalStateException> {
            database.deleteWhatRetentionDrops(setOf(ARTICLE_A), NOW)
        }
        assertEquals(listOf(ARTICLE_A), heldIds())
    }

    private fun retentionPass(serverIds: Collection<Long>, now: Long = NOW): Int {
        var deleted = 0
        database.runInTransaction { deleted = database.deleteWhatRetentionDrops(serverIds, now) }
        return deleted
    }

    private suspend fun store(article: Item) {
        database.itemDao().insert(article)
    }

    private suspend fun heldIds(): List<Long> =
        database.itemDao().selectEveryArticle().map { it.id }.sorted()

    private fun article(
        id: Long,
        read: Boolean = false,
        readAt: Long? = null,
        starred: Boolean = false
    ) = Item(
        id = id,
        title = "article $id",
        feedId = 1,
        pubDate = LocalDateTime.of(2026, 9, 6, 12, 0),
        isRead = read,
        readAt = readAt,
        isStarred = starred
    )

    private companion object {
        /** FreshRSS ids: the discovery second times a million. */
        const val ARTICLE_A = 1_625_234_531_559_678L
        const val ARTICLE_B = 1_625_234_531_559_679L
        const val ARTICLE_C = 1_625_234_531_559_680L

        /** The sync's own clock in these tests. */
        const val NOW = 1_757_160_000_000L

        const val THIRTY_DAYS = 30L * 24 * 60 * 60 * 1000
        const val THIRTY_ONE_DAYS = 31L * 24 * 60 * 60 * 1000
        const val TWENTY_NINE_DAYS = 29L * 24 * 60 * 60 * 1000
    }
}
