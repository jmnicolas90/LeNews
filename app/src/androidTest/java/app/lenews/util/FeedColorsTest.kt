package app.lenews.util

import app.lenews.api.HttpClients
import app.lenews.api.utils.ApiUtils
import app.lenews.api.utils.exceptions.HttpException
import app.lenews.testutil.LeNewsTestRule
import app.lenews.testutil.StubServerTls
import app.lenews.testutil.TestUtils
import app.lenews.testutil.stubServerOverTls
import app.lenews.testutil.tlsUrl
import app.lenews.userAgent
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koin.test.KoinTest
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class FeedColorsTest : KoinTest {

    private val mockServer = stubServerOverTls()
    private val bodies = ResponseBodyCounter()

    /**
     * The client the icon is fetched with: built the way the app builds its
     * plain client, plus trust in the stub server's certificate and a listener
     * that says when a response body reached its end.
     */
    private val client: OkHttpClient = HttpClients(userAgent) { builder ->
        StubServerTls.trustTheStubServer(builder)
        builder.eventListener(bodies)
    }.plain

    @get:Rule
    val testRule = LeNewsTestRule()

    @Before
    fun before() {
        mockServer.start()
    }

    @After
    fun after() {
        mockServer.shutdown()
    }

    @Test
    fun getFeedColorTest() = runTest {
        val stream = TestUtils.loadResource("favicon.ico")

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(HttpURLConnection.HTTP_OK)
                .addHeader(ApiUtils.CONTENT_TYPE_HEADER, "image/jpeg")
                .setBody(Buffer().readFrom(stream))
        )

        val url = mockServer.tlsUrl("/rss").toString()
        val color = FeedColors.getFeedColor(url, client)

        assertTrue { color != 0 }
        assertEquals(1, bodies.ended, "the icon response was never closed")
    }

    /**
     * A server that refuses the icon. The error interceptor throws before the
     * caller ever holds the response, so the response has to have been closed
     * by then or nothing will close it.
     */
    @Test
    fun theResponseIsClosedWhenTheServerRefusesTheIcon() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(HttpURLConnection.HTTP_NOT_FOUND)
                .setBody("no icon here")
        )

        try {
            FeedColors.getFeedColor(mockServer.tlsUrl("/rss").toString(), client)
            fail("a 404 icon answered with a colour")
        } catch (e: HttpException) {
            assertEquals(404, e.code)
        }

        assertEquals(1, bodies.ended, "the refused response was never closed")
    }

    /**
     * A server that answers something that is not an image. Decoding fails, the
     * colour is 0, and the response is closed all the same — this is the path
     * that used to leak, because the body was only closed once decoding had
     * succeeded.
     */
    @Test
    fun theResponseIsClosedWhenTheBytesAreNotAnImage() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(HttpURLConnection.HTTP_OK)
                .addHeader(ApiUtils.CONTENT_TYPE_HEADER, "image/jpeg")
                .setBody("<html>this is a login page, not an icon</html>")
        )

        val color = FeedColors.getFeedColor(mockServer.tlsUrl("/rss").toString(), client)

        assertEquals(0, color, "bytes that are not an image answered with a colour")
        assertEquals(1, bodies.ended, "the undecodable response was never closed")
    }

    /** Counts the response bodies that reached their end, by being read or closed. */
    private class ResponseBodyCounter : EventListener() {

        private val count = AtomicInteger()

        val ended: Int
            get() = count.get()

        override fun responseBodyEnd(call: Call, byteCount: Long) {
            count.incrementAndGet()
        }
    }
}
