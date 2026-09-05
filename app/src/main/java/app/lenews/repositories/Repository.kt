package app.lenews.repositories

import androidx.room.withTransaction
import app.lenews.db.Database
import app.lenews.db.entities.Feed
import app.lenews.db.entities.Folder
import app.lenews.db.entities.Item
import app.lenews.db.entities.ItemState
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
        database.withTransaction {
            when {
                account.config.useSeparateState -> {
                    database.itemStateChangeDao().upsertItemReadStateChange(item, account.id, true)
                    database.itemStateDao().upsertItemReadState(
                        ItemState(
                            id = 0,
                            read = item.isRead,
                            starred = item.isStarred,
                            remoteId = item.remoteId!!,
                            accountId = account.id
                        )
                    )
                }

                else -> {
                    database.itemStateChangeDao().upsertItemReadStateChange(item, account.id, false)
                    database.itemDao().updateReadState(item.id, item.isRead)
                }
            }
        }
    }

    open suspend fun setItemStarState(item: Item) {
        database.withTransaction {
            when {
                account.config.useSeparateState -> {
                    database.itemStateChangeDao().upsertItemStarStateChange(item, account.id, true)
                    database.itemStateDao().upsertItemStarState(
                        ItemState(
                            id = 0,
                            read = item.isRead,
                            starred = item.isStarred,
                            remoteId = item.remoteId!!,
                            accountId = account.id
                        )
                    )
                }

                else -> {
                    database.itemStateChangeDao().upsertItemStarStateChange(item, account.id, false)
                    database.itemDao().updateStarState(item.id, item.isStarred)
                }
            }
        }
    }

    open suspend fun setItemsRead(items: List<Item>) {
        require(items.all { it.isRead == false }) {
            "Do not add an item state change for an item which is already read"
        }

        val accountId = account.id
        val ids = items.map { it.id }

        database.withTransaction {
            when {
                account.config.useSeparateState -> {
                    items.forEach {
                        database.itemStateChangeDao().upsertItemReadStateChange(it, accountId, true)
                    }

                    database.itemStateDao().setItemsRead(
                        ids = items.map { it.remoteId!! },
                        itemStates = items.map {
                            ItemState(
                                read = true,
                                remoteId = it.remoteId!!,
                                accountId = accountId
                            )
                        },
                        accountId = accountId
                    )
                }

                else -> {
                    items.forEach {
                        database.itemStateChangeDao()
                            .upsertItemReadStateChange(it, accountId, false)
                    }
                    database.itemDao().setAllItemsRead(ids)
                }
            }
        }
    }

    open suspend fun setAllItemsRead() {
        val accountId = account.id

        database.withTransaction {
            when {
                account.config.useSeparateState -> {
                    database.itemStateChangeDao().upsertAllItemsReadStateChanges(accountId)
                    database.itemStateDao().setAllItemsRead(accountId)
                }

                else -> {
                    database.itemStateChangeDao().upsertAllItemsReadStateChanges(accountId)
                    database.itemDao().setAllItemsRead(accountId)
                }
            }
        }
    }

    open suspend fun setAllStarredItemsRead() {
        val accountId = account.id

        database.withTransaction {
            when {
                account.config.useSeparateState -> {
                    database.itemStateChangeDao().upsertStarredItemReadStateChanges(accountId)
                    database.itemStateDao().setAllStarredItemsRead(accountId)
                }

                else -> {
                    database.itemStateChangeDao().upsertStarredItemReadStateChanges(accountId)
                    database.itemDao().setAllStarredItemsRead(accountId)
                }
            }
        }
    }

    open suspend fun setAllNewItemsRead() {
        val accountId = account.id

        database.withTransaction {
            when {
                account.config.useSeparateState -> {
                    database.itemStateChangeDao().upsertNewItemReadStateChanges(accountId)
                    database.itemStateDao().setAllNewItemsRead(accountId)
                }

                else -> {
                    database.itemStateChangeDao().upsertNewItemReadStateChanges(accountId)
                    database.itemDao().setAllNewItemsRead(accountId)
                }
            }
        }
    }

    open suspend fun setAllItemsReadByFeed(feedId: Int) {
        val accountId = account.id

        database.withTransaction {
            when {
                account.config.useSeparateState -> {
                    database.itemStateChangeDao()
                        .upsertItemReadStateChangesByFeed(feedId, accountId)
                    database.itemStateDao().setAllItemsReadByFeed(feedId, accountId)
                }

                else -> {
                    database.itemStateChangeDao()
                        .upsertItemReadStateChangesByFeed(feedId, accountId)
                    database.itemDao().setAllItemsReadByFeed(feedId, accountId)
                }
            }
        }
    }

    open suspend fun setAllItemsReadByFolder(folderId: Int) {
        val accountId = account.id

        database.withTransaction {
            when {
                account.config.useSeparateState -> {
                    database.itemStateChangeDao()
                        .upsertItemReadStateChangesByFolder(folderId, accountId)
                    database.itemStateDao().setAllItemsReadByFolder(folderId, accountId)
                }

                else -> {
                    database.itemStateChangeDao()
                        .upsertItemReadStateChangesByFolder(folderId, accountId)
                    database.itemDao().setAllItemsReadByFolder(folderId, accountId)
                }
            }
        }
    }
}
