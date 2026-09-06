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

import app.lenews.api.services.Credentials
import app.lenews.api.services.greader.GReaderDataSource
import app.lenews.api.utils.AuthInterceptor
import app.lenews.api.utils.exceptions.ParseException
import app.lenews.db.Database
import app.lenews.db.entities.Item
import app.lenews.db.entities.account.Account
import app.lenews.repositories.GReaderRepository
import app.lenews.testutil.FreshRSSStub
import app.lenews.testutil.LeNewsTestRule
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koin.core.parameter.parametersOf
import org.koin.test.KoinTest
import org.koin.test.inject
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sync sequence of `docs/article-store.md` §3 and §7, against a FreshRSS
 * server that answers the way the real one does: one transaction, an upsert by
 * id, state from the three id lists alone, and a cursor that never runs ahead
 * of the content it accounts for.
 */
class SyncTest : KoinTest {

    private val database: Database by inject()
    private val mockServer = MockWebServer()
    private val server = FreshRSSStub()

    @get:Rule
    val rule = LeNewsTestRule()

    @Before
    fun before() {
        mockServer.dispatcher = server

        runBlocking {
            database.accountDao().upsert(
                Account(
                    name = "Account",
                    url = mockServer.url("/remote").toString(),
                    writeToken = "writeToken"
                )
            )
        }
    }

    @After
    fun after() {
        mockServer.shutdown()
        database.clearAllTables()
    }

    /**
     * Invariant 5: the same server answers applied twice leave the store
     * identical, `read_at` included. Nothing is inserted a second time, no
     * state flips again, and no read is stamped again.
     */
    @Test
    fun theSameAnswersAppliedTwiceLeaveTheStoreIdentical() = runTest {
        server.readingListPages = listOf(
            listOf(FreshRSSStub.articleJson(ARTICLE_A), FreshRSSStub.articleJson(ARTICLE_B))
        )
        server.serverIdPages = listOf(listOf(ARTICLE_A, ARTICLE_B))
        server.unreadIdPages = listOf(listOf(ARTICLE_A))
        server.starredIdPages = listOf(listOf(ARTICLE_B))

        synchronize()
        val afterTheFirstSync = everyArticle()

        assertEquals(2, afterTheFirstSync.size)
        assertNotNull(
            afterTheFirstSync.first { it.id == ARTICLE_B }.readAt,
            "the article the server does not call unread became read at this sync"
        )

        synchronize()

        assertEquals(
            afterTheFirstSync,
            everyArticle(),
            "the same answers applied twice must leave every row as it was"
        )
    }

    /** §2: within one response the last occurrence of an id wins, and it is one row. */
    @Test
    fun oneArticleDeliveredTwiceInOneResponseIsOneRow() = runTest {
        server.readingListPages = listOf(
            listOf(
                FreshRSSStub.articleJson(ARTICLE_A, title = "the first delivery"),
                FreshRSSStub.articleJson(ARTICLE_A, title = "the last delivery")
            )
        )
        server.serverIdPages = listOf(listOf(ARTICLE_A))
        server.unreadIdPages = listOf(listOf(ARTICLE_A))

        synchronize()

        val articles = everyArticle()
        assertEquals(1, articles.size)
        assertEquals("the last delivery", articles.single().title)
    }

    /**
     * §3, step 4: a failure between the articles and the cursor rolls the whole
     * transaction back. The store is exactly what the previous sync left, the
     * cursor has not moved, and the sync that follows stores no second row.
     */
    @Test
    fun aFailureBeforeTheCursorIsWrittenRollsBackEverything() = runTest {
        server.readingListPages = listOf(listOf(FreshRSSStub.articleJson(ARTICLE_A)))
        server.serverIdPages = listOf(listOf(ARTICLE_A))
        server.unreadIdPages = listOf(listOf(ARTICLE_A))

        synchronize()

        val articlesBefore = everyArticle()
        val cursorBefore = storedAccount().cursor
        assertEquals(1, articlesBefore.size)
        assertTrue(cursorBefore > 0, "a successful sync writes the cursor")

        // the next sync brings a second article back and fails before the cursor
        server.readingListPages = listOf(
            listOf(FreshRSSStub.articleJson(ARTICLE_A), FreshRSSStub.articleJson(ARTICLE_B))
        )
        server.serverIdPages = listOf(listOf(ARTICLE_A, ARTICLE_B))
        server.unreadIdPages = listOf(listOf(ARTICLE_A, ARTICLE_B))

        assertFailsWith<IOException> { synchronize(failInsideTheTransaction = true) }

        assertEquals(articlesBefore, everyArticle(), "the rolled back sync stored nothing")
        assertEquals(cursorBefore, storedAccount().cursor, "the cursor did not move")

        synchronize()

        val articlesAfter = everyArticle()
        assertEquals(2, articlesAfter.size, "the retried sync stores one row per article")
        assertEquals(listOf(ARTICLE_A, ARTICLE_B), articlesAfter.map { it.id })
    }

    /**
     * §3, step 1: an article read on the phone while offline is uploaded, and
     * the queue row goes once the server has taken it. The server still holds
     * the article as unread when the sync starts and says read afterwards,
     * which is the order the model relies on.
     */
    @Test
    fun anArticleReadOfflineIsUploadedAndLeavesTheQueue() = runTest {
        server.readingListPages = listOf(listOf(FreshRSSStub.articleJson(ARTICLE_A)))
        server.serverIdPages = listOf(listOf(ARTICLE_A))
        server.unreadIdPages = listOf(listOf(ARTICLE_A))

        synchronize()

        val readAtStamp = System.currentTimeMillis()
        database.itemDao().markRead(ARTICLE_A, readAtStamp)
        database.pendingChangeDao().queueRead(ARTICLE_A, true)

        // the server takes the change: its unread list no longer names the article
        server.onStateUpload = { server.unreadIdPages = listOf(emptyList()) }
        server.forget()

        synchronize()

        val upload = server.requestsTo("edit-tag").single().second
        assertTrue(
            upload.contains("i=$ARTICLE_A"),
            "the upload does not carry the article id in decimal: $upload"
        )
        assertTrue(
            upload.contains("a=user%2F-%2Fstate%2Fcom.google%2Fread"),
            "the upload does not add the read state: $upload"
        )

        val article = everyArticle().single()
        assertTrue(article.isRead, "the article stays read on the phone")
        assertEquals(readAtStamp, article.readAt, "a read the phone already knew is not restamped")
        assertNull(
            database.pendingChangeDao().select(ARTICLE_A),
            "the uploaded change leaves the queue"
        )
    }

    /**
     * §3, step 1: a decision made while the batch is in flight no longer holds
     * the value that was uploaded, so the clear does not match it and it waits
     * for the next sync. Step 4c leaves the article alone meanwhile.
     */
    @Test
    fun aChangeMadeWhileTheUploadIsInFlightStaysQueued() = runTest {
        server.readingListPages = listOf(listOf(FreshRSSStub.articleJson(ARTICLE_A)))
        server.serverIdPages = listOf(listOf(ARTICLE_A))
        server.unreadIdPages = listOf(listOf(ARTICLE_A))

        synchronize()

        database.itemDao().markRead(ARTICLE_A, System.currentTimeMillis())
        database.pendingChangeDao().queueRead(ARTICLE_A, true)

        // the user marks it unread again while the upload is on its way, and
        // the server, which took the read, stops calling it unread
        server.onStateUpload = {
            runBlocking {
                database.itemDao().markUnread(ARTICLE_A)
                database.pendingChangeDao().queueRead(ARTICLE_A, false)
            }
            server.unreadIdPages = listOf(emptyList())
        }

        synchronize()

        val queued = assertNotNull(
            database.pendingChangeDao().select(ARTICLE_A),
            "the change made while the batch was in flight was dropped"
        )
        assertEquals(false, queued.read, "the queue holds the decision the user made last")

        val article = everyArticle().single()
        assertFalse(article.isRead, "a pending read decision wins over the server's answer")
        assertNull(article.readAt, "an unread article has no read date")
    }

    /**
     * §3, step 2: an article arriving starred from the main stream is stored
     * like any other, and the state comes from the id lists — the read and
     * starred flags the content carries are ignored.
     */
    @Test
    fun anArticleArrivingStarredFromTheMainStreamIsStoredStarred() = runTest {
        server.readingListPages = listOf(
            listOf(
                FreshRSSStub.articleJson(
                    ARTICLE_A,
                    categories = listOf(FreshRSSStub.READ, FreshRSSStub.STARRED)
                )
            )
        )
        server.serverIdPages = listOf(listOf(ARTICLE_A))
        server.unreadIdPages = listOf(listOf(ARTICLE_A))
        server.starredIdPages = listOf(listOf(ARTICLE_A))

        synchronize()

        val article = everyArticle().single()
        assertTrue(article.isStarred, "the starred id list names this article")
        assertFalse(article.isRead, "the unread id list names it, whatever its content said")
        assertNull(article.readAt, "an unread article has no read date")
    }

    /**
     * §3, step 2: a starred id the store lacks has its content fetched from
     * `stream/items/contents`, so *starred articles survive both rules* can be
     * honoured for it.
     */
    @Test
    fun aStarredArticleTheStoreLacksHasItsContentFetched() = runTest {
        server.readingListPages = listOf(listOf(FreshRSSStub.articleJson(ARTICLE_A)))
        server.serverIdPages = listOf(listOf(ARTICLE_A))
        server.unreadIdPages = listOf(listOf(ARTICLE_A))

        synchronize()

        // the user stars an article on the web that this phone never held
        server.serverIdPages = listOf(listOf(ARTICLE_A, ARTICLE_C))
        server.starredIdPages = listOf(listOf(ARTICLE_C))
        server.itemsContentsArticles = listOf(
            FreshRSSStub.articleJson(ARTICLE_C, title = "the starred article the store lacked")
        )
        server.forget()

        synchronize()

        val fetch = assertNotNull(
            server.requestsTo("stream/items/contents").singleOrNull(),
            "the content of the starred article was never asked for"
        )
        assertTrue(
            fetch.second.contains("i=$ARTICLE_C"),
            "the content request does not name the article: ${fetch.second}"
        )

        val stored = everyArticle().first { it.id == ARTICLE_C }
        assertEquals("the starred article the store lacked", stored.title)
        assertTrue(stored.isStarred, "the article the starred list names is starred")
    }

    /**
     * §3, step 2: every call is paged to the end of `continuation` — the
     * contents and each of the three id lists.
     */
    @Test
    fun everyPageIsFollowedForTheContentsAndForEachIdList() = runTest {
        server.readingListPages = listOf(
            listOf(FreshRSSStub.articleJson(ARTICLE_A)),
            listOf(FreshRSSStub.articleJson(ARTICLE_B))
        )
        server.serverIdPages = listOf(listOf(ARTICLE_A), listOf(ARTICLE_B))
        server.unreadIdPages = listOf(listOf(ARTICLE_A), listOf(ARTICLE_B))
        server.starredIdPages = listOf(listOf(ARTICLE_A), listOf(ARTICLE_B))

        synchronize()

        val articles = everyArticle()
        assertEquals(
            listOf(ARTICLE_A, ARTICLE_B),
            articles.map { it.id },
            "the second page of the contents was not read"
        )
        assertTrue(
            articles.all { it.isStarred },
            "the second page of the starred ids was not read"
        )
        assertEquals(
            2,
            server.requestsTo("contents/user/-/state/com.google/reading-list").size,
            "the contents were not paged to the end"
        )
        assertEquals(
            6,
            server.requestsTo("stream/items/ids").size,
            "the three id lists were not each paged to the end"
        )
    }

    /**
     * §3, step 1: a batch the server refuses fails the sync before anything is
     * pulled. The queue is intact and the cursor has not moved, so the next
     * sync retries it.
     */
    @Test
    fun aRefusedUploadFailsTheSyncBeforeAnyPull() = runTest {
        server.readingListPages = listOf(listOf(FreshRSSStub.articleJson(ARTICLE_A)))
        server.serverIdPages = listOf(listOf(ARTICLE_A))
        server.unreadIdPages = listOf(listOf(ARTICLE_A))

        synchronize()

        database.itemDao().markRead(ARTICLE_A, System.currentTimeMillis())
        database.pendingChangeDao().queueRead(ARTICLE_A, true)

        val cursorBefore = storedAccount().cursor
        server.refuseStateUploads = true
        server.forget()

        assertFailsWith<Exception> { synchronize() }

        assertTrue(
            server.requests.all { (path, _) -> path.contains("edit-tag") },
            "the sync pulled although an upload had failed: ${server.requests.map { it.first }}"
        )
        assertEquals(
            true,
            database.pendingChangeDao().select(ARTICLE_A)?.read,
            "a change the server refused must stay queued"
        )
        assertEquals(cursorBefore, storedAccount().cursor, "the cursor did not move")
    }

    /**
     * §3, 4c: read state is only about the articles the server's full id list
     * still names. That list and the unread list are two separate calls and can
     * disagree — the server can drop an article between them — so an article
     * missing from the full list keeps the read it already had, and the date it
     * became read, which nothing could give back.
     */
    @Test
    fun anArticleMissingFromTheFullIdListKeepsItsReadState() = runTest {
        server.readingListPages = listOf(listOf(FreshRSSStub.articleJson(ARTICLE_A)))
        server.serverIdPages = listOf(listOf(ARTICLE_A))
        server.unreadIdPages = listOf(listOf(ARTICLE_A))

        synchronize()

        // read on the phone and already uploaded, so nothing waits in the queue
        val readAtStamp = System.currentTimeMillis() - A_MINUTE
        database.itemDao().markRead(ARTICLE_A, readAtStamp)

        // the server dropped the article between the two calls: its full id
        // list no longer names it, its unread list still does
        server.readingListPages = listOf(emptyList())
        server.serverIdPages = listOf(emptyList())
        server.unreadIdPages = listOf(listOf(ARTICLE_A))

        synchronize()

        val article = everyArticle().single()
        assertNull(
            database.pendingChangeDao().select(ARTICLE_A),
            "the read had already been uploaded, so nothing was queued"
        )
        assertTrue(article.isRead, "an article the full id list does not name keeps its read state")
        assertEquals(readAtStamp, article.readAt, "and the date on which it became read")
    }

    /**
     * §3, step 2: a page that brings nothing back and still asks for another one
     * is a broken answer, not the end of the walk. The sync fails with what it
     * had in hand and writes nothing: no article, no state, no cursor.
     */
    @Test
    fun contentsThatStopMakingProgressFailTheSyncAndWriteNothing() = runTest {
        server.readingListPages = listOf(listOf(FreshRSSStub.articleJson(ARTICLE_A)))
        server.serverIdPages = listOf(listOf(ARTICLE_A))
        server.unreadIdPages = listOf(listOf(ARTICLE_A))

        synchronize()

        val articlesBefore = everyArticle()
        val cursorBefore = storedAccount().cursor

        // a whole page of new content, then the broken answer behind it
        server.readingListPages = listOf(listOf(FreshRSSStub.articleJson(ARTICLE_B)))
        server.brokenReadingListPage =
            FreshRSSStub.BrokenPage.NOTHING_BACK_AND_ANOTHER_PAGE_ASKED_FOR
        server.serverIdPages = listOf(listOf(ARTICLE_A, ARTICLE_B))
        server.unreadIdPages = listOf(listOf(ARTICLE_B))

        assertFailsWith<ParseException> { synchronize() }

        assertEquals(
            articlesBefore,
            everyArticle(),
            "the partial content was committed, or the state of an article was changed"
        )
        assertEquals(cursorBefore, storedAccount().cursor, "the cursor moved past content")
    }

    /**
     * The same for an id list, which sends back the continuation it was given.
     * A partial full list is worse than partial content: it is what 4c, 4d and
     * retention decide against.
     */
    @Test
    fun anIdListThatStopsMakingProgressFailsTheSyncAndWritesNothing() = runTest {
        server.readingListPages = listOf(listOf(FreshRSSStub.articleJson(ARTICLE_A)))
        server.serverIdPages = listOf(listOf(ARTICLE_A))
        server.unreadIdPages = listOf(listOf(ARTICLE_A))
        server.starredIdPages = listOf(listOf(ARTICLE_A))

        synchronize()

        val articlesBefore = everyArticle()
        val cursorBefore = storedAccount().cursor
        assertTrue(articlesBefore.single().isStarred, "the starred id list names the article")

        server.serverIdPages = listOf(listOf(ARTICLE_A))
        server.brokenServerIdPage = FreshRSSStub.BrokenPage.THE_CONTINUATION_SENT_BACK
        server.unreadIdPages = listOf(emptyList())
        server.starredIdPages = listOf(emptyList())

        assertFailsWith<ParseException> { synchronize() }

        assertEquals(
            articlesBefore,
            everyArticle(),
            "state was applied from a list the server never finished sending"
        )
        assertEquals(cursorBefore, storedAccount().cursor, "the cursor moved")
    }

    /**
     * §4, the mirror: an article the server's full id list no longer names is
     * dropped by the sync that learns it, and the articles it still names stay.
     */
    @Test
    fun anArticleTheServerNoLongerHoldsIsGoneAfterTheSync() = runTest {
        server.readingListPages = listOf(
            listOf(FreshRSSStub.articleJson(ARTICLE_A), FreshRSSStub.articleJson(ARTICLE_B))
        )
        server.serverIdPages = listOf(listOf(ARTICLE_A, ARTICLE_B))
        server.unreadIdPages = listOf(listOf(ARTICLE_A, ARTICLE_B))

        synchronize()
        assertEquals(2, everyArticle().size)

        // the server has dropped the second article: it is in no list any more
        server.readingListPages = listOf(emptyList())
        server.serverIdPages = listOf(listOf(ARTICLE_A))
        server.unreadIdPages = listOf(listOf(ARTICLE_A))

        synchronize()

        assertEquals(
            listOf(ARTICLE_A),
            everyArticle().map { it.id },
            "the article the server no longer holds is still on the phone"
        )
    }

    /** §4: starred articles survive the mirror rule. */
    @Test
    fun aStarredArticleTheServerNoLongerHoldsStaysAfterTheSync() = runTest {
        server.readingListPages = listOf(
            listOf(FreshRSSStub.articleJson(ARTICLE_A), FreshRSSStub.articleJson(ARTICLE_B))
        )
        server.serverIdPages = listOf(listOf(ARTICLE_A, ARTICLE_B))
        server.unreadIdPages = listOf(listOf(ARTICLE_A, ARTICLE_B))
        server.starredIdPages = listOf(listOf(ARTICLE_B))

        synchronize()

        // the server drops the starred article from its reading list, and still
        // calls it starred
        server.readingListPages = listOf(emptyList())
        server.serverIdPages = listOf(listOf(ARTICLE_A))
        server.unreadIdPages = listOf(listOf(ARTICLE_A))

        synchronize()

        val starred = everyArticle().first { it.id == ARTICLE_B }
        assertTrue(starred.isStarred, "the article the starred list names is starred")
        assertEquals(2, everyArticle().size, "a starred article is dropped by neither rule")
    }

    /**
     * §4, the horizon: an article that became read more than thirty days before
     * this sync's own clock is dropped, although the server still holds it and
     * although this sync has nothing else to say about it.
     */
    @Test
    fun anArticleReadPastTheHorizonIsGoneAfterTheSync() = runTest {
        server.readingListPages = listOf(
            listOf(FreshRSSStub.articleJson(ARTICLE_A), FreshRSSStub.articleJson(ARTICLE_B))
        )
        server.serverIdPages = listOf(listOf(ARTICLE_A, ARTICLE_B))
        server.unreadIdPages = listOf(listOf(ARTICLE_A, ARTICLE_B))

        synchronize()

        // one was read a month ago, the other yesterday, both already uploaded
        database.itemDao().markRead(ARTICLE_A, System.currentTimeMillis() - THIRTY_ONE_DAYS)
        database.itemDao().markRead(ARTICLE_B, System.currentTimeMillis() - A_DAY)

        server.readingListPages = listOf(emptyList())
        server.serverIdPages = listOf(listOf(ARTICLE_A, ARTICLE_B))
        server.unreadIdPages = listOf(emptyList())

        synchronize()

        assertEquals(
            listOf(ARTICLE_B),
            everyArticle().map { it.id },
            "the horizon is measured from the moment the article became read"
        )
    }

    /**
     * §4: the delete runs inside the sync transaction and nowhere else, so a
     * sync that fails deletes nothing. The failure lands after the store has
     * been written — the delete has already run and its rows are gone until the
     * rollback puts them back.
     */
    @Test
    fun aFailedSyncDeletesNothing() = runTest {
        server.readingListPages = listOf(
            listOf(FreshRSSStub.articleJson(ARTICLE_A), FreshRSSStub.articleJson(ARTICLE_B))
        )
        server.serverIdPages = listOf(listOf(ARTICLE_A, ARTICLE_B))
        server.unreadIdPages = listOf(listOf(ARTICLE_A, ARTICLE_B))

        synchronize()

        val articlesBefore = everyArticle()
        val cursorBefore = storedAccount().cursor

        // the server has dropped one of them, and this sync fails before it
        // commits what it did about that
        server.readingListPages = listOf(emptyList())
        server.serverIdPages = listOf(listOf(ARTICLE_A))
        server.unreadIdPages = listOf(listOf(ARTICLE_A))

        assertFailsWith<IOException> { synchronize(failInsideTheTransaction = true) }

        assertEquals(articlesBefore, everyArticle(), "the rolled back sync deleted an article")
        assertEquals(cursorBefore, storedAccount().cursor, "the cursor moved")

        // and the sync that succeeds does delete it, so the check above is not
        // passing because nothing would have been deleted anyway
        synchronize()

        assertEquals(listOf(ARTICLE_A), everyArticle().map { it.id })
    }

    /**
     * §4: a page of the full id list the client cannot read is a broken answer,
     * not an empty one. Retention deletes what that list leaves out, so a
     * malformed first page — an empty object, an error the server sent with an
     * HTTP 200 — must fail the sync rather than be read as "the account holds
     * nothing". The store, the state and the cursor are untouched.
     */
    @Test
    fun aMalformedFirstPageOfTheIdListFailsTheSyncAndDeletesNothing() = runTest {
        server.readingListPages = listOf(
            listOf(FreshRSSStub.articleJson(ARTICLE_A), FreshRSSStub.articleJson(ARTICLE_B))
        )
        server.serverIdPages = listOf(listOf(ARTICLE_A, ARTICLE_B))
        server.unreadIdPages = listOf(listOf(ARTICLE_A, ARTICLE_B))

        synchronize()

        val articlesBefore = everyArticle()
        val cursorBefore = storedAccount().cursor
        assertEquals(2, articlesBefore.size)
        assertTrue(
            articlesBefore.none { it.isStarred },
            "these articles are the ones the mirror rule would drop: unread and unstarred"
        )

        // the server answers the full id list with an error object, which is
        // neither a page of ids nor an empty list
        server.readingListPages = listOf(emptyList())
        server.malformedServerIdBody = """{ "errors": [ "Unauthorized" ] }"""
        server.malformedServerIdPage = 0

        assertFailsWith<ParseException> { synchronize() }

        assertEquals(
            articlesBefore,
            everyArticle(),
            "an answer the client could not read was taken for the ids the server holds"
        )
        assertEquals(cursorBefore, storedAccount().cursor, "the cursor moved")
    }

    /**
     * The same for a page in the middle of the walk: the first page is a real
     * one, the answer behind it is not, and the ids already in hand are only
     * part of what the server holds.
     */
    @Test
    fun aMalformedContinuationPageOfTheIdListFailsTheSyncAndDeletesNothing() = runTest {
        server.readingListPages = listOf(
            listOf(FreshRSSStub.articleJson(ARTICLE_A), FreshRSSStub.articleJson(ARTICLE_B))
        )
        server.serverIdPages = listOf(listOf(ARTICLE_A, ARTICLE_B))
        server.unreadIdPages = listOf(listOf(ARTICLE_A, ARTICLE_B))

        synchronize()

        val articlesBefore = everyArticle()
        val cursorBefore = storedAccount().cursor
        assertEquals(2, articlesBefore.size)

        // one id a page, and the second page comes back empty of any list
        server.readingListPages = listOf(emptyList())
        server.serverIdPages = listOf(listOf(ARTICLE_A), listOf(ARTICLE_B))
        server.malformedServerIdBody = "{}"
        server.malformedServerIdPage = 1

        assertFailsWith<ParseException> { synchronize() }

        assertEquals(
            articlesBefore,
            everyArticle(),
            "half the server's id list was taken for the whole of it"
        )
        assertEquals(cursorBefore, storedAccount().cursor, "the cursor moved")
    }

    /**
     * §5, the last route: an article the server still holds and no longer calls
     * unread became read somewhere else, and the only date the API allows is
     * the sync's own clock — no output carries a read timestamp. The article the
     * server still calls unread gets no date at all, and the sync that follows
     * learns the same thing and leaves the first date alone.
     */
    @Test
    fun aReadLearnedAtSyncIsStampedWithTheSyncClockAndNotStampedAgain() = runTest {
        server.readingListPages = listOf(
            listOf(FreshRSSStub.articleJson(ARTICLE_A), FreshRSSStub.articleJson(ARTICLE_B))
        )
        server.serverIdPages = listOf(listOf(ARTICLE_A, ARTICLE_B))
        server.unreadIdPages = listOf(listOf(ARTICLE_A))

        val before = System.currentTimeMillis()
        synchronize()
        val after = System.currentTimeMillis()

        val stillUnread = everyArticle().first { it.id == ARTICLE_A }
        assertFalse(stillUnread.isRead)
        assertNull(stillUnread.readAt, "an unread article carries no date")

        val learnedRead = everyArticle().first { it.id == ARTICLE_B }
        assertTrue(learnedRead.isRead)
        val readAt = assertNotNull(learnedRead.readAt, "a read learned at sync carries no date")
        assertTrue(
            readAt in before..after,
            "the date is not this sync's clock: $readAt is outside $before..$after"
        )
        assertNull(
            database.pendingChangeDao().select(ARTICLE_B),
            "the server already knows, so there is nothing to tell it"
        )

        synchronize()

        assertEquals(
            readAt,
            everyArticle().first { it.id == ARTICLE_B }.readAt,
            "a read the phone already knew was stamped again"
        )
    }

    /**
     * The regression the review of ticket 15 asked for. The reader opens an
     * article and stars it; a sync runs while the screen is open and the
     * server's full id list no longer names that article.
     *
     * The star is in the store before that sync — the item screen writes each
     * decision as it is made and buffers nothing until it is left — so retention
     * sees a starred article and keeps it, which is the rule. Leaving the screen
     * writes nothing at all now, and a decision made afterwards still works.
     */
    @Test
    fun anArticleStarredWhileItIsOpenSurvivesASyncThatDropsIt() = runTest {
        server.readingListPages = listOf(
            listOf(FreshRSSStub.articleJson(ARTICLE_A), FreshRSSStub.articleJson(ARTICLE_B))
        )
        server.serverIdPages = listOf(listOf(ARTICLE_A, ARTICLE_B))
        server.unreadIdPages = listOf(listOf(ARTICLE_A, ARTICLE_B))

        synchronize()

        // the reader opens the first article and stars it
        val repository = repository()
        repository.setItemStarState(Item(id = ARTICLE_A).apply { isStarred = true })
        assertTrue(database.itemDao().select(ARTICLE_A)!!.isStarred, "the star was buffered")

        // the sync that runs meanwhile: the server has dropped that article and
        // does not call it starred either
        server.readingListPages = listOf(emptyList())
        server.serverIdPages = listOf(listOf(ARTICLE_B))
        server.unreadIdPages = listOf(listOf(ARTICLE_B))
        server.starredIdPages = listOf(emptyList())

        synchronize()

        val left = everyArticle()
        assertEquals(
            listOf(ARTICLE_A, ARTICLE_B),
            left.map { it.id },
            "the article the reader had just starred was dropped"
        )
        assertTrue(left.first { it.id == ARTICLE_A }.isStarred, "the star the reader saw is gone")

        // leaving the screen writes nothing; acting on the article again works
        repository.setItemReadState(Item(id = ARTICLE_A).apply { isRead = true })
        assertTrue(database.itemDao().select(ARTICLE_A)!!.isRead)
    }

    /**
     * Runs one sync through the repository rather than through [Synchronizer],
     * so that [GReaderRepository.afterTheStoreIsWritten] can be used to fail
     * the transaction where the model says everything must roll back.
     */
    private suspend fun synchronize(failInsideTheTransaction: Boolean = false) {
        repository(failInsideTheTransaction).synchronize()
    }

    /**
     * The repository the app builds for the one account, pointed at the stub.
     * The tests that act on an article the way a screen does use it too, so what
     * they exercise is the code the screens call.
     */
    private suspend fun repository(
        failInsideTheTransaction: Boolean = false
    ): GReaderRepository {
        val account = storedAccount()
        getKoin().get<AuthInterceptor>().credentials = Credentials.toCredentials(account)

        val dataSource = getKoin().get<GReaderDataSource> {
            parametersOf(Credentials.toCredentials(account))
        }

        return object : GReaderRepository(database, account, dataSource) {
            override suspend fun afterTheStoreIsWritten() {
                if (failInsideTheTransaction) {
                    throw IOException("a failure injected inside the sync transaction")
                }
            }
        }
    }

    private suspend fun storedAccount(): Account = database.accountDao().select()!!

    private suspend fun everyArticle(): List<Item> =
        database.itemDao().selectEveryArticle().sortedBy { it.id }

    private companion object {
        const val ARTICLE_A = 1625234531559678L
        const val ARTICLE_B = 1625234531559679L
        const val ARTICLE_C = 1625234531559680L

        /** Far enough in the past that a restamped read date could not match it. */
        const val A_MINUTE = 60_000L

        const val A_DAY = 86_400_000L
        const val THIRTY_ONE_DAYS = 31 * A_DAY
    }
}
