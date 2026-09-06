package app.lenews.db.queries

import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQueryBuilder

object ItemSelectionQueryBuilder {

    private val COLUMNS = arrayOf(
        "Article.id",
        "title",
        "Article.description",
        "content",
        "link",
        "pub_date",
        "image_link",
        "author",
        "read_time",
        "Article.feed_id",
        "read AS is_read",
        "read",
        "starred AS is_starred",
        "starred",
        "icon_url",
        "color",
        "Feed.name",
        "Feed.open_in",
        "Feed.open_in_ask",
        "Feed.id as feedId",
        "siteUrl",
        "Folder.id as folder_id",
        "Folder.name as folder_name"
    )

    private const val JOIN =
        "Article Inner Join Feed On Article.feed_id = Feed.id Left Join Folder on Folder.id = Feed.folder_id"

    @JvmStatic
    fun buildQuery(itemId: Long): SupportSQLiteQuery =
        SupportSQLiteQueryBuilder.builder(JOIN).run {
            columns(COLUMNS)
            selection("Article.id = $itemId", null)

            create()
        }
}
