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

/**
 * The ledger of ids the horizon dropped, read by the sync and written by the
 * retention delete.
 *
 * Both writes live in `Retention.kt`, in the same statement sequence as the
 * delete they belong to, because they are one rule and one transaction. What is
 * here is what the sync needs: asking which of the ids in front of it were
 * dropped, and forgetting the ones the server has just called unread or starred
 * again.
 */
@Dao
interface HorizonDroppedDao {

    /**
     * Which of these ids the horizon dropped. The caller gives them in chunks,
     * so no statement binds more values than SQLite will take.
     */
    @Query("Select id From HorizonDropped Where id In (:ids)")
    suspend fun droppedAmong(ids: List<Long>): List<Long>

    /** Forgets these ids, so the articles can be stored again. */
    @Query("Delete From HorizonDropped Where id In (:ids)")
    suspend fun forget(ids: List<Long>)

    @Query("Select id From HorizonDropped Order By id")
    suspend fun everyDroppedId(): List<Long>

    @Query("Delete From HorizonDropped")
    suspend fun forgetEverything()
}
