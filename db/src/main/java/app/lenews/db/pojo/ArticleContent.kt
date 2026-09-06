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
package app.lenews.db.pojo

import androidx.room.ColumnInfo
import app.lenews.db.entities.Item
import java.time.LocalDateTime

/**
 * The columns a re-delivered article overwrites: what the server sends, and
 * nothing else. `read`, `starred` and `read_at` are deliberately absent, so an
 * article FreshRSS sends again keeps the state it has on the phone.
 */
data class ArticleContent(
    val id: Long,
    val title: String?,
    val description: String?,
    @ColumnInfo(name = "clean_description") val cleanDescription: String?,
    val link: String?,
    @ColumnInfo(name = "image_link") val imageLink: String?,
    val author: String?,
    @ColumnInfo(name = "pub_date") val pubDate: LocalDateTime?,
    val content: String?,
    @ColumnInfo(name = "feed_id") val feedId: Int,
    @ColumnInfo(name = "read_time") val readTime: Double,
) {

    companion object {
        fun of(item: Item) = ArticleContent(
            id = item.id,
            title = item.title,
            description = item.description,
            cleanDescription = item.cleanDescription,
            link = item.link,
            imageLink = item.imageLink,
            author = item.author,
            pubDate = item.pubDate,
            content = item.content,
            feedId = item.feedId,
            readTime = item.readTime,
        )
    }
}
