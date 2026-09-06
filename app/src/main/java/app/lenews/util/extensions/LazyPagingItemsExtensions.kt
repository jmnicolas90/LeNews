package app.lenews.util.extensions

import androidx.paging.compose.LazyPagingItems
import app.lenews.util.paging.PagedListState
import app.lenews.util.paging.nextPageFailed as nextPageFailedIn
import app.lenews.util.paging.pagedListState

/**
 * What this list has to put on screen. The decision itself is in
 * [pagedListState], where it can be tested without Compose.
 */
fun <T : Any> LazyPagingItems<T>.listState(): PagedListState =
    pagedListState(loadState, itemCount)

/** Whether the next page failed to load, the articles already there being fine. */
fun <T : Any> LazyPagingItems<T>.nextPageFailed(): Boolean = nextPageFailedIn(loadState)

fun <T : Any> LazyPagingItems<T>.isLoading(): Boolean =
    listState() == PagedListState.Loading

fun <T : Any> LazyPagingItems<T>.isNotEmpty(): Boolean {
    return itemCount > 0
}
