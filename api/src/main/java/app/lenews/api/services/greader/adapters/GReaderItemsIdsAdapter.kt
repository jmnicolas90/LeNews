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
 *
 * A page is read strictly, because an id list is what the mirror rule of
 * retention decides against: an answer read as "the server holds these ids" is
 * an answer to delete the rest by. So `itemRefs` has to be there and every
 * reference in it has to carry an id; anything else — an empty object, an error
 * the server sent with an HTTP 200, a reference naming no article — is a
 * [ParseException] and fails the sync before it writes or deletes anything. An
 * `itemRefs` that is there and empty is a real answer and the only one that
 * means the stream holds nothing.
 */
class GReaderItemsIdsAdapter : JsonAdapter<GReaderItemIdsPage>() {

    override fun toJson(writer: JsonWriter, value: GReaderItemIdsPage?) {
        // not useful here
    }

    @SuppressLint("CheckResult")
    override fun fromJson(reader: JsonReader): GReaderItemIdsPage = with(reader) {
        val ids = arrayListOf<Long>()
        var continuation: String? = null
        var itemRefsWereSent = false

        return try {
            beginObject()
            while (hasNext()) {
                when (nextName()) {
                    "itemRefs" -> {
                        itemRefsWereSent = true
                        beginArray()

                        while (hasNext()) {
                            beginObject()
                            var id: Long? = null

                            // a reference may carry more than its id, and the
                            // rest is of no use here
                            while (hasNext()) {
                                when (nextName()) {
                                    "id" -> id = ArticleIds.fromDecimal(nextNonEmptyString())

                                    else -> skipValue()
                                }
                            }

                            endObject()
                            ids += id ?: throw ParseException("An item reference carries no id")
                        }

                        endArray()
                    }

                    "continuation" -> continuation = nextString()
                    else -> skipValue()
                }
            }

            endObject()

            if (!itemRefsWereSent) {
                throw ParseException("A page of stream/items/ids carries no itemRefs")
            }

            GReaderItemIdsPage(ids, continuation)
        } catch (e: Exception) {
            throw ParseException("GReader items ids parsing failure", e)
        }
    }
}
