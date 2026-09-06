package app.lenews.api.services.greader.adapters

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import junit.framework.TestCase.assertEquals
import okio.Buffer
import org.junit.Test

class GReaderItemsIdsAdapterTest {

    private val adapter = Moshi.Builder()
            .add(Types.newParameterizedType(List::class.java, Long::class.javaObjectType), GReaderItemsIdsAdapter())
            .build()
            .adapter<List<Long>>(Types.newParameterizedType(List::class.java, Long::class.javaObjectType))

    @Test
    fun validIdsTest() {
        val stream = javaClass.classLoader!!.getResourceAsStream("services/greader/adapters/items_starred_ids.json")

        val ids = adapter.fromJson(Buffer().readFrom(stream))!!

        // the decimal form this endpoint sends, parsed to the same numbers the
        // long form of stream/contents would give
        assertEquals(ids, listOf(
                1603918802432899L,
                1603917640272612L,
                1603914602186551L,
                1603909236998803L,
                1603907200327551L
        ))
    }
}
