package app.lenews.api.services.greader.adapters

import app.lenews.api.TestUtils
import app.lenews.db.entities.Item
import app.lenews.db.util.DateUtils
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNotNull
import okio.Buffer
import org.junit.Test

class GReaderItemsAdapterTest {

    private val adapter = Moshi.Builder()
            .add(Types.newParameterizedType(List::class.java, Item::class.java), GReaderItemsAdapter())
            .build()
            .adapter<List<Item>>(Types.newParameterizedType(List::class.java, Item::class.java))

    @Test
    fun validItemsTest() {
        val stream = TestUtils.loadResource("services/greader/adapters/items.json")

        val items = adapter.fromJson(Buffer().readFrom(stream))!!

        with(items.first()) {
            // the long form the server sent, as the number the store keys on
            assertEquals(1625234531559678L, id)
            assertEquals(title, "GNOME’s Default Theme is Getting a Revamp")
            assertNotNull(content)
            assertEquals(link, "http://feedproxy.google.com/~r/d0od/~3/4Zk-fncSuek/adwaita-borderless-theme-in-development-gnome-41")
            assertEquals(author, "Joey Sneddon")
            assertEquals(pubDate, DateUtils.fromEpochSeconds(1625234040))
            assertEquals(isRead, false)
            assertEquals(isStarred, false)
        }

        with(items[1]) {
            assertEquals(isRead, true)
            assertEquals(isStarred, true)
        }
    }

}