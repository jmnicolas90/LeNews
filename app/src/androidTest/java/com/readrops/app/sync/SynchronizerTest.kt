package com.readrops.app.sync

import android.content.Context
import androidx.core.app.NotificationCompat.Builder
import androidx.test.core.app.ApplicationProvider
import com.readrops.app.R
import com.readrops.app.ReadropsApp
import com.readrops.app.testutil.ReadropsTestRule
import com.readrops.app.testutil.TestUtils
import com.readrops.app.testutil.okResponseWithBody
import com.readrops.db.Database
import com.readrops.db.entities.account.Account
import com.readrops.db.entities.account.AccountType
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
    val rule = ReadropsTestRule()

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

        val notificationBuilder = Builder(context, ReadropsApp.SYNC_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sync)

        synchronizer.synchronizeAccounts(notificationBuilder, account.id)

        val feeds = database.feedDao().selectFeeds(account.id)
        assertEquals(1, feeds.size)

        // contains both unstarred and starred items
        val items = database.itemDao().selectItems(feeds.first().id)
        assertEquals(4, items.size)
    }
}
