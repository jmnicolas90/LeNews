/*
 * Copyright (C) 2026 Jean-Michel Nicolas
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package app.lenews.db.dao

import androidx.room.Dao
import androidx.room.Query
import app.lenews.db.entities.PendingChange

/**
 * The queue of read and starred decisions FreshRSS has not been told about yet.
 */
@Dao
interface PendingChangeDao : BaseDao<PendingChange> {

    @Query("Select * From PendingChange")
    suspend fun selectAll(): List<PendingChange>

    @Query("Select * From PendingChange Where article_id = :articleId")
    suspend fun select(articleId: Long): PendingChange?

    /** Records that the phone decided [read] for this article. */
    @Query(
        """Insert Into PendingChange(article_id, read, starred) Values (:articleId, :read, NULL)
        On Conflict(article_id) Do Update Set read = :read"""
    )
    suspend fun queueRead(articleId: Long, read: Boolean)

    /** Records that the phone decided [starred] for this article. */
    @Query(
        """Insert Into PendingChange(article_id, read, starred) Values (:articleId, NULL, :starred)
        On Conflict(article_id) Do Update Set starred = :starred"""
    )
    suspend fun queueStarred(articleId: Long, starred: Boolean)

    /**
     * Queues a read decision for every article the ids name. Articles already
     * read are left out by the caller, which passes only the ones it is
     * changing.
     */
    @Query(
        """Insert Into PendingChange(article_id, read, starred)
        Select Article.id, 1, NULL From Article Where Article.id In (:articleIds)
        On Conflict(article_id) Do Update Set read = 1"""
    )
    suspend fun queueReadForArticles(articleIds: List<Long>)

    /**
     * The mark-all-read routes, one statement each. Only unread articles are
     * queued: reading an already read article changes nothing and tells the
     * server nothing, so the queue grows by the unread count and not by the
     * number of articles stored. Each of these must run **before** the update
     * that sets `read = 1`, while `read = 0` still selects the right rows.
     */
    @Query(
        """Insert Into PendingChange(article_id, read, starred)
        Select id, 1, NULL From Article Where read = 0
        On Conflict(article_id) Do Update Set read = 1"""
    )
    suspend fun queueReadForAllUnread()

    @Query(
        """Insert Into PendingChange(article_id, read, starred)
        Select id, 1, NULL From Article Where read = 0 And feed_id = :feedId
        On Conflict(article_id) Do Update Set read = 1"""
    )
    suspend fun queueReadForUnreadInFeed(feedId: Int)

    @Query(
        """Insert Into PendingChange(article_id, read, starred)
        Select id, 1, NULL From Article Where read = 0
        And feed_id In (Select id From Feed Where folder_id = :folderId)
        On Conflict(article_id) Do Update Set read = 1"""
    )
    suspend fun queueReadForUnreadInFolder(folderId: Int)

    @Query(
        """Insert Into PendingChange(article_id, read, starred)
        Select id, 1, NULL From Article Where read = 0 And starred = 1
        On Conflict(article_id) Do Update Set read = 1"""
    )
    suspend fun queueReadForUnreadStarred()

    @Query(
        """Insert Into PendingChange(article_id, read, starred)
        Select id, 1, NULL From Article Where read = 0 And pub_date >= :since
        On Conflict(article_id) Do Update Set read = 1"""
    )
    suspend fun queueReadForUnreadSince(since: Long)

    /**
     * Clears the read half of the rows a batch uploaded, **only where it still
     * holds the value that was uploaded**. A decision the user made while the
     * batch was in flight no longer matches, so it stays queued and the next
     * sync sends it. Same for [clearUploadedStarred].
     */
    @Query("Update PendingChange Set read = Null Where article_id In (:ids) And read = :uploaded")
    suspend fun clearUploadedRead(ids: List<Long>, uploaded: Boolean)

    @Query(
        "Update PendingChange Set starred = Null Where article_id In (:ids) And starred = :uploaded"
    )
    suspend fun clearUploadedStarred(ids: List<Long>, uploaded: Boolean)

    @Query("Delete From PendingChange")
    suspend fun deleteAll()

    @Query("Delete From PendingChange Where read Is Null And starred Is Null")
    suspend fun deleteEmpty()
}
