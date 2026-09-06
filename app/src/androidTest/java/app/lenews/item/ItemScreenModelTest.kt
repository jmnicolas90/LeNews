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
package app.lenews.item

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.emptyPreferences
import androidx.paging.PagingDataEvent
import androidx.paging.PagingDataPresenter
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.datastore.preferences.core.Preferences as StoredPreferences
import cafe.adriel.voyager.core.model.screenModelScope
import app.lenews.db.Database
import app.lenews.db.entities.Feed
import app.lenews.db.entities.Item
import app.lenews.db.entities.account.Account
import app.lenews.db.filters.MainFilter
import app.lenews.db.filters.QueryFilters
import app.lenews.db.pojo.ItemWithFeed
import app.lenews.db.queries.ItemsQueryBuilder
import app.lenews.repositories.BaseRepository
import app.lenews.repositories.ErrorResult
import app.lenews.repositories.SyncResult
import app.lenews.testutil.LeNewsTestRule
import app.lenews.util.ApplicationScope
import app.lenews.util.DataStorePreferences
import app.lenews.util.Preferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koin.dsl.module
import org.koin.test.KoinTest
import org.koin.test.inject
import java.time.LocalDateTime
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What the item screen does with the reader's decisions, at the model, which is
 * the only seam the screen has: the pager, the page effect and the disposal all
 * arrive here as method calls.
 *
 * Three things this holds it to, all three of them found by the review of
 * ticket 16:
 *
 * - a list built again around the open article is not a page the reader has
 *   turned to, so it cannot mark that article read;
 * - a decision outlives the screen it was made on;
 * - after the process is killed the reader comes back to the article they were
 *   reading, not to whatever has taken its place in the list.
 *
 * And, from the review of ticket 21, what the reader is told about the images
 * they save: one message per image, in order, each one acknowledged on its own.
 */
class ItemScreenModelTest : KoinTest {

    private val database: Database by inject()

    // not the application's own: it is a file, and the rule builds a second
    // store on the same file for every test, which DataStore refuses
    private val preferences = Preferences(DataStorePreferences(PreferencesInMemory()))

    @get:Rule
    val rule = LeNewsTestRule()

    private lateinit var applicationScope: ApplicationScope
    private lateinit var writer: ExecutorService
    private lateinit var dispatcher: CoroutineDispatcher

    private val models = mutableListOf<ItemScreenModel>()

    @Before
    fun before() = runBlocking {
        database.accountDao()
            .upsert(Account(name = "Account", url = "https://freshrss.example/api/greader.php"))
        database.feedDao().insert(Feed(name = "First", remoteId = "feed/first"))

        applicationScope = ApplicationScope()

        // one thread, so that a decision that was launched has run by the time
        // the next one has: a test that looks for a write that should not have
        // happened has to give it its chance first
        writer = Executors.newSingleThreadExecutor()
        dispatcher = writer.asCoroutineDispatcher()
    }

    @After
    fun after() {
        models.forEach { it.screenModelScope.coroutineContext.cancelChildren() }
        applicationScope.cancel()
        writer.shutdown()
        database.clearAllTables()
    }

    /**
     * The history, and the reader marking the article they are on unread. It
     * leaves the list, the list is built again in another order, and the pager
     * follows the article's key to its new index — which the screen reports as
     * the page the reader is on. Reading that as a page turn marks the article
     * read again, one moment after the reader asked for the opposite.
     */
    @Test
    fun theListBeingBuiltAgainDoesNotUndoTheDecisionToMarkAnArticleUnread() = runBlocking {
        store(article(ARTICLE_A), article(ARTICLE_B))
        database.itemDao().markRead(ARTICLE_A, NOW)
        database.itemDao().markRead(ARTICLE_B, NOW - A_MINUTE)

        val model = screenModel(ARTICLE_A, itemIndex = 0, history)

        // the screen opens on the first article of the history
        model.setItemRead(page(ARTICLE_A, isRead = true))

        // the reader marks it unread from the bottom bar
        model.setItemReadState(Item(id = ARTICLE_A).apply { isRead = true })
        await("the article was never marked unread") {
            database.itemDao().select(ARTICLE_A)?.isRead == false
        }

        // the same article, at the index the reordered list gave it
        model.setItemRead(page(ARTICLE_A, isRead = false))

        // a decision launched after that one has to be through before the
        // absence of a write can mean anything
        model.setItemStarState(Item(id = ARTICLE_B))
        await("the star that follows the reindex was never written") {
            database.itemDao().select(ARTICLE_B)?.isStarred == true
        }

        val article = database.itemDao().select(ARTICLE_A)!!
        assertFalse(article.isRead, "the reader's decision to mark it unread was undone")
        assertNull(article.readAt, "it was stamped with a moment the reader never asked for")
        assertEquals(
            false,
            database.pendingChangeDao().select(ARTICLE_A)?.read,
            "FreshRSS would be told the opposite of what the reader decided"
        )
    }

    /** The guard above stops a reindex, not the reader turning the page. */
    @Test
    fun thePageTheReaderTurnsToStillBecomesRead() = runBlocking {
        store(article(ARTICLE_A), article(ARTICLE_B))

        val model = screenModel(ARTICLE_A, itemIndex = 0, QueryFilters())

        model.setItemRead(page(ARTICLE_A, isRead = false))
        await("the article the screen opened on was not marked read") {
            database.itemDao().select(ARTICLE_A)?.isRead == true
        }

        model.setItemRead(page(ARTICLE_B, isRead = false))
        await("the article the reader swiped to was not marked read") {
            database.itemDao().select(ARTICLE_B)?.isRead == true
        }
    }

    /**
     * The screen is disposed while the write it was given is still waiting for
     * Room — a sync holding the transaction executor is enough. The screen's
     * own scope goes with the screen, so the write cannot be on it.
     */
    @Test
    fun aDecisionMadeBeforeTheScreenIsDisposedIsStillWritten() = runBlocking {
        val writeHasStarted = CompletableDeferred<Unit>()
        val letTheWriteThrough = CompletableDeferred<Unit>()

        rule.koin.loadModules(
            listOf(
                module {
                    factory<BaseRepository> { (account: Account) ->
                        HeldUpRepository(get(), account, writeHasStarted, letTheWriteThrough)
                    }
                }
            )
        )

        store(article(ARTICLE_A))
        val model = screenModel(ARTICLE_A, itemIndex = 0, QueryFilters())

        // the reader taps the read toggle and leaves at once
        model.setItemReadState(Item(id = ARTICLE_A))
        withTimeout(FIVE_SECONDS) { writeHasStarted.await() }

        // Voyager disposing the screen. It cancels the screen's scope; this
        // cancels everything running in it, which is the same thing for a write
        // in flight, and leaves the scope itself for the next test — Voyager
        // hands one scope to every model built outside a screen, so a scope
        // cancelled here would be a dead scope there.
        model.screenModelScope.coroutineContext.cancelChildren()

        letTheWriteThrough.complete(Unit)

        await("the decision was lost when the screen was disposed") {
            database.itemDao().select(ARTICLE_A)?.isRead == true
        }
        assertEquals(
            true,
            database.pendingChangeDao().select(ARTICLE_A)?.read,
            "the article is read on the phone and FreshRSS will never hear of it"
        )
    }

    /**
     * The reader opens an article from the unread timeline, it is marked read,
     * and the process is killed. The screen that comes back is given the same
     * article id and the same position; the list behind it no longer holds the
     * article, and a sync has put **sixty** articles above it — more than the
     * pager loads at once, so a screen that looked for the article in the first
     * page it loaded would not find it, would fall back to the position the
     * timeline passed, and would open a stranger's article and mark it read.
     *
     * This goes through the model's own pager rather than through a query
     * written here, because the loaded window is the whole point.
     */
    @Test
    fun afterTheProcessDiesTheReaderIsBackOnTheArticleTheyWereReading() = runBlocking {
        store(article(ARTICLE_A, published = LocalDateTime.now().minusHours(NEWER_ARTICLES + 1L)))
        database.itemDao().markRead(ARTICLE_A, NOW)

        // the sync that arrived while the process was dead, newest last
        store(
            *(1..NEWER_ARTICLES).map {
                article(
                    ARTICLE_A + it,
                    published = LocalDateTime.now().minusHours(NEWER_ARTICLES + 1L - it)
                )
            }.toTypedArray()
        )

        val unreadTimeline = QueryFilters(showReadItems = false)
        assertFalse(
            ARTICLE_A in idsOf(ItemsQueryBuilder.buildItemsQuery(unreadTimeline)),
            "the article is still in the unread list, so this test proves nothing"
        )

        val model = screenModel(ARTICLE_A, itemIndex = 0, unreadTimeline)

        assertTrue(
            ARTICLE_A in model.keptArticles,
            "the recreated screen does not ask for the article it was opened on"
        )

        val position = awaitPosition(model)
        assertEquals(NEWER_ARTICLES, position, "the store was not asked where the article is now")

        val pages = presentedList(model)
        assertEquals(ARTICLE_A, pages[position], "the pager did not load the page it is on")
        assertEquals(
            position,
            initialPage(pages, itemId = ARTICLE_A, articlePosition = position),
            "the screen opens on an article that has taken the reader's place"
        )
    }

    /**
     * Retention drops articles inside every sync, so the article a screen was
     * opened on can be gone by the time the screen builds its list. There is no
     * honest page to show: the neighbour that would take its place is another
     * article, and opening it marks it read. The screen is told instead, and it
     * goes back to the list.
     */
    @Test
    fun anArticleTheStoreNoLongerHoldsClosesTheScreenInsteadOfOpeningANeighbour() = runBlocking {
        store(article(ARTICLE_B))

        val model = screenModel(ARTICLE_A, itemIndex = 0, QueryFilters())

        await("the screen was never told the article it was opened on is gone") {
            model.state.value.articleIsGone
        }
        assertNull(
            model.state.value.articlePosition,
            "the screen was given a position in a list the article is not in"
        )
    }

    /**
     * Two images in a row, one saved and one not. The snackbar can only show
     * one at a time, so the second has to wait rather than replace the first —
     * before, a failure arriving while a success was showing was thrown away
     * when the reader dismissed the success, and the download that failed said
     * nothing at all.
     */
    @Test
    fun twoImageResultsInARowAreTwoMessagesInOrder() = runBlocking {
        val model = screenModel(ARTICLE_A, itemIndex = 0, QueryFilters())

        model.reportImageSaved("cat.png")
        model.reportImageFailed("the image could not be saved")

        val results = model.state.value.imageResults
        assertEquals(2, results.size, "a result was dropped")
        assertEquals("cat.png", (results[0] as ImageResult.Saved).fileName)
        assertEquals(
            "the image could not be saved",
            (results[1] as ImageResult.Failed).message,
            "the failure did not follow the success"
        )
    }

    /** Acknowledging the snackbar that was shown acknowledges only that one. */
    @Test
    fun acknowledgingTheFirstMessageLeavesTheSecond() = runBlocking {
        val model = screenModel(ARTICLE_A, itemIndex = 0, QueryFilters())

        model.reportImageSaved("cat.png")
        model.reportImageFailed("the image could not be saved")

        val shown = model.state.value.imageResults.first()
        model.imageResultShown(shown.id)

        val left = model.state.value.imageResults
        assertEquals(1, left.size, "the message the reader never saw went with the one they did")
        assertEquals(
            "the image could not be saved",
            (left.single() as ImageResult.Failed).message
        )
    }

    /** Two images saved one after the other are two messages, not one. */
    @Test
    fun twoSavedImagesAreNotConflatedIntoOneMessage() = runBlocking {
        val model = screenModel(ARTICLE_A, itemIndex = 0, QueryFilters())

        model.reportImageSaved("cat.png")
        model.reportImageSaved("cat.png")

        val results = model.state.value.imageResults
        assertEquals(2, results.size, "the second download said nothing")
        assertTrue(
            results[0].id != results[1].id,
            "two results share an id, so acknowledging one acknowledges both"
        )
    }

    private fun screenModel(
        itemId: Long,
        itemIndex: Int,
        queryFilters: QueryFilters
    ): ItemScreenModel =
        ItemScreenModel(
            itemId = itemId,
            itemIndex = itemIndex,
            queryFilters = queryFilters,
            database = database,
            preferences = preferences,
            applicationScope = applicationScope,
            dispatcher = dispatcher
        ).also { models += it }

    /** What the pager hands the model for the page the reader is on. */
    private fun page(id: Long, isRead: Boolean) = ItemWithFeed(
        item = Item(id = id, title = "article $id", feedId = FEED_ONE),
        feedName = "First",
        feedId = FEED_ONE,
        color = 0,
        feedIconUrl = null,
        websiteUrl = null,
        folder = null,
        isRead = isRead,
        openIn = null
    )

    private suspend fun store(vararg articles: Item) {
        database.itemDao().upsertArticles(articles.toList())
    }

    private fun article(id: Long, published: LocalDateTime = LocalDateTime.now().minusHours(1)) =
        Item(id = id, title = "article $id", feedId = FEED_ONE, pubDate = published)

    private fun idsOf(query: SupportSQLiteQuery): List<Long> {
        val ids = mutableListOf<Long>()

        database.query(query).use { cursor ->
            val column = cursor.getColumnIndexOrThrow("id")
            while (cursor.moveToNext()) {
                ids += cursor.getLong(column)
            }
        }

        return ids
    }

    /**
     * Waits for the model to work out where in the list the article the screen
     * was opened on is now.
     */
    private suspend fun awaitPosition(model: ItemScreenModel): Int {
        val deadline = System.currentTimeMillis() + FIVE_SECONDS

        while (System.currentTimeMillis() < deadline) {
            model.state.value.articlePosition?.let { return it }

            if (model.state.value.articleIsGone) {
                fail("the article the screen was opened on was reported gone")
            }
            delay(20)
        }

        fail("the screen never found the article it was opened on")
    }

    /**
     * The list the reader's pager actually holds: the ids of the pages it
     * loaded, in order, with null where a page is a placeholder — which is
     * exactly what the screen reads off `LazyPagingItems.itemSnapshotList`.
     */
    private suspend fun presentedList(model: ItemScreenModel): List<Long?> {
        val presenter = object :
            PagingDataPresenter<ItemWithFeed>(mainContext = EmptyCoroutineContext) {

            override suspend fun presentPagingDataEvent(event: PagingDataEvent<ItemWithFeed>) = Unit
        }

        val presented = Channel<Unit>(Channel.CONFLATED)
        presenter.addOnPagesUpdatedListener { presented.trySend(Unit) }

        val collection = CoroutineScope(Dispatchers.Default).launch {
            model.itemState.collectLatest { presenter.collectFrom(it) }
        }

        try {
            withTimeout(FIVE_SECONDS) {
                while (presenter.size == 0) {
                    presented.receive()
                }
            }

            return presenter.snapshot().map { it?.item?.id }
        } finally {
            collection.cancelAndJoin()
        }
    }

    /** Waits for a write that is on its way, and says what was missing if it never lands. */
    private suspend fun await(missing: String, written: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + FIVE_SECONDS

        while (System.currentTimeMillis() < deadline) {
            if (written()) {
                return
            }
            delay(20)
        }

        fail(missing)
    }

    /** The preferences of a test: their defaults, and no file. */
    private class PreferencesInMemory : DataStore<StoredPreferences> {

        private val stored = MutableStateFlow(emptyPreferences())

        override val data = stored

        override suspend fun updateData(
            transform: suspend (StoredPreferences) -> StoredPreferences
        ): StoredPreferences = transform(stored.value).also { stored.value = it }
    }

    /**
     * A repository whose write waits to be let through, which is what a write
     * behind a sync holding Room's transaction executor does.
     */
    private class HeldUpRepository(
        database: Database,
        account: Account,
        private val writeHasStarted: CompletableDeferred<Unit>,
        private val letTheWriteThrough: CompletableDeferred<Unit>
    ) : BaseRepository(database, account) {

        override suspend fun setItemReadState(item: Item) {
            writeHasStarted.complete(Unit)
            letTheWriteThrough.await()
            super.setItemReadState(item)
        }

        override suspend fun login(account: Account) = Unit

        override suspend fun synchronize() = SyncResult()

        override suspend fun insertNewFeeds(
            newFeeds: List<Feed>,
            onUpdate: (Feed) -> Unit
        ): ErrorResult = ErrorResult()
    }

    private companion object {
        val history = QueryFilters(mainFilter = MainFilter.HISTORY)

        const val FEED_ONE = 1

        const val ARTICLE_A = 1_625_234_531_559_678L
        const val ARTICLE_B = 1_625_234_531_559_679L

        /** More than one page of the pager, which is what makes the list a new one. */
        const val NEWER_ARTICLES = 60

        const val A_MINUTE = 60_000L
        const val NOW = 1_757_160_000_000L
        const val FIVE_SECONDS = 5_000L
    }
}
