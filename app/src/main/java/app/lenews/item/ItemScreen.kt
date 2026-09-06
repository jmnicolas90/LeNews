package app.lenews.item

import android.content.Context
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import app.lenews.R
import app.lenews.util.components.AndroidScreen
import app.lenews.util.components.CenteredProgressIndicator
import app.lenews.util.components.PagingErrorPlaceholder
import app.lenews.util.extensions.isNotEmpty
import app.lenews.util.extensions.listState
import app.lenews.util.extensions.openInCustomTab
import app.lenews.util.extensions.openUrl
import app.lenews.util.paging.ArticlePageState
import app.lenews.util.paging.PagedListState
import app.lenews.util.paging.articlePageState
import app.lenews.db.filters.QueryFilters
import app.lenews.db.pojo.ItemWithFeed
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.core.parameter.parametersOf

class ItemScreen(
    private val itemId: Long,
    private val itemIndex: Int,
    private val queryFilters: QueryFilters
) : AndroidScreen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow

        val screenModel =
            koinScreenModel<ItemScreenModel>(parameters = { parametersOf(itemId, itemIndex, queryFilters) })
        val state by screenModel.state.collectAsStateWithLifecycle()
        val items = screenModel.itemState.collectAsLazyPagingItems()

        val snackbarHostState = remember { SnackbarHostState() }

        if (state.imageDialogUrl != null) {
            ItemImageDialog(
                onChoice = {
                    if (it == ItemImageChoice.SHARE) {
                        screenModel.shareImage(state.imageDialogUrl!!, context)
                    } else {
                        screenModel.downloadImage(state.imageDialogUrl!!, context)
                    }

                    screenModel.closeImageDialog()
                },
                onDismiss = { screenModel.closeImageDialog() }
            )
        }

        // One snackbar at a time, in the order the results arrived, and each one
        // acknowledged by its own id: a failure that arrives while a success is
        // still showing waits its turn instead of going out with it.
        val imageResult = state.imageResults.firstOrNull()

        LaunchedEffect(imageResult?.id) {
            if (imageResult != null) {
                snackbarHostState.showSnackbar(imageResult.snackbarText(context))
                screenModel.imageResultShown(imageResult.id)
            }
        }

        // Retention drops articles at every sync, so the article this screen was
        // opened on can be gone before the screen has anything to show. There is
        // no neighbour worth opening in its place — it would be marked read on
        // arrival — so the reader goes back to the list they came from.
        LaunchedEffect(state.articleIsGone) {
            if (state.articleIsGone) {
                navigator.pop()
            }
        }

        when (items.listState()) {
            PagedListState.Loading -> {
                CenteredProgressIndicator()
            }

            PagedListState.Error -> {
                PagingErrorPlaceholder(onRetry = { items.retry() })
            }

            else -> {
                // The page the reader's article is on, found by its id, and
                // failing that at the position the model read out of the store
                // — never at the index the timeline passed, which is where the
                // article was in the list the *timeline* was showing.
                val pagerState = rememberPagerState(
                    initialPage = initialPage(
                        loadedArticleIds = items.itemSnapshotList.map { it?.item?.id },
                        itemId = itemId,
                        articlePosition = state.articlePosition ?: itemIndex
                    ),
                    pageCount = { items.itemCount }
                )

                LaunchedEffect(pagerState.currentPage) {
                    snapshotFlow { pagerState.currentPage }
                        .distinctUntilChanged { old, new -> old == new }
                        .collect { pageIndex ->
                            if (items.isNotEmpty()) {
                                items[pageIndex]?.let {
                                    screenModel.setItemRead(it)
                                }
                            }
                        }
                }

                HorizontalPager(
                    state = pagerState,
                    beyondViewportPageCount = 2,
                    key = items.itemKey { it.item.id }
                ) { page ->
                    val itemWithFeed = items[page]

                    when (
                        articlePageState(
                            articleIsLoaded = itemWithFeed != null,
                            append = items.loadState.append,
                            prepend = items.loadState.prepend
                        )
                    ) {
                        ArticlePageState.Article -> LoadedArticlePage(
                            itemWithFeed = checkNotNull(itemWithFeed),
                            state = state,
                            screenModel = screenModel,
                            snackbarHostState = snackbarHostState,
                            onPop = { navigator.pop() }
                        )

                        // the page is one of the articles the query matches and
                        // the pager has not loaded yet; it is on its way
                        ArticlePageState.Loading -> CenteredProgressIndicator()

                        // it is not on its way any more. Without this the reader
                        // swiped onto a blank page with no message and no way
                        // back but leaving the screen.
                        ArticlePageState.Failed -> PagingErrorPlaceholder(
                            onRetry = { items.retry() }
                        )
                    }
                }
            }
        }
    }
}

/** One page of the pager, for an article the pager has actually loaded. */
@Composable
private fun LoadedArticlePage(
    itemWithFeed: ItemWithFeed,
    state: ItemState,
    screenModel: ItemScreenModel,
    snackbarHostState: SnackbarHostState,
    onPop: () -> Unit
) {
    val context = LocalContext.current

    val accentColor = if (itemWithFeed.color != 0) {
        Color(itemWithFeed.color)
    } else {
        MaterialTheme.colorScheme.primary
    }

    val item = itemWithFeed.item

    ItemScreenPage(
        itemWithFeed = itemWithFeed,
        snackbarHostState = snackbarHostState,
        onOpenUrl = { url ->
            if (state.openInExternalBrowser) {
                context.openUrl(url)
            } else {
                context.openInCustomTab(url, state.theme, accentColor)
            }
        },
        onShareItem = { screenModel.shareItem(itemWithFeed, context) },
        onSetReadState = { screenModel.setItemReadState(item) },
        onSetStarState = { screenModel.setItemStarState(item) },
        onOpenImageDialog = { screenModel.openImageDialog(it) },
        onPop = onPop,
    )
}

/** What the reader is told about an image they asked for. */
private fun ImageResult.snackbarText(context: Context): String = when (this) {
    is ImageResult.Saved -> context.getString(R.string.image_saved_in_downloads, fileName)
    is ImageResult.Failed -> message
}
