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

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One id the horizon dropped: an article that was read more than thirty days
 * ago and was deleted for it, while FreshRSS still held it.
 *
 * The table is the memory the article row can no longer keep. FreshRSS
 * re-delivers an article whose content it saw change, whatever its age, so the
 * content of an article the horizon dropped comes back on a later sync. Without
 * this ledger that delivery would look like a brand new article: it would be
 * inserted unread, the sync would learn it is read and stamp it with the sync's
 * own clock, and it would sit in the history for thirty more days with a date
 * that never happened — and be reported as new on top of it.
 *
 * So the ledger says "this one was dropped on purpose, do not take it back",
 * and the sync consults it before inserting. Two things clear a row from it: the
 * server naming the article unread or starred again, which is the reader asking
 * for it back, and the server no longer holding the article at all, which is the
 * mirror rule bounding the ledger by the server's own retention.
 *
 * One column and nothing else. It has to stay small enough that a store with a
 * year of reading behind it pays nothing for it.
 */
@Entity
data class HorizonDropped(
    @PrimaryKey val id: Long
)
