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

import app.lenews.db.Database
import app.lenews.db.entities.Feed
import app.lenews.db.entities.Folder
import app.lenews.db.entities.Item
import app.lenews.db.entities.account.Account
import app.lenews.testutil.LeNewsTestRule
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koin.test.KoinTest
import org.koin.test.inject
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The routes of `docs/article-store.md` §5 as the screens use them: the item
 * screen and the timeline both go through `BaseRepository`, and every one of
 * its methods writes the state, the date and the pending change together, at
 * the moment the reader acts. Nothing is buffered until the screen is left.
 *
 * The database tests in the `db` module hold the statements to their scope;
 * what is checked here is the pairing — a state written without a pending
 * change never reaches FreshRSS, a pending change written without the state
 * lies to it — and the clock, which is the phone's own.
 */
class BecomingReadRoutesTest : KoinTest {

    private val database: Database by inject()

    @get:Rule
    val rule = LeNewsTestRule()

    private lateinit var repository: BaseRepository

    @Before
    fun before() = runBlocking {
        val account = Account(name = "Account", url = "https://freshrss.example/api/greader.php")
        database.accountDao().upsert(account)

        database.folderDao().insert(Folder(name = "News", remoteId = "user/-/label/News"))
        database.folderDao().insert(Folder(name = "Other", remoteId = "user/-/label/Other"))
        database.feedDao().insert(feed("First", FOLDER_WITH_ARTICLES))
        database.feedDao().insert(feed("Second", FOLDER_WITH_ARTICLES))
        database.feedDao().insert(feed("Third", OTHER_FOLDER))

        repository = TestRepository(database, account)
    }

    @After
    fun after() {
        database.clearAllTables()
    }

    /**
     * Opening an article, swiping it, or the timeline marking it read as it
     * scrolls past: all three come here. The date is the phone's clock, marking
     * it unread takes it out of the history, and reading it again is a new date.
     */
    @Test
    fun readingAnArticleWritesTheStateTheDateAndThePendingChangeAtOnce() = runTest {
        store(unread(ARTICLE_A, FEED_ONE))

        val before = System.currentTimeMillis()
        repository.setItemReadState(article(ARTICLE_A).apply { isRead = true })
        val after = System.currentTimeMillis()

        val read = database.itemDao().select(ARTICLE_A)!!
        assertTrue(read.isRead)
        val readAt = assertNotNull(read.readAt, "the article became read with no date")
        assertTrue(readAt in before..after, "the date is not the moment the reader acted")
        assertEquals(true, queued(ARTICLE_A), "FreshRSS would never be told")

        repository.setItemReadState(article(ARTICLE_A).apply { isRead = false })
        val backToUnread = database.itemDao().select(ARTICLE_A)!!
        assertFalse(backToUnread.isRead)
        assertNull(backToUnread.readAt, "an article out of the history still carries a date")
        assertEquals(false, queued(ARTICLE_A))

        Thread.sleep(2)
        repository.setItemReadState(article(ARTICLE_A).apply { isRead = true })
        val readAgain = assertNotNull(database.itemDao().select(ARTICLE_A)!!.readAt)
        assertTrue(readAgain > readAt, "reading it again did not date it again")
    }

    /** Starring writes at once too, and says nothing about reading. */
    @Test
    fun starringAnArticleWritesAtOnceAndTouchesNeitherReadNorItsDate() = runTest {
        store(unread(ARTICLE_A, FEED_ONE))
        repository.setItemReadState(article(ARTICLE_A).apply { isRead = true })
        val readAt = database.itemDao().select(ARTICLE_A)!!.readAt

        repository.setItemStarState(article(ARTICLE_A).apply { isStarred = true })

        val starred = database.itemDao().select(ARTICLE_A)!!
        assertTrue(starred.isStarred)
        assertTrue(starred.isRead, "starring an article changed whether it was read")
        assertEquals(readAt, starred.readAt, "starring an article changed the date it became read")
        assertEquals(true, database.pendingChangeDao().select(ARTICLE_A)?.starred)
        assertEquals(true, queued(ARTICLE_A), "the read decision was overwritten by the star")
    }

    /**
     * An article the store no longer holds. Retention drops articles inside
     * every sync, so a screen open across one can be holding an article that is
     * gone; the decision is dropped rather than queued against a row that is not
     * there, which the foreign key would refuse.
     */
    @Test
    fun aDecisionAboutAnArticleTheStoreNoLongerHoldsChangesNothingAndThrowsNothing() = runTest {
        store(unread(ARTICLE_A, FEED_ONE))
        database.itemDao().delete(article(ARTICLE_A))

        repository.setItemStarState(article(ARTICLE_A).apply { isStarred = true })
        repository.setItemReadState(article(ARTICLE_A).apply { isRead = true })

        assertNull(database.itemDao().select(ARTICLE_A))
        assertNull(database.pendingChangeDao().select(ARTICLE_A))
    }

    @Test
    fun markingTheWholeListReadMarksAndQueuesOnlyWhatChanges() = runTest {
        val readLongAgo = threeArticlesOneOfThemAlreadyRead()

        repository.setAllItemsRead()

        assertStampedNow(ARTICLE_B)
        assertStampedNow(ARTICLE_C)
        assertEquals(LONG_AGO, database.itemDao().select(readLongAgo)!!.readAt)
        assertNull(database.pendingChangeDao().select(readLongAgo))
    }

    @Test
    fun markingOneFeedReadMarksAndQueuesOnlyThatFeed() = runTest {
        store(
            unread(ARTICLE_A, FEED_ONE),
            unread(ARTICLE_B, FEED_TWO)
        )

        repository.setAllItemsReadByFeed(FEED_ONE)

        assertStampedNow(ARTICLE_A)
        assertUnreadAndNotQueued(ARTICLE_B)
    }

    @Test
    fun markingOneFolderReadMarksAndQueuesOnlyThatFolder() = runTest {
        store(
            unread(ARTICLE_A, FEED_ONE),
            unread(ARTICLE_B, FEED_TWO),
            unread(ARTICLE_C, FEED_THREE)
        )

        repository.setAllItemsReadByFolder(FOLDER_WITH_ARTICLES)

        assertStampedNow(ARTICLE_A)
        assertStampedNow(ARTICLE_B)
        assertUnreadAndNotQueued(ARTICLE_C)
    }

    /**
     * Upstream issue #341: marking the starred list read did nothing at all,
     * because the statement chose the articles on a column FreshRSS accounts
     * never wrote. There is one `starred` column now and the sync writes it, so
     * the articles the stars screen shows are the articles this route marks.
     */
    @Test
    fun markingTheStarredListReadMarksAndQueuesTheStarredUnreadArticles() = runTest {
        store(unread(ARTICLE_A, FEED_ONE), unread(ARTICLE_B, FEED_ONE))
        repository.setItemStarState(article(ARTICLE_A).apply { isStarred = true })

        repository.setAllStarredItemsRead()

        assertStampedNow(ARTICLE_A)
        assertUnreadAndNotQueued(ARTICLE_B)
    }

    @Test
    fun markingTheLastDayReadMarksAndQueuesOnlyTheLastDay() = runTest {
        store(
            unread(ARTICLE_A, FEED_ONE, LocalDateTime.now().minusHours(2)),
            unread(ARTICLE_B, FEED_ONE, LocalDateTime.now().minusDays(7))
        )

        repository.setAllNewItemsRead()

        assertStampedNow(ARTICLE_A)
        assertUnreadAndNotQueued(ARTICLE_B)
    }

    private suspend fun threeArticlesOneOfThemAlreadyRead(): Long {
        store(
            unread(ARTICLE_A, FEED_ONE),
            unread(ARTICLE_B, FEED_ONE),
            unread(ARTICLE_C, FEED_TWO)
        )
        database.itemDao().markRead(ARTICLE_A, LONG_AGO)

        return ARTICLE_A
    }

    private suspend fun assertStampedNow(articleId: Long) {
        val article = database.itemDao().select(articleId)!!
        assertTrue(article.isRead, "article $articleId is not read")
        val readAt = assertNotNull(article.readAt, "article $articleId became read with no date")
        assertTrue(
            readAt > System.currentTimeMillis() - A_MINUTE,
            "article $articleId was not stamped with the moment the reader acted"
        )
        assertEquals(true, queued(articleId), "article $articleId was not queued for FreshRSS")
    }

    private suspend fun assertUnreadAndNotQueued(articleId: Long) {
        val article = database.itemDao().select(articleId)!!
        assertFalse(article.isRead, "article $articleId was marked read out of scope")
        assertNull(article.readAt)
        assertNull(database.pendingChangeDao().select(articleId))
    }

    private suspend fun queued(articleId: Long): Boolean? =
        database.pendingChangeDao().select(articleId)?.read

    private suspend fun store(vararg articles: Item) {
        database.itemDao().upsertArticles(articles.toList())
    }

    private fun unread(
        id: Long,
        feedId: Int,
        published: LocalDateTime = LocalDateTime.now().minusHours(1)
    ) = Item(id = id, title = "article $id", feedId = feedId, pubDate = published)

    /** What a screen hands the repository: an id and the state it decided. */
    private fun article(id: Long) = Item(id = id)

    private fun feed(name: String, folderId: Int) =
        Feed(name = name, remoteId = "feed/$name", folderId = folderId)

    /**
     * The routes under test are `BaseRepository`'s own; what the FreshRSS
     * subclass adds is the network, which none of them touches.
     */
    private class TestRepository(database: Database, account: Account) :
        BaseRepository(database, account) {

        override suspend fun login(account: Account) = Unit

        override suspend fun synchronize() = SyncResult()

        override suspend fun insertNewFeeds(
            newFeeds: List<Feed>,
            onUpdate: (Feed) -> Unit
        ): ErrorResult = ErrorResult()
    }

    private companion object {
        const val FOLDER_WITH_ARTICLES = 1
        const val OTHER_FOLDER = 2

        const val FEED_ONE = 1
        const val FEED_TWO = 2
        const val FEED_THREE = 3

        const val ARTICLE_A = 1_625_234_531_559_678L
        const val ARTICLE_B = 1_625_234_531_559_679L
        const val ARTICLE_C = 1_625_234_531_559_680L

        const val A_MINUTE = 60_000L
        const val LONG_AGO = 1_000_000_000_000L
    }
}
