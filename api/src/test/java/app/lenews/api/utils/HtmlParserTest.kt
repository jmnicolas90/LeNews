package app.lenews.api.utils

import android.nfc.FormatException
import app.lenews.api.HttpClients
import app.lenews.api.TestUtils
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HtmlParserTest {

    private val mockServer = MockWebServer()

    /**
     * The plain client, which is the one the new-feed screen fetches with: the
     * URL a user types is not the FreshRSS server and carries no credentials.
     */
    private val client = HttpClients("LeNews/0.0.0-test").plain

    @Before
    fun before() {
        mockServer.start()
    }

    @After
    fun after() {
        mockServer.shutdown()
    }

    @Test
    fun getFeedLinkTest() = runTest {
        val stream = TestUtils.loadResource("utils/file.html")

        mockServer.enqueue(
            MockResponse().setResponseCode(HttpURLConnection.HTTP_OK)
                .addHeader(ApiUtils.CONTENT_TYPE_HEADER, CONTENT_TYPE_HTML)
                .setBody(Buffer().readFrom(stream))
        )

        val links = HtmlParser.getFeedLink(mockServer.url("/rss").toString(), client)

        assertTrue { links.size == 2 }
        assertTrue { links.all { it.label!!.contains("The Mozilla Blog") } }
    }

    @Test(expected = FormatException::class)
    fun getFeedLinkWithoutHeadTest() = runTest {
        val stream = TestUtils.loadResource("utils/file_without_head.html")

        mockServer.enqueue(
            MockResponse().setResponseCode(HttpURLConnection.HTTP_OK)
                .addHeader(ApiUtils.CONTENT_TYPE_HEADER, CONTENT_TYPE_HTML)
                .setBody(Buffer().readFrom(stream))
        )

        HtmlParser.getFeedLink(mockServer.url("/rss").toString(), client)
    }

    @Test(expected = FormatException::class)
    fun getFeedLinkNoHtmlFileTest() = runTest {
        mockServer.enqueue(
            MockResponse().setResponseCode(HttpURLConnection.HTTP_OK)
                .addHeader(ApiUtils.CONTENT_TYPE_HEADER, "application/rss+xml")
        )

        HtmlParser.getFeedLink(mockServer.url("/rss").toString(), client)
    }

    @Test
    fun getFaviconLinkTest() = runTest {
        val stream = TestUtils.loadResource("utils/file.html")

        mockServer.enqueue(
            MockResponse().setResponseCode(HttpURLConnection.HTTP_OK)
                .addHeader(ApiUtils.CONTENT_TYPE_HEADER, CONTENT_TYPE_HTML)
                .setBody(Buffer().readFrom(stream))
        )

        val document = HtmlParser.getHTMLHeadFromUrl(mockServer.url("/rss").toString(), client)
        val link = HtmlParser.getFaviconLink(document)
        assertTrue { link!!.contains("apple-touch-icon") }
    }

    @Test
    fun getFaviconLinkWithoutHeadTest() = runTest {
        val stream = TestUtils.loadResource("utils/file_without_icon.html")

        mockServer.enqueue(
            MockResponse().setResponseCode(HttpURLConnection.HTTP_OK)
                .addHeader(ApiUtils.CONTENT_TYPE_HEADER, CONTENT_TYPE_HTML)
                .setBody(Buffer().readFrom(stream))
        )

        val document = HtmlParser.getHTMLHeadFromUrl(mockServer.url("/rss").toString(), client)
        val link = HtmlParser.getFaviconLink(document)
        assertNull(link)
    }

    @Test
    fun getFeedImageLinkTest() = runTest {
        val stream = TestUtils.loadResource("utils/file.html")

        mockServer.enqueue(
            MockResponse().setResponseCode(HttpURLConnection.HTTP_OK)
                .addHeader(ApiUtils.CONTENT_TYPE_HEADER, CONTENT_TYPE_HTML)
                .setBody(Buffer().readFrom(stream))
        )

        val document = HtmlParser.getHTMLHeadFromUrl(mockServer.url("/rss").toString(), client)
        val link = HtmlParser.getFeedImage(document)

        assertEquals(
            "https://blog.mozilla.org/wp-content/blogs.dir/278/files/2021/02/moz_blog_header_som_002_1200x600.jpg",
            link
        )
    }

    @Test
    fun getFeedDescriptionTest() = runTest {
        val stream = TestUtils.loadResource("utils/file.html")

        mockServer.enqueue(
            MockResponse().setResponseCode(HttpURLConnection.HTTP_OK)
                .addHeader(ApiUtils.CONTENT_TYPE_HEADER, CONTENT_TYPE_HTML)
                .setBody(Buffer().readFrom(stream))
        )

        val document = HtmlParser.getHTMLHeadFromUrl(mockServer.url("/rss").toString(), client)
        val description = HtmlParser.getFeedDescription(document)

        assertEquals("The Mozilla Blog", description)
    }

    @Test
    fun getFeedDescriptionNonUnicodeTest() = runTest {
        val stream = TestUtils.loadResource("utils/file_cp1253.html")

        mockServer.enqueue(
            MockResponse().setResponseCode(HttpURLConnection.HTTP_OK)
                .addHeader(ApiUtils.CONTENT_TYPE_HEADER, CONTENT_TYPE_HTML_CP1253)
                .setBody(Buffer().readFrom(stream))
        )

        val document = HtmlParser.getHTMLHeadFromUrl(mockServer.url("/rss").toString(), client)
        val description = HtmlParser.getFeedDescription(document)

        assertEquals("Μενέξενς", description)
    }

    private companion object {
        val CONTENT_TYPE_HTML = "text/html"
        val CONTENT_TYPE_HTML_CP1253 = "text/html; charset=cp1253"
    }
}