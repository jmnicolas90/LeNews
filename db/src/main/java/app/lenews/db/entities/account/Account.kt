package app.lenews.db.entities.account

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import java.io.Serializable

/**
 * The one FreshRSS account this app talks to. The table holds a single row,
 * whose primary key is always [ACCOUNT_ID]: there is one account, so nothing
 * else carries an account id and there is no "current account" to pick.
 *
 * The login and the password are not columns: they live in the encrypted
 * preferences, under [LOGIN_KEY] and [PASSWORD_KEY].
 */
@Entity
data class Account(
    @PrimaryKey var id: Int = ACCOUNT_ID,
    var url: String? = null,
    @ColumnInfo(name = "name") var name: String? = null,
    @ColumnInfo(name = "displayed_name") var displayedName: String? = null,
    /** The moment of the last successful sync, in Unix seconds. Zero means none yet. */
    @ColumnInfo(name = "cursor") var cursor: Long = 0,
    var token: String? = null,
    @ColumnInfo(name = "write_token") var writeToken: String? = null,
    @ColumnInfo(name = "notifications_enabled") var isNotificationsEnabled: Boolean = false,
    @Ignore var login: String? = null,
    @Ignore var password: String? = null,
) : Serializable {

    val config: AccountConfig
        get() = AccountConfig.FRESHRSS

    companion object {
        /** The primary key of the one account row. */
        const val ACCOUNT_ID = 1

        const val LOGIN_KEY = "freshrss_login"
        const val PASSWORD_KEY = "freshrss_password"
    }
}
