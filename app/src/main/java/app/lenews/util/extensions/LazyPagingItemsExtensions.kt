package app.lenews.util.extensions

import androidx.paging.compose.LazyPagingItems
import app.lenews.util.paging.PagedListState
import app.lenews.util.paging.nextPageFailed as nextPageFailedIn
import app.lenews.util.paging.pagedListState
import app.lenews.util.paging.timelineRowCount

/**
 * What this list has to put on screen. The decision itself is in
 * [pagedListState], where it can be tested without Compose.
 */
fun <T : Any> LazyPagingItems<T>.listState(): PagedListState =
    pagedListState(loadState, itemCount)

/** Whether the next page failed to load, the articles already there being fine. */
fun <T : Any> LazyPagingItems<T>.nextPageFailed(): Boolean = nextPageFailedIn(loadState)

/**
 * How many rows this timeline shows: every matching article while pages are
 * still arriving, and only the articles actually loaded once the next page has
 * failed. The reason is in [timelineRowCount].
 */
fun <T : Any> LazyPagingItems<T>.rowCount(): Int = timelineRowCount(
    itemCount = itemCount,
    placeholdersAfter = itemSnapshotList.placeholdersAfter,
    nextPageFailed = nextPageFailed()
)

fun <T : Any> LazyPagingItems<T>.isLoading(): Boolean =
    listState() == PagedListState.Loading

fun <T : Any> LazyPagingItems<T>.isNotEmpty(): Boolean {
    return itemCount > 0
}
