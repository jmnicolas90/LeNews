package app.lenews.repositories

import androidx.room.withTransaction
import app.lenews.db.Database
import app.lenews.db.entities.Feed
import app.lenews.db.entities.Folder
import app.lenews.db.entities.Item
import app.lenews.db.entities.account.Account

typealias ErrorResult = HashMap<Feed, Exception>

data class SyncResult(
    val items: List<Item> = listOf(),
    val feeds: List<Feed> = listOf()
)

interface Repository {

    suspend fun login(account: Account)

    /**
     * Global synchronization
     * @return the result of the synchronization: newly inserted items and feeds
     */
    suspend fun synchronize(): SyncResult

    /**
     * Insert new feeds by notifying each of them
     * @param newFeeds feeds to insert
     * @param onUpdate notify each feed insertion
     * @return errors by feed
     */
    suspend fun insertNewFeeds(newFeeds: List<Feed>, onUpdate: (Feed) -> Unit): ErrorResult
}

/**
 * Every route by which an article becomes read writes two things in one
 * transaction: the state on the article row, dated, and the decision in
 * `PendingChange` so the next sync tells the server. Marking unread clears the
 * date, which takes the article out of the history.
 */
abstract class BaseRepository(
    val database: Database,
    val account: Account,
) : Repository {

    open suspend fun updateFeed(feed: Feed) =
        database.feedDao().updateFeedFields(feed.id, feed.name!!, feed.url!!, feed.folderId)

    open suspend fun deleteFeed(feed: Feed) = database.feedDao().delete(feed)

    open suspend fun addFolder(folder: Folder) {
        database.folderDao().insert(folder)
    }

    open suspend fun updateFolder(folder: Folder) = database.folderDao().update(folder)

    open suspend fun deleteFolder(folder: Folder) = database.folderDao().delete(folder)

    open suspend fun setItemReadState(item: Item) {
        val now = System.currentTimeMillis()

        database.withTransaction {
            if (item.isRead) {
                database.itemDao().markRead(item.id, now)
            } else {
                database.itemDao().markUnread(item.id)
            }

            database.pendingChangeDao().queueRead(item.id, item.isRead)
        }
    }

    open suspend fun setItemStarState(item: Item) {
        database.withTransaction {
            database.itemDao().setStarred(item.id, item.isStarred)
            database.pendingChangeDao().queueStarred(item.id, item.isStarred)
        }
    }

    open suspend fun setItemsRead(items: List<Item>) {
        require(items.none { it.isRead }) {
            "Do not queue a read change for an article which is already read"
        }

        if (items.isEmpty()) {
            return
        }

        val ids = items.map { it.id }
        val now = System.currentTimeMillis()

        database.withTransaction {
            database.pendingChangeDao().queueReadForArticles(ids)
            database.itemDao().markRead(ids, now)
        }
    }

    open suspend fun setAllItemsRead() {
        val now = System.currentTimeMillis()

        database.withTransaction {
            // queued first, while the articles about to change are still unread
            database.pendingChangeDao().queueReadForAllUnread()
            database.itemDao().markAllRead(now)
        }
    }

    open suspend fun setAllStarredItemsRead() {
        val now = System.currentTimeMillis()

        database.withTransaction {
            database.pendingChangeDao().queueReadForUnreadStarred()
            database.itemDao().markAllStarredRead(now)
        }
    }

    open suspend fun setAllNewItemsRead() {
        val now = System.currentTimeMillis()
        val since = now - DAY_MILLIS

        database.withTransaction {
            database.pendingChangeDao().queueReadForUnreadSince(since)
            database.itemDao().markAllReadSince(since, now)
        }
    }

    open suspend fun setAllItemsReadByFeed(feedId: Int) {
        val now = System.currentTimeMillis()

        database.withTransaction {
            database.pendingChangeDao().queueReadForUnreadInFeed(feedId)
            database.itemDao().markAllReadByFeed(feedId, now)
        }
    }

    open suspend fun setAllItemsReadByFolder(folderId: Int) {
        val now = System.currentTimeMillis()

        database.withTransaction {
            database.pendingChangeDao().queueReadForUnreadInFolder(folderId)
            database.itemDao().markAllReadByFolder(folderId, now)
        }
    }

    companion object {
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
