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
package app.lenews.util.paging

import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingDataEvent
import androidx.paging.PagingDataPresenter
import androidx.paging.PagingSource
import androidx.paging.PagingState
import app.lenews.util.PAGING_INITIAL_SIZE
import app.lenews.util.PAGING_PAGE_SIZE
import app.lenews.util.PAGING_PREFETCH_DISTANCE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where the timeline's list starts and ends, and so where the retry for a page
 * that failed sits.
 *
 * The rule is a plain function of a handful of numbers, and the last two tests
 * hold it to the real thing: a paged list with placeholders, a thousand
 * matching articles, fifty loaded, and a load failing at one end or the other.
 */
class TimelineRowCountTest {

    @Test
    fun `while pages are still arriving every matching article is a row`() {
        // The rows for articles not loaded yet are blank for a moment and then
        // fill: the reader reaches one just before it arrives.
        assertEquals(1000, rowCount(itemCount = 1000, placeholdersAfter = 950))
        assertEquals(0, timelineFirstRow(placeholdersBefore = 0, previousPageFailed = false))
    }

    @Test
    fun `once the next page has failed the rows stop at the loaded articles`() {
        // Nothing is going to fill those 950 rows, so they are 950 blank rows
        // between the last article and the retry.
        assertEquals(
            50,
            rowCount(itemCount = 1000, placeholdersAfter = 950, nextPageFailed = true)
        )
    }

    @Test
    fun `a failure with everything loaded takes nothing off the list`() {
        assertEquals(50, rowCount(itemCount = 50, placeholdersAfter = 0, nextPageFailed = true))
    }

    /**
     * The mirror at the other end. A list opened in its middle has placeholders
     * before the articles it loaded too, and they stay blank just the same when
     * the page above has failed — with the retry above all of them, where nobody
     * scrolls up far enough to find it.
     */
    @Test
    fun `once the page above has failed the list starts at the loaded articles`() {
        assertEquals(
            400,
            timelineFirstRow(placeholdersBefore = 400, previousPageFailed = true)
        )
        assertEquals(
            600,
            rowCount(
                itemCount = 1000,
                placeholdersBefore = 400,
                placeholdersAfter = 550,
                previousPageFailed = true
            )
        )
    }

    @Test
    fun `a page above that is still loading leaves the rows alone`() {
        assertEquals(0, timelineFirstRow(placeholdersBefore = 400, previousPageFailed = false))
        assertEquals(
            1000,
            rowCount(itemCount = 1000, placeholdersBefore = 400, placeholdersAfter = 550)
        )
    }

    /** Both ends failed: the list is exactly the articles that did load. */
    @Test
    fun `a failure at both ends leaves only the loaded articles`() {
        assertEquals(
            50,
            rowCount(
                itemCount = 1000,
                placeholdersBefore = 400,
                placeholdersAfter = 550,
                nextPageFailed = true,
                previousPageFailed = true
            )
        )
    }

    @Test
    fun `the retry follows the last loaded article of a real paged list`() = runBlocking {
        val presenter = TimelinePresenter()
        val pager = Pager(
            config = PagingConfig(
                initialLoadSize = PAGING_INITIAL_SIZE,
                pageSize = PAGING_PAGE_SIZE,
                prefetchDistance = PAGING_PREFETCH_DISTANCE
            ),
            pagingSourceFactory = { FirstPageOnly(total = 1000) }
        )

        val presented = Channel<Unit>(Channel.CONFLATED)
        presenter.addOnPagesUpdatedListener { presented.trySend(Unit) }

        val collection = launch(Dispatchers.Default) {
            pager.flow.collectLatest { presenter.collectFrom(it) }
        }
        withTimeout(TIMEOUT_MS) { presented.receive() }

        // the reader reaches the last article of the first page, which is what
        // asks for the next one
        presenter.get(PAGING_INITIAL_SIZE - 1)
        withTimeout(TIMEOUT_MS) {
            while (presenter.loadStateFlow.value?.append !is LoadState.Error) delay(POLL_MS)
        }

        val loadState = requireNotNull(presenter.loadStateFlow.value)
        val snapshot = presenter.snapshot()

        assertEquals(1000, presenter.size, "the list does not count the articles not loaded")
        assertEquals(950, snapshot.placeholdersAfter, "the articles not loaded are not at the end")
        assertTrue(nextPageFailed(loadState), "the next page did not fail, so this proves nothing")

        assertEquals(
            PAGING_INITIAL_SIZE,
            timelineRowCount(
                itemCount = presenter.size,
                placeholdersBefore = snapshot.placeholdersBefore,
                placeholdersAfter = snapshot.placeholdersAfter,
                nextPageFailed = nextPageFailed(loadState),
                previousPageFailed = previousPageFailed(loadState)
            ),
            "the retry is not the row after the last article the reader can see"
        )

        collection.cancelAndJoin()
    }

    /**
     * The same against the real machinery at the other end. The item screen
     * opens the list on the article the reader tapped, so the pages it holds
     * start in the middle of the query; scrolling up from there is a prepend,
     * and this is one that fails.
     */
    @Test
    fun `the retry precedes the first loaded article of a real paged list`() = runBlocking {
        val presenter = TimelinePresenter()
        val pager = Pager(
            config = PagingConfig(
                initialLoadSize = PAGING_INITIAL_SIZE,
                pageSize = PAGING_PAGE_SIZE,
                prefetchDistance = PAGING_PREFETCH_DISTANCE
            ),
            initialKey = OPENED_AT,
            pagingSourceFactory = { NothingAbove(total = 1000, openedAt = OPENED_AT) }
        )

        val presented = Channel<Unit>(Channel.CONFLATED)
        presenter.addOnPagesUpdatedListener { presented.trySend(Unit) }

        val collection = launch(Dispatchers.Default) {
            pager.flow.collectLatest { presenter.collectFrom(it) }
        }
        withTimeout(TIMEOUT_MS) { presented.receive() }

        // the reader reaches the first article that loaded, which is what asks
        // for the page above it
        presenter.get(OPENED_AT)
        withTimeout(TIMEOUT_MS) {
            while (presenter.loadStateFlow.value?.prepend !is LoadState.Error) delay(POLL_MS)
        }

        val loadState = requireNotNull(presenter.loadStateFlow.value)
        val snapshot = presenter.snapshot()

        assertEquals(1000, presenter.size, "the list does not count the articles not loaded")
        assertEquals(
            OPENED_AT,
            snapshot.placeholdersBefore,
            "the articles not loaded are not before the loaded ones"
        )
        assertTrue(
            previousPageFailed(loadState),
            "the page above did not fail, so this proves nothing"
        )

        assertEquals(
            OPENED_AT,
            timelineFirstRow(snapshot.placeholdersBefore, previousPageFailed(loadState)),
            "the list does not start at the first article the reader can see"
        )

        collection.cancelAndJoin()
    }

    /** The two ends the tests above vary, with the settled case as the default. */
    private fun rowCount(
        itemCount: Int,
        placeholdersBefore: Int = 0,
        placeholdersAfter: Int,
        nextPageFailed: Boolean = false,
        previousPageFailed: Boolean = false
    ) = timelineRowCount(
        itemCount = itemCount,
        placeholdersBefore = placeholdersBefore,
        placeholdersAfter = placeholdersAfter,
        nextPageFailed = nextPageFailed,
        previousPageFailed = previousPageFailed
    )

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 10L

        /** Far enough down the query that the pages above it are worth a retry. */
        const val OPENED_AT = 400
    }
}

/** A presenter that keeps the pages and reports nothing, which is all this needs. */
private class TimelinePresenter :
    PagingDataPresenter<Int>(mainContext = EmptyCoroutineContext) {

    override suspend fun presentPagingDataEvent(event: PagingDataEvent<Int>) = Unit
}

/**
 * A list of [total] articles that answers the first page and then fails, the
 * way the database does when the query it is given stops working — and the way
 * a paged list behaves for the reader when the next page cannot be had.
 */
private class FirstPageOnly(private val total: Int) : PagingSource<Int, Int>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Int> {
        val start = params.key ?: return firstPage(params.loadSize)

        return LoadResult.Error(IllegalStateException("no page at $start"))
    }

    private fun firstPage(size: Int): LoadResult<Int, Int> {
        val end = minOf(size, total)

        return LoadResult.Page(
            data = (0 until end).toList(),
            prevKey = null,
            nextKey = end,
            itemsBefore = 0,
            itemsAfter = total - end
        )
    }

    override fun getRefreshKey(state: PagingState<Int, Int>): Int? = null
}

/**
 * A list of [total] articles opened at [openedAt] — the way the item screen
 * opens one on the article the reader tapped — whose pages downwards arrive and
 * whose page upwards fails.
 */
private class NothingAbove(private val total: Int, private val openedAt: Int) :
    PagingSource<Int, Int>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Int> = when (params) {
        is LoadParams.Prepend -> LoadResult.Error(IllegalStateException("no page above"))
        else -> page(params.key ?: openedAt, params.loadSize)
    }

    private fun page(start: Int, size: Int): LoadResult<Int, Int> {
        val end = minOf(start + size, total)

        return LoadResult.Page(
            data = (start until end).toList(),
            prevKey = if (start > 0) start - 1 else null,
            nextKey = if (end < total) end else null,
            itemsBefore = start,
            itemsAfter = total - end
        )
    }

    override fun getRefreshKey(state: PagingState<Int, Int>): Int? = null
}
