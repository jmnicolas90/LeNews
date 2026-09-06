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
package app.lenews.item

/**
 * The page the item screen opens on: the article the reader tapped, found by
 * its id.
 *
 * The screen is given both the article's id and the position it had in the
 * timeline's list. The position is the weaker of the two. The list the pager
 * gets is built again when the screen is created, and after the process was
 * killed and the screen recreated it can be a different list: a sync may have
 * put articles above this one, and an article read before the process died only
 * still matches an unread timeline because the screen asks for it by id. So the
 * id decides, and the position is what is left when the id is not in the loaded
 * window — a placeholder page the reader will scroll into.
 *
 * @param loadedArticleIds the ids of the pages the pager has, in order, with
 *   null where the page is a placeholder that has not loaded yet
 * @param itemId the article the screen was opened on
 * @param itemIndex the position that article had in the list the screen was
 *   opened from, or -1 when the screen shows one article and has no list
 */
fun initialPage(loadedArticleIds: List<Long?>, itemId: Long, itemIndex: Int): Int {
    val pageOfArticle = loadedArticleIds.indexOf(itemId)
    if (pageOfArticle > -1) {
        return pageOfArticle
    }

    val lastPage = (loadedArticleIds.size - 1).coerceAtLeast(0)
    return itemIndex.coerceIn(0, lastPage)
}
