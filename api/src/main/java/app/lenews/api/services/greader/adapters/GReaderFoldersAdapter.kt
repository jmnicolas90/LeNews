package app.lenews.api.services.greader.adapters

import app.lenews.api.utils.exceptions.ParseException
import app.lenews.api.utils.extensions.nextNonEmptyString
import app.lenews.db.entities.Folder
import com.squareup.moshi.FromJson
import com.squareup.moshi.JsonReader
import com.squareup.moshi.ToJson
import java.util.StringTokenizer

/**
 * The folders of `tag/list`. The same call also lists the user's labels, which
 * this fork does not store, so entries whose type is not `folder` are skipped.
 */
data class GReaderFolders(
    val folders: List<Folder>
)

class GReaderFoldersAdapter {

    @ToJson
    fun toJson(folders: GReaderFolders) = ""

    @FromJson
    fun fromJson(reader: JsonReader): GReaderFolders = with(reader) {
        val folders = mutableListOf<Folder>()

        return try {
            beginObject()
            while (hasNext()) {
                when (nextName()) {
                    "tags" -> {
                        beginArray()

                        while (hasNext()) {
                            beginObject()
                            parseFolder(reader, folders)

                            endObject()
                        }

                        endArray()
                    }

                    else -> skipValue()
                }
            }

            endObject()

            GReaderFolders(folders)
        } catch (e: Exception) {
            throw ParseException("GReader folders parsing failure", e)
        }
    }

    private fun parseFolder(
        reader: JsonReader,
        folders: MutableList<Folder>
    ) = with(reader) {
        var name: String? = null
        var remoteId: String? = null
        var type: String? = null

        while (hasNext()) {
            when (selectName(NAMES)) {
                0 -> {
                    val id = nextNonEmptyString()
                    name = StringTokenizer(id, "/")
                        .toList()
                        .last() as String
                    remoteId = id
                }

                1 -> type = nextString()
                else -> skipValue()
            }

        }

        if (name.isNullOrEmpty()) {
            return@with
        }

        if (type == "folder") {
            folders += Folder(
                name = name,
                remoteId = remoteId
            )
        }
    }

    companion object {
        val NAMES: JsonReader.Options = JsonReader.Options.of("id", "type")
    }
}
