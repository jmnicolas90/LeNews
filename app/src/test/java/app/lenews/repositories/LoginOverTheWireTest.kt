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

package app.lenews.repositories

import app.lenews.account.credentials.accountToLogInWith
import app.lenews.api.HttpClients
import app.lenews.api.PLAIN_CLIENT
import app.lenews.api.apiModule
import app.lenews.api.services.greader.GReaderDataSource
import app.lenews.api.services.greader.GReaderCredentials
import app.lenews.db.entities.account.Account
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koin.core.parameter.parametersOf
import org.koin.core.qualifier.named
import org.koin.test.KoinTest
import org.koin.test.KoinTestRule
import org.koin.test.get
import org.koin.test.inject
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit

/**
 * What actually goes out on the wire when someone logs in: a real socket, and
 * the data sources built by the same Koin bindings the app uses.
 *
 * The server here stands for the one whose URL was just typed on the
 * credentials screen. The account being logged in with may still hold the token
 * of the server it was logged in to before — editing the URL keeps the rest of
 * the account — and that token must never reach this one, not on ClientLogin
 * and not on anything else.
 */
class LoginOverTheWireTest : KoinTest {

    private val theServerJustTypedIn = MockWebServer()

    @get:Rule
    val koinTestRule = KoinTestRule.create { modules(apiModule(USER_AGENT)) }

    private val httpClients: HttpClients by inject()

    @Before
    fun before() {
        theServerJustTypedIn.start()
    }

    @After
    fun after() {
        theServerJustTypedIn.shutdown()
    }

    /**
     * The whole path the credentials screen takes when the URL is edited: the
     * account it builds, then the login. Nothing that reaches the new server
     * carries the token of the old one, and ClientLogin carries no token at all.
     */
    @Test
    fun anEditedServerUrlIsNeverSentThePreviousServersToken() = runTest {
        answerALogin()

        val accountAsItWasBeforeTheEdit = Account(
            name = "Account",
            url = "https://the.previous.server.example/",
            token = A_TOKEN_THE_PREVIOUS_SERVER_ISSUED,
            writeToken = "a write token the previous server issued"
        )

        // exactly what AccountCredentialsScreenModel builds when the URL is edited
        val account = accountToLogInWith(
            account = accountAsItWasBeforeTheEdit,
            url = theServerJustTypedIn.url("/").toString(),
            name = "Account",
            login = "ledev",
            password = "a password"
        )

        assertNull(
            "the account the screen logs in with must carry no token of its own",
            account.token
        )
        assertNull("nor a write token", account.writeToken)

        logInThroughKoin(account)

        assertEquals("the token in hand is this server's", THE_TOKEN_THIS_SERVER_ISSUES, account.token)
        assertEquals("theWriteToken", account.writeToken)

        val requests = whatTheServerWasSent(3)

        assertNull(
            "ClientLogin must go out with no authorization header at all",
            requests.first().getHeader(AUTHORIZATION)
        )
        requests.forEach {
            assertNotEquals(
                "a token issued by another server reached this one",
                "GoogleLogin auth=$A_TOKEN_THE_PREVIOUS_SERVER_ISSUED",
                it.getHeader(AUTHORIZATION)
            )
        }
        assertEquals(
            "the calls after the login carry the token this server issued",
            "GoogleLogin auth=$THE_TOKEN_THIS_SERVER_ISSUES",
            requests[1].getHeader(AUTHORIZATION)
        )
    }

    /**
     * The same, one layer down: even handed an account that still holds a token,
     * [logIn] sends nothing before the destination has issued one of its own.
     * The screen is not the only caller, so the rule lives here too.
     */
    @Test
    fun aLoginSendsNoTokenWhateverTheAccountStillHolds() = runTest {
        answerALogin()

        val account = Account(
            name = "Account",
            url = theServerJustTypedIn.url("/").toString(),
            token = A_TOKEN_THE_PREVIOUS_SERVER_ISSUED,
            writeToken = "a write token the previous server issued",
            login = "ledev",
            password = "a password"
        )

        logInThroughKoin(account)

        val clientLogin = whatTheServerWasSent(3).first()

        assertTrue("ClientLogin went somewhere else", clientLogin.path!!.endsWith("ClientLogin"))
        assertNull(
            "the token the account still held went out with ClientLogin",
            clientLogin.getHeader(AUTHORIZATION)
        )
    }

    /**
     * A login that fails leaves nothing bound: the authenticated client is the
     * plain one again, so the next request made on it cannot carry a token that
     * the login never replaced.
     */
    @Test
    fun aFailedLoginLeavesTheAuthenticatedClientUnbound() = runTest {
        httpClients.useCredentials(
            GReaderCredentials(
                A_TOKEN_THE_PREVIOUS_SERVER_ISSUED,
                "https://the.previous.server.example/api/greader.php/"
            )
        )
        assertNotEquals(
            "the test starts with a bound client, or it proves nothing",
            httpClients.plain,
            httpClients.authenticated
        )

        theServerJustTypedIn.enqueue(
            MockResponse().setResponseCode(HttpURLConnection.HTTP_UNAUTHORIZED)
        )

        val account = Account(
            name = "Account",
            url = theServerJustTypedIn.url("/").toString(),
            token = A_TOKEN_THE_PREVIOUS_SERVER_ISSUED,
            login = "ledev",
            password = "the wrong password"
        )

        val failure = runCatching { logInThroughKoin(account) }.exceptionOrNull()

        assertTrue("the server refused the login, so this must fail", failure != null)
        assertSame(
            "a login that failed left a token bound to the authenticated client",
            httpClients.plain,
            httpClients.authenticated
        )
        assertNull(
            "the refused ClientLogin carried a token",
            whatTheServerWasSent(1).first().getHeader(AUTHORIZATION)
        )
    }

    /** ClientLogin, the write token and user info, in the order a login asks for them. */
    private fun answerALogin() {
        theServerJustTypedIn.enqueue(
            MockResponse().setBody("SID=aSessionId\nAuth=$THE_TOKEN_THIS_SERVER_ISSUES\n")
        )
        theServerJustTypedIn.enqueue(MockResponse().setBody("theWriteToken"))
        theServerJustTypedIn.enqueue(MockResponse().setBody("""{ "userName": "ledev" }"""))
    }

    private fun whatTheServerWasSent(count: Int): List<RecordedRequest> =
        (1..count).map {
            requireNotNull(theServerJustTypedIn.takeRequest(5, TimeUnit.SECONDS)) {
                "the server was sent fewer than $count requests"
            }
        }

    /**
     * The login as [GReaderRepository] runs it: the two data sources come from
     * the app's own Koin bindings, so this exercises the wiring rather than a
     * copy of it.
     */
    private suspend fun logInThroughKoin(account: Account) = logIn(
        account = account,
        httpClients = httpClients,
        dataSourceOnThePlainClient = { credentials ->
            get<GReaderDataSource>(named(PLAIN_CLIENT)) { parametersOf(credentials) }
        },
        dataSourceOnTheAuthenticatedClient = { credentials ->
            get<GReaderDataSource> { parametersOf(credentials) }
        }
    )

    private companion object {

        const val USER_AGENT = "LeNews/0.0.0-test"

        const val AUTHORIZATION = "Authorization"

        const val A_TOKEN_THE_PREVIOUS_SERVER_ISSUED = "aTokenThePreviousServerIssued"

        const val THE_TOKEN_THIS_SERVER_ISSUES = "theTokenThisServerIssues"
    }
}
