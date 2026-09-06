package app.lenews.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.MapColumn
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import app.lenews.db.entities.Feed
import app.lenews.db.entities.Folder
import app.lenews.db.entities.Item
import app.lenews.db.pojo.ArticleContent
import app.lenews.db.pojo.ItemWithFeed
import kotlinx.coroutines.flow.Flow

@Dao
interface ItemDao : BaseDao<Item> {

    @Query("Select Count(id) From Article")
    suspend fun count(): Int

    @Query("Select * From Article Where id = :itemId")
    suspend fun select(itemId: Long): Item

    @Query("Select * From Article Limit 1")
    suspend fun selectFirst(): Item?

    @Query("Select * From Article Where feed_id = :feedId")
    suspend fun selectItems(feedId: Int): List<Item>

    @Query("Select Case When Exists(Select 1 From Article Where id = :itemId) Then 1 Else 0 End")
    suspend fun itemExists(itemId: Long): Boolean

    @RawQuery(observedEntities = [Item::class, Feed::class, Folder::class])
    fun selectAll(query: SupportSQLiteQuery): PagingSource<Int, ItemWithFeed>

    @RawQuery(observedEntities = [Item::class])
    fun selectItemById(query: SupportSQLiteQuery): Flow<ItemWithFeed>

    //region storing what a sync brought back

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNewArticles(articles: List<Item>): List<Long>

    @Update(entity = Item::class)
    suspend fun updateContent(content: List<ArticleContent>)

    /**
     * Stores the articles a sync brought back: a new id is inserted unread and
     * unstarred, an id already held has its content columns overwritten and
     * every other column left alone, so read, starred, `read_at` and the pending
     * change survive a re-delivery. Within one call the last occurrence of an id
     * wins.
     *
     * @return the articles that were new, in the order they were given.
     */
    @Transaction
    suspend fun upsertArticles(articles: List<Item>): List<Item> {
        if (articles.isEmpty()) {
            return emptyList()
        }

        val lastOfEachId = LinkedHashMap<Long, Item>(articles.size)
        articles.forEach { lastOfEachId[it.id] = it }
        val unique = lastOfEachId.values.toList()

        // insert returns the new row id, or -1 for a row the store already held
        val rowIds = insertNewArticles(unique)

        val inserted = mutableListOf<Item>()
        val alreadyHeld = mutableListOf<ArticleContent>()
        unique.forEachIndexed { index, article ->
            if (rowIds[index] == -1L) {
                alreadyHeld += ArticleContent.of(article)
            } else {
                inserted += article
            }
        }

        if (alreadyHeld.isNotEmpty()) {
            updateContent(alreadyHeld)
        }

        return inserted
    }

    //endregion

    //region becoming read, and starring

    @Query("Update Article Set read = 1, read_at = :now Where id = :itemId And read = 0")
    suspend fun markRead(itemId: Long, now: Long)

    @Query("Update Article Set read = 0, read_at = Null Where id = :itemId")
    suspend fun markUnread(itemId: Long)

    @Query("Update Article Set starred = :starred Where id = :itemId")
    suspend fun setStarred(itemId: Long, starred: Boolean)

    @Query("Update Article Set read = 1, read_at = :now Where read = 0 And id In (:itemIds)")
    suspend fun markRead(itemIds: List<Long>, now: Long)

    @Query("Update Article Set read = 0, read_at = Null Where read = 1 And id In (:itemIds)")
    suspend fun markUnread(itemIds: List<Long>)

    @Query("Update Article Set starred = 1 Where starred = 0 And id In (:itemIds)")
    suspend fun star(itemIds: List<Long>)

    /**
     * Unstars every article the starred list left out. SQLite accepts an empty
     * list here, which is what an account with no starred article gives.
     */
    @Query("Update Article Set starred = 0 Where starred = 1 And id Not In (:starredIds)")
    suspend fun unstarOutside(starredIds: List<Long>)

    @Query("Update Article Set read = 1, read_at = :now Where read = 0")
    suspend fun markAllRead(now: Long)

    @Query("Update Article Set read = 1, read_at = :now Where read = 0 And feed_id = :feedId")
    suspend fun markAllReadByFeed(feedId: Int, now: Long)

    @Query(
        """Update Article Set read = 1, read_at = :now Where read = 0
        And feed_id In (Select id From Feed Where folder_id = :folderId)"""
    )
    suspend fun markAllReadByFolder(folderId: Int, now: Long)

    @Query("Update Article Set read = 1, read_at = :now Where read = 0 And starred = 1")
    suspend fun markAllStarredRead(now: Long)

    @Query("Update Article Set read = 1, read_at = :now Where read = 0 And pub_date >= :since")
    suspend fun markAllReadSince(since: Long, now: Long)

    //endregion

    //region the drawer's counts

    @Query(
        """Select count(*) From Article
        Where read = 0 And pub_date >= (strftime('%s', 'now', '-1 day') * 1000)"""
    )
    fun selectUnreadNewItemsCount(): Flow<Int>

    @RawQuery(observedEntities = [Item::class])
    fun selectFeedUnreadItemsCount(query: SupportSQLiteQuery):
            Flow<Map<@MapColumn(columnName = "feed_id") Int, @MapColumn(columnName = "item_count") Int>>

    //endregion
}
