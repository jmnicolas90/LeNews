package app.lenews.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(indices = [Index(value = ["remote_id"], unique = true)])
data class Folder(
    @PrimaryKey(autoGenerate = true) var id: Int = 0,
    var name: String? = null,
    @ColumnInfo(name = "remote_id") var remoteId: String? = null,
) : Comparable<Folder> {

    override fun compareTo(other: Folder): Int = this.name!!.compareTo(other.name!!)
}
