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

import app.lenews.account.credentials.ServerUrl
import app.lenews.account.credentials.accountToLogInWith
import app.lenews.account.credentials.canonicalServerUrl
import app.lenews.api.HttpClients
import app.lenews.api.PLAIN_CLIENT
import app.lenews.api.apiModule
import app.lenews.api.services.greader.GReaderDataSource
import app.lenews.api.services.greader.GReaderCredentials
import app.lenews.db.entities.account.Account
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
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
import org.koin.dsl.module
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
 *
 * It serves TLS, because the app speaks nothing else: a login over plain HTTP
 * is a thing this app cannot do, so a test that made one would be testing
 * something else. The certificate is the test's own and the clients are built
 * with the trust for it through `HttpClients`'s `configure` seam, which the app
 * itself never passes.
 */
class LoginOverTheWireTest : KoinTest {

    private val theServerJustTypedIn =
        MockWebServer().apply { useHttps(SERVER_SOCKET_FACTORY, false) }

    @get:Rule
    val koinTestRule = KoinTestRule.create {
        modules(
            apiModule(USER_AGENT),
            // Last, so it replaces the HttpClients apiModule declares: the same
            // clients, plus trust in this test's certificate.
            module { single { HttpClients(USER_AGENT, TRUST_THIS_TEST_SERVER) } }
        )
    }

    /** The address of the stub server, on the name its certificate carries. */
    private fun serverUrl(path: String): HttpUrl =
        HttpUrl.Builder()
            .scheme("https")
            .host(HOST)
            .port(theServerJustTypedIn.port)
            .build()
            .resolve(path)!!

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
            url = serverUrl("/").toString(),
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
            url = serverUrl("/").toString(),
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
            url = serverUrl("/").toString(),
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

    /**
     * The address typed on screen, read once, is the address the socket sees.
     *
     * This is the check that used to be missing. The screen decided whether an
     * address was cleartext on one reading of the text and the request was built
     * from another, so an address the screen called safe could be fetched over
     * plain HTTP. Now one function answers both, and this test follows its
     * answer all the way to the request the server received.
     */
    @Test
    fun theAddressTheLoginUsesIsTheOneTheServerSees() = runTest {
        answerALogin()

        // typed the way people type: no scheme, blanks around it, and a
        // fragment — here one holding "http://", the shape that used to get
        // through
        val typed = "  $HOST:${theServerJustTypedIn.port}/remote#http://  "

        val canonical = canonicalServerUrl(typed)

        assertEquals(
            ServerUrl.Usable("https://$HOST:${theServerJustTypedIn.port}/remote/"),
            canonical
        )

        val account = accountToLogInWith(
            account = Account(name = "Account"),
            url = (canonical as ServerUrl.Usable).url,
            name = "Account",
            login = "ledev",
            password = "a password"
        )

        logInThroughKoin(account)

        // The FreshRSS API path is appended to the stored address by simple
        // string concatenation (`Credentials.toCredentials`), which is the
        // second reason the canonical address has to end in a slash.
        assertEquals(
            "the login went somewhere other than the address the screen accepted",
            "https://$HOST:${theServerJustTypedIn.port}/remote/api/greader.php/accounts/ClientLogin",
            whatTheServerWasSent(1).first().requestUrl.toString()
        )
        assertEquals(
            "what is stored is the address the request used",
            "https://$HOST:${theServerJustTypedIn.port}/remote/",
            account.url
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

        /** The name the stub server is reached by, and the name on its certificate. */
        const val HOST = "localhost"

        private val CERTIFICATE: HeldCertificate = HeldCertificate.Builder()
            .commonName(HOST)
            .addSubjectAlternativeName(HOST)
            .build()

        val SERVER_SOCKET_FACTORY = HandshakeCertificates.Builder()
            .heldCertificate(CERTIFICATE)
            .build()
            .sslSocketFactory()

        private val TRUST_IN_THIS_TEST_SERVER = HandshakeCertificates.Builder()
            .addTrustedCertificate(CERTIFICATE.certificate)
            .build()

        val TRUST_THIS_TEST_SERVER: (OkHttpClient.Builder) -> Unit = { builder ->
            builder.sslSocketFactory(
                TRUST_IN_THIS_TEST_SERVER.sslSocketFactory(),
                TRUST_IN_THIS_TEST_SERVER.trustManager
            )
        }

        const val AUTHORIZATION = "Authorization"

        const val A_TOKEN_THE_PREVIOUS_SERVER_ISSUED = "aTokenThePreviousServerIssued"

        const val THE_TOKEN_THIS_SERVER_ISSUES = "theTokenThisServerIssues"
    }
}
