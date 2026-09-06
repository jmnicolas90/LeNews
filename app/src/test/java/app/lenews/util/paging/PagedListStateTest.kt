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

import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import androidx.paging.LoadStates
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PagedListStateTest {

    @Test
    fun `the first page still loading is a loading list`() {
        assertEquals(
            PagedListState.Loading,
            pagedListState(loadStates(refresh = LoadState.Loading), itemCount = 0)
        )
    }

    @Test
    fun `a refresh that failed is an error, not an empty list`() {
        assertEquals(
            PagedListState.Error,
            pagedListState(loadStates(refresh = failed()), itemCount = 0)
        )
    }

    @Test
    fun `a refresh that failed is an error even with a stale list on screen`() {
        assertEquals(
            PagedListState.Error,
            pagedListState(loadStates(refresh = failed()), itemCount = 20)
        )
    }

    @Test
    fun `an append that failed keeps the articles it already has`() {
        val states = loadStates(append = failed())

        assertEquals(PagedListState.Content, pagedListState(states, itemCount = 20))
        assertTrue(nextPageFailed(states))
    }

    @Test
    fun `an append that failed with nothing on screen is an error`() {
        assertEquals(
            PagedListState.Error,
            pagedListState(loadStates(append = failed()), itemCount = 0)
        )
    }

    @Test
    fun `a prepend that failed with nothing on screen is an error`() {
        assertEquals(
            PagedListState.Error,
            pagedListState(loadStates(prepend = failed()), itemCount = 0)
        )
    }

    @Test
    fun `a settled list with articles is content`() {
        val states = loadStates()

        assertEquals(PagedListState.Content, pagedListState(states, itemCount = 20))
        assertFalse(nextPageFailed(states))
    }

    @Test
    fun `a settled list with no articles and no error is empty`() {
        assertEquals(PagedListState.Empty, pagedListState(loadStates(), itemCount = 0))
    }

    @Test
    fun `a next page loading under the articles already there is content`() {
        assertEquals(
            PagedListState.Content,
            pagedListState(loadStates(append = LoadState.Loading), itemCount = 20)
        )
    }

    @Test
    fun `a refresh loading over the articles already there is content`() {
        assertEquals(
            PagedListState.Content,
            pagedListState(loadStates(refresh = LoadState.Loading), itemCount = 20)
        )
    }

    @Test
    fun `a prepend that failed keeps the articles it already has`() {
        val states = loadStates(prepend = failed())

        assertEquals(PagedListState.Content, pagedListState(states, itemCount = 20))
        assertTrue(previousPageFailed(states))
        assertFalse(nextPageFailed(states), "a failed prepend was read as a failed append")
    }

    @Test
    fun `an append that failed is not a prepend that failed`() {
        assertFalse(previousPageFailed(loadStates(append = failed())))
    }

    //region one page of the reader's pager

    @Test
    fun `a page whose article is loaded shows the article`() {
        assertEquals(
            ArticlePageState.Article,
            articlePageState(
                articleIsLoaded = true,
                append = LoadState.NotLoading(endOfPaginationReached = false),
                prepend = LoadState.NotLoading(endOfPaginationReached = false)
            )
        )
    }

    /** A failure elsewhere in the list is no reason to hide an article. */
    @Test
    fun `a page whose article is loaded shows it even when a page failed`() {
        assertEquals(
            ArticlePageState.Article,
            articlePageState(articleIsLoaded = true, append = failed(), prepend = failed())
        )
    }

    @Test
    fun `a page still being loaded waits`() {
        assertEquals(
            ArticlePageState.Loading,
            articlePageState(
                articleIsLoaded = false,
                append = LoadState.Loading,
                prepend = LoadState.NotLoading(endOfPaginationReached = true)
            )
        )
    }

    /**
     * The one the reader could not get out of: the pager counts every matching
     * article, so a failed append leaves pages the reader can swipe to that
     * nothing is going to fill. Blank, with no message and no retry.
     */
    @Test
    fun `a page the next load failed to bring says so`() {
        assertEquals(
            ArticlePageState.Failed,
            articlePageState(
                articleIsLoaded = false,
                append = failed(),
                prepend = LoadState.NotLoading(endOfPaginationReached = true)
            )
        )
    }

    /** The same at the other end, which the item screen reaches by opening in the middle. */
    @Test
    fun `a page the previous load failed to bring says so`() {
        assertEquals(
            ArticlePageState.Failed,
            articlePageState(
                articleIsLoaded = false,
                append = LoadState.NotLoading(endOfPaginationReached = true),
                prepend = failed()
            )
        )
    }

    //endregion

    private fun failed() = LoadState.Error(RuntimeException("no"))

    private fun loadStates(
        refresh: LoadState = LoadState.NotLoading(endOfPaginationReached = false),
        prepend: LoadState = LoadState.NotLoading(endOfPaginationReached = true),
        append: LoadState = LoadState.NotLoading(endOfPaginationReached = true)
    ) = CombinedLoadStates(
        refresh = refresh,
        prepend = prepend,
        append = append,
        source = LoadStates(refresh = refresh, prepend = prepend, append = append)
    )
}
