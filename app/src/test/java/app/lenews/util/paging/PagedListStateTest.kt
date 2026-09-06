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
