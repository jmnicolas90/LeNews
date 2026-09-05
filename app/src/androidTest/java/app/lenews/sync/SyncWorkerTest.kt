package app.lenews.sync

import android.app.Notification
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import app.lenews.testutil.LeNewsTestRule
import app.lenews.testutil.TestUtils
import app.lenews.testutil.okResponseWithBody
import app.lenews.util.extensions.getSerializable
import app.lenews.db.Database
import app.lenews.db.entities.account.Account
import app.lenews.db.entities.account.AccountType
import junit.framework.TestCase.assertNotNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koin.test.KoinTest
import org.koin.test.inject
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *
 * This test suite runs over [SyncWorker] setup and implementation:
 *  - WorkManagerTestInitHelper is used to test worker setup
 *  - TestListenableWorkerBuilder is used to test worker implementation
 *
 * Notifications are also tested:
 *  - Show notification
 *  - Trigger read and star actions
 *
 * Remaining to test:
 *  - [SyncWorker.startNow] which is currently untestable
 *  - Simultaneous execution between a manual and auto worker
 *  - Notification click (show the right screen)
 */
class SyncWorkerTest : KoinTest {

    private val database: Database by inject()
    private val notificationManager: NotificationManagerCompat by inject()
    private val mockServer = MockWebServer()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    // written from the MockWebServer dispatcher threads, read from the test thread
    private val editTagRequests = Collections.synchronizedList(mutableListOf<String>())

    @get:Rule
    val rule = LeNewsTestRule()

    private val account = Account(
        name = "Account",
        type = AccountType.FRESHRSS,
        url = mockServer.url("/").toString(),
        writeToken = "writeToken",
        isNotificationsEnabled = true
    )

    // an account with no url makes the synchronization fail before any request
    private val brokenAccount = Account(
        name = "Broken account",
        type = AccountType.FRESHRSS
    )

    @Before
    fun before() = runTest {
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)

        mockServer.dispatcher = greaderDispatcher("greader/items_1_item.json")

        account.id = database.accountDao().insert(account).toInt()
        brokenAccount.id = database.accountDao().insert(brokenAccount).toInt()
    }

    @After
    fun after() {
        mockServer.shutdown()
        database.clearAllTables()
        notificationManager.cancelAll()
        editTagRequests.clear()
    }

    /**
     * Answers the calls one synchronization makes, with a single new article.
     *
     * The article the notification is about is also the one id the unread ids call
     * returns, so the synchronization gives it a row in ItemState, which is where a
     * FreshRSS account keeps its read and starred state.
     */
    private fun greaderDispatcher(itemsResource: String) = object : Dispatcher() {

        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.requestUrl!!

            with(request.path!!) {
                return when {
                    contains("tag/list") -> {
                        MockResponse.okResponseWithBody(TestUtils.loadResource("greader/folders.json"))
                    }

                    contains("subscription/list") -> {
                        MockResponse.okResponseWithBody(TestUtils.loadResource("greader/feeds.json"))
                    }

                    contains("contents/user/-/state/com.google/reading-list") -> {
                        MockResponse.okResponseWithBody(TestUtils.loadResource(itemsResource))
                    }

                    contains("contents/user/-/state/com.google/starred") -> {
                        MockResponse.okResponseWithBody(TestUtils.loadResource("greader/items_empty.json"))
                    }

                    // the three ids calls differ by what they ask the server to
                    // leave out: nothing for the starred ids, the read articles for
                    // the unread ids, the unread ones for the read ids
                    contains("stream/items/ids") -> when (url.queryParameter("xt")) {
                        GOOGLE_READ ->
                            MockResponse.okResponseWithBody(TestUtils.loadResource("greader/items_unread_ids.json"))

                        GOOGLE_UNREAD ->
                            MockResponse.okResponseWithBody(TestUtils.loadResource("greader/items_no_ids.json"))

                        else ->
                            MockResponse.okResponseWithBody(TestUtils.loadResource("greader/items_starred_ids.json"))
                    }

                    contains("edit-tag") -> {
                        editTagRequests += URLDecoder.decode(request.body.readUtf8(), "UTF-8")
                        MockResponse().setResponseCode(200).setBody("OK")
                    }

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    @Test
    fun manualWorkerTest() = runTest {
        val worker = TestListenableWorkerBuilder.from<SyncWorker>(context, SyncWorker::class.java)
            .setTags(listOf(SyncWorker.WORK_MANUAL))
            .setInputData(workDataOf(SyncWorker.ACCOUNT_ID_KEY to account.id))
            .build()

        val result = worker.doWork()

        assertTrue { result is ListenableWorker.Result.Success }
        assertTrue { result.outputData.getBoolean(SyncWorker.END_SYNC_KEY, false) }

        assertEquals(0, notificationManager.activeNotifications.size)
    }

    @Test
    fun autoWorkerWithNotificationsTest() = runBlocking {
        val worker = TestListenableWorkerBuilder.from<SyncWorker>(context, SyncWorker::class.java)
            .setTags(listOf(SyncWorker.WORK_AUTO))
            .setInputData(workDataOf(SyncWorker.ACCOUNT_ID_KEY to account.id))
            .build()

        val result = worker.doWork()

        assertTrue { result is ListenableWorker.Result.Success }
        assertTrue { result.outputData.getBoolean(SyncWorker.END_SYNC_KEY, false) }

        val notification = with(notificationManager.activeNotifications.first()) {
            assertEquals(SyncWorker.SYNC_RESULT_NOTIFICATION_ID, id)
            assertEquals(
                "FreshRSS @ GitHub",
                this.notification.extras.getString(Notification.EXTRA_TITLE)
            )

            this.notification
        }

        // the actions are added in this order, and are triggered one at a time
        // because each one waits on the state the other wrote
        val (markReadAction, starAction) = notification.actions

        markReadAction.actionIntent.send()
        delay(1000L) // wait for global scope to execute in SyncBroadcastReceiver
        starAction.actionIntent.send()
        delay(1000L)

        // a FreshRSS account keeps its read and starred state in ItemState, which is
        // what the timeline reads; the Item row is not where the actions belong
        val itemState = database.itemStateDao().selectItemState(account.id, ITEM_REMOTE_ID)
        assertTrue { itemState.read }
        assertTrue { itemState.starred }

        // and both changes are queued for the next synchronization to upload
        val feed = database.feedDao().selectFeeds(account.id).first()
        val item = database.itemDao().selectItems(feed.id).first()
        assertTrue { database.itemStateChangeDao().readStateChangeExists(item.id) }
        assertTrue { database.itemStateChangeDao().starStateChangeExists(item.id) }

        // the next synchronization uploads them
        editTagRequests.clear()

        val nextWorker =
            TestListenableWorkerBuilder.from<SyncWorker>(context, SyncWorker::class.java)
                .setTags(listOf(SyncWorker.WORK_MANUAL))
                .setInputData(workDataOf(SyncWorker.ACCOUNT_ID_KEY to account.id))
                .build()

        assertTrue { nextWorker.doWork() is ListenableWorker.Result.Success }

        assertTrue {
            editTagRequests.any { it.contains("a=$GOOGLE_READ") && it.contains(ITEM_REMOTE_ID) }
        }
        assertTrue {
            editTagRequests.any { it.contains("a=$GOOGLE_STARRED") && it.contains(ITEM_REMOTE_ID) }
        }
    }

    @Test
    fun workerConflictTest() = runTest {
        val workManager = WorkManager.getInstance(context)
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!

        val request1 = OneTimeWorkRequestBuilder<SyncWorker>()
            .addTag(SyncWorker.TAG)
            .addTag(SyncWorker.WORK_MANUAL)
            .setInputData(workDataOf(SyncWorker.ACCOUNT_ID_KEY to account.id))
            .build()

        val request2 = OneTimeWorkRequestBuilder<SyncWorker>()
            .addTag(SyncWorker.TAG)
            .addTag(SyncWorker.WORK_MANUAL)
            .setInputData(workDataOf(SyncWorker.ACCOUNT_ID_KEY to account.id))
            .build()

        workManager.enqueue(request1)
        workManager.enqueue(request2)

        driver.setAllConstraintsMet(request1.id)
        driver.setAllConstraintsMet(request2.id)

        val workInfos = listOf(
            workManager.getWorkInfoById(request1.id).get(),
            workManager.getWorkInfoById(request2.id).get()
        )

        assertTrue { workInfos.any { it?.state == WorkInfo.State.FAILED } }
        val failedWorkInfo = workInfos.find { it?.state == WorkInfo.State.FAILED }!!
        assertEquals(true, failedWorkInfo.outputData.getBoolean(SyncWorker.SYNC_FAILURE_KEY, false))
        assertNotNull { failedWorkInfo.outputData.getSerializable(SyncWorker.SYNC_FAILURE_EXCEPTION_KEY) }
    }

    @Test
    fun periodicLaunchTest() {
        val workManager = WorkManager.getInstance(context)

        SyncWorker.startPeriodically(context, "1")
        var workInfo = workManager.getWorkInfosByTag(SyncWorker.WORK_AUTO).get()
            .first()
        assertTrue { workInfo.state == WorkInfo.State.ENQUEUED }
        assertEquals(TimeUnit.HOURS.toMillis(1L), workInfo.periodicityInfo?.repeatIntervalMillis)

        SyncWorker.startPeriodically(context, "manual")
        workInfo = workManager.getWorkInfoById(workInfo.id).get()!!
        assertTrue { workInfo.state == WorkInfo.State.CANCELLED }
    }

    @Test
    fun exceptionTest() = runTest {
        val manualWorker =
            TestListenableWorkerBuilder.from<SyncWorker>(context, SyncWorker::class.java)
                .setTags(listOf(SyncWorker.WORK_MANUAL))
                .setInputData(workDataOf(SyncWorker.ACCOUNT_ID_KEY to brokenAccount.id))
                .build()

        val result = manualWorker.doWork()

        assertTrue { result is ListenableWorker.Result.Failure }
        assertTrue { result.outputData.getBoolean(SyncWorker.SYNC_FAILURE_KEY, false) }
        assertNotNull { result.outputData.getSerializable(SyncWorker.SYNC_FAILURE_EXCEPTION_KEY) }

        val autoWorker =
            TestListenableWorkerBuilder.from<SyncWorker>(context, SyncWorker::class.java)
                .setTags(listOf(SyncWorker.WORK_AUTO))
                .setInputData(workDataOf(SyncWorker.ACCOUNT_ID_KEY to brokenAccount.id))
                .build()

        val autoResult = autoWorker.doWork()

        assertTrue { autoResult is ListenableWorker.Result.Failure }
        assertFalse { autoResult.outputData.getBoolean(SyncWorker.SYNC_FAILURE_KEY, false) }
    }

    companion object {

        // the one article in greader/items_1_item.json, as the unread ids call
        // returns it: decimal 1625234531559678 is hexadecimal 0005c62466ee28fe
        private const val ITEM_REMOTE_ID = "tag:google.com,2005:reader/item/0005c62466ee28fe"

        private const val GOOGLE_READ = "user/-/state/com.google/read"
        private const val GOOGLE_UNREAD = "user/-/state/com.google/unread"
        private const val GOOGLE_STARRED = "user/-/state/com.google/starred"
    }
}
