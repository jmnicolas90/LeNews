package app.lenews.api.services.greader.adapters

import android.annotation.SuppressLint
import app.lenews.api.services.greader.ArticleIds
import app.lenews.api.utils.exceptions.ParseException
import app.lenews.api.utils.extensions.nextNonEmptyString
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter

/**
 * The id lists of `stream/items/ids`, which sends the decimal form. Both this
 * and [GReaderItemsAdapter] end up with the same number for the same article.
 */
class GReaderItemsIdsAdapter : JsonAdapter<List<Long>>() {

    override fun toJson(writer: JsonWriter, value: List<Long>?) {
        // not useful here
    }

    @SuppressLint("CheckResult")
    override fun fromJson(reader: JsonReader): List<Long> = with(reader) {
        val ids = arrayListOf<Long>()

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
                    else -> skipValue()
                }
            }

            endObject()
            ids
        } catch (e: Exception) {
            throw ParseException("GReader items ids parsing failure", e)
        }
    }
}
