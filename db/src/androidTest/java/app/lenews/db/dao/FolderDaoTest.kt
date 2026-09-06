package app.lenews.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lenews.db.Database
import app.lenews.db.entities.Folder
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FolderDaoTest {

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
    }

    @After
    fun after() {
        database.close()
    }

    @Test
    fun upsertFoldersTest() = runTest {
        val remoteFolders = listOf(
            // updated folder
            Folder(name = "New Folder 0", remoteId = "folder_0"),

            // removed folder
            //Folder(name = "Folder 1", remoteId = "folder_1"),

            // new inserted Folder
            Folder(name = "Folder 2", remoteId = "folder_2")
        )

        database.folderDao().upsertFolders(remoteFolders)
        val allFolders = database.folderDao().selectFolders().first()

        assertTrue(allFolders.any { it.name == "New Folder 0" })

        assertFalse(allFolders.any { it.remoteId == "folder_1" })
        assertTrue(allFolders.any { it.remoteId == "folder_2" })
    }
}