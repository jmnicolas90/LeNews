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
package app.lenews.account.credentials

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The one decision a login makes about the store it is logging in over: keep it
 * or empty it. An account is a server and a user of it, so those two decide, and
 * nothing else does.
 */
class StoreOwnershipTest {

    /**
     * This is also the password-only change, which has to keep the articles,
     * the history and the queue: a password is not part of an account's
     * identity, which is why it is not an argument here at all and why no test
     * can be written that passes one.
     */
    @Test
    fun theSameServerAndTheSameUserKeepTheStore() {
        assertFalse(
            theStoreBelongsToAnotherAccount(
                storedUrl = SERVER,
                storedLogin = USER,
                url = SERVER,
                login = USER
            ),
            "logging in again as the same user of the same server keeps the articles"
        )
    }

    @Test
    fun anotherServerEmptiesTheStore() {
        assertTrue(
            theStoreBelongsToAnotherAccount(
                storedUrl = SERVER,
                storedLogin = USER,
                url = "https://reader.example.invalid/",
                login = USER
            ),
            "the same user name on another server is another account"
        )
    }

    @Test
    fun anotherUserEmptiesTheStore() {
        assertTrue(
            theStoreBelongsToAnotherAccount(
                storedUrl = SERVER,
                storedLogin = USER,
                url = SERVER,
                login = "someone-else"
            ),
            "another user of the same server is another account"
        )
    }

    @Test
    fun bothChangedEmptiesTheStore() {
        assertTrue(
            theStoreBelongsToAnotherAccount(
                storedUrl = SERVER,
                storedLogin = USER,
                url = "https://reader.example.invalid/",
                login = "someone-else"
            )
        )
    }

    /**
     * The first login of all: nothing is stored, so there is nothing to keep and
     * emptying an empty store costs nothing.
     */
    @Test
    fun aFirstLoginHasNoStoreToKeep() {
        assertTrue(
            theStoreBelongsToAnotherAccount(
                storedUrl = null,
                storedLogin = null,
                url = SERVER,
                login = USER
            )
        )
    }

    /**
     * The comparison is between two canonical addresses, which is what
     * [canonicalServerUrl] answers and what `Account.url` holds — so a reader
     * who retypes their address without the scheme or without the trailing
     * slash does not lose their articles for it.
     */
    @Test
    fun theSameServerWrittenAnotherWayKeepsTheStore() {
        val stored = (canonicalServerUrl("https://rss.lan/") as ServerUrl.Usable).url
        val typedAgain = (canonicalServerUrl(" rss.lan ") as ServerUrl.Usable).url

        assertFalse(
            theStoreBelongsToAnotherAccount(
                storedUrl = stored,
                storedLogin = USER,
                url = typedAgain,
                login = USER
            ),
            "$typedAgain and $stored are the same server"
        )
    }

    private companion object {
        const val SERVER = "https://rss.lan/"
        const val USER = "ledev"
    }
}
