package app.lenews.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lenews.db.Database
import app.lenews.db.entities.Feed
import app.lenews.db.entities.Folder
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FeedDaoTest {

    private lateinit var database: Database

    @Before
    fun before() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, Database::class.java).build()

        repeat(2) { time ->
            database.folderDao().insert(
                Folder(
                    name = "Folder $time",
                    remoteId = "folder_$time"
                )
            )
        }

        repeat(3) { time ->
            database.feedDao().insert(
                Feed(
                    name = "Feed $time",
                    remoteId = "feed_$time",
                    remoteFolderId = "folder_${if (time % 2 == 0) 0 else 1}"
                )
            )
        }
    }

    @After
    fun after() {
        database.close()
    }

    @Test
    fun upsertFeedsTest() = runTest {
        val newFeeds = listOf(
            // updated feed (name + folder to null)
            Feed(
                name = "New Feed 0",
                remoteId = "feed_0",
                remoteFolderId = null
            ),

            // deleted feed
            /*Feed(
                name = "Feed 1",
                remoteId = "feed_1",
                remoteFolderId = "folder_1"
            ),*/

            // updated feed (folder change)
            Feed(
                name = "Feed 2",
                remoteId = "feed_2",
                remoteFolderId = "folder_1"
            ),

            // inserted feed
            Feed(
                name = "Feed 3",
                remoteId = "feed_3",
                remoteFolderId = "folder_0"
            ),
        )

        database.feedDao().upsertFeeds(newFeeds)
        val allFeeds = database.feedDao().selectFeeds()

        assertTrue(allFeeds.any { it.name == "New Feed 0" && it.folderId == null })
        assertTrue(allFeeds.any { it.remoteId == "feed_2" && it.folderId == 2 })

        assertFalse(allFeeds.any { it.remoteId == "feed_1" })
        assertTrue(allFeeds.any { it.remoteId == "feed_3" && it.folderId == 1 })
    }
}