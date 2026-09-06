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

    /**
     * One article, or null when the store no longer holds it — retention drops
     * articles at every sync, so anything holding an id from before a sync has
     * to be ready for the row to be gone.
     */
    @Query("Select * From Article Where id = :itemId")
    suspend fun select(itemId: Long): Item?

    @Query("Select * From Article Limit 1")
    suspend fun selectFirst(): Item?

    @Query("Select * From Article")
    suspend fun selectEveryArticle(): List<Item>

    /** Which of these ids the store already holds, asked one chunk at a time. */
    @Query("Select id From Article Where id In (:ids)")
    suspend fun selectHeldIds(ids: List<Long>): List<Long>

    @Query("Select id From Article Where read = 0")
    suspend fun selectUnreadIds(): List<Long>

    /** Empties the article table; the pending changes cascade with it. */
    @Query("Delete From Article")
    suspend fun deleteEveryArticle()

    @Query("Select id From Article Where starred = 1")
    suspend fun selectStarredIds(): List<Long>

    @Query("Select * From Article Where feed_id = :feedId")
    suspend fun selectItems(feedId: Int): List<Item>

    @Query("Select Case When Exists(Select 1 From Article Where id = :itemId) Then 1 Else 0 End")
    suspend fun itemExists(itemId: Long): Boolean

    @RawQuery(observedEntities = [Item::class, Feed::class, Folder::class])
    fun selectAll(query: SupportSQLiteQuery): PagingSource<Int, ItemWithFeed>

    @RawQuery(observedEntities = [Item::class])
    fun selectItemById(query: SupportSQLiteQuery): Flow<ItemWithFeed>

    /**
     * The position an article has in a list right now, counted from zero: how
     * many of the list's articles come before it. The query is
     * `ItemsQueryBuilder.buildItemPositionQuery`, which is where the conditions
     * and the order are.
     *
     * The answer means nothing at all for an article the store no longer
     * holds — not even zero — so ask [itemExists] first.
     */
    @RawQuery
    suspend fun countArticlesBefore(query: SupportSQLiteQuery): Int

    //region storing what a sync brought back

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNewArticles(articles: List<Item>): List<Long>

    @Update(entity = Item::class)
    suspend fun updateContent(content: List<ArticleContent>)

    /**
     * Stores the articles a sync brought back: a new id is inserted in the
     * neutral state — unread, unstarred, no `read_at` — and an id already held
     * has its content columns overwritten and every other column left alone, so
     * read, starred, `read_at` and the pending change survive a re-delivery.
     * Within one call the last occurrence of an id wins.
     *
     * The neutral insert is step 4b of `docs/article-store.md` §3, and it is
     * enforced here rather than trusted to the caller: the read and starred
     * flags an article arrives with are not state — the id lists of the sync
     * are — and inserting them would write `read = 1` with no `read_at`, which
     * invariant 2 forbids. Nothing would repair it either, since marking read
     * only touches rows that are unread. State is applied after the insert, and
     * a read learned at sync is stamped with the sync's clock there.
     *
     * @return the articles that were new, in the order they were given and in
     * the state they were stored in.
     */
    @Transaction
    suspend fun upsertArticles(articles: List<Item>): List<Item> {
        if (articles.isEmpty()) {
            return emptyList()
        }

        val lastOfEachId = LinkedHashMap<Long, Item>(articles.size)
        articles.forEach { lastOfEachId[it.id] = it }
        val unique = lastOfEachId.values.map {
            it.copy(isRead = false, isStarred = false, readAt = null)
        }

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

    //endregion

    //region the state a sync learned

    /**
     * Steps 4c and 4d of `docs/article-store.md` §3: the state the three id
     * lists of a sync decided.
     *
     * Each of these skips an article with a pending value for the half it
     * writes, because the phone's decision wins over the server's answer until
     * it has been uploaded. The skip is a subquery on `PendingChange` rather
     * than a list the caller filters, so it is read when the statement runs —
     * inside the sync transaction, after the queue was cleared of what the
     * server took, and including anything the user queued meanwhile.
     *
     * The caller gives these ids in chunks, so no statement binds more values
     * than SQLite will take, and the ids it gives are the ones whose state
     * actually differs, so a sync that changes nothing writes no row.
     */
    @Query(
        """Update Article Set read = 1, read_at = :syncStart
        Where read = 0 And id In (:itemIds)
        And Not Exists (Select 1 From PendingChange
            Where PendingChange.article_id = Article.id And PendingChange.read Is Not Null)"""
    )
    suspend fun markReadFromSync(itemIds: List<Long>, syncStart: Long)

    @Query(
        """Update Article Set read = 0, read_at = Null
        Where read = 1 And id In (:itemIds)
        And Not Exists (Select 1 From PendingChange
            Where PendingChange.article_id = Article.id And PendingChange.read Is Not Null)"""
    )
    suspend fun markUnreadFromSync(itemIds: List<Long>)

    @Query(
        """Update Article Set starred = 1 Where starred = 0 And id In (:itemIds)
        And Not Exists (Select 1 From PendingChange
            Where PendingChange.article_id = Article.id And PendingChange.starred Is Not Null)"""
    )
    suspend fun starFromSync(itemIds: List<Long>)

    @Query(
        """Update Article Set starred = 0 Where starred = 1 And id In (:itemIds)
        And Not Exists (Select 1 From PendingChange
            Where PendingChange.article_id = Article.id And PendingChange.starred Is Not Null)"""
    )
    suspend fun unstarFromSync(itemIds: List<Long>)

    //endregion

    //region marking read in bulk

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
