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

import app.lenews.api.HttpClients
import app.lenews.api.services.Credentials
import app.lenews.api.services.greader.GReaderDataSource
import app.lenews.api.services.greader.GReaderService
import app.lenews.api.services.greader.adapters.FreshRSSUserInfo
import app.lenews.api.services.greader.adapters.GReaderFolders
import app.lenews.api.services.greader.adapters.GReaderItemIdsPage
import app.lenews.api.services.greader.adapters.GReaderItemsPage
import app.lenews.db.entities.Feed
import app.lenews.db.entities.account.Account
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * What logging in does to the pair of clients.
 *
 * The server is faked, not mocked over the network: the question here is which
 * client each call would have gone out on, and that is decided before any
 * socket is opened.
 */
class GReaderLoginTest {

    private val httpClients = HttpClients("LeNews/0.0.0-test")

    /** The client that was current each time a data source was asked for. */
    private val clientsUsed = mutableListOf<OkHttpClient>()

    private val account = Account(
        url = "https://rss.lan/",
        login = "ledev",
        password = "a password"
    )

    @Test
    fun theLoginIsSentWithNoTokenAndEverythingAfterItCarriesOne() = runTest {
        logIn(account, httpClients, ::dataSourceFor)

        assertEquals("theToken", account.token)
        assertEquals("theWriteToken", account.writeToken)
        assertEquals("ledev", account.displayedName)

        assertSame(
            "ClientLogin has no token to send and must go out on the plain client",
            httpClients.plain,
            clientsUsed.first()
        )
        assertNotSame(
            "the calls after the login carry the token",
            httpClients.plain,
            clientsUsed.last()
        )
    }

    @Test
    fun theLoginReplacesTheClientRatherThanChangingIt() = runTest {
        // an account already logged in, so there is a client to replace
        httpClients.useCredentials(Credentials.toCredentials(account.copy(token = "anOldToken")))
        val before = httpClients.authenticated

        logIn(account, httpClients, ::dataSourceFor)

        assertNotSame(
            "the client that held the old token is still whole; a new one holds the new token",
            before,
            httpClients.authenticated
        )
    }

    private fun dataSourceFor(credentials: Credentials): GReaderDataSource {
        clientsUsed += httpClients.authenticated
        return GReaderDataSource(FakeFreshRSS(credentials))
    }

    /**
     * Answers the three calls a login makes, and refuses the token calls unless
     * the credentials it was built with carry a token — which is what makes the
     * order the test asserts on matter rather than merely happen.
     */
    private class FakeFreshRSS(private val credentials: Credentials) : GReaderService {

        override suspend fun login(login: String, password: String): ResponseBody {
            check(credentials.authorization == null) { "ClientLogin must not send a token" }
            return "Auth=theToken\n".toResponseBody("text/plain".toMediaType())
        }

        override suspend fun getWriteToken(): ResponseBody {
            checkNotNull(credentials.authorization) { "the write token call needs the token" }
            return "theWriteToken".toResponseBody("text/plain".toMediaType())
        }

        override suspend fun userInfo(): FreshRSSUserInfo {
            checkNotNull(credentials.authorization) { "the user info call needs the token" }
            return FreshRSSUserInfo("ledev")
        }

        override suspend fun getFeeds(): List<Feed> = notCalled()

        override suspend fun getFolders(): GReaderFolders = notCalled()

        override suspend fun getItems(
            excludeTarget: String?,
            max: Int,
            cursor: Long?,
            continuation: String?
        ): GReaderItemsPage = notCalled()

        override suspend fun getStarredItems(
            max: Int,
            continuation: String?
        ): GReaderItemsPage = notCalled()

        override suspend fun getItemsIds(
            excludeTarget: String?,
            includeTarget: String?,
            max: Int,
            continuation: String?
        ): GReaderItemIdsPage = notCalled()

        override suspend fun getItemsContents(
            token: String,
            itemIds: List<String>
        ): GReaderItemsPage = notCalled()

        override suspend fun setItemsState(
            token: String,
            addAction: String?,
            removeAction: String?,
            itemIds: List<String>
        ) = notCalled()

        override suspend fun createOrDeleteFeed(
            token: String,
            feedUrl: String,
            action: String,
            folderId: String?
        ) = notCalled()

        override suspend fun updateFeed(
            token: String,
            feedUrl: String,
            title: String,
            folderId: String,
            action: String
        ) = notCalled()

        override suspend fun createFolder(token: String, tagName: String) = notCalled()

        override suspend fun updateFolder(
            token: String,
            folderId: String,
            newFolderId: String
        ) = notCalled()

        override suspend fun deleteFolder(token: String, folderId: String) = notCalled()

        private fun notCalled(): Nothing = error("a login makes no such call")
    }
}
