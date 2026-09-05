package app.lenews.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import app.lenews.db.entities.TagJoin

@Dao
interface TagJoinDao : BaseDao<TagJoin> {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertConflict(tagJoins: List<TagJoin>)
}