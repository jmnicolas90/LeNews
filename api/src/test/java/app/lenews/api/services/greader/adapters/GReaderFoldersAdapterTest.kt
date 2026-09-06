package app.lenews.api.services.greader.adapters

import app.lenews.api.TestUtils
import com.squareup.moshi.Moshi
import junit.framework.TestCase.assertEquals
import okio.Buffer
import org.junit.Test

class GReaderFoldersAdapterTest {

    private val adapter = Moshi.Builder()
        .add(GReaderFoldersAdapter())
        .build()
        .adapter(GReaderFolders::class.java)

    @Test
    fun validFoldersTest() {
        val stream = TestUtils.loadResource("services/greader/adapters/folders.json")

        val (folders) = adapter.fromJson(Buffer().readFrom(stream))!!

        assertEquals(folders.size, 1)

        with(folders.first()) {
            assertEquals(name, "Blogs")
            assertEquals(remoteId, "user/-/label/Blogs")
        }
    }
}
