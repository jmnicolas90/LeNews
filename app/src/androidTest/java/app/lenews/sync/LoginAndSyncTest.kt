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
package app.lenews.sync

import android.content.Context
import androidx.core.app.NotificationCompat.Builder
import androidx.test.core.app.ApplicationProvider
import app.lenews.LeNewsApp
import app.lenews.R
import app.lenews.db.Database
import app.lenews.db.entities.account.Account
import app.lenews.repositories.BaseRepository
import app.lenews.testutil.FreshRSSStub
import app.lenews.testutil.LeNewsTestRule
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koin.core.parameter.parametersOf
import org.koin.test.KoinTest
import org.koin.test.get
import org.koin.test.inject
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Logging in and then syncing, through the bindings the app itself uses: the
 * repository Koin builds for the account, and the [Synchronizer] the sync
 * worker runs. Nothing here builds a client or a data source of its own, so
 * what this test exercises is the wiring rather than a copy of it.
 *
 * The stub demands the token it issued on every call but ClientLogin, so a sync
 * sent on the plain client fails here instead of passing quietly; and
 * ClientLogin itself has to arrive with no authorization header at all, which
 * is what keeps a token left over from an earlier login off that request.
 */
class LoginAndSyncTest : KoinTest {

    private val database: Database by inject()
    private val synchronizer: Synchronizer by inject()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mockServer = MockWebServer()
    private val server = FreshRSSStub()

    @get:Rule
    val rule = LeNewsTestRule()

    @Before
    fun before() {
        mockServer.dispatcher = server
        server.token = THE_TOKEN_THE_SERVER_ISSUES
    }

    @After
    fun after() {
        mockServer.shutdown()
        database.clearAllTables()
    }

    @Test
    fun aLoginCarriesNoTokenAndTheSyncAfterItCarriesTheOneItGot() = runTest {
        server.readingListPages = listOf(listOf(FreshRSSStub.articleJson(ARTICLE)))
        server.serverIdPages = listOf(listOf(ARTICLE))
        server.unreadIdPages = listOf(listOf(ARTICLE))

        val account = Account(
            name = "Account",
            url = mockServer.url("/remote").toString(),
            login = "ledev",
            password = "a password"
        )

        // the login the credentials screen runs
        get<BaseRepository> { parametersOf(account) }.login(account)

        assertEquals(THE_TOKEN_THE_SERVER_ISSUES, account.token, "the login brought no token back")
        assertEquals("writeToken", account.writeToken)
        assertEquals("ledev", account.displayedName)

        val clientLogin = server.receivedFor("accounts/ClientLogin").single()
        assertNull(
            clientLogin.authorization,
            "ClientLogin went out with an authorization header"
        )

        database.accountDao().upsert(account)

        // the sync the worker runs, on the account the login just wrote, with
        // the notification the worker builds
        synchronizer.synchronize(
            Builder(context, LeNewsApp.SYNC_CHANNEL_ID).setSmallIcon(R.drawable.ic_sync)
        )

        assertEquals(
            listOf(ARTICLE),
            database.itemDao().selectEveryArticle().map { it.id },
            "the sync stored nothing, so it never reached the server"
        )

        val afterTheLogin = server.received.filterNot { it.path.contains("ClientLogin") }
        assertTrue(afterTheLogin.isNotEmpty(), "nothing was called after the login")
        assertEquals(
            emptyList<String>(),
            afterTheLogin
                .filter { it.authorization != FreshRSSStub.AUTH_PREFIX + THE_TOKEN_THE_SERVER_ISSUES }
                .map { it.path },
            "these calls went out without the token the login obtained"
        )
    }

    private companion object {

        const val THE_TOKEN_THE_SERVER_ISSUES = "theTokenTheServerIssues"

        const val ARTICLE = 1625234531559678L
    }
}
