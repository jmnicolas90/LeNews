package app.lenews.sync

import android.content.Context
import app.lenews.R
import app.lenews.repositories.SyncResult
import app.lenews.testutil.LeNewsTestRule
import app.lenews.db.Database
import app.lenews.db.entities.Feed
import app.lenews.db.entities.Item
import app.lenews.db.entities.account.Account
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koin.test.KoinTest
import org.koin.test.get
import org.koin.test.inject
import java.time.LocalDateTime
import kotlin.test.assertNotNull

class SyncAnalyzerTest : KoinTest {

    private val database: Database by inject()
    private val syncAnalyzer: SyncAnalyzer by inject()

    @get:Rule
    val testRule = LeNewsTestRule()

    private val account = Account(
        name = "test account",
        isNotificationsEnabled = true
    )

    private val accountWithNotificationsOff = Account(
        name = "test account",
        isNotificationsEnabled = false
    )

    @Before
    fun before() = runTest {
        database.accountDao().upsert(account)

        // feed 1 has notifications on, feed 2 has them off
        database.feedDao().insert(
            Feed(
                name = "Feed 0",
                remoteId = "feed/0",
                iconUrl = "https://url.com/icon.jpg",
                isNotificationEnabled = true
            )
        )
        database.feedDao().insert(
            Feed(
                name = "Feed 1",
                remoteId = "feed/1",
                iconUrl = "https://url.com/icon.jpg",
                isNotificationEnabled = false
            )
        )
        database.feedDao().insert(
            Feed(
                name = "Feed 2",
                remoteId = "feed/2",
                iconUrl = "https://url.com/icon.jpg",
                isNotificationEnabled = true
            )
        )
    }

    @Test
    fun oneArticleFromOneFeedTest() = runTest {
        val item = Item(
            id = 1,
            title = "caseOneElementEveryWhere",
            feedId = 1,
            pubDate = LocalDateTime.now()
        )

        val content = syncAnalyzer.getNotificationContent(account, SyncResult(items = listOf(item)))

        assertNotNull(content)
        assertEquals("caseOneElementEveryWhere", content.text)
        assertEquals("Feed 0", content.title)
        assertTrue(content.largeIcon != null)
    }

    @Test
    fun severalArticlesFromOneFeedTest() = runTest {
        val item = Item(id = 1, title = "caseTwoItemsOneFeed", feedId = 1)
        val syncResult = SyncResult(items = listOf(item, item, item))

        syncAnalyzer.getNotificationContent(account, syncResult).let { content ->
            assertNotNull(content)

            assertEquals(get<Context>().getString(R.string.new_items, 3), content.text)
            assertEquals("Feed 0", content.title)
            assertTrue(content.largeIcon != null)
        }
    }

    @Test
    fun severalArticlesFromSeveralFeedsTest() = runTest {
        val item = Item(id = 1, feedId = 1)
        val item2 = Item(id = 2, feedId = 3)

        val syncResult = SyncResult(items = listOf(item, item2))
        val content = syncAnalyzer.getNotificationContent(account, syncResult)

        assertNotNull(content)
        assertEquals(get<Context>().getString(R.string.new_items, 2), content.text)
        assertEquals(account.name, content.title)
        assertTrue(content.largeIcon != null)
    }

    @Test
    fun accountNotificationsDisabledTest() = runTest {
        val item1 = Item(id = 1, title = "testAccountNotificationsDisabled", feedId = 1)
        val item2 = Item(id = 2, title = "testAccountNotificationsDisabled2", feedId = 1)

        val syncResult = SyncResult(items = listOf(item1, item2))
        assertNull(syncAnalyzer.getNotificationContent(accountWithNotificationsOff, syncResult))
    }

    @Test
    fun feedNotificationsDisabledTest() = runTest {
        val item1 = Item(id = 1, title = "testFeedNotificationsDisabled", feedId = 2)
        val item2 = Item(id = 2, title = "testFeedNotificationsDisabled2", feedId = 2)

        val syncResult = SyncResult(items = listOf(item1, item2))
        assertNull(syncAnalyzer.getNotificationContent(account, syncResult))
    }

    /**
     * Two feeds, one of which has notifications off, so only one article is
     * left: the notification is about that article and names its feed.
     */
    @Test
    fun oneFeedOfTwoWithNotificationsEnabledTest() = runTest {
        val item1 = Item(
            id = 1,
            title = "testTwoFeedsWithOneFeedNotificationEnabled",
            feedId = 1,
            pubDate = LocalDateTime.now()
        )

        val item2 = Item(id = 2, title = "from the silent feed", feedId = 2)
        val item3 = Item(id = 3, title = "from the silent feed again", feedId = 2)

        val syncResult = SyncResult(items = listOf(item1, item2, item3))
        val content = syncAnalyzer.getNotificationContent(account, syncResult)

        assertNotNull(content)
        assertEquals("testTwoFeedsWithOneFeedNotificationEnabled", content.text)
        assertEquals("Feed 0", content.title)
        assertTrue(content.largeIcon != null)
        assertTrue(content.item != null)
    }
}
