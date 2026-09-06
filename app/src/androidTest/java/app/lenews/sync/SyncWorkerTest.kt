package app.lenews.sync

import android.app.Notification
import android.content.Context
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import app.lenews.testutil.LeNewsTestRule
import app.lenews.testutil.TestUtils
import app.lenews.testutil.okResponseWithBody
import app.lenews.testutil.stubServerOverTls
import app.lenews.testutil.tlsUrl
import app.lenews.db.Database
import app.lenews.db.deleteWhatRetentionDrops
import app.lenews.db.entities.account.Account
import app.lenews.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

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
    private val mockServer = stubServerOverTls()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    // written from the MockWebServer dispatcher threads, read from the test thread
    private val editTagRequests = Collections.synchronizedList(mutableListOf<String>())

    @get:Rule
    val rule = LeNewsTestRule()

    private val account = Account(
        name = "Account",
        url = mockServer.tlsUrl("/").toString(),
        writeToken = "writeToken",
        isNotificationsEnabled = true
    )

    // an account with no url makes the synchronization fail before any request
    private val brokenAccount = Account(name = "Broken account")

    @Before
    fun before() = runTest {
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)

        mockServer.dispatcher = greaderDispatcher("greader/items_1_item.json")

        database.accountDao().upsert(account)
    }

    @After
    fun after() {
        mockServer.shutdown()
        database.clearAllTables()
        notificationManager.cancelAll()
        // cancelAll is queued like every other notification call, so the next
        // test starts while this one's notifications may still be posted.
        // Waiting for them here is what keeps one test out of the next one's
        // assertions.
        awaitNotifications("the notifications of this test are cancelled") { it.isEmpty() }
        editTagRequests.clear()
    }

    /**
     * The active notifications, once they are as [description] says they should
     * be. Posting and cancelling are queued inside the system: the call hands
     * the work to system_server, which does it on a handler thread, while
     * activeNotifications reads the list from a binder thread. Reading it on the
     * line after the call that changed it is a race, and it is the race that
     * made this class fail about one run in several — under load on the bench
     * emulator, five runs in six.
     */
    private fun awaitNotifications(
        description: String,
        predicate: (List<StatusBarNotification>) -> Boolean
    ): List<StatusBarNotification> {
        val deadline = SystemClock.uptimeMillis() + NOTIFICATION_TIMEOUT_MS
        var active = notificationManager.activeNotifications

        while (true) {
            if (predicate(active)) {
                return active
            }
            if (SystemClock.uptimeMillis() >= deadline) {
                break
            }
            Thread.sleep(NOTIFICATION_POLL_MS)
            active = notificationManager.activeNotifications
        }

        fail(
            "$description — still not so after $NOTIFICATION_TIMEOUT_MS ms." +
                    " Active notification ids: ${active.map { it.id }}"
        )
    }

    /**
     * Answers the calls one synchronization makes, with a single new article.
     *
     * The article the notification is about is also the one id the unread ids
     * call returns, so the synchronization stores it unread.
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

                    // the content of named articles, which this account never
                    // needs: nothing is starred that the store does not hold
                    contains("stream/items/contents") -> {
                        MockResponse.okResponseWithBody(TestUtils.loadResource("greader/items_empty.json"))
                    }

                    // the three id lists differ by the stream they name and by
                    // what they ask the server to leave out: the starred stream,
                    // the reading list without its read articles, and the whole
                    // reading list
                    contains("stream/items/ids") -> when {
                        url.queryParameter("s") == GOOGLE_STARRED ->
                            MockResponse.okResponseWithBody(TestUtils.loadResource("greader/items_no_ids.json"))

                        url.queryParameter("xt") == GOOGLE_READ ->
                            MockResponse.okResponseWithBody(TestUtils.loadResource("greader/items_unread_ids.json"))

                        else ->
                            MockResponse.okResponseWithBody(TestUtils.loadResource("greader/items_all_ids_one.json"))
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
            .build()

        val result = worker.doWork()

        assertTrue { result is ListenableWorker.Result.Success }
        assertTrue { result.outputData.getBoolean(SyncWorker.END_SYNC_KEY, false) }

        // the worker cancels the progress notification when the sync ends, and
        // that cancellation is queued: this waits for it instead of reading the
        // list on the instant doWork() returned
        awaitNotifications("a manual sync leaves no notification behind") { it.isEmpty() }
    }

    @Test
    fun autoWorkerWithNotificationsTest() = runBlocking {
        val worker = TestListenableWorkerBuilder.from<SyncWorker>(context, SyncWorker::class.java)
            .setTags(listOf(SyncWorker.WORK_AUTO))
            .build()

        val result = worker.doWork()

        assertTrue { result is ListenableWorker.Result.Success }
        assertTrue { result.outputData.getBoolean(SyncWorker.END_SYNC_KEY, false) }

        // the result notification is posted from inside doWork(), which does
        // not wait for the system to have posted it
        val posted = awaitNotifications("the auto sync posts its result notification") { active ->
            active.any { it.id == SyncWorker.SYNC_RESULT_NOTIFICATION_ID }
        }

        val notification = with(posted.first { it.id == SyncWorker.SYNC_RESULT_NOTIFICATION_ID }) {
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

        // read and starred state lives on the article row, dated
        val item = assertNotNull(database.itemDao().select(ARTICLE_ID))
        assertTrue { item.isRead }
        assertTrue { item.isStarred }
        assertNotNull(item.readAt, "an article that became read carries the date it did")

        // and both changes are queued for the next synchronization to upload
        val pendingChange = assertNotNull(database.pendingChangeDao().select(ARTICLE_ID))
        assertEquals(true, pendingChange.read)
        assertEquals(true, pendingChange.starred)

        // the next synchronization uploads them
        editTagRequests.clear()

        val nextWorker =
            TestListenableWorkerBuilder.from<SyncWorker>(context, SyncWorker::class.java)
                .setTags(listOf(SyncWorker.WORK_MANUAL))
                .build()

        assertTrue { nextWorker.doWork() is ListenableWorker.Result.Success }

        // the id goes back to the server in its decimal form
        assertTrue {
            editTagRequests.any { it.contains("a=$GOOGLE_READ") && it.contains("$ARTICLE_ID") }
        }
        assertTrue {
            editTagRequests.any { it.contains("a=$GOOGLE_STARRED") && it.contains("$ARTICLE_ID") }
        }
    }

    /**
     * A notification names an article, and the sync that runs next can drop it:
     * retention deletes what FreshRSS no longer holds. Neither the mark read
     * action nor the star action may throw on the article that is gone, and
     * neither may queue a change for a row that no longer exists.
     */
    @Test
    fun theActionsOfANotificationWhoseArticleIsGoneDoNothing() = runBlocking {
        val worker = TestListenableWorkerBuilder.from<SyncWorker>(context, SyncWorker::class.java)
            .setTags(listOf(SyncWorker.WORK_AUTO))
            .build()

        assertTrue { worker.doWork() is ListenableWorker.Result.Success }

        val posted = awaitNotifications("the auto sync posts its result notification") { active ->
            active.any { it.id == SyncWorker.SYNC_RESULT_NOTIFICATION_ID }
        }
        val notification =
            posted.first { it.id == SyncWorker.SYNC_RESULT_NOTIFICATION_ID }.notification

        // the retention of a later sync drops the article the notification
        // names: it is unread, unstarred, and the server holds nothing any more
        database.withTransaction {
            database.deleteWhatRetentionDrops(emptyList(), System.currentTimeMillis())
        }
        assertNull(
            database.itemDao().select(ARTICLE_ID),
            "the article the notification names was not dropped, so this test proves nothing"
        )

        val (markReadAction, starAction) = notification.actions

        markReadAction.actionIntent.send()
        delay(1000L) // wait for global scope to execute in SyncBroadcastReceiver
        starAction.actionIntent.send()
        delay(1000L)

        assertNull(database.itemDao().select(ARTICLE_ID), "an action brought the article back")
        assertNull(
            database.pendingChangeDao().select(ARTICLE_ID),
            "an action queued a change for an article the store no longer holds"
        )
    }

    /**
     * A sync that has nothing new to say takes the notification the previous
     * one posted down, rather than leaving it pointing at articles a sync ago —
     * one of which retention may since have dropped.
     */
    @Test
    fun aSyncWithNothingNewTakesTheLastNotificationDown() = runBlocking<Unit> {
        fun autoWorker() =
            TestListenableWorkerBuilder.from<SyncWorker>(context, SyncWorker::class.java)
                .setTags(listOf(SyncWorker.WORK_AUTO))
                .build()

        assertTrue { autoWorker().doWork() is ListenableWorker.Result.Success }

        awaitNotifications("the auto sync posts its result notification") { active ->
            active.any { it.id == SyncWorker.SYNC_RESULT_NOTIFICATION_ID }
        }

        // the server sends the same article again, so this sync stores nothing new
        assertTrue { autoWorker().doWork() is ListenableWorker.Result.Success }

        awaitNotifications("a sync with nothing new takes the last notification down") { active ->
            active.none { it.id == SyncWorker.SYNC_RESULT_NOTIFICATION_ID }
        }
    }

    @Test
    fun workerConflictTest() = runTest {
        val workManager = WorkManager.getInstance(context)
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!

        val request1 = OneTimeWorkRequestBuilder<SyncWorker>()
            .addTag(SyncWorker.TAG)
            .addTag(SyncWorker.WORK_MANUAL)
            .build()

        val request2 = OneTimeWorkRequestBuilder<SyncWorker>()
            .addTag(SyncWorker.TAG)
            .addTag(SyncWorker.WORK_MANUAL)
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

        // the payload itself, not a lambda that is never run: assertNotNull { }
        // takes the lambda object as its argument, so what used to stand here
        // asserted that a function object is not null and never read the Data
        assertEquals(
            context.getString(R.string.background_sync_already_running),
            failedWorkInfo.outputData.getString(SyncWorker.SYNC_FAILURE_MESSAGE_KEY)
        )
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
        // one account means the broken one replaces the good one
        database.accountDao().upsert(brokenAccount)

        val manualWorker =
            TestListenableWorkerBuilder.from<SyncWorker>(context, SyncWorker::class.java)
                .setTags(listOf(SyncWorker.WORK_MANUAL))
                .build()

        val result = manualWorker.doWork()

        assertTrue { result is ListenableWorker.Result.Failure }
        assertTrue { result.outputData.getBoolean(SyncWorker.SYNC_FAILURE_KEY, false) }

        // the payload, not a lambda: the worker turns what it caught into a
        // sentence and puts it in the output Data, and for an account with no
        // url that is Koin failing to build the repository
        val message = assertNotNull(
            result.outputData.getString(SyncWorker.SYNC_FAILURE_MESSAGE_KEY),
            "the failure payload carries no message"
        )
        assertTrue(
            message.contains("BaseRepository"),
            "the failure payload does not say what failed: $message"
        )

        val autoWorker =
            TestListenableWorkerBuilder.from<SyncWorker>(context, SyncWorker::class.java)
                .setTags(listOf(SyncWorker.WORK_AUTO))
                .build()

        val autoResult = autoWorker.doWork()

        assertTrue { autoResult is ListenableWorker.Result.Failure }
        assertFalse { autoResult.outputData.getBoolean(SyncWorker.SYNC_FAILURE_KEY, false) }
    }

    companion object {

        // the one article in greader/items_1_item.json: the long form
        // tag:google.com,2005:reader/item/0005c62466ee28fe is this number, and
        // the unread ids call sends the same number in decimal
        private const val ARTICLE_ID = 1625234531559678L

        private const val GOOGLE_READ = "user/-/state/com.google/read"
        private const val GOOGLE_STARRED = "user/-/state/com.google/starred"

        // far more than the system needs when it is idle, far less than the
        // test framework's own timeout when it is not
        private const val NOTIFICATION_TIMEOUT_MS = 5_000L
        private const val NOTIFICATION_POLL_MS = 50L
    }
}
