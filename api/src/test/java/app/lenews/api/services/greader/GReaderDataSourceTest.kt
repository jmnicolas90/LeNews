package app.lenews.api.services.greader

import app.lenews.api.TestUtils
import app.lenews.api.apiModule
import app.lenews.api.enqueueOK
import app.lenews.api.enqueueOKStream
import app.lenews.api.okResponseWithBody
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.test.KoinTest
import org.koin.test.KoinTestRule
import org.koin.test.get
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GReaderDataSourceTest : KoinTest {

    private lateinit var freshRSSDataSource: GReaderDataSource
    private val mockServer = MockWebServer()

    @get:Rule
    val koinTestRule = KoinTestRule.create {
        modules(apiModule, module {
            single {
                Retrofit.Builder()
                    .baseUrl("http://localhost:8080/")
                    .client(get())
                    .addConverterFactory(MoshiConverterFactory.create(get(named("greaderMoshi"))))
                    .build()
                    .create(GReaderService::class.java)
            }
        })
    }

    @Before
    fun before() {
        mockServer.start(8080)
        freshRSSDataSource = GReaderDataSource(get())
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
    }

    @Test
    fun loginTest() = runTest {
        val responseBody = TestUtils.loadResource("services/greader/login_response_body")
        mockServer.enqueueOKStream(responseBody)

        val authString = freshRSSDataSource.login("Login", "Password")
        assertEquals("login/p1f8vmzid4hzzxf31mgx50gt8pnremgp4z8xe44a", authString)

        val request = mockServer.takeRequest()
        val requestBody = request.body.readUtf8()

        assertTrue {
            requestBody.contains("name=\"Email\"") && requestBody.contains("Login")
        }

        assertTrue {
            requestBody.contains("name=\"Passwd\"") && requestBody.contains("Password")
        }
    }

    @Test
    fun writeTokenTest() = runTest {
        val responseBody = TestUtils.loadResource("services/greader/writetoken_response_body")
        mockServer.enqueueOKStream(responseBody)

        val writeToken = freshRSSDataSource.getWriteToken()

        assertEquals("PMvYZHrnC57cyPLzxFvQmJEGN6KvNmkHCmHQPKG5eznWMXriq13H1nQZg", writeToken)
    }

    @Test
    fun userInfoTest() = runTest {
        val responseBody = TestUtils.loadResource("services/greader/adapters/user_info.json")
        mockServer.enqueueOKStream(responseBody)

        val userInfo = freshRSSDataSource.getUserInfo()

        assertEquals("test", userInfo.userName)
    }

    @Test
    fun foldersTest() = runTest {
        val stream = TestUtils.loadResource("services/greader/adapters/folders.json")
        mockServer.enqueueOKStream(stream)

        val (folders) = freshRSSDataSource.getFolders()
        assertTrue { folders.size == 1 }
    }

    @Test
    fun feedsTest() = runTest {
        val stream = TestUtils.loadResource("services/greader/adapters/feeds.json")
        mockServer.enqueueOKStream(stream)

        val feeds = freshRSSDataSource.getFeeds()
        assertTrue { feeds.size == 1 }
    }

    @Test
    fun itemsTest() = runTest {
        val stream = TestUtils.loadResource("services/greader/adapters/items.json")
        mockServer.enqueueOKStream(stream)

        val items = freshRSSDataSource.getItems(
            excludeTarget = GReaderDataSource.GOOGLE_READ,
            cursor = 21343321321321
        )
        assertTrue { items.size == 2 }

        val request = mockServer.takeRequest()

        with(request.requestUrl!!) {
            assertEquals(GReaderDataSource.GOOGLE_READ, queryParameter("xt"))
            // the page size the FreshRSS maintainer recommends for contents
            assertEquals("1000", queryParameter("n"))
            assertEquals("21343321321321", queryParameter("ot"))
            assertNull(queryParameter("c"), "the first page asks for no continuation")
        }
    }

    @Test
    fun starredItemsTest() = runTest {
        val stream = TestUtils.loadResource("services/greader/adapters/items.json")
        mockServer.enqueueOKStream(stream)

        val items = freshRSSDataSource.getStarredItems()
        assertTrue { items.size == 2 }

        val request = mockServer.takeRequest()

        assertEquals("1000", request.requestUrl!!.queryParameter("n"))
    }

    @Test
    fun getItemsIdsTest() = runTest {
        mockServer.enqueueOKStream(
            TestUtils.loadResource("services/greader/adapters/items_starred_ids.json")
        )

        val ids = freshRSSDataSource.getItemsIds(
            excludeTarget = GReaderDataSource.GOOGLE_READ,
            includeTarget = GReaderDataSource.GOOGLE_READING_LIST
        )
        assertEquals(5, ids.size)

        val request = mockServer.takeRequest()
        with(request.requestUrl!!) {
            assertEquals(GReaderDataSource.GOOGLE_READ, queryParameter("xt"))
            assertEquals(GReaderDataSource.GOOGLE_READING_LIST, queryParameter("s"))
            // the page size the FreshRSS maintainer recommends for id lists
            assertEquals("10000", queryParameter("n"))
        }
    }

    /**
     * The walk stops when the server sends no continuation, and every page but
     * the first sends back the token the page before it carried.
     */
    @Test
    fun idsAreReadToTheEndOfTheContinuation() = runTest {
        mockServer.enqueueJson(
            """{ "itemRefs": [ { "id": "1" }, { "id": "2" } ], "continuation": "2" }"""
        )
        mockServer.enqueueJson("""{ "itemRefs": [ { "id": "3" } ] }""")

        val ids = freshRSSDataSource.getItemsIds(null, GReaderDataSource.GOOGLE_READING_LIST)

        assertEquals(listOf(1L, 2L, 3L), ids)
        assertNull(mockServer.takeRequest().requestUrl!!.queryParameter("c"))
        assertEquals("2", mockServer.takeRequest().requestUrl!!.queryParameter("c"))
        assertEquals(2, mockServer.requestCount)
    }

    @Test
    fun contentsAreReadToTheEndOfTheContinuation() = runTest {
        mockServer.enqueueJson(
            """{ "items": [ ${itemJson(1)} ], "continuation": "1" }"""
        )
        mockServer.enqueueJson("""{ "items": [ ${itemJson(2)} ] }""")

        val items = freshRSSDataSource.getItems(excludeTarget = null, cursor = null)

        assertEquals(listOf(1L, 2L), items.map { it.id })
        assertNull(mockServer.takeRequest().requestUrl!!.queryParameter("c"))
        assertEquals("1", mockServer.takeRequest().requestUrl!!.queryParameter("c"))
    }

    /**
     * FreshRSS applies the ids of one `edit-tag` call in statements of at most
     * 998 and truncates a request body at 1 MiB without saying so, so a batch
     * is at most 998 ids and the caller is told which ones went up.
     */
    @Test
    fun stateUploadsAreSplitInBatchesOfAtMost998() = runTest {
        val ids = (1L..2000L).toList()
        repeat(3) { mockServer.enqueueOK() }

        val accepted = mutableListOf<Pair<ArticleStateChange, List<Long>>>()
        freshRSSDataSource.uploadPendingChanges(
            GReaderSyncData(readIds = ids),
            "writeToken"
        ) { change, batch -> accepted += change to batch }

        assertEquals(3, mockServer.requestCount)
        assertEquals(listOf(998, 998, 4), accepted.map { it.second.size })
        assertEquals(ids, accepted.flatMap { it.second })
        assertTrue { accepted.all { it.first == ArticleStateChange.READ } }

        val firstBatch = mockServer.takeRequest().body.readUtf8()
        assertEquals(998, firstBatch.split("i=").size - 1)
        assertTrue { firstBatch.contains("T=writeToken") }
    }

    /** One request per state, and only for the states that have something to say. */
    @Test
    fun eachStateGoesUpInItsOwnRequest() = runTest {
        repeat(2) { mockServer.enqueueOK() }

        val accepted = mutableListOf<ArticleStateChange>()
        freshRSSDataSource.uploadPendingChanges(
            GReaderSyncData(readIds = listOf(1L), unstarredIds = listOf(2L)),
            "writeToken"
        ) { change, _ -> accepted += change }

        assertEquals(
            listOf(ArticleStateChange.READ, ArticleStateChange.UNSTARRED),
            accepted
        )

        with(mockServer.takeRequest().body.readUtf8()) {
            assertTrue(contains("a=user%2F-%2Fstate%2Fcom.google%2Fread"), this)
            assertTrue(contains("i=1"), this)
        }
        with(mockServer.takeRequest().body.readUtf8()) {
            assertTrue(contains("r=user%2F-%2Fstate%2Fcom.google%2Fstarred"), this)
            assertTrue(contains("i=2"), this)
        }
    }

    /** The content of named articles goes up in batches of at most 998 too. */
    @Test
    fun contentOfNamedArticlesIsAskedForInBatches() = runTest {
        repeat(2) { mockServer.enqueueJson("""{ "items": [] }""") }

        freshRSSDataSource.getItemsContents((1L..1000L).toList(), "writeToken")

        assertEquals(2, mockServer.requestCount)
        assertEquals(998, mockServer.takeRequest().body.readUtf8().split("i=").size - 1)
        assertEquals(2, mockServer.takeRequest().body.readUtf8().split("i=").size - 1)
    }

    @Test
    fun createFeedTest() = runTest {
        mockServer.enqueueOK()

        freshRSSDataSource.createFeed("token", "https://feed.url", "feed/1")
        val request = mockServer.takeRequest()

        with(request.body.readUtf8()) {
            assertTrue { contains("T=token") }
            assertTrue { contains("a=feed%2F1") }
            assertTrue {
                contains(
                    "s=${
                        URLEncoder.encode(
                            "${GReaderDataSource.FEED_PREFIX}https://feed.url", "UTF-8"
                        )
                    }"
                )
            }
            assertTrue { contains("ac=subscribe") }
        }
    }

    @Test
    fun deleteFeedTest() = runTest {
        mockServer.enqueueOK()

        freshRSSDataSource.deleteFeed("token", "https://feed.url")
        val request = mockServer.takeRequest()

        with(request.body.readUtf8()) {
            assertTrue { contains("T=token") }
            assertTrue {
                contains(
                    "s=${
                        URLEncoder.encode(
                            "${GReaderDataSource.FEED_PREFIX}https://feed.url",
                            "UTF-8"
                        )
                    }"
                )
            }
            assertTrue { contains("ac=unsubscribe") }
        }
    }

    @Test
    fun updateFeedTest() = runTest {
        mockServer.enqueueOK()

        freshRSSDataSource.updateFeed("token", "https://feed.url", "title", "folderId")
        val request = mockServer.takeRequest()

        with(request.body.readUtf8()) {
            assertTrue { contains("T=token") }
            assertTrue {
                contains(
                    "s=${
                        URLEncoder.encode(
                            "${GReaderDataSource.FEED_PREFIX}https://feed.url",
                            "UTF-8"
                        )
                    }"
                )
            }
            assertTrue { contains("t=title") }
            assertTrue { contains("a=folderId") }
            assertTrue { contains("ac=edit") }
        }
    }

    @Test
    fun createFolderTest() = runTest {
        mockServer.enqueueOK()

        freshRSSDataSource.createFolder("token", "folder")
        val request = mockServer.takeRequest()

        with(request.body.readUtf8()) {
            assertTrue { contains("T=token") }
            assertTrue {
                contains(
                    "a=${
                        URLEncoder.encode(
                            "${GReaderDataSource.FOLDER_PREFIX}folder",
                            "UTF-8"
                        )
                    }"
                )
            }
        }
    }

    @Test
    fun updateFolderTest() = runTest {
        mockServer.enqueueOK()

        freshRSSDataSource.updateFolder("token", "folderId", "folder")
        val request = mockServer.takeRequest()

        with(request.body.readUtf8()) {
            assertTrue { contains("T=token") }
            assertTrue { contains("s=folderId") }
            assertTrue {
                contains(
                    "dest=${
                        URLEncoder.encode(
                            "${GReaderDataSource.FOLDER_PREFIX}folder",
                            "UTF-8"
                        )
                    }"
                )
            }
        }
    }

    @Test
    fun deleteFolderTest() = runTest {
        mockServer.enqueueOK()

        freshRSSDataSource.deleteFolder("token", "folderId")
        val request = mockServer.takeRequest()

        with(request.body.readUtf8()) {
            assertTrue { contains("T=token") }
            assertTrue { contains("s=folderId") }
        }
    }

    /**
     * §7: the first sync asks for the unread articles and the starred articles,
     * for no read article at all, and for the same three id lists as any other
     * sync.
     */
    @Test
    fun theInitialSyncPullsUnreadAndStarredContentAndTheThreeIdLists() = runTest {
        val paths = Collections.synchronizedList(mutableListOf<String>())
        mockServer.dispatcher = recordingDispatcher(paths)

        val result = freshRSSDataSource.synchronize(
            cursor = GReaderDataSource.NO_CURSOR,
            pendingChanges = GReaderSyncData(),
            writeToken = "writeToken"
        ) { _, _ -> }

        with(result) {
            assertEquals(1, folders.size)
            assertEquals(1, feeds.size)
            // two from the reading list and the same two from the starred stream
            assertEquals(4, items.size)
            assertEquals(5, serverIds.size)
            assertEquals(5, unreadIds.size)
            assertEquals(5, starredIds.size)
        }

        val readingList = paths.first { it.contains("contents/user/-/state/com.google/reading-list") }
        assertTrue(readingList.contains("xt="), "the initial sync asked for read articles: $readingList")
        assertTrue(!readingList.contains("ot="), "the initial sync sent a cursor: $readingList")
        assertTrue(paths.any { it.contains("contents/user/-/state/com.google/starred") })
    }

    /** A later sync uploads first, then asks the reading list for what changed. */
    @Test
    fun aLaterSyncUploadsThenPullsFromTheCursor() = runTest {
        val paths = Collections.synchronizedList(mutableListOf<String>())
        mockServer.dispatcher = recordingDispatcher(paths)

        val ids = listOf(1L, 2L, 3L, 4L)
        val accepted = mutableListOf<ArticleStateChange>()

        val result = freshRSSDataSource.synchronize(
            cursor = 10L,
            pendingChanges = GReaderSyncData(
                readIds = ids,
                unreadIds = ids,
                starredIds = ids,
                unstarredIds = ids
            ),
            writeToken = "writeToken"
        ) { change, _ -> accepted += change }

        assertEquals(
            listOf(
                ArticleStateChange.READ,
                ArticleStateChange.UNREAD,
                ArticleStateChange.STARRED,
                ArticleStateChange.UNSTARRED
            ),
            accepted
        )
        assertEquals(4, paths.count { it.contains("edit-tag") })

        with(result) {
            assertEquals(1, folders.size)
            assertEquals(1, feeds.size)
            // the starred stream is not walked once there is a cursor
            assertEquals(2, items.size)
            assertEquals(5, serverIds.size)
            assertEquals(5, unreadIds.size)
            assertEquals(5, starredIds.size)
        }

        val readingList = paths.first { it.contains("contents/user/-/state/com.google/reading-list") }
        assertTrue(readingList.contains("ot=10"), readingList)
        assertTrue(!readingList.contains("xt="), "a later sync must fetch read articles too")
        assertTrue(paths.none { it.contains("contents/user/-/state/com.google/starred") })
    }

    /**
     * A batch the server refuses fails the sync before anything is pulled, so
     * the caller's queue is intact and its store untouched.
     */
    @Test
    fun aRefusedBatchStopsTheSyncBeforeAnythingIsPulled() = runTest {
        val paths = Collections.synchronizedList(mutableListOf<String>())
        mockServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.path!!
                return MockResponse().setResponseCode(HttpURLConnection.HTTP_INTERNAL_ERROR)
            }
        }

        val accepted = mutableListOf<ArticleStateChange>()
        assertFailsWith<Exception> {
            freshRSSDataSource.synchronize(
                cursor = 10L,
                pendingChanges = GReaderSyncData(readIds = listOf(1L)),
                writeToken = "writeToken"
            ) { change, _ -> accepted += change }
        }

        assertEquals(listOf("edit-tag"), paths.map { if (it.contains("edit-tag")) "edit-tag" else it })
        assertTrue(accepted.isEmpty(), "a refused batch must not be reported as accepted")
    }

    private fun recordingDispatcher(paths: MutableList<String>) = object : Dispatcher() {

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path!!
            paths += path

            return when {
                path.contains("edit-tag") -> MockResponse().setResponseCode(200)

                path.contains("tag/list") -> MockResponse.okResponseWithBody(
                    TestUtils.loadResource("services/greader/adapters/folders.json")
                )

                path.contains("subscription/list") -> MockResponse.okResponseWithBody(
                    TestUtils.loadResource("services/greader/adapters/feeds.json")
                )

                path.contains("stream/contents") -> MockResponse.okResponseWithBody(
                    TestUtils.loadResource("services/greader/adapters/items.json")
                )

                path.contains("stream/items/ids") -> MockResponse()
                    .setResponseCode(HttpURLConnection.HTTP_OK)
                    .setBody(FIVE_IDS)

                else -> MockResponse().setResponseCode(HttpURLConnection.HTTP_NOT_FOUND)
            }
        }
    }

    private fun MockWebServer.enqueueJson(body: String) {
        enqueue(MockResponse().setResponseCode(HttpURLConnection.HTTP_OK).setBody(body))
    }

    private fun itemJson(id: Long): String = """
        {
          "id": "tag:google.com,2005:reader/item/${java.lang.Long.toUnsignedString(id, 16).padStart(16, '0')}",
          "published": 1625234040,
          "title": "article $id",
          "summary": { "content": "content of $id" },
          "categories": [ "user/-/state/com.google/reading-list" ],
          "origin": { "streamId": "feed/2" }
        }
    """.trimIndent()

    private companion object {

        /** Five ids and no continuation, which is one whole id list. */
        val FIVE_IDS = """
            { "itemRefs": [ { "id": "1" }, { "id": "2" }, { "id": "3" },
                            { "id": "4" }, { "id": "5" } ] }
        """.trimIndent()
    }
}
