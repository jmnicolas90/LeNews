package app.lenews.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteQuery
import app.lenews.db.entities.Feed
import app.lenews.db.entities.Folder
import app.lenews.db.entities.Item
import app.lenews.db.pojo.FolderWithFeed
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao : BaseDao<Folder> {

    // TODO react to Item changes when this table is not part of the query might be a perf issue
    @RawQuery(observedEntities = [Folder::class, Feed::class, Item::class])
    fun selectFoldersAndFeeds(query: SupportSQLiteQuery): Flow<List<FolderWithFeed>>

    @Query("Select * From Folder")
    fun selectFolders(): Flow<List<Folder>>

    @Query("Select * from Folder Where id = :folderId")
    fun select(folderId: Int): Folder

    @Query("Select * From Folder Where name = :name")
    suspend fun selectFolderByName(name: String): Folder?

    @Query("Select remote_id From Folder")
    suspend fun selectFolderRemoteIds(): List<String>

    @Query("Update Folder set name = :name Where remote_id = :remoteId")
    suspend fun updateFolderName(name: String, remoteId: String)

    @Query("Delete From Folder")
    suspend fun deleteEveryFolder()

    @Query("Delete From Folder Where remote_id in (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    /**
     * Insert, update and delete folders
     *
     * @param folders folders to insert or update
     * @return the list of the inserted folders ids
     */
    @Transaction
    suspend fun upsertFolders(folders: List<Folder>): List<Long> {
        val localFolderIds = selectFolderRemoteIds()

        val foldersToInsert = folders.filter { folder -> localFolderIds.none { localFolderId -> folder.remoteId == localFolderId  } }
        val foldersToDelete = localFolderIds.filter { localFolderId -> folders.none { folder -> localFolderId == folder.remoteId } }

        // folders to update
        folders.filter { folder -> localFolderIds.any { localFolderId -> folder.remoteId == localFolderId} }
            .forEach { updateFolderName(it.name!!, it.remoteId!!) }

        if (foldersToDelete.isNotEmpty()) {
            deleteByIds(foldersToDelete)
        }

        return insert(foldersToInsert)
    }
}
