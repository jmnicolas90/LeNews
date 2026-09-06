package app.lenews.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import app.lenews.db.dao.AccountDao
import app.lenews.db.dao.FeedDao
import app.lenews.db.dao.FolderDao
import app.lenews.db.dao.HorizonDroppedDao
import app.lenews.db.dao.ItemDao
import app.lenews.db.dao.PendingChangeDao
import app.lenews.db.entities.Feed
import app.lenews.db.entities.Folder
import app.lenews.db.entities.HorizonDropped
import app.lenews.db.entities.Item
import app.lenews.db.entities.PendingChange
import app.lenews.db.entities.account.Account
import app.lenews.db.util.Converters

@Database(
    entities = [
        Feed::class,
        Item::class,
        Folder::class,
        Account::class,
        PendingChange::class,
        HorizonDropped::class
    ],
    version = 1
)
@TypeConverters(Converters::class)
abstract class Database : RoomDatabase() {

    abstract fun feedDao(): FeedDao

    abstract fun itemDao(): ItemDao

    abstract fun accountDao(): AccountDao

    abstract fun folderDao(): FolderDao

    abstract fun pendingChangeDao(): PendingChangeDao

    abstract fun horizonDroppedDao(): HorizonDroppedDao

    /**
     * Runs `PRAGMA optimize`, which the article store model asks for at the end
     * of every sync transaction and once when the database is created.
     *
     * It goes through the support database rather than through a DAO because
     * Room's `@Query` takes statements, not pragmas. Called from inside a
     * transaction it runs in that transaction, on the same connection.
     */
    fun optimize() {
        openHelper.writableDatabase.execSQL("PRAGMA optimize")
    }
}

/**
 * Gives the query planner its statistics once, when the database is created.
 * `PRAGMA optimize` runs `ANALYZE` on the tables whose statistics are stale and
 * nothing otherwise. Without it the planner works on default estimates, which is
 * what made an index change nothing at all in the ticket 11 measurements.
 */
object AnalyzeOnCreate : RoomDatabase.Callback() {

    override fun onCreate(db: SupportSQLiteDatabase) {
        db.execSQL("PRAGMA optimize")
    }
}
