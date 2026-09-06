package app.lenews.util

import app.lenews.api.utils.ApiUtils
import app.lenews.testutil.LeNewsTestRule
import app.lenews.testutil.TestUtils
import app.lenews.testutil.stubServerOverTls
import app.lenews.testutil.tlsUrl
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koin.test.KoinTest
import java.net.HttpURLConnection
import kotlin.test.assertTrue

class FeedColorsTest : KoinTest {

    private val mockServer = stubServerOverTls()

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
        val color = FeedColors.getFeedColor(url)

        assertTrue { color != 0 }
    }
}