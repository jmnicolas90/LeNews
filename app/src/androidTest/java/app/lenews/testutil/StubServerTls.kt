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
package app.lenews.testutil

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import javax.net.ssl.SSLSocketFactory

/**
 * The TLS the stub servers of these tests serve, and the trust the app under
 * test is given for it.
 *
 * The app speaks nothing but HTTPS — `res/xml/network_security_config.xml`
 * refuses cleartext to every host, the loopback interface included — and that
 * file is the shipped one, not a test copy. So a stub server running on the
 * device has to serve TLS like any other server, with a certificate no
 * authority signed, and the app has to be told to trust that one certificate.
 *
 * It is told through the `configure` seam of `HttpClients`, which the test Koin
 * module in [LeNewsTestRule] passes and the app itself never does. The trust is
 * as narrow as it can be: this certificate and nothing else, not even the
 * preinstalled authorities, because no test here talks to any server but its
 * own stub.
 *
 * The certificate is generated once for the whole run rather than per test —
 * generating a key pair costs more than every request these tests make.
 */
object StubServerTls {

    /** The name the stub servers are reached by, and the name on the certificate. */
    const val HOST = "localhost"

    private val certificate: HeldCertificate = HeldCertificate.Builder()
        .commonName(HOST)
        .addSubjectAlternativeName(HOST)
        .addSubjectAlternativeName("127.0.0.1")
        .build()

    /** What a [MockWebServer] serves this certificate with. */
    val serverSocketFactory: SSLSocketFactory = HandshakeCertificates.Builder()
        .heldCertificate(certificate)
        .build()
        .sslSocketFactory()

    private val trustInTheStubServer: HandshakeCertificates = HandshakeCertificates.Builder()
        .addTrustedCertificate(certificate.certificate)
        .build()

    /**
     * What every client built under test is configured with: trust in the stub
     * server's certificate, and in nothing else.
     */
    val trustTheStubServer: (OkHttpClient.Builder) -> Unit = { builder ->
        builder.sslSocketFactory(
            trustInTheStubServer.sslSocketFactory(),
            trustInTheStubServer.trustManager
        )
    }
}

/** A [MockWebServer] serving the certificate the app under test trusts. */
fun stubServerOverTls(): MockWebServer =
    MockWebServer().apply { useHttps(StubServerTls.serverSocketFactory, false) }

/**
 * The address of this stub server, on the name its certificate carries.
 *
 * `MockWebServer.url` builds the host out of a reverse lookup of the loopback
 * address, which can answer something the certificate does not cover; this
 * names the host the certificate was made for and leaves nothing to the
 * resolver.
 */
fun MockWebServer.tlsUrl(path: String): HttpUrl =
    HttpUrl.Builder()
        .scheme("https")
        .host(StubServerTls.HOST)
        .port(port)
        .build()
        .resolve(path)!!
