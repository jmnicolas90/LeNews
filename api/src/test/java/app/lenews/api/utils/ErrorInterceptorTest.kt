package app.lenews.api.utils

import app.lenews.api.utils.exceptions.HttpException
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.fail

class ErrorInterceptorTest {

    private val interceptor = ErrorInterceptor()
    private val server = MockWebServer()
    private lateinit var client: OkHttpClient

    @Before
    fun before() {
        client = OkHttpClient.Builder()
                .addInterceptor(interceptor)
                .build()

        server.start(8080)
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test(expected = HttpException::class)
    fun interceptorErrorTest() {
        server.enqueue(MockResponse().setResponseCode(HttpURLConnection.HTTP_NOT_FOUND))

        client.newCall(Request.Builder().url(server.url("/url")).build()).execute()
    }

    @Test
    fun interceptorSuccessTest() {
        server.enqueue(MockResponse().setResponseCode(HttpURLConnection.HTTP_NOT_MODIFIED))

        client.newCall(Request.Builder().url(server.url("/url")).build()).execute()
    }

    /**
     * A refused response is closed before the exception goes out.
     *
     * Nothing downstream can do it: the caller is handed an exception and never
     * sees the response, so if this interceptor does not close it, nothing does
     * until the garbage collector gets around to it — and until then the body
     * holds its connection. The two assertions are the two halves of that: the
     * body ended, and the connection went back to the pool, which is why the
     * next request opens no second socket.
     */
    @Test
    fun aRefusedResponseIsClosedAndItsConnectionReused() {
        val watched = CallWatcher()
        val watchedClient = client.newBuilder()
            .eventListener(watched)
            .build()

        server.enqueue(
            MockResponse()
                .setResponseCode(HttpURLConnection.HTTP_BAD_REQUEST)
                .setBody("Bad Request!")
        )

        try {
            watchedClient.newCall(Request.Builder().url(server.url("/refused")).build()).execute()
            fail("the interceptor let a 400 through")
        } catch (e: HttpException) {
            assertEquals(400, e.code)
        }

        assertEquals(1, watched.bodiesEnded, "the refused response was never closed")

        server.enqueue(MockResponse().setResponseCode(HttpURLConnection.HTTP_OK).setBody("OK"))
        watchedClient.newCall(Request.Builder().url(server.url("/taken")).build())
            .execute()
            .use { it.body?.string() }

        assertEquals(
            1,
            watched.connectionsOpened,
            "the refused response kept its connection out of the pool"
        )
    }

    /** Counts what the client did, from the outside. */
    private class CallWatcher : EventListener() {

        private val bodies = AtomicInteger()
        private val connections = AtomicInteger()

        /** Response bodies that reached their end, by being read or closed. */
        val bodiesEnded: Int
            get() = bodies.get()

        /** Sockets opened, as opposed to taken back out of the pool. */
        val connectionsOpened: Int
            get() = connections.get()

        override fun responseBodyEnd(call: Call, byteCount: Long) {
            bodies.incrementAndGet()
        }

        override fun connectStart(
            call: Call,
            inetSocketAddress: InetSocketAddress,
            proxy: Proxy
        ) {
            connections.incrementAndGet()
        }
    }
}
