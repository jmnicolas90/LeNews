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
 * an error state: the articles stay and the screen shows the failure at the end
 * of the list it happened at, which is what [nextPageFailed] and
 * [previousPageFailed] are for. With nothing on screen there is nothing to
 * keep, so it is the error state, and the retry covers every load type at once.
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
 * Whether the next page failed to load, which a screen showing
 * [PagedListState.Content] reports in a retry row under the articles it already
 * has.
 */
fun nextPageFailed(loadState: CombinedLoadStates): Boolean = loadState.append is LoadState.Error

/**
 * Whether the page *above* the loaded articles failed to load, which the same
 * screen reports in a retry row above them.
 *
 * A prepend does run here, which this file used to say it did not. Room builds
 * the list again whenever the store changes — every sync, and every article
 * marked read on scroll — and it builds it around the row the reader is on, so
 * the pages it keeps start in the middle of the query rather than at its first
 * article. Scrolling back up from there is a prepend. The item screen asks for
 * one on purpose: it opens the list at the article the reader tapped, wherever
 * in the query that is.
 */
fun previousPageFailed(loadState: CombinedLoadStates): Boolean =
    loadState.prepend is LoadState.Error

/** What one page of the item screen's pager has to put on screen. */
enum class ArticlePageState {
    /** The article is loaded; show it. */
    Article,

    /** The page has not loaded yet and is on its way. */
    Loading,

    /** The load this page was waiting for failed. Offer the retry. */
    Failed
}

/**
 * What the item screen shows on the page it has been asked to draw.
 *
 * The pager's page count is every article the query matches, loaded or not, so
 * the reader can swipe onto a page whose article is not there. While the load
 * that would fill it is running that is a moment of nothing, and the reader
 * waits. When it has **failed** nothing is going to fill it, and a page that
 * draws nothing is a blank screen with no message and no way out — the reader's
 * only exit is to leave the screen, which is what the whole retry work of
 * ticket 21 was for and what the reader's own pager was still missing.
 *
 * Either direction counts, and the state is not split by which one the page is
 * on: the retry the screen offers retries every load type at once, so telling
 * an unloaded page above the articles from one below it would change nothing
 * the reader can act on.
 */
fun articlePageState(
    articleIsLoaded: Boolean,
    append: LoadState,
    prepend: LoadState
): ArticlePageState = when {
    articleIsLoaded -> ArticlePageState.Article
    append is LoadState.Error || prepend is LoadState.Error -> ArticlePageState.Failed
    else -> ArticlePageState.Loading
}

/**
 * The first row the timeline draws: the top of the list while pages are still
 * arriving, and the first article that actually loaded once the page above them
 * has failed.
 *
 * The mirror of [timelineRowCount] at the other end, and for the same reason —
 * see there for why blank rows are only cut off after a failure.
 */
fun timelineFirstRow(placeholdersBefore: Int, previousPageFailed: Boolean): Int =
    if (previousPageFailed) placeholdersBefore else 0

/**
 * How many rows the timeline shows for [itemCount] matching articles of which
 * [placeholdersBefore] come before and [placeholdersAfter] after the ones it has
 * loaded, given whether the page below and the page above failed.
 *
 * The timeline pages with placeholders on, so the count it is given is every
 * article the query matches, loaded or not. It draws nothing at all for a row
 * it has not loaded — there is no skeleton article — but the list still spaces
 * every one of them, so an unloaded row is blank height. That is invisible
 * while loading keeps up with scrolling, because a row is only reached moments
 * before it fills.
 *
 * When a page has failed, nothing is going to fill them: they stay blank for as
 * long as the reader is willing to scroll, and anything the screen puts past
 * them — the retry — ends up at the far side of thousands of empty dp, where
 * nobody finds it. So the list stops at the last article that did load and the
 * retry is the next row, and, when it is the page *above* that failed, it starts
 * at the first article that loaded with the retry as the row before it.
 *
 * Placeholders themselves stay on, deliberately. They are what makes a row's
 * position in the list the article's position in the query: the timeline is
 * rebuilt around the article the reader is on whenever the store changes — a
 * sync, or an article marked read on scroll — and the pages it keeps after that
 * start in the middle of the query, not at its first article. That position is
 * what the timeline hands the item screen when the reader taps an article, and
 * what the item screen checks against the store before it opens. Without
 * placeholders the position would be an index into the loaded window instead.
 */
fun timelineRowCount(
    itemCount: Int,
    placeholdersBefore: Int,
    placeholdersAfter: Int,
    nextPageFailed: Boolean,
    previousPageFailed: Boolean
): Int {
    val lastRow = if (nextPageFailed) itemCount - placeholdersAfter else itemCount

    return (lastRow - timelineFirstRow(placeholdersBefore, previousPageFailed))
        .coerceAtLeast(0)
}
