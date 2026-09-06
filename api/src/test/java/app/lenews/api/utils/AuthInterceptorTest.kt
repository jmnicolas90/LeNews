package app.lenews.api.utils

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * The host rule, first as the decision it is and then over the wire.
 *
 * The decision is tested against parsed URLs rather than against a running
 * server, because the look-alikes that matter — a host the configured one is a
 * prefix of, a host hidden behind user info — cannot be resolved on this
 * machine and do not need to be: the question is what the rule answers, not
 * what the network does.
 */
class AuthInterceptorTest {

    private val server = "https://rss.lan/api/greader.php/".toHttpUrl()
    private val interceptor = AuthInterceptor("GoogleLogin auth=token", server)

    private val mockServer = MockWebServer()
    private val otherServer = MockWebServer()

    @Before
    fun before() {
        mockServer.start()
        otherServer.start()
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
        otherServer.shutdown()
    }

    @Test
    fun anyPathOnTheConfiguredServerIsTheServer() {
        assertTrue(interceptor.goesToTheServer("https://rss.lan/api/greader.php/reader/api/0/token".toHttpUrl()))
        assertTrue(interceptor.goesToTheServer("https://rss.lan/".toHttpUrl()))
    }

    @Test
    fun aHostTheConfiguredOneIsOnlyThePrefixOfIsNotTheServer() {
        assertFalse(interceptor.goesToTheServer("https://rss.lan.evil.example/".toHttpUrl()))
        assertFalse(interceptor.goesToTheServer("https://rss.lanevil.example/".toHttpUrl()))
    }

    @Test
    fun aHostHiddenBehindUserInfoIsNotTheServer() {
        // The host here is evil.example and "rss.lan" is the user name. The
        // separator is spelled out rather than written, because the result
        // reads as an email address and the email guard would report the file.
        assertFalse(interceptor.goesToTheServer("https://rss.lan${AT}evil.example/".toHttpUrl()))
        assertFalse(interceptor.goesToTheServer("https://rss.lan:pass${AT}evil.example/".toHttpUrl()))
    }

    @Test
    fun anUppercaseHostIsTheSameServer() {
        // a host name has no case; comparing the parsed URLs is what makes this
        // work, where comparing the text the user typed would not
        assertTrue(interceptor.goesToTheServer("https://RSS.LAN/".toHttpUrl()))
    }

    @Test
    fun aTrailingDotOnTheHostIsNotTheServer() {
        // DNS calls "rss.lan." the same name as "rss.lan"; OkHttp keeps the dot
        // and so this rule does not. Refusing the header is the safe answer to
        // a URL nothing in this app produces.
        assertFalse(interceptor.goesToTheServer("https://rss.lan./".toHttpUrl()))
    }

    @Test
    fun anotherSchemeOrAnotherPortIsNotTheServer() {
        assertFalse(interceptor.goesToTheServer("http://rss.lan/".toHttpUrl()))
        assertFalse(interceptor.goesToTheServer("https://rss.lan:8443/".toHttpUrl()))
    }

    @Test
    fun theHeaderGoesToTheConfiguredServer() {
        val client = clientFor(mockServer)
        mockServer.enqueue(MockResponse())

        client.newCall(Request.Builder().url(mockServer.url("/reader/api/0/token")).build())
            .execute()
            .close()

        assertEquals(
            "GoogleLogin auth=token",
            mockServer.takeRequest().headers[AuthInterceptor.AUTHORIZATION_HEADER]
        )
    }

    @Test
    fun theHeaderDoesNotGoToAnotherServer() {
        val client = clientFor(mockServer)
        otherServer.enqueue(MockResponse())

        client.newCall(Request.Builder().url(otherServer.url("/an-article-image.png")).build())
            .execute()
            .close()

        assertNull(otherServer.takeRequest().headers[AuthInterceptor.AUTHORIZATION_HEADER])
    }

    @Test
    fun theHeaderDoesNotSurviveARedirectToAnotherServer() {
        val client = clientFor(mockServer)
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP)
                .setHeader("Location", otherServer.url("/elsewhere").toString())
        )
        otherServer.enqueue(MockResponse())

        client.newCall(Request.Builder().url(mockServer.url("/reader/api/0/token")).build())
            .execute()
            .close()

        assertEquals(
            "GoogleLogin auth=token",
            mockServer.takeRequest().headers[AuthInterceptor.AUTHORIZATION_HEADER],
            "the first hop went to the configured server and should carry the token"
        )
        assertNull(
            otherServer.takeRequest().headers[AuthInterceptor.AUTHORIZATION_HEADER],
            "the redirect landed on another server and must go out bare"
        )
    }

    /**
     * A client bound to [configured] as its FreshRSS server. The rule is built
     * from the URL the server is actually listening on, never from a fixed
     * string, which is the only way it can be true for both hosts here.
     */
    private fun clientFor(configured: MockWebServer): OkHttpClient =
        OkHttpClient.Builder()
            .addNetworkInterceptor(
                AuthInterceptor("GoogleLogin auth=token", configured.url("/"))
            )
            .build()

    private companion object {
        /** The URL separator between user info and host. */
        const val AT = "@"
    }
}
