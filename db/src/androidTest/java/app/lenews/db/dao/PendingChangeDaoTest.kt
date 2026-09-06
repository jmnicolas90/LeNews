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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Clearing the queue after an upload, from `docs/article-store.md` §3, step 1:
 * a half is cleared **only where it still holds the value that was uploaded**,
 * so a decision the user made while the batch was in flight survives.
 */
@RunWith(AndroidJUnit4::class)
class PendingChangeDaoTest {

    private lateinit var database: Database

    @Before
    fun before() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, Database::class.java).build()

        database.feedDao().insert(Feed(name = "Feed", remoteId = "feed/one"))
        database.itemDao().upsertArticles(
            listOf(ARTICLE_ONE, ARTICLE_TWO).map { id ->
                Item(
                    id = id,
                    title = "article $id",
                    feedId = 1,
                    pubDate = LocalDateTime.of(2026, 9, 6, 12, 0)
                )
            }
        )
    }

    @After
    fun after() {
        database.close()
    }

    @Test
    fun anUploadedHalfIsClearedAndAnEmptyRowGoes() = runTest {
        val dao = database.pendingChangeDao()
        dao.queueRead(ARTICLE_ONE, true)

        dao.clearUploadedRead(listOf(ARTICLE_ONE), true)
        assertNotNull(dao.select(ARTICLE_ONE), "the row is not deleted by the clear itself")
        assertNull(dao.select(ARTICLE_ONE)?.read)

        dao.deleteEmpty()
        assertNull(dao.select(ARTICLE_ONE), "a row with nothing left to say is deleted")
    }

    @Test
    fun aHalfThatNoLongerHoldsTheUploadedValueStaysQueued() = runTest {
        val dao = database.pendingChangeDao()
        dao.queueRead(ARTICLE_ONE, true)

        // the user marks it unread again while the batch is in flight
        dao.queueRead(ARTICLE_ONE, false)

        dao.clearUploadedRead(listOf(ARTICLE_ONE), true)
        dao.deleteEmpty()

        val queued = assertNotNull(dao.select(ARTICLE_ONE), "the newer decision was dropped")
        assertEquals(false, queued.read)
    }

    @Test
    fun clearingOneHalfLeavesTheOther() = runTest {
        val dao = database.pendingChangeDao()
        dao.queueRead(ARTICLE_ONE, true)
        dao.queueStarred(ARTICLE_ONE, true)

        dao.clearUploadedRead(listOf(ARTICLE_ONE), true)
        dao.deleteEmpty()

        val queued = assertNotNull(dao.select(ARTICLE_ONE))
        assertNull(queued.read)
        assertEquals(true, queued.starred)
    }

    @Test
    fun onlyTheArticlesOfTheBatchAreCleared() = runTest {
        val dao = database.pendingChangeDao()
        dao.queueStarred(ARTICLE_ONE, false)
        dao.queueStarred(ARTICLE_TWO, false)

        dao.clearUploadedStarred(listOf(ARTICLE_ONE), false)
        dao.deleteEmpty()

        assertNull(dao.select(ARTICLE_ONE))
        assertEquals(false, assertNotNull(dao.select(ARTICLE_TWO)).starred)
    }

    private companion object {
        const val ARTICLE_ONE = 1_625_234_531_559_678L
        const val ARTICLE_TWO = 1_625_234_531_559_679L
    }
}
