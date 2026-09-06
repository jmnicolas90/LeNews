package app.lenews.db.entities

import androidx.annotation.ColorInt
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import java.io.Serializable

enum class OpenIn {
    LOCAL_VIEW,
    EXTERNAL_VIEW
}

@Entity(
    foreignKeys = [
        ForeignKey(
            entity = Folder::class,
            parentColumns = ["id"],
            childColumns = ["folder_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["folder_id"]),
        Index(value = ["remote_id"], unique = true)
    ]
)
data class Feed(
    @PrimaryKey(autoGenerate = true) var id: Int = 0,
    var name: String? = null,
    var description: String? = null,
    var url: String? = null,
    @ColumnInfo("image_url") var imageUrl: String? = null,
    var siteUrl: String? = null,
    @ColumnInfo("last_updated") var lastUpdated: String? = null,
    @ColorInt var color: Int = 0,
    @ColumnInfo(name = "icon_url") var iconUrl: String? = null,
    var etag: String? = null,
    @ColumnInfo(name = "last_modified") var lastModified: String? = null,
    @ColumnInfo(name = "folder_id") var folderId: Int? = null,
    @ColumnInfo("remote_id") var remoteId: String? = null,
    @ColumnInfo(
        name = "notification_enabled",
        defaultValue = "1"
    ) var isNotificationEnabled: Boolean = true,
    @ColumnInfo(name = "open_in") var openIn: OpenIn = OpenIn.LOCAL_VIEW,
    @ColumnInfo(name = "open_in_ask", defaultValue = "1") var openInAsk: Boolean = true,
    @Ignore var unreadCount: Int = 0,
    @Ignore var remoteFolderId: String? = null,
) : Serializable
