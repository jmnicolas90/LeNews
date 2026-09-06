package app.lenews.util.extensions

import androidx.paging.compose.LazyPagingItems
import app.lenews.util.paging.PagedListState
import app.lenews.util.paging.nextPageFailed as nextPageFailedIn
import app.lenews.util.paging.pagedListState
import app.lenews.util.paging.previousPageFailed as previousPageFailedIn
import app.lenews.util.paging.timelineFirstRow
import app.lenews.util.paging.timelineRowCount

/**
 * What this list has to put on screen. The decision itself is in
 * [pagedListState], where it can be tested without Compose.
 */
fun <T : Any> LazyPagingItems<T>.listState(): PagedListState =
    pagedListState(loadState, itemCount)

/** Whether the next page failed to load, the articles already there being fine. */
fun <T : Any> LazyPagingItems<T>.nextPageFailed(): Boolean = nextPageFailedIn(loadState)

/** Whether the page above the loaded articles failed to load, the same way. */
fun <T : Any> LazyPagingItems<T>.previousPageFailed(): Boolean = previousPageFailedIn(loadState)

/**
 * The index in this list of the first row the timeline draws — 0, unless the
 * page above the loaded articles has failed. The reason is in [timelineFirstRow].
 */
fun <T : Any> LazyPagingItems<T>.firstRow(): Int = timelineFirstRow(
    placeholdersBefore = itemSnapshotList.placeholdersBefore,
    previousPageFailed = previousPageFailed()
)

/**
 * How many rows this timeline shows, starting at [firstRow]: every matching
 * article while pages are still arriving, and only the articles actually loaded
 * at whichever end a page has failed. The reason is in [timelineRowCount].
 */
fun <T : Any> LazyPagingItems<T>.rowCount(): Int = timelineRowCount(
    itemCount = itemCount,
    placeholdersBefore = itemSnapshotList.placeholdersBefore,
    placeholdersAfter = itemSnapshotList.placeholdersAfter,
    nextPageFailed = nextPageFailed(),
    previousPageFailed = previousPageFailed()
)

fun <T : Any> LazyPagingItems<T>.isLoading(): Boolean =
    listState() == PagedListState.Loading

fun <T : Any> LazyPagingItems<T>.isNotEmpty(): Boolean {
    return itemCount > 0
}
