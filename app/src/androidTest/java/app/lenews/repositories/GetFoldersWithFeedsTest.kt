package app.lenews.repositories

import app.lenews.testutil.LeNewsTestRule
import app.lenews.db.Database
import app.lenews.db.entities.Feed
import app.lenews.db.entities.Folder
import app.lenews.db.entities.Item
import app.lenews.db.entities.account.Account
import app.lenews.db.filters.MainFilter
import junit.framework.Assert.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koin.test.KoinTest
import org.koin.test.inject
import java.time.LocalDateTime
import kotlin.test.assertNull

class GetFoldersWithFeedsTest : KoinTest {

    private val database: Database by inject()
    private val getFoldersWithFeeds: GetFoldersWithFeeds by inject()

    @get:Rule
    val koinTest = LeNewsTestRule()

    @Before
    fun before() = runTest {
        database.accountDao().upsert(Account(name = "FreshRSS"))

        // inserting 3 folders (folder 0, folder 1, folder 2)
        repeat(3) { time ->
            database.folderDao().insert(Folder(name = "Folder $time", remoteId = "folder/$time"))
        }

        // inserting 2 feeds, not linked to any folder (feed 0, feed 1)
        repeat(2) { time ->
            database.feedDao().insert(Feed(name = "Feed $time", remoteId = "feed/$time"))
        }

        // inserting 2 feeds linked to folder 0
        repeat(2) { time ->
            database.feedDao()
                .insert(Feed(name = "Feed ${time + 2}", remoteId = "feed/${time + 2}", folderId = 1))
        }

        // feed 0 gets 3 unread articles, 2 of them starred, 1 of them published
        // in the last 24 hours
        repeat(3) { time ->
            database.itemDao().insert(
                Item(
                    id = 100L + time,
                    title = "Article $time",
                    feedId = 1,
                    pubDate = if (time % 2 != 0) {
                        LocalDateTime.now()
                    } else {
                        LocalDateTime.now().minusMonths(1L)
                    },
                    isStarred = time % 2 == 0
                )
            )
        }

        // feed 2 gets 3 read articles
        repeat(3) { time ->
            database.itemDao().insert(
                Item(
                    id = 200L + time,
                    title = "Article ${time + 3}",
                    feedId = 3,
                    isRead = true,
                    readAt = System.currentTimeMillis(),
                    pubDate = LocalDateTime.now()
                )
            )
        }

        // folder 0 -> (feed 2, feed 3)
        // folder 1 -> null
        // folder 2 -> null
        // null -> (feed 0, feed 1)

        // feed 0 -> 3 unread articles, 2 starred, 1 published in the last 24 hours
        // feed 1 -> nothing
        // feed 2 -> 3 read articles
        // feed 3 -> nothing
    }

    @Test
    fun defaultCaseTest() = runTest {
        val foldersAndFeeds = getFoldersWithFeeds.get(
            mainFilter = MainFilter.ALL,
            hideReadFeeds = false
        ).first()

        assertEquals(4, foldersAndFeeds.size)
        assertEquals(2, foldersAndFeeds.entries.first().value.size)
        assertNull(foldersAndFeeds.entries.last().key)
        assertEquals(2, foldersAndFeeds[null]!!.size)
        assertEquals(3, foldersAndFeeds[null]!!.first().unreadCount)
    }

    @Test
    fun hideReadFeedsTest() = runTest {
        val foldersAndFeeds = getFoldersWithFeeds.get(
            mainFilter = MainFilter.ALL,
            hideReadFeeds = true
        ).first()

        assertEquals(1, foldersAndFeeds.size)
        assertNull(foldersAndFeeds.entries.first().key)
        assertEquals(1, foldersAndFeeds.entries.first().value.size)
        assertEquals(3, foldersAndFeeds.entries.first().value.first().unreadCount)
    }

    @Test
    fun starsFilterTest() = runTest {
        val foldersAndFeeds = getFoldersWithFeeds.get(
            mainFilter = MainFilter.STARS,
            hideReadFeeds = true
        ).first()

        assertEquals(1, foldersAndFeeds.size)
        assertEquals(2, foldersAndFeeds.values.flatten().sumOf { it.unreadCount })
    }

    @Test
    fun newFilterTest() = runTest {
        val foldersAndFeeds = getFoldersWithFeeds.get(
            mainFilter = MainFilter.NEW,
            hideReadFeeds = true
        ).first()

        assertEquals(1, foldersAndFeeds.size)
        assertEquals(1, foldersAndFeeds.values.flatten().sumOf { it.unreadCount })
    }

    @Test
    fun newItemsUnreadCountTest() = runTest {
        val count = getFoldersWithFeeds.getNewItemsUnreadCount().first()

        assertEquals(1, count)
    }
}
