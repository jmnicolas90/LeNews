package app.lenews.api.services.greader.adapters

import app.lenews.api.utils.exceptions.ParseException
import app.lenews.api.utils.extensions.nextNonEmptyString
import app.lenews.db.entities.Folder
import app.lenews.db.entities.Tag
import com.squareup.moshi.FromJson
import com.squareup.moshi.JsonReader
import com.squareup.moshi.ToJson
import java.util.StringTokenizer

data class GReaderFoldersTags(
    val folders: List<Folder>,
    val tags: List<Tag>
)

class GReaderFoldersTagsAdapter {

    @ToJson
    fun toJson(foldersTags: GReaderFoldersTags) = ""

    @FromJson
    fun fromJson(reader: JsonReader): GReaderFoldersTags = with(reader) {
        val folders = mutableListOf<Folder>()
        val tags = mutableListOf<Tag>()

        return try {
            beginObject()
            while (hasNext()) {
                when (nextName()) {
                    "tags" -> {
                        beginArray()

                        while (hasNext()) {
                            beginObject()
                            parseFolder(reader, folders, tags)

                            endObject()
                        }

                        endArray()
                    }

                    else -> skipValue()
                }
            }

            endObject()

            GReaderFoldersTags(
                folders,
                tags
            )
        } catch (e: Exception) {
            throw ParseException("GReader folders parsing failure", e)
        }
    }

    private fun parseFolder(
        reader: JsonReader,
        folders: MutableList<Folder>,
        tags: MutableList<Tag>
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

        when (type) {
            "folder" -> {
                folders += Folder(
                    name = name,
                    remoteId = remoteId
                )
            }

            "tag" -> {
                tags += Tag(
                    name = name,
                    remoteId = remoteId!!
                )
            }
        }
    }

    companion object {
        val NAMES: JsonReader.Options = JsonReader.Options.of("id", "type")
    }
}