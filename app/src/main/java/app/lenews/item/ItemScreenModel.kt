package app.lenews.item

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import androidx.compose.runtime.Stable
import androidx.core.content.FileProvider
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.cachedIn
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import app.lenews.R
import app.lenews.repositories.BaseRepository
import app.lenews.util.ApplicationScope
import app.lenews.util.PAGING_PAGE_SIZE
import app.lenews.util.PAGING_PREFETCH_DISTANCE
import app.lenews.util.Preferences
import app.lenews.util.Utils
import app.lenews.db.Database
import app.lenews.db.entities.Item
import app.lenews.db.filters.QueryFilters
import app.lenews.db.pojo.ItemWithFeed
import app.lenews.db.queries.ItemSelectionQueryBuilder
import app.lenews.db.queries.ItemsQueryBuilder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.parameter.parametersOf
import java.io.File
import java.net.URI

class ItemScreenModel(
    private val itemId: Long,
    private val itemIndex: Int,
    private val queryFilters: QueryFilters,
    private val database: Database,
    private val preferences: Preferences,
    private val applicationScope: ApplicationScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : StateScreenModel<ItemState>(ItemState()), KoinComponent {

    /**
     * The repository the account gives, once the account has arrived — it is
     * read from the database, so it is not there when the screen opens.
     *
     * A flow rather than a `lateinit var`: a decision made before the account
     * has arrived waits for it instead of throwing, and a decision made after
     * the screen is gone still finds it.
     */
    private val repository = MutableStateFlow<BaseRepository?>(null)

    private val useCustomShareIntentTpl = preferences.useCustomShareIntentTpl.flow.stateIn(
        screenModelScope, SharingStarted.Eagerly, false
    )
    private val customShareIntentTpl = preferences.customShareIntentTpl.flow.stateIn(
        screenModelScope, SharingStarted.Eagerly, ""
    )

    /**
     * The articles the reader has acted on while this screen has been open,
     * starting with the one it was opened on.
     *
     * A read or a star is written to the store the moment it is made, which is
     * what `docs/article-store.md` §5 asks for and what keeps a background sync
     * from dropping an article the reader has just starred. The cost is that an
     * article read while the unread timeline is behind this screen stops
     * matching the query that built the list, and the list under the reader's
     * finger would shift by one. So the ids stay in the query until the screen
     * is left: the store is never stale, and the list never jumps.
     *
     * [itemId] is in the set from the start for the same reason, one process
     * later: the article is marked read as soon as it is opened, so if the
     * process is killed and the screen recreated, the unread list it was opened
     * from no longer holds it. Asking for it by id is what puts the reader back
     * on the article they were reading instead of on whatever has taken its
     * place.
     */
    private val keptArticleIds = MutableStateFlow(setOf(itemId))

    /** The ids above, for the tests of this screen. */
    internal val keptArticles: Set<Long>
        get() = keptArticleIds.value

    /**
     * The id of the article the pager last told this model about.
     *
     * The pager reports the page the reader is on, and the reader changing page
     * is not the only thing that changes it: when the open article stops
     * matching the query — marked unread in the history, for one — the list is
     * built again in a different order and the pager follows the article's key
     * to its new index. That is the same article, not a page the reader has
     * turned to, and it must not count as a visit.
     */
    private var lastPagedArticleId: Long? = null

    private val _itemState: MutableStateFlow<PagingData<ItemWithFeed>> =
        MutableStateFlow(
            PagingData.empty(
                sourceLoadStates = LoadStates(
                    refresh = LoadState.Loading,
                    prepend = LoadState.Loading,
                    append = LoadState.Loading
                )
            )
        )

    // based type is Flow because with StateFlow when coming back from process death, pager doesn't resume
    // it might be due to stateIn() overlapping cachedIn(), but not sure
    var itemState: Flow<PagingData<ItemWithFeed>> = _itemState.asStateFlow()

    init {
        screenModelScope.launch(dispatcher) {
            database.accountDao().selectAccount()
                .collect { storedAccount ->
                    val account = storedAccount ?: return@collect

                    repository.value = get<BaseRepository> { parametersOf(account) }

                    if (itemIndex > -1) {
                        itemState = buildPager()
                    } else {
                        val query = ItemSelectionQueryBuilder.buildQuery(itemId)

                        database.itemDao().selectItemById(query)
                            .collect { itemWithFeed ->
                                _itemState.update { PagingData.from(listOf(itemWithFeed)) }
                            }
                    }
                }
        }

        screenModelScope.launch(dispatcher) {
            combine(
                preferences.openLinksWith.flow,
                preferences.theme.flow
            ) { openLinksWith, theme ->
                openLinksWith to theme
            }.collect { (openLinksWith, theme) ->
                mutableState.update {
                    it.copy(
                        openInExternalBrowser = when (openLinksWith) {
                            "external_navigator" -> true
                            else -> false
                        },
                        theme = theme
                    )
                }
            }
        }
    }

    private fun createPagingSource(): PagingSource<Int, ItemWithFeed> {
        val query = ItemsQueryBuilder.buildItemsQuery(queryFilters, keptArticleIds.value)

        return database.itemDao().selectAll(query)
    }

    private fun buildPager(): Flow<PagingData<ItemWithFeed>> {
        val pageNb = (((itemIndex + PAGING_PAGE_SIZE - 1) / PAGING_PAGE_SIZE) + 1)
            .coerceAtLeast(1)

        return Pager(
            config = PagingConfig(
                initialLoadSize = PAGING_PAGE_SIZE * pageNb,
                pageSize = PAGING_PAGE_SIZE,
                prefetchDistance = PAGING_PREFETCH_DISTANCE
            ),
            pagingSourceFactory = { createPagingSource() }
        )
            .flow
            .cachedIn(screenModelScope)
    }

    /**
     * The article the reader has just swiped to becomes read, once. It is not a
     * toggle: the page can be entered again, and an article that is already read
     * has nothing to become.
     *
     * The page can also be *renumbered* rather than entered, when the list is
     * built again around the article the reader is on. The article the pager
     * names is then the one it named last time, and nothing has been visited:
     * marking it read there would undo the reader's own decision to mark it
     * unread, and stamp it with a date they never asked for.
     */
    fun setItemRead(itemWithFeed: ItemWithFeed) {
        val item = itemWithFeed.item
        val sameArticleAsLastTime = item.id == lastPagedArticleId
        lastPagedArticleId = item.id

        if (sameArticleAsLastTime || itemWithFeed.isRead) {
            return
        }

        keepShowing(item.id)
        write { repository -> repository.setItemReadState(item.apply { isRead = true }) }
    }

    /** The read toggle of the bottom bar. */
    fun setItemReadState(item: Item) {
        keepShowing(item.id)
        write { repository -> repository.setItemReadState(item.apply { isRead = !isRead }) }
    }

    /** The star toggle of the bottom bar. */
    fun setItemStarState(item: Item) {
        keepShowing(item.id)
        write { repository -> repository.setItemStarState(item.apply { isStarred = !isStarred }) }
    }

    /**
     * Writes a decision the reader has made.
     *
     * On the application's scope, not on the screen's: the screen can be gone
     * before the write reaches the store — Room's transaction executor is busy
     * for as long as a sync holds it — and a decision the reader has made and
     * seen is not the screen's to cancel. It waits for the account rather than
     * assuming it has arrived, and the scope logs whatever throws.
     */
    private fun write(decision: suspend (BaseRepository) -> Unit) {
        applicationScope.launch(dispatcher) {
            decision(repository.filterNotNull().first())
        }
    }

    /**
     * Keeps an article in the list even once it no longer matches the filter the
     * list was built with. Recorded before the write, so that the reload the
     * write sets off already reads it.
     */
    private fun keepShowing(itemId: Long) {
        keptArticleIds.update { it + itemId }
    }

    fun openImageDialog(url: String) = mutableState.update { it.copy(imageDialogUrl = url) }

    fun closeImageDialog() = mutableState.update { it.copy(imageDialogUrl = null) }

    fun downloadImage(url: String, context: Context) {
        screenModelScope.launch(dispatcher) {
            val bitmap = getImage(url, context)

            if (bitmap == null) {
                mutableState.update { it.copy(error = context.getString(R.string.error_image_download)) }
                return@launch
            }

            val target = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                url.substringAfterLast('/')
            ).apply {
                outputStream().apply {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 90, this)
                    flush()
                    close()
                }
            }

            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)
            mutableState.update { it.copy(fileDownloadedEvent = true) }
        }
    }

    fun shareImage(url: String, context: Context) {
        screenModelScope.launch(dispatcher) {
            val bitmap = getImage(url, context)
            if (bitmap == null) {
                mutableState.update { it.copy(error = context.getString(R.string.error_image_download)) }
                return@launch
            }

            val uri = saveImageInCache(bitmap, url, context)

            Intent().apply {
                action = Intent.ACTION_SEND

                clipData = ClipData.newRawUri(null, uri)
                putExtra(Intent.EXTRA_STREAM, uri)

                type = "image/*"
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }.also {
                context.startActivity(Intent.createChooser(it, null))
            }
        }
    }

    private suspend fun getImage(url: String, context: Context): Bitmap? {
        val downloader = context.imageLoader

        val image = downloader.execute(
            ImageRequest.Builder(context)
                .data(url)
                .allowHardware(false)
                .build()
        ).image

        return image?.toBitmap()
    }

    private fun saveImageInCache(bitmap: Bitmap, url: String, context: Context): Uri {
        val imagesFolder = File(context.cacheDir.absolutePath, "images")
        if (!imagesFolder.exists()) imagesFolder.mkdirs()

        val name = URI.create(url).path.substringAfterLast('/')
        val image = File(imagesFolder, name).apply {
            outputStream().apply {
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, this)
                flush()
                close()
            }
        }

        return FileProvider.getUriForFile(context, context.packageName, image)
    }

    fun shareItem(itemWithFeed: ItemWithFeed, context: Context) = Utils.shareItem(
        itemWithFeed, context, useCustomShareIntentTpl.value, customShareIntentTpl.value
    )
}

@Stable
data class ItemState(
    val imageDialogUrl: String? = null,
    val fileDownloadedEvent: Boolean = false,
    val openInExternalBrowser: Boolean = false,
    val theme: String? = "",
    val error: String? = null
)
