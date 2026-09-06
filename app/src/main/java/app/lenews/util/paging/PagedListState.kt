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

/**
 * What a screen showing a paged list of articles has to put on screen.
 *
 * The decision is here, as a plain function of the load states and the number
 * of articles already loaded, so that it can be tested without a device and
 * without Compose. The screens read it and do nothing else with the load
 * states.
 */
enum class PagedListState {
    /** Nothing loaded yet and the first page is on its way. */
    Loading,

    /** A load failed and there is nothing to show instead. Offer the retry. */
    Error,

    /** The query answered, and it answered with no article at all. */
    Empty,

    /** There are articles to show. */
    Content
}

/**
 * The state of a paged list of articles.
 *
 * A failed refresh is an error and not an empty list: showing "no article" for
 * a query that never ran tells the reader their timeline is empty when it is
 * only unread. It wins over articles still held in memory too — those are the
 * answer to a query that has since been asked again and failed, so the reader
 * is told rather than left with a list that is quietly out of date.
 *
 * An append or a prepend that failed with articles already on screen is *not*
 * an error state: the articles stay and the screen shows the failure under
 * them, which is what [nextPageFailed] is for. With nothing on screen there is
 * nothing to keep, so it is the error state, and the retry covers every load
 * type at once.
 */
fun pagedListState(loadState: CombinedLoadStates, itemCount: Int): PagedListState = when {
    loadState.refresh is LoadState.Error -> PagedListState.Error
    loadState.refresh is LoadState.Loading && itemCount == 0 -> PagedListState.Loading
    itemCount > 0 -> PagedListState.Content
    loadState.append is LoadState.Error || loadState.prepend is LoadState.Error ->
        PagedListState.Error

    else -> PagedListState.Empty
}

/**
 * Whether the next page failed to load, which a screen showing [PagedListState.Content]
 * reports in a footer under the articles it already has.
 *
 * There is no matching header for a failed prepend, on purpose: nothing in this
 * app opens a list in its middle — the timeline starts at the top and the item
 * screen loads whole pages from the first one — so a prepend never runs, let
 * alone fails. Were one to fail, the retry the footer offers would retry it too.
 */
fun nextPageFailed(loadState: CombinedLoadStates): Boolean = loadState.append is LoadState.Error

/**
 * How many rows the timeline shows for [itemCount] matching articles of which
 * [placeholdersAfter] have not been loaded, given whether the next page failed.
 *
 * The timeline pages with placeholders on, so the count it is given is every
 * article the query matches, loaded or not. It draws nothing at all for a row
 * it has not loaded — there is no skeleton article — but the list still spaces
 * every one of them, so an unloaded row is blank height. That is invisible
 * while loading keeps up with scrolling, because a row is only reached moments
 * before it fills.
 *
 * When the next page has failed, nothing is going to fill them: they stay blank
 * for as long as the reader is willing to scroll, and anything the screen puts
 * after the whole count — the retry — ends up under all of it, thousands of
 * empty dp below the last article, where nobody finds it. So the list stops at
 * the last article that did load and the retry is the next row.
 *
 * Placeholders themselves stay on, deliberately. They are what makes a row's
 * position in the list the article's position in the query: the timeline is
 * rebuilt around the article the reader is on whenever the store changes — a
 * sync, or an article marked read on scroll — and the pages it keeps after that
 * start in the middle of the query, not at its first article. That position is
 * what the timeline hands the item screen when the reader taps an article, and
 * what the item screen loads far enough to reach it. Without placeholders the
 * position would be an index into the loaded window instead, and the reader
 * would open an article they did not tap.
 */
fun timelineRowCount(itemCount: Int, placeholdersAfter: Int, nextPageFailed: Boolean): Int =
    if (nextPageFailed) itemCount - placeholdersAfter else itemCount
