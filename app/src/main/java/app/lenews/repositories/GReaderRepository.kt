package app.lenews.repositories

import androidx.room.withTransaction
import app.lenews.api.PLAIN_CLIENT
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
import org.koin.core.qualifier.named

open class GReaderRepository(
    database: Database,
    account: Account,
    private val dataSource: GReaderDataSource,
) : BaseRepository(database, account), KoinComponent {

    /**
     * Deliberately not [dataSource]: the login needs one data source on the
     * plain client before the token is known and another on the authenticated
     * client after it, which is what [logIn] does. This one was built with
     * whichever client was in place when the repository was resolved, and that
     * is the wrong client for both halves.
     */
    override suspend fun login(account: Account) = logIn(
        account = account,
        httpClients = get(),
        dataSourceOnThePlainClient = { credentials ->
            get(named(PLAIN_CLIENT)) { parametersOf(credentials) }
        },
        dataSourceOnTheAuthenticatedClient = { credentials -> get { parametersOf(credentials) } }
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

        // Which account this sync speaks for. Read now, checked again inside
        // the transaction: see [refuseToWriteIntoAnotherAccountsStore].
        val accountThisSyncStartedFrom = AccountIdentity.of(account)

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

        // Step 2, last: the content of the articles the store lacks and the
        // server still calls unread or starred
        val fetched = pulled.items + dataSource.getItemsContents(
            idsTheStoreLacks(pulled),
            writeToken
        )

        // The two sets the transaction works from, built before it opens so
        // that it holds no work it does not have to.
        val serverIds = pulled.serverIds.toHashSet()
        val unreadOrStarred = HashSet<Long>(pulled.unreadIds.size + pulled.starredIds.size)
        unreadOrStarred += pulled.unreadIds
        unreadOrStarred += pulled.starredIds

        // Step 3: still nothing written.
        var newArticles: List<Item> = emptyList()
        var newFeeds: List<Feed> = emptyList()
        val newCursor = syncStart / MILLISECONDS_IN_A_SECOND

        beforeTheStoreIsWritten()

        // Step 4: one transaction, no network call inside it.
        database.withTransaction {
            refuseToWriteIntoAnotherAccountsStore(accountThisSyncStartedFrom)

            insertFolders(pulled.folders)                       // 4a
            newFeeds = insertFeeds(pulled.feeds)                // 4a
            newArticles = insertItems(fetched, unreadOrStarred) // 4b

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
     * Runs after every network call and before the transaction opens. It does
     * nothing, and exists so that a test can put the one event this window is
     * about into it: the account being replaced while a sync is running. That
     * window is the only one there is — SQLite takes one writer at a time, so a
     * login that replaces the account either commits before the transaction
     * opens, and this sync must refuse to write, or after it commits, and wipes
     * what this sync wrote, which is what replacing an account means.
     */
    protected open suspend fun beforeTheStoreIsWritten() = Unit

    /**
     * Step 4, first: the account row is read again, inside the transaction, and
     * compared with the account this sync started from.
     *
     * A login that names another server or another user empties the store in one
     * transaction with the account write, so a sync that started before it and
     * commits after it would refill the new account's store with the previous
     * account's articles, upload the previous account's pending ids to it, and
     * leave a cursor that makes the new account's older articles look like
     * content already fetched. None of it belongs to the account now on screen.
     *
     * So the sync gives up instead, and the transaction rolls back whole: the
     * new account keeps its empty store and syncs itself from nothing, which is
     * what it should do.
     *
     * The identity is the server address and the user name the server itself
     * reported at login. Those are the two things a replacement changes and a
     * password change does not, and both are columns of the row, so this needs
     * neither the credentials nor a reading of the preferences.
     */
    private suspend fun refuseToWriteIntoAnotherAccountsStore(
        accountThisSyncStartedFrom: AccountIdentity
    ) {
        val accountNow = AccountIdentity.of(database.accountDao().select())

        if (accountNow != accountThisSyncStartedFrom) {
            throw AccountReplacedDuringSync()
        }
    }

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
     * The starred and unread ids that are neither in the store nor in the
     * content this sync fetched. Their content is asked for by name, which is
     * the only way to get it: `stream/contents` with `ot` sends what the server
     * discovered or last saw change since the cursor, and neither reading nor
     * unreading an article moves either date.
     *
     * **Starred**: an article starred on the web that this phone never held, or
     * one an earlier retention dropped. Without its content, *starred articles
     * survive both rules* could not be honoured for it.
     *
     * **Unread**: the same article seen from the other side. The horizon drops
     * an article read thirty days ago; the reader then marks it unread on the
     * FreshRSS web interface, which changes no date the incremental pull looks
     * at, so the article would be named in the unread list of every sync from
     * then on and its content would never arrive. Step 4c only writes state on
     * rows the store holds, so the article would be missing for good. The same
     * happens to an article the initial sync skipped for being read.
     *
     * An unread id the server's full list does not name is left out: the mirror
     * rule deletes an unread article the server no longer holds, in this very
     * transaction, so fetching its content would be a request for a row that is
     * dropped before the sync ends. A starred id is not filtered that way, on
     * purpose — a starred article is kept whatever the full list says.
     */
    private suspend fun idsTheStoreLacks(pulled: DataSourceResult): List<Long> {
        val justFetched = pulled.items.mapTo(hashSetOf()) { it.id }
        val serverIds = pulled.serverIds.toHashSet()

        val candidates = LinkedHashSet<Long>()
        pulled.starredIds.filterTo(candidates) { it !in justFetched }
        pulled.unreadIds.filterTo(candidates) { it !in justFetched && it in serverIds }

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
    private suspend fun insertItems(items: List<Item>, unreadOrStarred: Set<Long>): List<Item> {
        // The last occurrence of an id in the response wins (§2), so the
        // duplicates go here, in the order the server sent them, and not after
        // the sort below: an article whose publication date the server corrected
        // backwards would otherwise have its stale occurrence sorted last, and
        // the stale content would be the one stored.
        val lastOfEachId = LinkedHashMap<Long, Item>(items.size)
        items.forEach { lastOfEachId[it.id] = it }
        val unique = whatTheHorizonHasNotDropped(lastOfEachId.values.toList(), unreadOrStarred)

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
     * The articles of this response minus the ones the horizon dropped and the
     * server has nothing new to say about.
     *
     * FreshRSS re-delivers an article whose content it saw change however old
     * that article is, so the content of an article deleted for being read more
     * than thirty days ago comes back. Stored as it stands it would be a brand
     * new row: inserted unread, learned read at this sync, stamped with this
     * sync's clock and kept thirty more days, sitting in the history under a
     * date on which nothing happened, and counted as a new article on top of
     * that. Repeating a sync would then not leave the store identical, which is
     * invariant 5.
     *
     * The ledger of ids the horizon dropped is what tells that delivery from a
     * genuinely new article. An id it holds is left out — until the server calls
     * the article unread or starred again, which is the reader asking for it
     * back: then the ledger forgets it and it is stored like any other article,
     * unread and unstamped, and its thirty days start over from the next read.
     */
    private suspend fun whatTheHorizonHasNotDropped(
        articles: List<Item>,
        unreadOrStarred: Set<Long>
    ): List<Item> {
        val ledger = database.horizonDroppedDao()

        val dropped = articles.map { it.id }
            .chunked(MAX_IDS_PER_STATEMENT)
            .flatMap { ledger.droppedAmong(it) }

        if (dropped.isEmpty()) {
            return articles
        }

        val wantedBack = dropped.filter { it in unreadOrStarred }
        wantedBack.chunked(MAX_IDS_PER_STATEMENT).forEach { ledger.forget(it) }

        val stillDropped = dropped.toHashSet() - wantedBack.toHashSet()

        return if (stillDropped.isEmpty()) {
            articles
        } else {
            articles.filterNot { it.id in stillDropped }
        }
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

/**
 * Which FreshRSS account a store belongs to: the server it is on, and the user
 * name that server itself reported when the login asked it.
 *
 * Not the login typed on screen, which is a credential and lives with the
 * password in the encrypted preferences rather than in the account row — and
 * not the account name either, which is a label the reader can change without
 * changing which account it is.
 */
internal data class AccountIdentity(val serverUrl: String?, val userName: String?) {

    companion object {
        /** The identity of [account], or of no account at all when it is null. */
        fun of(account: Account?) = AccountIdentity(account?.url, account?.displayedName)
    }
}

/**
 * A sync that ran while the account was replaced, and gave up rather than
 * writing one account's articles into another account's store.
 */
class AccountReplacedDuringSync : Exception(
    "The account was replaced while this sync was running, so nothing it brought back was stored"
)
