package app.lenews.api.services.greader.adapters

import android.annotation.SuppressLint
import app.lenews.api.services.greader.ArticleIds
import app.lenews.api.utils.exceptions.ParseException
import app.lenews.api.utils.extensions.nextNonEmptyString
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter

/**
 * One page of `stream/items/ids`. [continuation] means the same thing as in
 * [GReaderItemsPage]: the token the next request sends back as `c`, absent when
 * the page just sent was the last one.
 */
data class GReaderItemIdsPage(
    val ids: List<Long> = emptyList(),
    val continuation: String? = null
)

/**
 * The id lists of `stream/items/ids`, which sends the decimal form. Both this
 * and [GReaderItemsAdapter] end up with the same number for the same article.
 */
class GReaderItemsIdsAdapter : JsonAdapter<GReaderItemIdsPage>() {

    override fun toJson(writer: JsonWriter, value: GReaderItemIdsPage?) {
        // not useful here
    }

    @SuppressLint("CheckResult")
    override fun fromJson(reader: JsonReader): GReaderItemIdsPage = with(reader) {
        val ids = arrayListOf<Long>()
        var continuation: String? = null

        return try {
            beginObject()
            while (hasNext()) {
                when (nextName()) {
                    "itemRefs" -> {
                        beginArray()

                        while (hasNext()) {
                            beginObject()

                            when (nextName()) {
                                "id" -> ids += ArticleIds.fromDecimal(nextNonEmptyString())

                                else -> skipValue()
                            }

                            endObject()
                        }

                        endArray()
                    }

                    "continuation" -> continuation = nextString()
                    else -> skipValue()
                }
            }

            endObject()
            GReaderItemIdsPage(ids, continuation)
        } catch (e: Exception) {
            throw ParseException("GReader items ids parsing failure", e)
        }
    }
}
