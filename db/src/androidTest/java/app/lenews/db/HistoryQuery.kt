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
package app.lenews.db

/**
 * What the history list of `docs/article-store.md` §5 reads: every article that
 * became read, newest first, which is `Article(read_at)` walked backwards.
 *
 * Ticket 16 builds the screen and will replace this with a query builder of the
 * `db` module's own. Until then the query lives here, in one place, because both
 * `TimelineTimeBudgetTest` — which holds §6's 10 ms budget for it — and the
 * benchmark measure it, and two copies of it would drift apart.
 */
object HistoryQuery {

    const val SQL = "Select Article.id, Article.title, Article.read_at, Feed.name From Article " +
            "Inner Join Feed On Article.feed_id = Feed.id " +
            "Where read_at Is Not Null Order By read_at DESC"
}
