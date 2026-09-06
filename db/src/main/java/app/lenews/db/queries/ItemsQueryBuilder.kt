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
        // the moment the article became read, which the history list shows in
        // place of the publication date
        "Article.read_at",
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
     *
     * The price of fixing the order is that a condition on a column of `Feed`
     * can only be tested after an article row has been read, so it filters
     * nothing away and every page of a folder visits the whole store — worst of
     * all a folder with no article, which reads a hundred thousand rows to
     * return none. [JOIN_FOR_A_FOLDER] is what that filter uses instead.
     */
    private const val JOIN = "Article CROSS JOIN Feed On Article.feed_id = Feed.id " +
            "LEFT JOIN Folder On Feed.folder_id = Folder.id"

    /**
     * The same join, with the index the folder timeline walks named: the feeds
     * of the folder are looked up first and the articles of each are found
     * through `Article(feed_id, pub_date)`, so a folder costs what its own
     * articles cost and nothing more.
     *
     * Naming the index is what makes that the plan whatever the planner knows.
     * Measured on a hundred thousand articles, first page: with the index named,
     * 3.9 ms for a folder holding a tenth of the store and 0.02 ms for a folder
     * holding nothing, in both statistics states. Left to the planner, the same
     * query costs 0.5 ms for the full folder but **39 ms** for the empty one
     * once `PRAGMA optimize` has run, because it then prefers to walk
     * `Article(pub_date)` from the newest article and test every row against the
     * folder — a hundred thousand of them, to return nothing. The sort the named
     * index costs is bounded by the page, and it is paid on the articles of one
     * folder rather than on the store.
     */
    private const val JOIN_FOR_A_FOLDER =
        "Article Indexed By index_Article_feed_id_pub_date " +
                "CROSS JOIN Feed On Article.feed_id = Feed.id " +
                "LEFT JOIN Folder On Feed.folder_id = Folder.id"

    /**
     * The same join again, with `Article(read_at)` named: the history is that
     * index walked backwards, which gives the filter and the order in one pass
     * and stops at the end of the page.
     *
     * The index is named for the same reason the folder names one. Left to the
     * planner, nothing forbids a scan of `Article(pub_date)` with a sort on top,
     * and ticket 13 measured a planner changing its mind for the worse as soon
     * as `PRAGMA optimize` gave it statistics. Named, the plan is the same
     * before and after.
     */
    private const val JOIN_FOR_THE_HISTORY =
        "Article Indexed By index_Article_read_at " +
                "CROSS JOIN Feed On Article.feed_id = Feed.id " +
                "LEFT JOIN Folder On Feed.folder_id = Folder.id"

    /**
     * The query the timeline, the history and the item screen all page through.
     *
     * [keptArticleIds] are articles the caller wants to keep in the list even
     * though their state no longer matches the filter. The item screen uses it:
     * an article read while it is open leaves the unread timeline the moment the
     * read is written, and the list under the reader's finger would shift by
     * one. The state conditions — unread, starred, in the history — are the only
     * ones relaxed for those ids; which feed an article belongs to and when it
     * was published do not change while it is being read, so those conditions
     * stay as they are. Empty, which is what the timeline passes, the query is
     * exactly what it was before the set existed.
     */
    fun buildItemsQuery(
        queryFilters: QueryFilters,
        keptArticleIds: Set<Long> = emptySet()
    ): SupportSQLiteQuery =
        with(queryFilters) {
            refuseASubFilterWithNothingToFilterOn(this@with)

            SupportSQLiteQueryBuilder.builder(tableToRead(this@with)).run {
                columns(COLUMNS)
                selection(buildWhereClause(this@with, keptArticleIds), null)
                orderBy(buildOrderByClause(this@with))

                create()
            }
        }

    /**
     * How many articles of that same list come before the article [itemId] —
     * which is that article's position in the list, counted from zero.
     *
     * The item screen needs it to open on the article the reader tapped. The
     * position the timeline hands it is the position the article had in the
     * list the timeline was showing, and by the time the screen builds its own
     * list that can be a different list: a sync may have put any number of
     * articles above it, and after the process was killed and the screen
     * recreated it certainly may. Counting under the same conditions and the
     * same order is the only answer that is right whatever happened meanwhile.
     *
     * It is the query [buildItemsQuery] builds — same table, same conditions,
     * same order — with one condition added: the row sorts before the article.
     * Anything else and the count would be a position in a list the reader is
     * not looking at, so [keptArticleIds] has to be the set the list is built
     * with, the article's own id included.
     *
     * **An article the store no longer holds has no position here, and the
     * answer is not zero either**: nothing sorts against a row that is not
     * there, so the comparison is made against a null and the count comes out
     * as the length of the list or as nought depending on which way the list is
     * ordered — and nought reads exactly like "the first article". Retention
     * drops articles at every sync, so the caller asks whether the article is
     * still there before it asks where.
     */
    fun buildItemPositionQuery(
        queryFilters: QueryFilters,
        itemId: Long,
        keptArticleIds: Set<Long> = emptySet()
    ): SupportSQLiteQuery =
        with(queryFilters) {
            refuseASubFilterWithNothingToFilterOn(this@with)

            SupportSQLiteQueryBuilder.builder(tableToRead(this@with)).run {
                columns(arrayOf("Count(*) As position"))
                selection(
                    buildWhereClause(this@with, keptArticleIds) +
                            "And ${sortsBeforeClause(this@with, itemId)} ",
                    null
                )

                create()
            }
        }

    private fun refuseASubFilterWithNothingToFilterOn(queryFilters: QueryFilters) {
        if (queryFilters.subFilter == SubFilter.FEED && queryFilters.feedId == 0) {
            throw IllegalArgumentException("FeedId must be greater than 0 if subFilter is FEED")
        } else if (queryFilters.subFilter == SubFilter.FOLDER && queryFilters.folderId == 0) {
            throw IllegalArgumentException("FolderId must be greater than 0 if subFilter is FOLDER")
        }
    }

    private fun tableToRead(queryFilters: QueryFilters): String = when {
        queryFilters.subFilter == SubFilter.FOLDER -> JOIN_FOR_A_FOLDER
        queryFilters.mainFilter == MainFilter.HISTORY -> JOIN_FOR_THE_HISTORY
        else -> JOIN
    }

    private fun buildWhereClause(queryFilters: QueryFilters, keptArticleIds: Set<Long>): String =
        buildString {
            // SupportSQLiteQueryBuilder writes no WHERE at all for an empty
            // selection, so a clause that is always true keeps the shape simple
            append("1 = 1 ")

            val state = buildStateClause(queryFilters)
            if (state.isNotEmpty()) {
                if (keptArticleIds.isEmpty()) {
                    append("And $state ")
                } else {
                    append("And ($state Or Article.id In (${keptArticleIds.joinToString(",")})) ")
                }
            }

            if (queryFilters.mainFilter == MainFilter.NEW) {
                append("And $WITHIN_LAST_24_HOURS ")
            }

            when (queryFilters.subFilter) {
                SubFilter.FEED -> append("And Article.feed_id = ${queryFilters.feedId} ")
                // the article's own feed id, against the feeds of the folder,
                // rather than Feed.folder_id: see the note on JOIN_FOR_A_FOLDER
                SubFilter.FOLDER -> append(
                    "And Article.feed_id In " +
                            "(Select id From Feed Where folder_id = ${queryFilters.folderId}) "
                )
                else -> {}
            }

            toString()
        }

    /**
     * The conditions on the article's own state, which are the ones an article
     * the reader acts on can stop satisfying.
     */
    private fun buildStateClause(queryFilters: QueryFilters): String = buildString {
        if (queryFilters.mainFilter == MainFilter.HISTORY) {
            // an article in the history is read by definition, so showReadItems
            // has nothing to say about this list
            append("Article.read_at Is Not Null")
            return@buildString
        }

        if (!queryFilters.showReadItems) {
            append("Article.read = 0")
        }

        if (queryFilters.mainFilter == MainFilter.STARS) {
            if (isNotEmpty()) {
                append(" And ")
            }
            append("Article.starred = 1")
        }
    }

    /**
     * The order of the list, which always ends in the article's own id.
     *
     * The id is there to make the order **total**. Two articles published in the
     * same second — which one feed delivering a batch produces all the time —
     * would otherwise come out in whatever order the plan happened to give, and
     * a position in the list would name a group of articles rather than one.
     * The item screen counts the rows before an article to find the page to open
     * on, and that count is only a page if there is exactly one article at every
     * position. It costs nothing to read: the id is the table's `rowid`, so it
     * is the last column of every index already, and the same index scan that
     * serves the first column serves the tie.
     */
    private fun buildOrderByClause(queryFilters: QueryFilters): String {
        // the history is the order in which articles became read, newest first,
        // and nothing else: it is what the list is for
        if (queryFilters.mainFilter == MainFilter.HISTORY) {
            return "Article.read_at DESC, Article.id DESC"
        }

        val direction = if (queryFilters.orderType == OrderType.DESC) "DESC" else "ASC"

        return when (queryFilters.orderField) {
            // the id is the order already; there is no tie to break
            OrderField.ID -> "Article.id $direction"
            else -> "pub_date $direction, Article.id $direction"
        }
    }

    /**
     * The rows that come before the article [itemId] in the order
     * [buildOrderByClause] gives, which is what turns a count into a position.
     *
     * Written out rather than as one comparison of two pairs because the column
     * the list is sorted on can be null — an article that arrived with no
     * publication date, or, in the history, one the reader marked unread while
     * it was open, whose moment of becoming read is gone. SQLite sorts nulls
     * first ascending and last descending, and no comparison operator says that;
     * `Is` is used for the tie because it is the equality that a null passes.
     */
    private fun sortsBeforeClause(queryFilters: QueryFilters, itemId: Long): String {
        val descending = orderIsDescending(queryFilters)
        val column = orderColumn(queryFilters)
            // the list is ordered by the id itself: no null and no tie
            ?: return if (descending) "Article.id > $itemId" else "Article.id < $itemId"

        val ofTheArticle = "(Select sorted.$column From Article As sorted Where sorted.id = $itemId)"
        val sameKey = "(Article.$column Is $ofTheArticle " +
                "And Article.id ${if (descending) ">" else "<"} $itemId)"

        return if (descending) {
            "((Article.$column Is Not Null " +
                    "And ($ofTheArticle Is Null Or Article.$column > $ofTheArticle)) " +
                    "Or $sameKey)"
        } else {
            "((Article.$column Is Null And $ofTheArticle Is Not Null) " +
                    "Or (Article.$column Is Not Null And $ofTheArticle Is Not Null " +
                    "And Article.$column < $ofTheArticle) " +
                    "Or $sameKey)"
        }
    }

    /** The column the list is sorted on, or null when it is sorted on the id. */
    private fun orderColumn(queryFilters: QueryFilters): String? = when {
        queryFilters.mainFilter == MainFilter.HISTORY -> "read_at"
        queryFilters.orderField == OrderField.ID -> null
        else -> "pub_date"
    }

    private fun orderIsDescending(queryFilters: QueryFilters): Boolean =
        queryFilters.mainFilter == MainFilter.HISTORY ||
                queryFilters.orderType == OrderType.DESC
}
