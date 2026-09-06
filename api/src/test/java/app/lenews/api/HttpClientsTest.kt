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

import app.lenews.api.services.greader.GReaderCredentials
import app.lenews.api.utils.AuthInterceptor
import app.lenews.api.utils.UserAgentInterceptor
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

class HttpClientsTest {

    private val mockServer = MockWebServer()
    private lateinit var httpClients: HttpClients

    @Before
    fun before() {
        mockServer.start()
        httpClients = HttpClients(USER_AGENT)
    }

    @After
    fun after() {
        mockServer.shutdown()
    }

    @Test
    fun thePlainClientNeverSendsTheToken() {
        useTheMockServerAsTheFreshRSSServer()
        mockServer.enqueue(MockResponse())

        httpClients.plain.newCall(Request.Builder().url(mockServer.url("/an-image.png")).build())
            .execute()
            .close()

        assertNull(mockServer.takeRequest().headers[AuthInterceptor.AUTHORIZATION_HEADER])
    }

    @Test
    fun theAuthenticatedClientSendsTheTokenToTheConfiguredServer() {
        useTheMockServerAsTheFreshRSSServer()
        mockServer.enqueue(MockResponse())

        httpClients.authenticated
            .newCall(Request.Builder().url(mockServer.url("/reader/api/0/token")).build())
            .execute()
            .close()

        assertEquals(
            "GoogleLogin auth=token",
            mockServer.takeRequest().headers[AuthInterceptor.AUTHORIZATION_HEADER]
        )
    }

    @Test
    fun bothClientsSayWhoTheyAre() {
        useTheMockServerAsTheFreshRSSServer()
        mockServer.enqueue(MockResponse())
        mockServer.enqueue(MockResponse())

        httpClients.plain.newCall(Request.Builder().url(mockServer.url("/one")).build())
            .execute()
            .close()
        httpClients.authenticated.newCall(Request.Builder().url(mockServer.url("/two")).build())
            .execute()
            .close()

        repeat(2) {
            assertEquals(
                USER_AGENT,
                mockServer.takeRequest().headers[UserAgentInterceptor.USER_AGENT_HEADER],
                "no request may go out as okhttp/4.x"
            )
        }
    }

    @Test
    fun withNoTokenTheAuthenticatedClientIsThePlainOne() {
        assertSame(httpClients.plain, httpClients.authenticated)

        // credentials whose token is not known yet, which is what the login
        // screen has in hand when it sends ClientLogin
        httpClients.useCredentials(GReaderCredentials(null, mockServer.url("/").toString()))

        assertSame(httpClients.plain, httpClients.authenticated)
    }

    @Test
    fun newCredentialsReplaceTheClientRatherThanChangingIt() {
        useTheMockServerAsTheFreshRSSServer()
        val afterFirstLogin = httpClients.authenticated

        httpClients.useCredentials(
            GReaderCredentials("anotherToken", mockServer.url("/").toString())
        )

        assertNotSame(afterFirstLogin, httpClients.authenticated)
    }

    @Test
    fun theSameCredentialsKeepTheSameClient() {
        useTheMockServerAsTheFreshRSSServer()
        val client = httpClients.authenticated

        useTheMockServerAsTheFreshRSSServer()

        assertSame(client, httpClients.authenticated)
    }

    @Test
    fun forgettingTheCredentialsDropsTheAuthenticatedClient() {
        useTheMockServerAsTheFreshRSSServer()
        assertNotSame(httpClients.plain, httpClients.authenticated)

        httpClients.forgetCredentials()

        assertSame(httpClients.plain, httpClients.authenticated)
    }

    /**
     * The host rule is built from the URL the mock server is listening on, so
     * the tests say nothing about which host or port that turns out to be.
     */
    private fun useTheMockServerAsTheFreshRSSServer() {
        httpClients.useCredentials(GReaderCredentials("token", mockServer.url("/").toString()))
    }

    private companion object {
        const val USER_AGENT = "LeNews/0.0.0-test"
    }
}
