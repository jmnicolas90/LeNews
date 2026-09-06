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
package app.lenews.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lenews.db.entities.Feed
import app.lenews.db.entities.Folder
import app.lenews.db.entities.Item
import app.lenews.db.entities.account.Account
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Writing the account after a login, and what happens to the store it filled.
 *
 * The store belongs to the account it was synchronized from: change the server
 * or the user and none of it is that account's any more. Change only the
 * password and all of it still is.
 *
 * Every case starts from a store with something in each of the five things a
 * reset removes — articles, a pending change, a feed, a folder and a row in the
 * ledger of what the horizon dropped — plus a cursor, so that a reset that
 * forgot one of them shows up here.
 */
@RunWith(AndroidJUnit4::class)
class StoreResetTest {

    private lateinit var database: Database

    @Before
    fun before() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, Database::class.java).build()

        fillTheStore()
    }

    @After
    fun after() {
        database.close()
    }

    @Test
    fun aPasswordOnlyChangeKeepsEverything() = runTest {
        database.writeTheAccountAfterLogin(
            account = storedAccount().copy(token = "a new token"),
            theStoreBelongsToAnotherAccount = false
        )

        assertEquals(2, database.itemDao().selectEveryArticle().size, "the articles stayed")
        assertEquals(1, database.pendingChangeDao().selectAll().size, "the queue stayed")
        assertEquals(1, database.feedDao().selectFeedCount(), "the feed stayed")
        assertEquals(1, database.folderDao().selectFolderRemoteIds().size, "the folder stayed")
        assertEquals(
            listOf(ARTICLE_READ_LONG_AGO),
            database.horizonDroppedDao().everyDroppedId(),
            "the ledger of what the horizon dropped stayed"
        )
        assertEquals(CURSOR, storedAccount().cursor, "the cursor stayed")
        assertEquals("a new token", storedAccount().token, "the account itself was written")
    }

    @Test
    fun anotherAccountEmptiesTheStoreAndClearsTheCursor() = runTest {
        database.writeTheAccountAfterLogin(
            account = storedAccount().copy(
                url = ANOTHER_SERVER,
                displayedName = "someone-else",
                token = "a token of the other server"
            ),
            theStoreBelongsToAnotherAccount = true
        )

        assertTrue(database.itemDao().selectEveryArticle().isEmpty(), "the articles went")
        assertTrue(database.pendingChangeDao().selectAll().isEmpty(), "the queue went")
        assertEquals(0, database.feedDao().selectFeedCount(), "the feeds went")
        assertTrue(database.folderDao().selectFolderRemoteIds().isEmpty(), "the folders went")
        assertTrue(database.horizonDroppedDao().everyDroppedId().isEmpty(), "the ledger went")

        val account = storedAccount()
        assertEquals(0L, account.cursor, "the cursor of an account that never synchronized")
        assertEquals(ANOTHER_SERVER, account.url)
        assertEquals("someone-else", account.displayedName)
    }

    /**
     * One row, whatever happens: the account table holds the one account, and a
     * login writes over it rather than beside it.
     */
    @Test
    fun theResetLeavesOneAccountRow() = runTest {
        database.writeTheAccountAfterLogin(
            account = storedAccount().copy(url = ANOTHER_SERVER),
            theStoreBelongsToAnotherAccount = true
        )

        assertEquals(1, database.accountDao().selectAccountCount())
    }

    /**
     * The account object the caller handed in is not written back to: it still
     * carries whatever cursor it arrived with. The row is what changed, and the
     * caller's copy cannot end up saying something the store does not.
     */
    @Test
    fun theAccountHandedInIsNotChanged() = runTest {
        val handedIn = storedAccount().copy(url = ANOTHER_SERVER)

        database.writeTheAccountAfterLogin(handedIn, theStoreBelongsToAnotherAccount = true)

        assertEquals(CURSOR, handedIn.cursor)
        assertEquals(0L, storedAccount().cursor)
    }

    /**
     * Fills every table a reset empties. The ledger row is put there the one way
     * anything puts one there — a retention pass dropping an article read past
     * the horizon — rather than by an insert written for the test, so what this
     * checks is the ledger the app really writes.
     */
    private suspend fun fillTheStore() {
        database.accountDao().upsert(
            Account(
                url = SERVER,
                name = "LeNews",
                displayedName = USER,
                cursor = CURSOR,
                token = "a token"
            )
        )

        database.folderDao().insert(Folder(name = "Folder", remoteId = "user/-/label/Folder"))
        database.feedDao().insert(Feed(name = "Feed", remoteId = "feed/one"))

        database.itemDao().insert(
            article(ARTICLE_READ_LONG_AGO, read = true, readAt = NOW - THIRTY_ONE_DAYS)
        )
        database.itemDao().insert(article(FIRST_UNREAD_ARTICLE))
        database.itemDao().insert(article(SECOND_UNREAD_ARTICLE))

        // drops the old one and writes its id to the ledger, leaving two articles
        database.runInTransaction {
            database.deleteWhatRetentionDrops(
                serverIds = setOf(
                    ARTICLE_READ_LONG_AGO,
                    FIRST_UNREAD_ARTICLE,
                    SECOND_UNREAD_ARTICLE
                ),
                now = NOW
            )
        }

        database.pendingChangeDao().queueRead(FIRST_UNREAD_ARTICLE, true)
    }

    private suspend fun storedAccount(): Account = assertNotNull(database.accountDao().select())

    private fun article(id: Long, read: Boolean = false, readAt: Long? = null) = Item(
        id = id,
        title = "article $id",
        feedId = 1,
        pubDate = LocalDateTime.of(2026, 9, 6, 12, 0),
        isRead = read,
        readAt = readAt
    )

    private companion object {
        const val SERVER = "https://rss.lan/"
        const val ANOTHER_SERVER = "https://reader.example.invalid/"
        const val USER = "ledev"

        const val ARTICLE_READ_LONG_AGO = 1_625_234_531_559_678L
        const val FIRST_UNREAD_ARTICLE = 1_625_234_531_559_679L
        const val SECOND_UNREAD_ARTICLE = 1_625_234_531_559_680L

        const val CURSOR = 1_788_693_705L
        const val NOW = 1_757_160_000_000L
        const val THIRTY_ONE_DAYS = 31L * 24 * 60 * 60 * 1000
    }
}
