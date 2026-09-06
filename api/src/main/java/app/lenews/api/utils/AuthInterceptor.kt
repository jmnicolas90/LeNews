package app.lenews.api.utils

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds the FreshRSS authorization header, and adds it to nothing else.
 *
 * The token and the server it belongs to are fixed when the interceptor is
 * built and never change afterwards. A new token means a new interceptor on a
 * new client (see `HttpClients`), so no request can pick up a token meant for
 * another server, and no request can lose halfway through the one it started
 * with.
 *
 * The header goes on only when the request is going to the configured server.
 * The comparison is on the parsed URL — scheme, host and port, all three — and
 * never on the text of the URL. `https://rss.lan.evil.example/` starts with the
 * configured server's URL as text and is another host; so does a URL that
 * writes `rss.lan` as the user name of `evil.example`, where the parsed host is
 * `evil.example` and the part that looks like the server is user info.
 *
 * It is a *network* interceptor, which is what makes the check hold per
 * request rather than per call: OkHttp runs it again for every hop, so a
 * redirect that lands on another host is checked again and goes out bare.
 */
class AuthInterceptor(
    private val authorization: String,
    private val serverUrl: HttpUrl
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        return if (goesToTheServer(request.url)) {
            chain.proceed(
                request.newBuilder()
                    .header(AUTHORIZATION_HEADER, authorization)
                    .build()
            )
        } else {
            chain.proceed(request)
        }
    }

    /**
     * True when [url] names the same server as the configured URL: same scheme,
     * same host, same port. The path is irrelevant — every FreshRSS call is
     * under the same server — and the user info is deliberately ignored rather
     * than compared, because it is not part of what a host is.
     */
    fun goesToTheServer(url: HttpUrl): Boolean =
        url.scheme == serverUrl.scheme &&
                url.host == serverUrl.host &&
                url.port == serverUrl.port

    companion object {
        const val AUTHORIZATION_HEADER = "Authorization"
    }
}
