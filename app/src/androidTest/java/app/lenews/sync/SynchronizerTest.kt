package app.lenews.sync

import android.content.Context
import androidx.core.app.NotificationCompat.Builder
import androidx.test.core.app.ApplicationProvider
import app.lenews.R
import app.lenews.LeNewsApp
import app.lenews.testutil.LeNewsTestRule
import app.lenews.testutil.TestUtils
import app.lenews.testutil.okResponseWithBody
import app.lenews.testutil.stubServerOverTls
import app.lenews.testutil.tlsUrl
import app.lenews.db.Database
import app.lenews.db.entities.account.Account
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.koin.test.KoinTest
import org.koin.test.inject
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SynchronizerTest : KoinTest {

    // TODO database.accountDao().selectAllAccounts().first() test case
    // TODO FeedColors.getFeedColor in fetchFeedColors test case, but should wait for FeedColors.getFeedColor coil usage?

    private val database: Database by inject()
    private val synchronizer: Synchronizer by inject()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mockServer = stubServerOverTls()

    @get:Rule
    val rule = LeNewsTestRule()

    private val account = Account(
        name = "Account",
        url = mockServer.tlsUrl("/remote").toString(),
        writeToken = "writeToken"
    )

    @After
    fun after() {
        mockServer.shutdown()
        database.clearAllTables()
    }

    /**
     * One synchronization, and the store ends up with one row for one article.
     *
     * `greader/items.json` holds the same article twice — the same
     * `tag:google.com,2005:reader/item/0005c62466ee28fe` id — and the dispatcher
     * answers both the reading-list contents call and the starred contents call
     * with that same fixture, so the sync is handed the article four times. The
     * id is the article's primary key, so four deliveries are one row: the
     * duplicate the map called a pain point is what the schema now makes
     * impossible.
     */
    @Test
    fun syncStoresOneRowForOneArticle() = runTest {
        database.accountDao().upsert(account)

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

                        // the content of named articles, which nothing here needs
                        contains("stream/items/contents") -> {
                            MockResponse.okResponseWithBody(TestUtils.loadResource("greader/items_empty.json"))
                        }

                        // The id lists. The full list names the article that
                        // was delivered, because that is what a server saying
                        // "I still hold it" looks like and retention drops what
                        // it does not name. The unread list names other
                        // articles, so this one is read, and nothing is
                        // starred.
                        contains("stream/items/ids") -> {
                            val fixture = when {
                                contains("s=user/-/state/com.google/starred") ->
                                    "greader/items_no_ids.json"

                                contains("xt=") -> "greader/items_starred_ids.json"
                                else -> "greader/items_all_ids_one.json"
                            }
                            MockResponse.okResponseWithBody(TestUtils.loadResource(fixture))
                        }

                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }

        }

        val notificationBuilder = Builder(context, LeNewsApp.SYNC_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sync)

        synchronizer.synchronize(notificationBuilder)

        val feeds = database.feedDao().selectFeeds()
        assertEquals(1, feeds.size)

        // one row, whatever the number of times the article was delivered
        val items = database.itemDao().selectItems(feeds.first().id)
        assertEquals(1, items.size)
        assertEquals(1625234531559678L, items.first().id)
    }

    /**
     * An article the user read and starred on the web, delivered twice in one
     * response, the second delivery carrying an earlier publication date than
     * the first.
     *
     * Two rules meet here. The **last** occurrence of an id in a response wins
     * (§2 of `docs/article-store.md`), whatever the dates say: the content
     * stored is the second one, even though sorting the response by publication
     * date would have put it first. And a new article is inserted in the
     * **neutral** state (§3, step 4b) and given its state afterwards: storing
     * the read flag the content carried would leave a row that is read with no
     * `read_at`, which invariant 2 forbids and which nothing repairs later,
     * since marking read only touches rows that are unread. So the article ends
     * up read with the sync's clock on it — never read with nothing to say when.
     */
    @Test
    fun syncStoresAnArticleThatArrivesReadWithTheMomentItWasLearned() = runTest {
        database.accountDao().upsert(account)

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

                        // the id lists: the server holds this one article, does
                        // not call it unread and does call it starred, which is
                        // what it says about an article read and starred on the
                        // web
                        contains("stream/items/ids") -> {
                            val fixture = when {
                                contains("s=user/-/state/com.google/starred") ->
                                    "greader/items_starred_ids_one.json"

                                contains("xt=") -> "greader/items_no_ids.json"
                                else -> "greader/items_all_ids_one.json"
                            }
                            MockResponse.okResponseWithBody(TestUtils.loadResource(fixture))
                        }

                        // items
                        contains("contents/user/-/state/com.google/reading-list") -> {
                            MockResponse.okResponseWithBody(
                                TestUtils.loadResource("greader/items_one_id_twice_read_and_starred.json")
                            )
                        }

                        // starred items
                        contains("contents/user/-/state/com.google/starred") -> {
                            MockResponse.okResponseWithBody(TestUtils.loadResource("greader/items_empty.json"))
                        }

                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }

        }

        val notificationBuilder = Builder(context, LeNewsApp.SYNC_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sync)

        val beforeTheSync = System.currentTimeMillis()
        synchronizer.synchronize(notificationBuilder)
        val afterTheSync = System.currentTimeMillis()

        assertEquals(1, database.itemDao().count())

        with(database.itemDao().select(1625234531559678L)!!) {
            assertEquals("the delivery the server sent last", title, "the last delivery wins")
            assertEquals("the content the server sent last", content)

            assertTrue(isRead, "the server holds this article as read")
            val readAtValue = assertNotNull(readAt, "a read article says when it became read")
            assertTrue(
                readAtValue in beforeTheSync..afterTheSync,
                "a read learned at sync is stamped with the sync's clock"
            )

            assertTrue(isStarred, "the starred id list names it")
        }
    }
}
