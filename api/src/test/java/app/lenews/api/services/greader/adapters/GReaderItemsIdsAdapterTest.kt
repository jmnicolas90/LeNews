package app.lenews.api.services.greader.adapters

import com.squareup.moshi.Moshi
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNull
import okio.Buffer
import org.junit.Test

class GReaderItemsIdsAdapterTest {

    private val adapter = Moshi.Builder()
            .add(GReaderItemIdsPage::class.java, GReaderItemsIdsAdapter())
            .build()
            .adapter(GReaderItemIdsPage::class.java)

    @Test
    fun validIdsTest() {
        val stream = javaClass.classLoader!!.getResourceAsStream("services/greader/adapters/items_starred_ids.json")

        val page = adapter.fromJson(Buffer().readFrom(stream))!!

        // the decimal form this endpoint sends, parsed to the same numbers the
        // long form of stream/contents would give
        assertEquals(page.ids, listOf(
                1603918802432899L,
                1603917640272612L,
                1603914602186551L,
                1603909236998803L,
                1603907200327551L
        ))

        // the last page of a walk carries no continuation
        assertNull(page.continuation)
    }

    /**
     * The continuation is handed back untouched. FreshRSS writes it as a bare
     * number, which is not a JSON string, so it is read as text rather than
     * parsed: nothing here is meant to understand it.
     */
    @Test
    fun continuationTest() {
        val page = adapter.fromJson("""{ "itemRefs": [ { "id": "7" } ], "continuation": 42 }""")!!

        assertEquals(listOf(7L), page.ids)
        assertEquals("42", page.continuation)
    }
}
