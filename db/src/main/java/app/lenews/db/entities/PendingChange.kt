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
package app.lenews.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * A read or starred decision made on the phone that FreshRSS has not been told
 * yet. One row per article at most; a column is null when that half of the
 * state did not change. The row is deleted once both halves have been uploaded.
 */
@Entity(
    foreignKeys = [
        ForeignKey(
            entity = Item::class,
            parentColumns = ["id"],
            childColumns = ["article_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class PendingChange(
    @PrimaryKey @ColumnInfo(name = "article_id") val articleId: Long,
    @ColumnInfo(name = "read") val read: Boolean? = null,
    @ColumnInfo(name = "starred") val starred: Boolean? = null,
)
