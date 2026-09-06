package app.lenews.repositories

import app.lenews.api.services.Credentials
import app.lenews.api.services.SyncType
import app.lenews.api.services.greader.GReaderDataSource
import app.lenews.api.services.greader.GReaderSyncData
import app.lenews.api.utils.AuthInterceptor
import app.lenews.util.Utils
import app.lenews.db.Database
import app.lenews.db.entities.Feed
import app.lenews.db.entities.Folder
import app.lenews.db.entities.Item
import app.lenews.db.entities.account.Account
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

class GReaderRepository(
    database: Database,
    account: Account,
    private val dataSource: GReaderDataSource,
) : BaseRepository(database, account), KoinComponent {

    override suspend fun login(account: Account) {
        val authInterceptor = get<AuthInterceptor>().apply {
            credentials = Credentials.toCredentials(account)
        }

        account.token = dataSource.login(account.login!!, account.password!!)
        // we got the authToken, time to provide it to make real calls
        authInterceptor.credentials = Credentials.toCredentials(account)

        account.writeToken = dataSource.getWriteToken()

        val userInfo = dataSource.getUserInfo()
        account.displayedName = userInfo.userName
    }

    override suspend fun synchronize(): SyncResult {
        val pendingChanges = database.pendingChangeDao().selectAll()

        val syncData = GReaderSyncData(
            readIds = pendingChanges.filter { it.read == true }.map { it.articleId },
            unreadIds = pendingChanges.filter { it.read == false }.map { it.articleId },
            starredIds = pendingChanges.filter { it.starred == true }.map { it.articleId },
            unstarredIds = pendingChanges.filter { it.starred == false }.map { it.articleId }
        )

        val syncType: SyncType
        if (account.cursor != 0L) {
            syncType = SyncType.CLASSIC_SYNC
            syncData.cursor = account.cursor
        } else {
            syncType = SyncType.INITIAL_SYNC
        }

        val newCursor = System.currentTimeMillis() / 1000L

        return dataSource.synchronize(syncType, syncData, account.writeToken!!).run {
            insertFolders(folders)
            val newFeeds = insertFeeds(feeds)

            val newItems = insertItems(items + starredItems)

            applyItemStates(unreadIds, readIds, starredIds)

            account.cursor = newCursor
            database.accountDao().updateCursor(newCursor)

            database.pendingChangeDao().deleteAll()

            SyncResult(
                items = newItems,
                feeds = newFeeds
            )
        }
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
        val feedIdsByRemoteId = mutableMapOf<String?, Int>()

        for (item in items) {
            item.feedId = feedIdsByRemoteId.getOrPut(item.feedRemoteId) {
                database.feedDao().selectRemoteFeedLocalId(item.feedRemoteId!!)
            }

            if (item.text != null) {
                item.readTime = Utils.readTimeFromString(item.text!!)
            }
        }

        return database.itemDao().upsertArticles(items.sortedWith(Item::compareTo))
    }

    /**
     * Writes read and starred state from the three id lists the sync fetched,
     * which are the only source of it: an article the unread list names is
     * unread, one the reading list holds but the unread list does not is read,
     * and the starred list says which articles are starred.
     *
     * The lists are capped by [GReaderDataSource], well under SQLite's limit on
     * the number of values one statement can bind, and they are chunked anyway
     * so that raising the caps cannot break this. The one statement that cannot
     * be chunked is the unstarring, which needs the whole starred list at once
     * to know what is *not* in it.
     */
    private suspend fun applyItemStates(
        unreadIds: List<Long>,
        readIds: List<Long>,
        starredIds: List<Long>
    ) {
        val now = System.currentTimeMillis()
        val itemDao = database.itemDao()

        unreadIds.chunked(MAX_IDS_PER_STATEMENT).forEach { itemDao.markUnread(it) }
        readIds.chunked(MAX_IDS_PER_STATEMENT).forEach { itemDao.markRead(it, now) }

        itemDao.unstarOutside(starredIds)
        starredIds.chunked(MAX_IDS_PER_STATEMENT).forEach { itemDao.star(it) }
    }

    private companion object {
        const val MAX_IDS_PER_STATEMENT = 900
    }
}
