package app.lenews.db.queries

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQueryBuilder
import app.lenews.db.filters.MainFilter

object FoldersAndFeedsQueryBuilder {

    private val COLUMNS = arrayOf(
        "Feed.id As feedId",
        "Feed.name As feedName",
        "Feed.icon_url As feedIcon",
        "Feed.color As feedColor",
        "Feed.url As feedUrl",
        "Feed.image_url as feedImage",
        "Feed.siteUrl As feedSiteUrl",
        "Feed.description as feedDescription",
        "Feed.notification_enabled as feedNotificationsEnabled",
        "Feed.open_in as feedOpenIn",
        "Feed.remote_id as feedRemoteId",
        "Folder.id As folderId",
        "Folder.name As folderName",
        "Folder.remote_id as folderRemoteId"
    )

    private const val FEED_JOIN = "Feed Left Join Folder On Folder.id = Feed.folder_id"

    private const val FOLDER_JOIN = "Folder Left Join Feed On Folder.id = Feed.folder_id "

    private const val ARTICLE_JOIN = " Inner Join Article On Article.feed_id = Feed.id "

    private const val FEED_SELECTION = "Feed.folder_id is NULL OR Feed.folder_id is NOT NULL "

    private const val FOLDER_SELECTION = "Feed.id is NULL"

    fun build(mainFilter: MainFilter, hideReadFeeds: Boolean): SupportSQLiteQuery {
        return SimpleSQLiteQuery(
            """
            ${buildFeedQuery(mainFilter, hideReadFeeds).sql}
            ${
                if (!hideReadFeeds) {
                    """UNION ALL
                        ${buildFolderQuery().sql}
                    """.trimIndent()
                } else {
                    ""
                }
            }""".trimIndent()
        )
    }

    private fun buildFeedQuery(mainFilter: MainFilter, hideReadFeeds: Boolean): SupportSQLiteQuery {
        val tables = buildString {
            append(FEED_JOIN)
            if (hideReadFeeds) {
                append(ARTICLE_JOIN)
            }
        }
        val selection = buildString {
            append(FEED_SELECTION)
            if (hideReadFeeds) {
                append("And Article.read = 0 ")

                when (mainFilter) {
                    MainFilter.STARS -> append("And Article.starred = 1 ")
                    MainFilter.NEW -> append("And Article.pub_date >= $LAST_24_HOURS_START ")
                    else -> {}
                }
            }
        }

        return SupportSQLiteQueryBuilder.builder(tables).run {
            columns(COLUMNS)
            selection(selection, null)
            groupBy("Feed.id")

            create()
        }
    }

    private fun buildFolderQuery(): SupportSQLiteQuery {
        return SupportSQLiteQueryBuilder.builder(FOLDER_JOIN).run {
            columns(COLUMNS)
            selection(FOLDER_SELECTION, null)

            create()
        }
    }
}
