package app.lenews.repositories

import androidx.room.withTransaction
import app.lenews.api.services.DataSourceResult
import app.lenews.api.services.greader.ArticleStateChange
import app.lenews.api.services.greader.GReaderDataSource
import app.lenews.api.services.greader.GReaderSyncData
import app.lenews.util.Utils
import app.lenews.db.Database
import app.lenews.db.deleteWhatRetentionDrops
import app.lenews.db.entities.Feed
import app.lenews.db.entities.Folder
import app.lenews.db.entities.Item
import app.lenews.db.entities.account.Account
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.parameter.parametersOf

open class GReaderRepository(
    database: Database,
    account: Account,
    private val dataSource: GReaderDataSource,
) : BaseRepository(database, account), KoinComponent {

    /**
     * Deliberately not [dataSource]: the login needs one data source before the
     * token is known and another after it, which is what [logIn] does. This one
     * was built with whichever client was in place when the repository was
     * resolved, and that is the wrong client for both halves.
     */
    override suspend fun login(account: Account) = logIn(
        account = account,
        httpClients = get(),
        dataSourceFor = { credentials -> get { parametersOf(credentials) } }
    )

    /**
     * One sync, as `docs/article-store.md` §3 describes it: take the clock, push
     * what the phone decided, pull everything, then write it all in one
     * transaction that holds no network call.
     *
     * A failure anywhere before the transaction leaves the store exactly as the
     * previous sync left it; a failure inside it rolls back the articles, the
     * state and the cursor together. Either way the next sync repeats the same
     * pull with the same cursor, which the upsert makes harmless.
     */
    override suspend fun synchronize(): SyncResult {
        // Step 0: the clock. The same instant stamps every read learned at this
        // sync and becomes the cursor if it succeeds.
        val syncStart = System.currentTimeMillis()
        val writeToken = account.writeToken!!

        // Step 1: the snapshot of what the server has not been told
        val queued = database.pendingChangeDao().selectAll()
        val pendingChanges = GReaderSyncData(
            readIds = queued.filter { it.read == true }.map { it.articleId },
            unreadIds = queued.filter { it.read == false }.map { it.articleId },
            starredIds = queued.filter { it.starred == true }.map { it.articleId },
            unstarredIds = queued.filter { it.starred == false }.map { it.articleId }
        )

        // Steps 1 and 2: upload, then pull. Each batch the server took is
        // cleared as it is taken, so a batch that fails leaves the rest queued.
        val pulled = dataSource
            .synchronize(account.cursor, pendingChanges, writeToken) { change, ids ->
                clearUploaded(change, ids)
            }

        // Step 2, last: the starred articles the store has no content for
        val fetched = pulled.items + dataSource.getItemsContents(
            starredIdsTheStoreLacks(pulled),
            writeToken
        )

        // Step 3: still nothing written.
        // Step 4: one transaction, no network call inside it.
        var newArticles: List<Item> = emptyList()
        var newFeeds: List<Feed> = emptyList()
        val newCursor = syncStart / MILLISECONDS_IN_A_SECOND

        database.withTransaction {
            insertFolders(pulled.folders)                       // 4a
            newFeeds = insertFeeds(pulled.feeds)                // 4a
            newArticles = insertItems(fetched)                  // 4b

            val serverIds = pulled.serverIds.toHashSet()
            applyReadState(pulled, serverIds, syncStart)        // 4c
            applyStarredState(pulled, serverIds)                // 4d

            // 4e: the mirror and the horizon, against the ids the server still
            // holds and this sync's own clock. Here and nowhere else, so that a
            // failed sync deletes nothing.
            database.deleteWhatRetentionDrops(serverIds, syncStart)

            afterTheStoreIsWritten()

            database.accountDao().updateCursor(newCursor)       // 4f
            database.optimize()                                 // 4g
        }

        account.cursor = newCursor

        return SyncResult(
            items = newArticles,
            feeds = newFeeds
        )
    }

    /**
     * Runs inside the sync transaction, after everything it writes and deletes
     * and before the cursor. It does nothing, and exists so that the tests which
     * check that the transaction rolls back as a whole — the articles it stored
     * and the articles retention dropped alike — have somewhere to fail.
     */
    protected open suspend fun afterTheStoreIsWritten() = Unit

    /**
     * Clears the half of each queued row that a batch just uploaded, where the
     * row still holds the value that went up, and drops the rows both halves of
     * which have now been sent.
     */
    private suspend fun clearUploaded(change: ArticleStateChange, ids: List<Long>) {
        val pendingChangeDao = database.pendingChangeDao()

        when (change) {
            ArticleStateChange.READ -> pendingChangeDao.clearUploadedRead(ids, true)
            ArticleStateChange.UNREAD -> pendingChangeDao.clearUploadedRead(ids, false)
            ArticleStateChange.STARRED -> pendingChangeDao.clearUploadedStarred(ids, true)
            ArticleStateChange.UNSTARRED -> pendingChangeDao.clearUploadedStarred(ids, false)
        }

        pendingChangeDao.deleteEmpty()
    }

    /**
     * The starred ids that are neither in the store nor in the content this sync
     * fetched: an article starred on the web that this phone never held, or one
     * an earlier retention dropped. Without their content, *starred articles
     * survive both rules* could not be honoured for them.
     */
    private suspend fun starredIdsTheStoreLacks(pulled: DataSourceResult): List<Long> {
        val justFetched = pulled.items.mapTo(hashSetOf()) { it.id }
        val candidates = pulled.starredIds.filterNot { it in justFetched }

        if (candidates.isEmpty()) {
            return emptyList()
        }

        val held = candidates.chunked(MAX_IDS_PER_STATEMENT)
            .flatMap { database.itemDao().selectHeldIds(it) }
            .toHashSet()

        return candidates.filterNot { it in held }
    }

    override suspend fun insertNewFeeds(
        newFeeds: List<Feed>,
        onUpdate: (Feed) -> Unit
    ): ErrorResult {
        val errors = hashMapOf<Feed, Exception>()

        for (newFeed in newFeeds) {
            onUpdate(newFeed)

            try {
                dataSource.createFeed(account.writeToken!!, newFeed.url!!, newFeed.remoteFolderId)
            } catch (e: Exception) {
                errors[newFeed] = e
            }
        }

        return errors
    }

    override suspend fun updateFeed(feed: Feed) {
        dataSource.updateFeed(account.writeToken!!, feed.url!!, feed.name!!, feed.remoteFolderId!!)
        super.updateFeed(feed)
    }

    override suspend fun deleteFeed(feed: Feed) {
        dataSource.deleteFeed(account.writeToken!!, feed.url!!)
        super.deleteFeed(feed)
    }

    override suspend fun updateFolder(folder: Folder) {
        dataSource.updateFolder(account.writeToken!!, folder.remoteId!!, folder.name!!)
        folder.remoteId = GReaderDataSource.FOLDER_PREFIX + folder.name

        super.updateFolder(folder)
    }

    override suspend fun deleteFolder(folder: Folder) {
        dataSource.deleteFolder(account.writeToken!!, folder.remoteId!!)
        super.deleteFolder(folder)
    }

    private suspend fun insertFeeds(feeds: List<Feed>): List<Feed> =
        database.feedDao().upsertFeeds(feeds)

    private suspend fun insertFolders(folders: List<Folder>) {
        database.folderDao().upsertFolders(folders)
    }

    /**
     * Stores what the content calls brought back. An id the store already holds
     * has its content overwritten and its state left alone, so a re-delivery —
     * which FreshRSS does on every sync — is an update and never a second row.
     *
     * @return the articles that were new to the store, which are what the new
     * articles notification reports.
     */
    private suspend fun insertItems(items: List<Item>): List<Item> {
        // The last occurrence of an id in the response wins (§2), so the
        // duplicates go here, in the order the server sent them, and not after
        // the sort below: an article whose publication date the server corrected
        // backwards would otherwise have its stale occurrence sorted last, and
        // the stale content would be the one stored.
        val lastOfEachId = LinkedHashMap<Long, Item>(items.size)
        items.forEach { lastOfEachId[it.id] = it }
        val unique = lastOfEachId.values.toList()

        val feedIdsByRemoteId = mutableMapOf<String?, Int>()

        for (item in unique) {
            item.feedId = feedIdsByRemoteId.getOrPut(item.feedRemoteId) {
                database.feedDao().selectRemoteFeedLocalId(item.feedRemoteId!!)
            }

            if (item.text != null) {
                item.readTime = Utils.readTimeFromString(item.text!!)
            }
        }

        return database.itemDao().upsertArticles(unique.sortedWith(Item::compareTo))
    }

    /**
     * Step 4c: read state, from the id lists, which are its only source.
     *
     * An article the unread list names is unread; an article the server still
     * holds and does not call unread is read, stamped with this sync's clock,
     * which is the only date the server allows since no API output says when an
     * article became read.
     *
     * Both directions are about the articles the server's full id list still
     * names, and nothing else. The full list and the unread list come from two
     * separate calls and can disagree — the server can drop an article between
     * them — so the unread list is cut down to what the full list holds before
     * anything is written. Without that, an article read on the phone and
     * dropped by the server would be put back to unread and lose the date on
     * which it became read, which nothing could give back.
     *
     * Only the articles whose state actually differs are named in a statement.
     * The candidates for becoming read are the articles this store holds as
     * unread, never the server's whole read list, which on a full account is
     * tens of thousands of ids: the set difference is done here, in one pass
     * over a hash set, and the statements that follow are short.
     */
    private suspend fun applyReadState(
        pulled: DataSourceResult,
        serverIds: HashSet<Long>,
        syncStart: Long
    ) {
        val itemDao = database.itemDao()
        val stillUnreadOnTheServer = pulled.unreadIds.filter { it in serverIds }

        stillUnreadOnTheServer
            .chunked(MAX_IDS_PER_STATEMENT)
            .forEach { itemDao.markUnreadFromSync(it) }

        val unreadIds = stillUnreadOnTheServer.toHashSet()
        val becameRead = itemDao.selectUnreadIds()
            .filter { it in serverIds && it !in unreadIds }

        becameRead.chunked(MAX_IDS_PER_STATEMENT).forEach { itemDao.markReadFromSync(it, syncStart) }
    }

    /**
     * Step 4d: starred state. An article this store holds and the starred list
     * names is starred; an article the server still holds and no longer calls
     * starred is unstarred. An article in neither list is left alone — the
     * server has nothing to say about it, and retention decides whether it
     * stays.
     *
     * The two directions are not symmetrical, and the model says so: starring
     * asks only that the store hold the article, which the statement itself
     * enforces since it can only update rows that are there, while unstarring
     * asks in addition that the full id list still name it — the server saying
     * nothing about an article is not the server saying it is no longer
     * starred.
     */
    private suspend fun applyStarredState(pulled: DataSourceResult, serverIds: HashSet<Long>) {
        val itemDao = database.itemDao()

        pulled.starredIds.chunked(MAX_IDS_PER_STATEMENT).forEach { itemDao.starFromSync(it) }

        val starredOnTheServer = pulled.starredIds.toHashSet()
        val noLongerStarred = itemDao.selectStarredIds()
            .filter { it in serverIds && it !in starredOnTheServer }

        noLongerStarred.chunked(MAX_IDS_PER_STATEMENT).forEach { itemDao.unstarFromSync(it) }
    }

    private companion object {
        /**
         * How many ids one statement names. Well under what SQLite will bind,
         * and the lists a sync works from can hold tens of thousands.
         */
        const val MAX_IDS_PER_STATEMENT = 900

        const val MILLISECONDS_IN_A_SECOND = 1000L
    }
}
