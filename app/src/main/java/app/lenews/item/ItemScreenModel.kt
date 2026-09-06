package app.lenews.item

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
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
import app.lenews.util.PAGING_INITIAL_SIZE
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
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

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

    /**
     * Whether this screen is one article of a list, rather than the single
     * article a notification opened. [itemIndex] is -1 for the second, which is
     * all this model still reads off it: the position it carries is the
     * position the article had in the list the *timeline* was showing, and this
     * screen asks the store for the position it has now.
     */
    private val openedFromAList = itemIndex > -1

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

    /** Tells one image result from the next, including an identical one. */
    private val nextImageResultId = AtomicLong(0)

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

    /** So that an account write does not build the reader's list a second time. */
    private var listWasBuilt = false

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

                    if (openedFromAList) {
                        // the list is built once, on the first account this
                        // reads; nothing about editing the account moves the
                        // article the reader is on
                        if (!listWasBuilt) {
                            listWasBuilt = true
                            openTheListOnTheTappedArticle()
                        }
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

    /**
     * Opens the list on the article the reader tapped, wherever it is now.
     *
     * The position the timeline handed this screen is the position the article
     * had in the list the timeline was showing. By the time this screen builds
     * its own list that can be a different list — a sync that arrived in
     * between, or the process having been killed and the screen recreated —
     * and the article can be anywhere in it. Loading a first page and hoping
     * the article is in it is what let the screen open on a *neighbour* and
     * mark that one read, which is the reader losing an article they never saw.
     *
     * So the store is asked where the article is, under the same conditions and
     * the same order as the list, and the answer is both the page the pager
     * opens on and the row the pager loads around.
     *
     * An article that is not there any more — retention drops articles at every
     * sync — has no position at all, and there is no neighbour worth showing in
     * its place. The screen is told, and it goes back to the timeline.
     */
    private suspend fun openTheListOnTheTappedArticle() {
        val itemDao = database.itemDao()

        if (!itemDao.itemExists(itemId)) {
            mutableState.update { it.copy(articleIsGone = true) }
            return
        }

        val position = itemDao.countArticlesBefore(
            ItemsQueryBuilder.buildItemPositionQuery(queryFilters, itemId, keptArticleIds.value)
        )

        itemState = buildPager(position)
        mutableState.update { it.copy(articlePosition = position) }
    }

    private fun createPagingSource(): PagingSource<Int, ItemWithFeed> {
        val query = ItemsQueryBuilder.buildItemsQuery(queryFilters, keptArticleIds.value)

        return database.itemDao().selectAll(query)
    }

    /**
     * The pager, anchored on [position] so that the first page loaded is the one
     * holding the article the reader tapped rather than the first page of the
     * list. Reading the four hundred articles above it only to reach it would
     * be a query that grows with how far down the reader tapped; the pages
     * before are prepended when they are scrolled to, which is why a failed
     * prepend has a retry of its own.
     */
    private fun buildPager(position: Int): Flow<PagingData<ItemWithFeed>> =
        Pager(
            config = PagingConfig(
                initialLoadSize = PAGING_INITIAL_SIZE,
                pageSize = PAGING_PAGE_SIZE,
                prefetchDistance = PAGING_PREFETCH_DISTANCE
            ),
            initialKey = position,
            pagingSourceFactory = { createPagingSource() }
        )
            .flow
            .cachedIn(screenModelScope)

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

    /**
     * Saves the image the reader long-pressed into the phone's Downloads.
     *
     * An image written into the article itself — a `data:` address, which the
     * article sanitiser allows for images — is saved like any other: the loader
     * decodes it, and the name comes from the type the address declares, since
     * there is no file name in it to take.
     */
    fun downloadImage(url: String, context: Context) {
        screenModelScope.launch(dispatcher) {
            val bitmap = getImage(url, context)

            if (bitmap == null) {
                reportImageFailed(context.getString(R.string.error_image_download))
                return@launch
            }

            // Coil answers with a decoded bitmap and not with the type it was
            // sent, so the name is decided from the address alone.
            val name = downloadedImageName(url, reportedMimeType = null)

            val saved = ImageDownload.saveInDownloads(context, name) { stream ->
                writeImage(bitmap, name.mimeType, stream)
            }

            if (saved == null) {
                reportImageFailed(context.getString(R.string.error_image_save))
            } else {
                reportImageSaved(name.displayName)
            }
        }
    }

    /**
     * The image is in Downloads under [fileName].
     *
     * Internal rather than private because it is the only way into the queue
     * from outside a download, which is what the tests of this screen need: a
     * real success needs a loader, a bitmap and a MediaStore.
     */
    internal fun reportImageSaved(fileName: String) =
        report { ImageResult.Saved(id = it, fileName = fileName) }

    /** The image did not get there, and [message] says why, ready to show. */
    internal fun reportImageFailed(message: String) =
        report { ImageResult.Failed(id = it, message = message) }

    /**
     * Adds a result to the queue the screen shows one snackbar at a time.
     *
     * Every result is its own event with its own id, so a result that arrives
     * while an earlier one is still on screen waits its turn instead of
     * replacing it, and two identical successes are two messages rather than
     * one. The id is taken outside the update because [MutableStateFlow.update]
     * may run its block more than once.
     */
    private fun report(result: (Long) -> ImageResult) {
        val event = result(nextImageResultId.incrementAndGet())

        mutableState.update { it.copy(imageResults = it.imageResults + event) }
    }

    /**
     * The reader has seen the snackbar for the result [id]. Only that one is
     * dropped: whatever arrived while it was showing is still to be shown.
     */
    fun imageResultShown(id: Long) = mutableState.update { state ->
        state.copy(imageResults = state.imageResults.filterNot { it.id == id })
    }

    /**
     * Writes [bitmap] as [mimeType], which is the type the file is being saved
     * under, so the bytes and the extension always agree.
     */
    private fun writeImage(bitmap: Bitmap, mimeType: String, stream: OutputStream) {
        val format = when (mimeType) {
            "image/jpeg" -> Bitmap.CompressFormat.JPEG
            "image/webp" -> Bitmap.CompressFormat.WEBP_LOSSLESS
            else -> Bitmap.CompressFormat.PNG
        }

        if (!bitmap.compress(format, IMAGE_QUALITY, stream)) {
            throw IOException("the image could not be encoded as $mimeType")
        }
    }

    fun shareImage(url: String, context: Context) {
        screenModelScope.launch(dispatcher) {
            val bitmap = getImage(url, context)
            if (bitmap == null) {
                reportImageFailed(context.getString(R.string.error_image_download))
                return@launch
            }

            val uri = try {
                saveImageInCache(bitmap, url, context)
            } catch (error: Exception) {
                Log.w(TAG, "the image could not be prepared for sharing", error)
                reportImageFailed(context.getString(R.string.error_image_save))
                return@launch
            }

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

    /**
     * Writes the image into the app's own cache, where the app it is shared
     * with can read it through the file provider.
     *
     * The name goes through the same sanitiser as a download: the address is
     * whatever the article put in the `src`, and `java.net.URI` refuses some of
     * those outright — a `data:` image has no path at all, which is how sharing
     * an image written into an article used to end in a crash.
     */
    private fun saveImageInCache(bitmap: Bitmap, url: String, context: Context): Uri {
        val imagesFolder = File(context.cacheDir.absolutePath, "images")
        if (!imagesFolder.exists()) imagesFolder.mkdirs()

        val name = downloadedImageName(url, reportedMimeType = null)
        val image = File(imagesFolder, name.displayName)

        image.outputStream().use { writeImage(bitmap, name.mimeType, it) }

        return FileProvider.getUriForFile(context, context.packageName, image)
    }

    fun shareItem(itemWithFeed: ItemWithFeed, context: Context) = Utils.shareItem(
        itemWithFeed, context, useCustomShareIntentTpl.value, customShareIntentTpl.value
    )

    companion object {
        private const val TAG = "ItemScreenModel"

        /** Ignored by PNG, which is what most article images are saved as. */
        private const val IMAGE_QUALITY = 90
    }
}

@Stable
data class ItemState(
    val imageDialogUrl: String? = null,
    /** What has happened to the images the reader asked for, oldest first. */
    val imageResults: List<ImageResult> = emptyList(),
    /**
     * Where in the list the article the screen was opened on is now, counted
     * from zero, or null while that is still being read from the store — and
     * for the single-article screen a notification opens, which has no list.
     */
    val articlePosition: Int? = null,
    /**
     * The store no longer holds the article this screen was opened on, so there
     * is nothing to show and the screen goes back to the list it came from.
     */
    val articleIsGone: Boolean = false,
    val openInExternalBrowser: Boolean = false,
    val theme: String? = ""
)

/**
 * Something that happened to an image the reader asked for.
 *
 * A queue of these replaces the one success flag and the one error message the
 * screen used to hold. Those were latched: a failure arriving while a success
 * was on screen was thrown away when the reader dismissed the success, and two
 * successes in a row were one message. Each result is now its own event, kept
 * until the snackbar that showed *it* is done.
 */
sealed interface ImageResult {

    /** What the screen acknowledges when it has shown this result. */
    val id: Long

    /** The image is in Downloads, under [fileName]. */
    data class Saved(override val id: Long, val fileName: String) : ImageResult

    /** The image did not get there, and [message] says why. */
    data class Failed(override val id: Long, val message: String) : ImageResult
}
