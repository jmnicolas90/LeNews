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
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lenews.db.entities.Feed
import app.lenews.db.entities.Item
import app.lenews.db.filters.MainFilter
import app.lenews.db.filters.OrderField
import app.lenews.db.filters.OrderType
import app.lenews.db.filters.QueryFilters
import app.lenews.db.filters.SubFilter
import app.lenews.db.queries.ItemsQueryBuilder
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The history list of `docs/article-store.md` §5: every article that became
 * read, newest first, one row each, whatever else the timeline was showing when
 * the reader asked for it.
 */
@RunWith(AndroidJUnit4::class)
class HistoryListTest {

    private lateinit var database: Database

    @Before
    fun before() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, Database::class.java).build()

        database.feedDao().insert(Feed(name = "First", remoteId = "feed/first"))
        database.feedDao().insert(Feed(name = "Second", remoteId = "feed/second"))
    }

    @After
    fun after() {
        database.close()
    }

    /**
     * The order is the moment each article became read, and it has nothing to do
     * with when the article was published: the two are deliberately opposite
     * here, so a list that came out in publication order would be caught.
     */
    @Test
    fun theHistoryIsEveryReadArticleNewestFirstAndOnceEach() = runTest {
        store(
            article(ARTICLE_A, FEED_ONE, published = LocalDateTime.now().minusDays(1)),
            article(ARTICLE_B, FEED_ONE, published = LocalDateTime.now().minusDays(2)),
            article(ARTICLE_C, FEED_TWO, published = LocalDateTime.now().minusDays(3)),
            article(ARTICLE_D, FEED_TWO, published = LocalDateTime.now().minusHours(1))
        )

        // read in the reverse of the order they were published, and D not at all
        database.itemDao().markRead(ARTICLE_A, NOW - 2 * A_MINUTE)
        database.itemDao().markRead(ARTICLE_B, NOW - A_MINUTE)
        database.itemDao().markRead(ARTICLE_C, NOW)

        assertEquals(listOf(ARTICLE_C, ARTICLE_B, ARTICLE_A), history())
    }

    /**
     * Every article in the history is read, so the "show read articles" filter
     * of the timeline says nothing about it — off, it would otherwise empty the
     * list — and neither does the order the reader chose for the timeline.
     */
    @Test
    fun theHistoryIgnoresTheShowReadArticlesFilterAndTheTimelineOrder() = runTest {
        store(article(ARTICLE_A, FEED_ONE), article(ARTICLE_B, FEED_ONE))
        database.itemDao().markRead(ARTICLE_A, NOW - A_MINUTE)
        database.itemDao().markRead(ARTICLE_B, NOW)

        val asTheTimelineWasLeft = QueryFilters(
            showReadItems = false,
            mainFilter = MainFilter.HISTORY,
            orderField = OrderField.ID,
            orderType = OrderType.ASC
        )

        assertEquals(listOf(ARTICLE_B, ARTICLE_A), history(asTheTimelineWasLeft))
    }

    /** Marking an article unread takes it out of the history, and back in when read again. */
    @Test
    fun anArticleMarkedUnreadLeavesTheHistoryAndComesBackWhenItIsReadAgain() = runTest {
        store(article(ARTICLE_A, FEED_ONE), article(ARTICLE_B, FEED_ONE))
        database.itemDao().markRead(ARTICLE_A, NOW - A_MINUTE)
        database.itemDao().markRead(ARTICLE_B, NOW)

        database.itemDao().markUnread(ARTICLE_B)
        assertEquals(listOf(ARTICLE_A), history())

        // read again, later than A this time, so it comes back at the top
        database.itemDao().markRead(ARTICLE_B, NOW + A_MINUTE)
        assertEquals(listOf(ARTICLE_B, ARTICLE_A), history())
    }

    /**
     * A feed narrows the history the way it narrows any other timeline, and so
     * does a folder: the main filter and the sub-filter are independent
     * everywhere else, and making the history the exception would be a special
     * case with nothing behind it.
     */
    @Test
    fun aFeedNarrowsTheHistoryToTheArticlesOfThatFeed() = runTest {
        store(
            article(ARTICLE_A, FEED_ONE),
            article(ARTICLE_B, FEED_TWO),
            article(ARTICLE_C, FEED_ONE)
        )
        database.itemDao().markRead(ARTICLE_A, NOW - A_MINUTE)
        database.itemDao().markRead(ARTICLE_B, NOW)
        database.itemDao().markRead(ARTICLE_C, NOW + A_MINUTE)

        val oneFeed = QueryFilters(
            mainFilter = MainFilter.HISTORY,
            subFilter = SubFilter.FEED,
            feedId = FEED_ONE
        )

        assertEquals(listOf(ARTICLE_C, ARTICLE_A), history(oneFeed))
    }

    /**
     * The item screen's "keep showing these" set, on the history: an article
     * marked unread while it is open stays in the list under the reader's
     * finger instead of vanishing, and no other article joins it.
     */
    @Test
    fun anArticleTheItemScreenKeepsStaysInTheListAfterItIsMarkedUnread() = runTest {
        store(article(ARTICLE_A, FEED_ONE), article(ARTICLE_B, FEED_ONE))
        database.itemDao().markRead(ARTICLE_A, NOW)
        database.itemDao().markUnread(ARTICLE_B)

        val history = QueryFilters(mainFilter = MainFilter.HISTORY)
        assertEquals(listOf(ARTICLE_A), history(history))

        val kept = idsOf(ItemsQueryBuilder.buildItemsQuery(history, setOf(ARTICLE_B)))
        assertTrue(ARTICLE_B in kept, "the article the reader has open was dropped from the list")
        assertTrue(ARTICLE_A in kept)
        assertEquals(2, kept.size)
    }

    private fun history(
        filters: QueryFilters = QueryFilters(mainFilter = MainFilter.HISTORY)
    ): List<Long> = idsOf(ItemsQueryBuilder.buildItemsQuery(filters))

    private fun idsOf(query: SupportSQLiteQuery): List<Long> {
        val ids = mutableListOf<Long>()

        database.query(query).use { cursor ->
            val column = cursor.getColumnIndexOrThrow("id")
            while (cursor.moveToNext()) {
                ids += cursor.getLong(column)
            }
        }

        assertEquals(ids.distinct(), ids, "an article came back more than once")
        return ids
    }

    private suspend fun store(vararg articles: Item) {
        database.itemDao().upsertArticles(articles.toList())
    }

    private fun article(
        id: Long,
        feedId: Int,
        published: LocalDateTime = LocalDateTime.now().minusHours(1)
    ) = Item(id = id, title = "article $id", feedId = feedId, pubDate = published)

    private companion object {
        const val FEED_ONE = 1
        const val FEED_TWO = 2

        const val ARTICLE_A = 1_625_234_531_559_678L
        const val ARTICLE_B = 1_625_234_531_559_679L
        const val ARTICLE_C = 1_625_234_531_559_680L
        const val ARTICLE_D = 1_625_234_531_559_681L

        const val A_MINUTE = 60_000L
        const val NOW = 1_757_160_000_000L
    }
}
