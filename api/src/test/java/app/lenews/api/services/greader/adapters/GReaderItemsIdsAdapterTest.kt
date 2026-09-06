package app.lenews.api.services.greader.adapters

import app.lenews.api.utils.exceptions.ParseException
import com.squareup.moshi.Moshi
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import okio.Buffer
import org.junit.Test
import kotlin.test.assertFailsWith

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

    /**
     * A page with no `itemRefs` at all is not an empty page: the server was
     * meant to send the list and sent something else. Reading it as "no ids"
     * would tell the mirror rule the server holds nothing, and retention would
     * drop every unread article the answer left out.
     */
    @Test
    fun anAnswerWithoutItemRefsFailsTest() {
        assertFailsWith<ParseException> { adapter.fromJson("{}") }
    }

    /** The same for an error object, which is what an HTTP 200 error looks like. */
    @Test
    fun anErrorObjectFailsTest() {
        assertFailsWith<ParseException> {
            adapter.fromJson("""{ "errors": [ "Unauthorized" ] }""")
        }
    }

    /** A reference with no id is a reference to nothing, so the page is broken. */
    @Test
    fun aReferenceWithoutAnIdFailsTest() {
        assertFailsWith<ParseException> {
            adapter.fromJson("""{ "itemRefs": [ { "directStreamIds": [ "feed/2" ] } ] }""")
        }
    }

    /**
     * An explicitly empty list is a real answer, and the only one that means
     * the stream holds nothing.
     */
    @Test
    fun anExplicitlyEmptyListIsAnEmptyPageTest() {
        val page = adapter.fromJson("""{ "itemRefs": [] }""")!!

        assertTrue(page.ids.isEmpty())
        assertNull(page.continuation)
    }

    /** A reference may carry more than its id, and the rest is skipped. */
    @Test
    fun aReferenceCarryingMoreThanItsIdIsReadTest() {
        val page = adapter.fromJson(
            """{ "itemRefs": [ { "id": "7", "directStreamIds": [ "feed/2" ] } ] }"""
        )!!

        assertEquals(listOf(7L), page.ids)
    }
}
