package app.lenews.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDateTime

/**
 * One article, keyed by the 64-bit id FreshRSS gave it, in decimal form. There
 * is no second id and no separate state table: read, starred and the moment the
 * article became read are columns of this row.
 *
 * The table is named `Article`, which is the word `CONTEXT.md` uses. The Kotlin
 * class kept the name `Item` because renaming it would touch every screen for
 * no behaviour change.
 */
@Entity(
    tableName = "Article",
    foreignKeys = [
        ForeignKey(
            entity = Feed::class,
            parentColumns = ["id"],
            childColumns = ["feed_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["pub_date"]),
        Index(value = ["feed_id", "pub_date"]),
        Index(value = ["read", "pub_date"]),
        Index(value = ["starred", "pub_date"]),
        Index(value = ["read_at"]),
    ]
)
data class Item(
    @PrimaryKey var id: Long = 0,
    var title: String? = null,
    var description: String? = null,
    @ColumnInfo(name = "clean_description") var cleanDescription: String? = null,
    var link: String? = null,
    @ColumnInfo(name = "image_link") var imageLink: String? = null,
    var author: String? = null,
    @ColumnInfo(name = "pub_date") var pubDate: LocalDateTime? = null,
    var content: String? = null,
    @ColumnInfo(name = "feed_id") var feedId: Int = 0,
    @ColumnInfo(name = "read_time") var readTime: Double = 0.0,
    @ColumnInfo(name = "read", defaultValue = "0") var isRead: Boolean = false,
    @ColumnInfo(name = "starred", defaultValue = "0") var isStarred: Boolean = false,
    /** When the article became read, on the phone's clock. Null while unread. */
    @ColumnInfo(name = "read_at") var readAt: Long? = null,
    @Ignore var feedRemoteId: String? = null,
) : Comparable<Item> {

    val text
        get() = if (content != null) content else description

    val hasImage
        get() = imageLink != null

    override fun compareTo(other: Item): Int = this.pubDate!!.compareTo(other.pubDate)
}
