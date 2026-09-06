package app.lenews.db.queries

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import app.lenews.db.filters.MainFilter
import org.intellij.lang.annotations.Language

object FeedUnreadCountQueryBuilder {

    /**
     * The index the count has to walk, named rather than left to the planner.
     *
     * With no statistics SQLite picks it by itself and the count of a hundred
     * thousand articles takes about a millisecond. After `PRAGMA optimize` it
     * changes its mind: it prefers `index_Article_feed_id_pub_date`, because
     * that one hands back the rows already grouped and saves the temporary
     * b-tree — and then walks all hundred thousand rows instead of the few
     * thousand unread ones, which measured 68 ms against 1.1 ms. Naming the
     * index keeps the plan the same whether the planner has statistics or not.
     *
     * The name is Room's, built from the table and the columns of the index
     * declared on `Item`. If that index is renamed or dropped, SQLite refuses
     * the query outright rather than running it slowly.
     */
    const val UNREAD_INDEX = "index_Article_read_pub_date"

    fun build(mainFilter: MainFilter): SupportSQLiteQuery {
        val filter = when (mainFilter) {
            MainFilter.STARS -> "And starred = 1 "
            MainFilter.NEW -> "And $WITHIN_LAST_24_HOURS "
            else -> ""
        }

        @Language("SQL")
        val query = SimpleSQLiteQuery(
            "Select feed_id, count(*) AS item_count From Article Indexed By $UNREAD_INDEX " +
                    "Where read = 0 $filter Group By feed_id"
        )

        return query
    }
}
