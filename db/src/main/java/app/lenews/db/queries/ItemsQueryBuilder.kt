package app.lenews.db.queries

import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQueryBuilder
import app.lenews.db.filters.MainFilter
import app.lenews.db.filters.OrderField
import app.lenews.db.filters.OrderType
import app.lenews.db.filters.QueryFilters
import app.lenews.db.filters.SubFilter

object ItemsQueryBuilder {

    private val COLUMNS = arrayOf(
        "Article.id",
        "title",
        "author",
        "clean_description",
        "Article.description",
        "content",
        "image_link",
        "pub_date",
        "link",
        "read_time",
        "Article.feed_id",
        "read AS is_read",
        "read",
        "starred AS is_starred",
        "starred",
        "Feed.name",
        "color",
        "icon_url",
        "Feed.id as feedId",
        "Feed.open_in",
        "Feed.open_in_ask",
        "Folder.id as folder_id",
        "Folder.name as folder_name"
    )

    /**
     * `CROSS JOIN` is how SQLite is told not to reorder the loops, so the
     * article table is always the outer one and the index that serves the
     * ordering is the only sensible plan. Ticket 11 measured what happens
     * otherwise: driving from `Feed` makes every page sort the whole store in a
     * temporary b-tree, 90 ms a page on a year of articles, and adding the index
     * alone changed nothing because the planner kept the join order it had.
     */
    private const val JOIN = "Article CROSS JOIN Feed On Article.feed_id = Feed.id " +
            "LEFT JOIN Folder On Feed.folder_id = Folder.id"

    fun buildItemsQuery(queryFilters: QueryFilters): SupportSQLiteQuery =
        with(queryFilters) {
            if (subFilter == SubFilter.FEED && feedId == 0) {
                throw IllegalArgumentException("FeedId must be greater than 0 if subFilter is FEED")
            } else if (subFilter == SubFilter.FOLDER && folderId == 0) {
                throw IllegalArgumentException("FolderId must be greater than 0 if subFilter is FOLDER")
            }

            SupportSQLiteQueryBuilder.builder(JOIN).run {
                columns(COLUMNS)
                selection(buildWhereClause(this@with), null)
                orderBy(buildOrderByClause(orderField, orderType))

                create()
            }
        }

    private fun buildWhereClause(queryFilters: QueryFilters): String =
        buildString {
            // SupportSQLiteQueryBuilder writes no WHERE at all for an empty
            // selection, so a clause that is always true keeps the shape simple
            append("1 = 1 ")

            if (!queryFilters.showReadItems) {
                append("And Article.read = 0 ")
            }

            when (queryFilters.mainFilter) {
                MainFilter.STARS -> append("And Article.starred = 1 ")
                MainFilter.NEW -> append("And $WITHIN_LAST_24_HOURS ")
                else -> {}
            }

            when (queryFilters.subFilter) {
                SubFilter.FEED -> append("And Article.feed_id = ${queryFilters.feedId} ")
                SubFilter.FOLDER -> append("And Feed.folder_id = ${queryFilters.folderId} ")
                else -> {}
            }

            toString()
        }

    private fun buildOrderByClause(orderField: OrderField, orderType: OrderType): String {
        return buildString {
            when (orderField) {
                OrderField.ID -> append("Article.id ")
                else -> append("pub_date ")
            }

            when (orderType) {
                OrderType.DESC -> append("DESC")
                else -> append("ASC")
            }
        }
    }
}
