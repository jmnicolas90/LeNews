package app.lenews.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.lenews.db.entities.account.Account
import kotlinx.coroutines.flow.Flow

/**
 * The one account row. Every query is keyed on [Account.ACCOUNT_ID], which is
 * spelled out because a Room query string cannot hold a constant.
 */
@Dao
interface AccountDao : BaseDao<Account> {

    /** Writes the account, replacing the row if there already is one. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: Account)

    @Query("Select * From Account Where id = 1")
    suspend fun select(): Account?

    @Query("Select * From Account Where id = 1")
    fun selectAccount(): Flow<Account?>

    @Query("Select Count(*) From Account")
    suspend fun selectAccountCount(): Int

    @Query("Delete From Account")
    suspend fun deleteAllAccounts()

    @Query("Update Account set cursor = :cursor Where id = 1")
    suspend fun updateCursor(cursor: Long)

    @Query("Update Account set notifications_enabled = :enabled Where id = 1")
    suspend fun updateNotificationState(enabled: Boolean)

    @Query("Select notifications_enabled From Account Where id = 1")
    fun selectAccountNotificationsState(): Flow<Boolean>

    @Query("Update Account set name = :name Where id = 1")
    suspend fun renameAccount(name: String)
}
