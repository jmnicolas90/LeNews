package app.lenews.db.queries

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.lenews.db.Database
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ItemSelectionQueryBuilderTest {

    private lateinit var database: Database

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, Database::class.java).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun defaultCaseTest() {
        val query = ItemSelectionQueryBuilder.buildQuery(10)
        database.query(query)

        with(query.sql) {
            assertTrue(contains("Article.id = 10"))
            assertTrue(contains("read AS is_read"))
            assertTrue(contains("starred AS is_starred"))
            assertFalse(contains("ItemState"))
        }
    }
}
