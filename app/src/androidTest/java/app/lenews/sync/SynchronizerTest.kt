package app.lenews.sync

import android.content.Context
import androidx.core.app.NotificationCompat.Builder
import androidx.test.core.app.ApplicationProvider
import app.lenews.R
import app.lenews.LeNewsApp
import app.lenews.testutil.LeNewsTestRule
import app.lenews.testutil.TestUtils
import app.lenews.testutil.okResponseWithBody
import app.lenews.db.Database
import app.lenews.db.entities.account.Account
import app.lenews.db.entities.account.AccountType
import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.koin.test.KoinTest
import org.koin.test.inject

class SynchronizerTest : KoinTest {

    // TODO database.accountDao().selectAllAccounts().first() test case
    // TODO FeedColors.getFeedColor in fetchFeedColors test case, but should wait for FeedColors.getFeedColor coil usage?

    private val database: Database by inject()
    private val synchronizer: Synchronizer by inject()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mockServer = MockWebServer()

    @get:Rule
    val rule = LeNewsTestRule()

    private val account = Account(
        name = "Account",
        type = AccountType.FRESHRSS,
        url = mockServer.url("/remote").toString(),
        writeToken = "writeToken"
    )

    @After
    fun after() {
        mockServer.shutdown()
        database.clearAllTables()
    }

    @Test
    fun synchronizeTest() = runTest {
        account.id = database.accountDao().insert(account).toInt()

        mockServer.dispatcher = object : Dispatcher() {

            override fun dispatch(request: RecordedRequest): MockResponse {
                with(request.path!!) {
                    return when {
                        contains("tag/list") -> {
                            MockResponse.okResponseWithBody(TestUtils.loadResource("greader/folders.json"))
                        }

                        contains("subscription/list") -> {
                            MockResponse.okResponseWithBody(TestUtils.loadResource("greader/feeds.json"))
                        }

                        // items
                        contains("contents/user/-/state/com.google/reading-list") -> {
                            MockResponse.okResponseWithBody(TestUtils.loadResource("greader/items.json"))
                        }

                        // starred items
                        contains("contents/user/-/state/com.google/starred") -> {
                            MockResponse.okResponseWithBody(TestUtils.loadResource("greader/items.json"))
                        }

                        // unread ids & starred ids
                        contains("stream/items/ids") -> {
                            MockResponse.okResponseWithBody(TestUtils.loadResource("greader/items_starred_ids.json"))
                        }

                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }

        }

        val notificationBuilder = Builder(context, LeNewsApp.SYNC_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sync)

        synchronizer.synchronizeAccounts(notificationBuilder, account.id)

        val feeds = database.feedDao().selectFeeds(account.id)
        assertEquals(1, feeds.size)

        // contains both unstarred and starred items
        val items = database.itemDao().selectItems(feeds.first().id)
        assertEquals(4, items.size)
    }
}
