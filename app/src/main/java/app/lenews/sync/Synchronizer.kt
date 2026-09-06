package app.lenews.sync

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.app.NotificationCompat.Builder
import androidx.core.app.NotificationManagerCompat
import app.lenews.api.services.Credentials
import app.lenews.api.utils.AuthInterceptor
import app.lenews.R
import app.lenews.repositories.BaseRepository
import app.lenews.repositories.SyncResult
import app.lenews.sync.SyncWorker.Companion.SYNC_NOTIFICATION_ID
import app.lenews.util.FeedColors
import app.lenews.db.Database
import app.lenews.db.entities.account.Account
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.parameter.parametersOf

/** One account and what one synchronization of it brought back. */
data class SyncOutcome(
    val account: Account,
    val syncResult: SyncResult
)

class Synchronizer(
    private val notificationManager: NotificationManagerCompat,
    private val database: Database,
    private val context: Context,
    private val encryptedPreferences: SharedPreferences,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : KoinComponent {

    /**
     * Synchronizes the one account, and returns it with what the sync brought
     * back so the caller can build the notification.
     */
    suspend fun synchronize(notificationBuilder: Builder): SyncOutcome {
        val account = checkNotNull(database.accountDao().select()) {
            "There is no account to synchronize"
        }

        account.login = encryptedPreferences.getString(Account.LOGIN_KEY, null)
        account.password = encryptedPreferences.getString(Account.PASSWORD_KEY, null)

        val repository = get<BaseRepository> { parametersOf(account) }

        notificationBuilder.setContentTitle(
            context.resources.getString(
                R.string.updating_account,
                account.name
            )
        )

        if (notificationManager.areNotificationsEnabled()) {
            notificationManager.notify(SYNC_NOTIFICATION_ID, notificationBuilder.build())
        }

        get<AuthInterceptor>().credentials = Credentials.toCredentials(account)
        val syncResult = repository.synchronize()

        fetchFeedColors(syncResult, notificationBuilder)

        return SyncOutcome(account, syncResult)
    }

    private suspend fun fetchFeedColors(
        syncResult: SyncResult,
        notificationBuilder: Builder
    ) = withContext(dispatcher) {
        notificationBuilder.setContentTitle(context.getString(R.string.get_feeds_colors))

        var index = 0
        syncResult.feeds.chunked(MAX_PARALLEL_REQUESTS)
            .map {
                it.map { feed ->
                    async {
                        notificationBuilder.setProgress(syncResult.feeds.size, ++index, false)

                        if (notificationManager.areNotificationsEnabled()) {
                            notificationManager.notify(
                                SYNC_NOTIFICATION_ID,
                                notificationBuilder.build()
                            )
                        }

                        try {
                            if (feed.iconUrl != null) {
                                val color = FeedColors.getFeedColor(feed.iconUrl!!)
                                database.feedDao().updateFeedColor(feed.id, color)
                            }

                            Unit
                        } catch (e: Exception) {
                            Log.e(TAG, "${feed.name}: ${e.message}")
                        }
                    }
                }
                    .awaitAll()
            }
    }

    companion object {
        private val TAG = Synchronizer::class.java.simpleName

        private const val MAX_PARALLEL_REQUESTS = 30
    }
}