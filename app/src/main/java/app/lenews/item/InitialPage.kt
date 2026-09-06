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
 * The screen is given both the article's id and the position the article has in
 * the list right now — counted in the store by
 * `ItemsQueryBuilder.buildItemPositionQuery` when the screen opens, not the
 * index the timeline handed over, which was a position in the list the timeline
 * was showing and can be stale by any number of articles.
 *
 * The id still decides when the article is among the pages the pager has
 * loaded, because it is exact and free: the store can have changed again in the
 * moment between the count and the load. The position is what is left when the
 * article is a placeholder page the reader will scroll into.
 *
 * @param loadedArticleIds the ids of the pages the pager has, in order, with
 *   null where the page is a placeholder that has not loaded yet
 * @param itemId the article the screen was opened on
 * @param articlePosition that article's position in the list, or -1 when the
 *   screen shows one article and has no list
 */
fun initialPage(loadedArticleIds: List<Long?>, itemId: Long, articlePosition: Int): Int {
    val pageOfArticle = loadedArticleIds.indexOf(itemId)
    if (pageOfArticle > -1) {
        return pageOfArticle
    }

    val lastPage = (loadedArticleIds.size - 1).coerceAtLeast(0)
    return articlePosition.coerceIn(0, lastPage)
}
