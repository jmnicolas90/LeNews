package app.lenews.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteQuery
import app.lenews.db.entities.Feed
import app.lenews.db.entities.Item
import app.lenews.db.entities.OpenIn
import app.lenews.db.pojo.FeedWithCount
import app.lenews.db.pojo.FeedWithFolder
import kotlinx.coroutines.flow.Flow

@Dao
interface FeedDao : BaseDao<Feed> {

    @Query("Select * From Feed Where id = :feedId")
    suspend fun selectFeed(feedId: Int): Feed

    @Query("Select * From Feed Where folder_id = :folderId")
    suspend fun selectFeedsByFolder(folderId: Int): List<Feed>

    @Query("Select * from Feed order by name ASC")
    suspend fun selectFeeds(): List<Feed>

    @Query("Update Feed set etag = :etag, last_modified = :lastModified Where id = :feedId")
    suspend fun updateHeaders(etag: String, lastModified: String, feedId: Int)

    @Query("Select case When :feedUrl In (Select url from Feed) Then 1 else 0 end")
    suspend fun feedExists(feedUrl: String): Boolean

    @RawQuery(observedEntities = [Feed::class, Item::class])
    fun selectFeedsWithoutFolder(query: SupportSQLiteQuery): Flow<List<FeedWithCount>>

    @Query("Update Feed set name = :feedName, url = :feedUrl, folder_id = :folderId Where id = :feedId")
    fun updateFeedFields(feedId: Int, feedName: String, feedUrl: String, folderId: Int?)

    @Query("Select count(*) from Feed")
    suspend fun selectFeedCount(): Int

    @Query("Select remote_id From Feed")
    suspend fun selectFeedRemoteIds(): List<String>

    @Query("Select id From Folder Where remote_id = :remoteId")
    suspend fun selectRemoteFolderLocalId(remoteId: String): Int

    @Query("Select id From Feed Where remote_id = :remoteId")
    suspend fun selectRemoteFeedLocalId(remoteId: String): Int

    @Query("Update Feed set name = :name, folder_id = :folderId Where remote_id = :remoteFeedId")
    suspend fun updateFeedNameAndFolder(remoteFeedId: String, name: String, folderId: Int?)

    @Query("Delete from Feed Where remote_id in (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("Update Feed set color = :color Where id = :feedId")
    suspend fun updateFeedColor(feedId: Int, color: Int)

    @Query(
        """Select Feed.*, Folder.name as folder_name From Feed Left Join Folder On Feed.folder_id = Folder.id 
        Order By Feed.name, Folder.name"""
    )
    fun selectFeedsWithFolderName(): Flow<List<FeedWithFolder>>

    @Query("Update Feed set notification_enabled = :enabled Where id = :feedId")
    suspend fun updateFeedNotificationState(feedId: Int, enabled: Boolean)

    @Query("Update Feed set notification_enabled = :enabled")
    suspend fun updateAllFeedsNotificationState(enabled: Boolean)

    @Query("Select * From Feed Where id in (:ids)")
    suspend fun selectFromIds(ids: List<Int>): List<Feed>

    @Query("Update Feed set icon_url = :iconUrl Where id = :feedId")
    suspend fun updateFeedIconUrl(feedId: Int, iconUrl: String)

    @Query("Update Feed set open_in = :openIn Where id = :feedId")
    suspend fun updateOpenInSetting(feedId: Int, openIn: OpenIn)

    @Query("Update Feed set open_in_ask = :openInAsk Where id = :feedId")
    suspend fun updateOpenInAsk(feedId: Int, openInAsk: Boolean)

    /**
     * Insert, update and delete feeds.
     *
     * This method must always be called with the full [feeds] list
     *
     * @param feeds feeds to insert or update
     * @return newly inserted feeds
     */
    @Transaction
    suspend fun upsertFeeds(feeds: List<Feed>): List<Feed> {
        val storedFeedIds = selectFeedRemoteIds()

        val feedsToInsert = feeds.filter { feed -> storedFeedIds.none { storedFeedId -> feed.remoteId == storedFeedId } }
        val feedsToDelete = storedFeedIds.filter { storedFeedId -> feeds.none { feed -> storedFeedId == feed.remoteId } }

        feeds.forEach { feed ->
            feed.folderId = if (feed.remoteFolderId == null) {
                null
            } else {
                selectRemoteFolderLocalId(feed.remoteFolderId!!)
            }

            // works only for already existing feeds
            updateFeedNameAndFolder(feed.remoteId!!, feed.name!!, feed.folderId)
        }

        if (feedsToDelete.isNotEmpty()) {
            deleteByIds(feedsToDelete)
        }

        if (feedsToInsert.isNotEmpty()) {
            insert(feedsToInsert)
                .zip(feedsToInsert)
                .forEach { (id, feed) -> feed.id = id.toInt() }

        }

        return feedsToInsert
    }
}
