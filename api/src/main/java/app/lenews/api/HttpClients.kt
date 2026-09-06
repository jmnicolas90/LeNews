/*
 * Copyright (C) 2026 Jean-Michel Nicolas
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package app.lenews.api

import app.lenews.api.services.Credentials
import app.lenews.api.utils.AuthInterceptor
import app.lenews.api.utils.ErrorInterceptor
import app.lenews.api.utils.UserAgentInterceptor
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.TimeUnit

/**
 * The two HTTP clients this app makes requests with, and the only place either
 * of them is built.
 *
 * [plain] never carries the FreshRSS token. Article images, feed icons and the
 * new-feed screen's discovery all fetch URLs that have nothing to do with the
 * user's server, and they all use this one. It is a client of its own rather
 * than a copy of the authenticated one, because copying a client copies its
 * interceptors: that is how the token used to end up attached to article
 * images.
 *
 * [authenticated] carries the token, and only to the server the token belongs
 * to. Its credentials are fixed for its lifetime: [useCredentials] builds a new
 * client rather than changing the one in use. A sync already running therefore
 * keeps the client, the token and the server it started with and finishes
 * against them, which is deliberate — the alternative is a request halfway
 * through a sync suddenly carrying different credentials, or none.
 *
 * Before anyone has logged in, and after [forgetCredentials], [authenticated]
 * *is* [plain]: same instance, and so provably no authorization header, because
 * that client has no interceptor that could add one.
 *
 * [userAgent] is what both clients call themselves on the network. This module
 * has no version of its own to build it from, so the app module passes it in
 * (see `apiModule`).
 *
 * [configure] is the one way anything outside this file gets a say in how a
 * client is built, and it exists for the tests. The instrumented tests talk to
 * a stub server running on the device, and since this app speaks nothing but
 * HTTPS that server serves TLS with a certificate no authority signed; the
 * tests hand in a block that trusts that one certificate. `apiModule` passes
 * nothing, so every client the app itself builds is the plain one described
 * above, and a test that wants the seam has to say so in its own Koin module.
 */
class HttpClients(
    private val userAgent: String,
    private val configure: (OkHttpClient.Builder) -> Unit = {}
) {

    val plain: OkHttpClient = buildClient(authInterceptor = null)

    @Volatile
    private var currentClient: OkHttpClient = plain

    private var currentCredentials: Pair<String, HttpUrl>? = null

    /**
     * The client for calls to FreshRSS. Read it once and keep what you got: it
     * is the instance the credentials of the moment are bound to, and a later
     * login replaces the one this property answers with, not the one you hold.
     */
    val authenticated: OkHttpClient
        get() = currentClient

    /**
     * Builds the authenticated client for [credentials], replacing whatever was
     * there. Credentials without a token, or with a URL OkHttp cannot parse,
     * mean there is nothing to authenticate with, which is [forgetCredentials].
     *
     * Asking again for the credentials already in use changes nothing and keeps
     * the client, so the call sites that make sure the credentials are current
     * before every sync do not churn connection pools.
     */
    @Synchronized
    fun useCredentials(credentials: Credentials?) {
        val authorization = credentials?.authorization
        val serverUrl = credentials?.url?.toHttpUrlOrNull()

        if (authorization == null || serverUrl == null) {
            forgetCredentials()
            return
        }

        if (currentCredentials == authorization to serverUrl) {
            return
        }

        currentCredentials = authorization to serverUrl
        currentClient = buildClient(AuthInterceptor(authorization, serverUrl))
    }

    /** Drops the authenticated client, on logout or when there is no token yet. */
    @Synchronized
    fun forgetCredentials() {
        if (currentCredentials == null) {
            return
        }

        currentCredentials = null
        currentClient = plain
    }

    private fun buildClient(authInterceptor: AuthInterceptor?): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .callTimeout(1, TimeUnit.MINUTES)
            .readTimeout(1, TimeUnit.MINUTES)
            // HTTP/1.1 only, on both clients, and not for speed.
            //
            // Over HTTP/2 OkHttp reuses one connection for two hostnames that
            // resolve to the same address whenever the certificate it already
            // has covers both. It re-checks the hostname and the pins on the
            // second host, but not which authorities that host is allowed to be
            // signed by — and this app allows two different sets: user-installed
            // authorities for rss.lan, preinstalled ones only for everywhere
            // else (res/xml/network_security_config.xml). So a certificate
            // issued by the user's own authority for rss.lan and for a second
            // name at the same address would carry that trust to the second
            // name, which nothing checked. One connection per host closes that.
            //
            // The cost is a connection per host rather than one shared one. This
            // app makes a handful of sequential calls to a single server per
            // sync, plus images from feed hosts; there is no request pattern
            // here that HTTP/2 multiplexing was going to help.
            .protocols(listOf(Protocol.HTTP_1_1))
            .addInterceptor(UserAgentInterceptor(userAgent))
            .addInterceptor(ErrorInterceptor())

        // A network interceptor, so that it runs on every hop and a redirect to
        // another host is checked on its own.
        if (authInterceptor != null) builder.addNetworkInterceptor(authInterceptor)

        configure(builder)

        return builder.build()
    }
}
