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
package app.lenews.api.services.greader

/**
 * The four things a pending change can tell the server, one `edit-tag` request
 * each: a state to add or a state to remove.
 */
enum class ArticleStateChange(val addTarget: String?, val removeTarget: String?) {
    READ(GReaderDataSource.GOOGLE_READ, null),
    UNREAD(null, GReaderDataSource.GOOGLE_READ),
    STARRED(GReaderDataSource.GOOGLE_STARRED, null),
    UNSTARRED(null, GReaderDataSource.GOOGLE_STARRED)
}
