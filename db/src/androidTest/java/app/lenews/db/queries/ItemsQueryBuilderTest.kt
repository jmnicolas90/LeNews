package app.lenews.db.queries

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lenews.db.Database
import app.lenews.db.filters.MainFilter
import app.lenews.db.filters.OrderField
import app.lenews.db.filters.OrderType
import app.lenews.db.filters.QueryFilters
import app.lenews.db.filters.SubFilter
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ItemsQueryBuilderTest {

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
    fun noFilterDefaultSortCaseTest() {
        val query = ItemsQueryBuilder.buildItemsQuery(QueryFilters())

        database.query(query)

        with(query.sql) {
            assertTrue(contains("pub_date DESC"))

            assertFalse(contains("Article.read = 0"))
        }
    }

    /**
     * The article table has to be the outer loop, or the timeline sorts the
     * whole store on every page. `CROSS JOIN` is what says so to SQLite.
     */
    @Test
    fun theArticleTableDrivesTheJoin() {
        val query = ItemsQueryBuilder.buildItemsQuery(QueryFilters())

        assertTrue(query.sql.contains("Article CROSS JOIN Feed"))
    }

    @Test
    fun feedFilterCaseTest() {
        val queryFilters = QueryFilters(
            subFilter = SubFilter.FEED,
            feedId = 15
        )

        val query = ItemsQueryBuilder.buildItemsQuery(queryFilters)
        database.query(query)

        assertTrue(query.sql.contains("Article.feed_id = 15"))
    }

    @Test
    fun starsFilterCaseTest() {
        val queryFilters = QueryFilters(mainFilter = MainFilter.STARS)

        val query = ItemsQueryBuilder.buildItemsQuery(queryFilters)
        database.query(query)

        assertTrue(query.sql.contains("Article.starred = 1"))
    }

    @Test
    fun newFilterCaseTest() {
        val queryFilters = QueryFilters(mainFilter = MainFilter.NEW)

        val query = ItemsQueryBuilder.buildItemsQuery(queryFilters)
        database.query(query)

        assertTrue(query.sql.contains(WITHIN_LAST_24_HOURS))
    }

    @Test
    fun folderFilterCaseTest() {
        val queryFilters = QueryFilters(subFilter = SubFilter.FOLDER, folderId = 1)

        val query = ItemsQueryBuilder.buildItemsQuery(queryFilters)
        database.query(query)

        assertTrue(query.sql.contains("Feed.folder_id = ${queryFilters.folderId}"))
    }

    @Test
    fun oldestSortCaseTest() {
        val queryFilters = QueryFilters(
            orderType = OrderType.ASC,
            orderField = OrderField.DATE,
            showReadItems = false
        )

        val query = ItemsQueryBuilder.buildItemsQuery(queryFilters)
        database.query(query)

        with(query.sql) {
            assertTrue(contains("Article.read = 0"))
            assertTrue(contains("pub_date ASC"))
        }
    }

    @Test
    fun newestSortCaseTest() {
        val queryFilters = QueryFilters(
            orderType = OrderType.DESC,
            orderField = OrderField.ID
        )

        val query = ItemsQueryBuilder.buildItemsQuery(queryFilters)
        database.query(query)

        with(query.sql) {
            assertTrue(contains("Article.id DESC"))
        }
    }

    /** Read and starred state comes from the article row, with no join for it. */
    @Test
    fun stateComesFromTheArticleRow() {
        val queryFilters = QueryFilters(
            showReadItems = false,
            mainFilter = MainFilter.STARS
        )

        val query = ItemsQueryBuilder.buildItemsQuery(queryFilters)
        database.query(query)

        with(query.sql) {
            assertTrue(contains("read AS is_read"))
            assertTrue(contains("starred AS is_starred"))
            assertFalse(contains("ItemState"))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun filterFeedIdExceptionTest() {
        val queryFilters = QueryFilters(subFilter = SubFilter.FEED)
        ItemsQueryBuilder.buildItemsQuery(queryFilters)
    }

    @Test
    fun folderFilterExceptionTest() {
        val queryFilters = QueryFilters(subFilter = SubFilter.FOLDER)
        assertThrows(IllegalArgumentException::class.java) {
            ItemsQueryBuilder.buildItemsQuery(queryFilters)
        }
    }
}
