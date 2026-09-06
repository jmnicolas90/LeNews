package app.lenews.api.services.greader

import app.lenews.api.services.DataSourceResult
import app.lenews.api.services.greader.adapters.FreshRSSUserInfo
import app.lenews.db.entities.Item
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.MultipartBody
import java.io.StringReader
import java.util.Properties

class GReaderDataSource(private val service: GReaderService) {

    suspend fun login(login: String, password: String): String {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("Email", login)
            .addFormDataPart("Passwd", password)
            .build()

        val response = service.login(requestBody)

        val properties = Properties()
        properties.load(StringReader(response.string()))

        response.close()
        return properties.getProperty("Auth")
    }

    suspend fun getWriteToken(): String = service.getWriteToken().string()

    suspend fun getUserInfo(): FreshRSSUserInfo = service.userInfo()

    /**
     * Steps 1 and 2 of `docs/article-store.md` §3: the pending changes go up,
     * then everything the store needs comes down. No database is touched here
     * and nothing is written until the caller's transaction.
     *
     * [cursor] is the moment of the last successful sync, in seconds, or
     * [NO_CURSOR] when there has never been one — which is the initial sync of
     * §7: all unread articles and all starred articles, no read article, and
     * the same three id lists as any other sync.
     *
     * A failed upload throws before anything is pulled, so the queue is intact
     * and the store untouched. [onBatchAccepted] runs after each batch the
     * server took, with the ids of that batch, so the caller can clear exactly
     * what was accepted and no more.
     */
    suspend fun synchronize(
        cursor: Long,
        pendingChanges: GReaderSyncData,
        writeToken: String,
        onBatchAccepted: suspend (ArticleStateChange, List<Long>) -> Unit
    ): DataSourceResult = withContext(Dispatchers.IO) {
        uploadPendingChanges(pendingChanges, writeToken, onBatchAccepted)

        DataSourceResult().apply {
            awaitAll(
                async { folders = getFolders().folders },
                async { feeds = getFeeds() },
                async { items = getContent(cursor) },
                async { serverIds = getItemsIds(null, GOOGLE_READING_LIST) },
                async { unreadIds = getItemsIds(GOOGLE_READ, GOOGLE_READING_LIST) },
                async { starredIds = getItemsIds(null, GOOGLE_STARRED) }
            )
        }
    }

    /**
     * Sends the four lists to `edit-tag`, one state a request and at most
     * [MAX_IDS_PER_REQUEST] ids a request.
     *
     * The batch size is the server's: FreshRSS applies the ids of one `edit-tag`
     * call in SQL statements of at most 998 without a wrapping transaction, and
     * truncates a request body at 1 MiB without saying so, answering `OK` in
     * both cases. One batch is therefore one statement the server either took
     * whole or did not take at all.
     */
    suspend fun uploadPendingChanges(
        pendingChanges: GReaderSyncData,
        writeToken: String,
        onBatchAccepted: suspend (ArticleStateChange, List<Long>) -> Unit
    ) {
        upload(ArticleStateChange.READ, pendingChanges.readIds, writeToken, onBatchAccepted)
        upload(ArticleStateChange.UNREAD, pendingChanges.unreadIds, writeToken, onBatchAccepted)
        upload(ArticleStateChange.STARRED, pendingChanges.starredIds, writeToken, onBatchAccepted)
        upload(
            ArticleStateChange.UNSTARRED,
            pendingChanges.unstarredIds,
            writeToken,
            onBatchAccepted
        )
    }

    private suspend fun upload(
        change: ArticleStateChange,
        ids: List<Long>,
        writeToken: String,
        onBatchAccepted: suspend (ArticleStateChange, List<Long>) -> Unit
    ) {
        for (batch in ids.chunked(MAX_IDS_PER_REQUEST)) {
            // every write endpoint takes the decimal form of the article id
            service.setItemsState(
                writeToken,
                change.addTarget,
                change.removeTarget,
                batch.map { ArticleIds.toDecimal(it) }
            )

            onBatchAccepted(change, batch)
        }
    }

    suspend fun getFolders() = service.getFolders()

    suspend fun getFeeds() = service.getFeeds()

    /**
     * The content of §3, step 2, or of §7 when there is no cursor yet.
     *
     * An initial sync asks for the unread articles and the starred articles and
     * for no read article at all; every later sync asks the reading list for
     * what changed since the cursor, read and unread alike, because an article
     * read on the web before the phone ever saw it still belongs in the store
     * and in the history.
     */
    private suspend fun getContent(cursor: Long): List<Item> =
        if (cursor == NO_CURSOR) {
            getItems(excludeTarget = GOOGLE_READ, cursor = null) + getStarredItems()
        } else {
            getItems(excludeTarget = null, cursor = cursor)
        }

    suspend fun getItems(excludeTarget: String?, cursor: Long?): List<Item> =
        everyPage { continuation ->
            val page = service.getItems(excludeTarget, CONTENTS_PAGE_SIZE, cursor, continuation)
            page.items to page.continuation
        }

    suspend fun getStarredItems(): List<Item> = everyPage { continuation ->
        val page = service.getStarredItems(CONTENTS_PAGE_SIZE, continuation)
        page.items to page.continuation
    }

    suspend fun getItemsIds(excludeTarget: String?, includeTarget: String): List<Long> =
        everyPage { continuation ->
            val page = service.getItemsIds(excludeTarget, includeTarget, IDS_PAGE_SIZE, continuation)
            page.ids to page.continuation
        }

    /**
     * The content of named articles, in requests of at most
     * [MAX_IDS_PER_REQUEST] ids for the same reason the uploads are batched.
     */
    suspend fun getItemsContents(ids: List<Long>, writeToken: String): List<Item> =
        ids.chunked(MAX_IDS_PER_REQUEST).flatMap { batch ->
            service.getItemsContents(writeToken, batch.map { ArticleIds.toDecimal(it) }).items
        }

    /**
     * Walks a paged call to its end and returns everything it brought back.
     *
     * FreshRSS sends a continuation only when the page it just sent was full,
     * so an absent one is the end of the walk. The two other stops are guards
     * against a server that never ends it: a page that brought nothing back, and
     * a continuation that repeats the one just used.
     */
    private suspend fun <T> everyPage(
        fetchPage: suspend (continuation: String?) -> Pair<List<T>, String?>
    ): List<T> {
        val everything = mutableListOf<T>()
        var continuation: String? = null

        while (true) {
            val (rows, next) = fetchPage(continuation)
            everything += rows

            if (next.isNullOrBlank() || rows.isEmpty() || next == continuation) {
                return everything
            }

            continuation = next
        }
    }

    suspend fun createFeed(token: String, feedUrl: String, folderId: String?) {
        // no feed here of the folder prefix for the folder id
        service.createOrDeleteFeed(token, FEED_PREFIX + feedUrl, "subscribe", folderId)
    }

    suspend fun deleteFeed(token: String, feedUrl: String) {
        service.createOrDeleteFeed(token, FEED_PREFIX + feedUrl, "unsubscribe", null)
    }

    suspend fun updateFeed(token: String, feedUrl: String, title: String, folderId: String) {
        service.updateFeed(token, FEED_PREFIX + feedUrl, title, folderId, "edit")
    }

    suspend fun createFolder(token: String, tagName: String) {
        service.createFolder(token, "$FOLDER_PREFIX$tagName")
    }

    suspend fun updateFolder(token: String, folderId: String, name: String) {
        service.updateFolder(token, folderId, "$FOLDER_PREFIX$name")
    }

    suspend fun deleteFolder(token: String, folderId: String) {
        service.deleteFolder(token, folderId)
    }

    companion object {

        /** The cursor of an account that has never synchronized. */
        const val NO_CURSOR = 0L

        /**
         * The page sizes the FreshRSS maintainer recommends. There is no
         * server-side cap on either; a page is a page, and the walk ends when
         * the server stops sending a continuation.
         */
        private const val CONTENTS_PAGE_SIZE = 1000
        private const val IDS_PAGE_SIZE = 10000

        /** What FreshRSS applies in one statement, and what it takes in one body. */
        private const val MAX_IDS_PER_REQUEST = 998

        const val GOOGLE_READ = "user/-/state/com.google/read"
        const val GOOGLE_STARRED = "user/-/state/com.google/starred"
        const val GOOGLE_READING_LIST = "user/-/state/com.google/reading-list"

        const val FEED_PREFIX = "feed/"
        const val FOLDER_PREFIX = "user/-/label/"
    }
}
