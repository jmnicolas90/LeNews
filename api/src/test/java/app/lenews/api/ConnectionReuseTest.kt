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

import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

/**
 * One connection per host, whatever a certificate says it covers.
 *
 * This app does not trust the same authorities everywhere. `rss.lan` is allowed
 * to be signed by an authority the phone's owner installed; every other host has
 * to be signed by a preinstalled one
 * (`app/src/main/res/xml/network_security_config.xml`). Over HTTP/2 OkHttp
 * shares one connection between two hostnames that resolve to the same address
 * when the certificate it already holds covers both — it re-checks the hostname
 * and the certificate pins, and nothing else. So a certificate the user's own
 * authority issued for `rss.lan` *and* for a second name would let a request to
 * that second name ride on a connection that was only ever allowed for
 * `rss.lan`.
 *
 * [HttpClients] closes that by speaking HTTP/1.1 only. This test holds it shut:
 * two hostnames on one certificate at one address, and the second request has to
 * open its own connection. The control below asks the same two questions of a
 * client that still allows HTTP/2, and gets one connection — which is what makes
 * the first result mean something.
 */
class ConnectionReuseTest {

    private val certificateCoveringBothHosts: HeldCertificate = HeldCertificate.Builder()
        .commonName(FRESHRSS_HOST)
        .addSubjectAlternativeName(FRESHRSS_HOST)
        .addSubjectAlternativeName(THE_OTHER_HOST)
        .build()

    private val server = MockWebServer().apply {
        useHttps(
            HandshakeCertificates.Builder()
                .heldCertificate(certificateCoveringBothHosts)
                .build()
                .sslSocketFactory(),
            false
        )
    }

    private val trustTheCertificate = HandshakeCertificates.Builder()
        .addTrustedCertificate(certificateCoveringBothHosts.certificate)
        .build()

    @Before
    fun before() {
        server.start()
        repeat(4) { server.enqueue(MockResponse()) }
    }

    @After
    fun after() {
        server.shutdown()
    }

    @Test
    fun theSecondHostOpensItsOwnConnection() {
        val connections = ConnectionCounter()

        val clients = HttpClients(USER_AGENT) { builder ->
            configureForTheTestServer(builder, connections)
        }

        askBothHosts(clients.plain)

        assertEquals(
            2,
            connections.opened,
            "the second host was served on the connection opened for the first"
        )
    }

    /**
     * The control. Same certificate, same two hosts, same address — but HTTP/2
     * allowed, which is what the clients used to allow. One connection serves
     * both, and that is the reuse the test above proves is gone.
     */
    @Test
    fun withHttp2AllowedOneConnectionServesBothHosts() {
        val connections = ConnectionCounter()

        val builder = OkHttpClient.Builder()
        configureForTheTestServer(builder, connections)

        askBothHosts(builder.build())

        assertEquals(
            1,
            connections.opened,
            "the two hosts did not share a connection, so this test proves nothing"
        )
    }

    private fun askBothHosts(client: OkHttpClient) {
        get(client, FRESHRSS_HOST)
        get(client, THE_OTHER_HOST)
    }

    private fun get(client: OkHttpClient, host: String) {
        client.newCall(
            Request.Builder()
                .url("https://$host:${server.port}/an-article")
                .build()
        ).execute().close()
    }

    /**
     * Trust in this test's certificate, both hostnames pointed at the server,
     * and a count of the connections opened.
     */
    private fun configureForTheTestServer(
        builder: OkHttpClient.Builder,
        connections: ConnectionCounter
    ) {
        builder.sslSocketFactory(
            trustTheCertificate.sslSocketFactory(),
            trustTheCertificate.trustManager
        )
        builder.dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                listOf(InetAddress.getByName(LOOPBACK))
        })
        builder.eventListener(connections)
    }

    private class ConnectionCounter : EventListener() {

        private val count = AtomicInteger()

        val opened: Int
            get() = count.get()

        override fun connectStart(
            call: Call,
            inetSocketAddress: InetSocketAddress,
            proxy: Proxy
        ) {
            count.incrementAndGet()
        }
    }

    private companion object {

        const val USER_AGENT = "LeNews/0.0.0-test"

        /** The FreshRSS server, the one host user-installed authorities are trusted for. */
        const val FRESHRSS_HOST = "rss.lan"

        /** Another name on the same certificate, at the same address. */
        const val THE_OTHER_HOST = "images.example"

        const val LOOPBACK = "127.0.0.1"
    }
}
