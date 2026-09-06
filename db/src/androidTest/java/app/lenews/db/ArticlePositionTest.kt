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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Where an article is in a list right now, which is what the item screen opens
 * on.
 *
 * The check every test here makes is the same one: walk the list the timeline
 * would show, and for every article in it ask the store how many articles come
 * before it. The two have to agree at every position, under every order and
 * every filter, or the reader taps an article and gets a different one — and
 * the item screen marks whatever it opens on read, so a wrong answer here loses
 * an article the reader never saw.
 */
@RunWith(AndroidJUnit4::class)
class ArticlePositionTest {

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

    @Test
    fun theNewestFirstTimelineAgreesWithTheListItCounts() = runTest {
        storeAMixedShelf()

        everyPositionMatchesTheList(QueryFilters())
    }

    @Test
    fun theOldestFirstTimelineAgreesWithTheListItCounts() = runTest {
        storeAMixedShelf()

        everyPositionMatchesTheList(
            QueryFilters(orderField = OrderField.DATE, orderType = OrderType.ASC)
        )
    }

    @Test
    fun theTimelineOrderedByIdAgreesWithTheListItCounts() = runTest {
        storeAMixedShelf()

        everyPositionMatchesTheList(
            QueryFilters(orderField = OrderField.ID, orderType = OrderType.DESC)
        )
        everyPositionMatchesTheList(
            QueryFilters(orderField = OrderField.ID, orderType = OrderType.ASC)
        )
    }

    @Test
    fun theUnreadTimelineCountsOnlyItsOwnArticles() = runTest {
        storeAMixedShelf()

        everyPositionMatchesTheList(QueryFilters(showReadItems = false))
    }

    @Test
    fun theStarredListCountsOnlyItsOwnArticles() = runTest {
        storeAMixedShelf()

        everyPositionMatchesTheList(QueryFilters(mainFilter = MainFilter.STARS))
    }

    @Test
    fun aFeedCountsOnlyItsOwnArticles() = runTest {
        storeAMixedShelf()

        everyPositionMatchesTheList(QueryFilters(subFilter = SubFilter.FEED, feedId = FEED_ONE))
    }

    /** The history is its own order — when each article became read, newest first. */
    @Test
    fun theHistoryAgreesWithTheListItCounts() = runTest {
        storeAMixedShelf()

        everyPositionMatchesTheList(QueryFilters(mainFilter = MainFilter.HISTORY))
    }

    /**
     * The article the reader has open is kept in the list even once its state
     * no longer matches, so the position has to be counted with the same set —
     * otherwise the count is a position in a list that is one article shorter.
     */
    @Test
    fun theKeptArticlesAreCountedTheWayTheListShowsThem() = runTest {
        storeAMixedShelf()
        database.itemDao().markRead(ARTICLE_C, NOW)

        val unread = QueryFilters(showReadItems = false)
        val kept = setOf(ARTICLE_C)

        assertFalse(
            ARTICLE_C in idsOf(ItemsQueryBuilder.buildItemsQuery(unread)),
            "the article is still in the unread list, so the kept set proves nothing here"
        )

        everyPositionMatchesTheList(unread, kept)
    }

    /**
     * Two articles published in the same second, which one feed delivering a
     * batch produces all the time. Without a tie-break they would share a
     * position and the reader would open whichever the plan happened to give.
     */
    @Test
    fun articlesPublishedAtTheSameMomentStillHaveOnePositionEach() = runTest {
        val sameMoment = LocalDateTime.of(2026, 9, 1, 12, 0)
        store(
            article(ARTICLE_A, published = sameMoment),
            article(ARTICLE_B, published = sameMoment),
            article(ARTICLE_C, published = sameMoment)
        )

        everyPositionMatchesTheList(QueryFilters())
        everyPositionMatchesTheList(
            QueryFilters(orderField = OrderField.DATE, orderType = OrderType.ASC)
        )
    }

    /** An article that arrived with no publication date is still somewhere. */
    @Test
    fun anArticleWithNoPublicationDateHasAPositionToo() = runTest {
        store(
            article(ARTICLE_A, published = LocalDateTime.of(2026, 9, 1, 12, 0)),
            Item(id = ARTICLE_B, title = "no date", feedId = FEED_ONE, pubDate = null),
            article(ARTICLE_C, published = LocalDateTime.of(2026, 9, 3, 12, 0))
        )

        everyPositionMatchesTheList(QueryFilters())
        everyPositionMatchesTheList(
            QueryFilters(orderField = OrderField.DATE, orderType = OrderType.ASC)
        )
    }

    /**
     * The one answer that has to be read with care. Retention deletes articles
     * at every sync, and nothing sorts against a row that is not there: the
     * comparison is made against a null, so the count is the length of the list
     * one way round and nought the other — and nought reads exactly like "the
     * first article". Neither is a position, which is why the item screen asks
     * whether the article is there before it asks where.
     */
    @Test
    fun anArticleTheStoreNoLongerHoldsHasNoPositionToRead() = runTest {
        storeAMixedShelf()

        assertFalse(database.itemDao().itemExists(GONE), "the fixture holds the deleted article")

        val newestFirst = QueryFilters()
        assertEquals(
            idsOf(ItemsQueryBuilder.buildItemsQuery(newestFirst)).size,
            positionOf(GONE, newestFirst),
            "a deleted article does not come out past the end of the newest-first list"
        )

        val oldestFirst = QueryFilters(orderField = OrderField.DATE, orderType = OrderType.ASC)
        assertEquals(
            0,
            positionOf(GONE, oldestFirst),
            "a deleted article does not come out at the top of the oldest-first list"
        )
    }

    /**
     * Every article of the list, at the position the list itself puts it at.
     * The list is read through the production query, so the two cannot drift.
     */
    private suspend fun everyPositionMatchesTheList(
        filters: QueryFilters,
        keptArticleIds: Set<Long> = emptySet()
    ) {
        val list = idsOf(ItemsQueryBuilder.buildItemsQuery(filters, keptArticleIds))

        assertTrue(
            list.size > 2,
            "a list of ${list.size} articles proves nothing about positions"
        )

        list.forEachIndexed { index, id ->
            assertEquals(
                index,
                positionOf(id, filters, keptArticleIds),
                "article $id is at $index in the list and the store counts it elsewhere"
            )
        }
    }

    private suspend fun positionOf(
        itemId: Long,
        filters: QueryFilters,
        keptArticleIds: Set<Long> = emptySet()
    ): Int = database.itemDao().countArticlesBefore(
        ItemsQueryBuilder.buildItemPositionQuery(filters, itemId, keptArticleIds)
    )

    /**
     * Six articles across two feeds, some read, some starred, published in an
     * order the read order deliberately contradicts, so that a list ordered by
     * the wrong column would be caught.
     */
    private suspend fun storeAMixedShelf() {
        store(
            article(ARTICLE_A, published = LocalDateTime.of(2026, 9, 1, 8, 0)),
            article(ARTICLE_B, published = LocalDateTime.of(2026, 9, 2, 8, 0)),
            article(ARTICLE_C, published = LocalDateTime.of(2026, 9, 3, 8, 0)),
            article(ARTICLE_D, published = LocalDateTime.of(2026, 9, 4, 8, 0), feedId = FEED_TWO),
            article(ARTICLE_E, published = LocalDateTime.of(2026, 9, 5, 8, 0), feedId = FEED_TWO),
            article(ARTICLE_F, published = LocalDateTime.of(2026, 9, 6, 8, 0))
        )

        // read oldest-published last, so the history and the timeline disagree
        database.itemDao().markRead(ARTICLE_F, NOW - 3 * A_MINUTE)
        database.itemDao().markRead(ARTICLE_D, NOW - 2 * A_MINUTE)
        database.itemDao().markRead(ARTICLE_A, NOW - A_MINUTE)

        database.itemDao().setStarred(ARTICLE_B, true)
        database.itemDao().setStarred(ARTICLE_D, true)
        database.itemDao().setStarred(ARTICLE_E, true)
    }

    private suspend fun store(vararg articles: Item) {
        database.itemDao().upsertArticles(articles.toList())
    }

    private fun article(id: Long, published: LocalDateTime, feedId: Int = FEED_ONE) =
        Item(id = id, title = "article $id", feedId = feedId, pubDate = published)

    private fun idsOf(query: SupportSQLiteQuery): List<Long> {
        val ids = mutableListOf<Long>()

        database.query(query).use { cursor ->
            val column = cursor.getColumnIndexOrThrow("id")
            while (cursor.moveToNext()) {
                ids += cursor.getLong(column)
            }
        }

        return ids
    }

    private companion object {
        const val FEED_ONE = 1
        const val FEED_TWO = 2

        const val ARTICLE_A = 1_625_234_531_559_678L
        const val ARTICLE_B = 1_625_234_531_559_679L
        const val ARTICLE_C = 1_625_234_531_559_680L
        const val ARTICLE_D = 1_625_234_531_559_681L
        const val ARTICLE_E = 1_625_234_531_559_682L
        const val ARTICLE_F = 1_625_234_531_559_683L

        /** An id the store has never held, which is what a deleted one looks like. */
        const val GONE = 1_625_234_531_559_999L

        const val NOW = 1_757_000_000_000L
        const val A_MINUTE = 60_000L
    }
}
