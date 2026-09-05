package com.readrops.app.sync

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.app.NotificationCompat.Builder
import androidx.core.app.NotificationManagerCompat
import com.readrops.api.services.Credentials
import com.readrops.api.utils.AuthInterceptor
import com.readrops.app.R
import com.readrops.app.repositories.BaseRepository
import com.readrops.app.repositories.SyncResult
import com.readrops.app.sync.SyncWorker.Companion.SYNC_NOTIFICATION_ID
import com.readrops.app.util.FeedColors
import com.readrops.db.Database
import com.readrops.db.entities.account.Account
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.parameter.parametersOf

class Synchronizer(
    private val notificationManager: NotificationManagerCompat,
    private val database: Database,
    private val context: Context,
    private val encryptedPreferences: SharedPreferences,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : KoinComponent {

    suspend fun synchronizeAccounts(
        notificationBuilder: Builder,
        accountId: Int
    ): Map<Account, SyncResult> {
        val syncResults = mutableMapOf<Account, SyncResult>()

        val accounts = if (accountId == -1) {
            database.accountDao().selectAllAccounts().first()
        } else {
            listOf(database.accountDao().select(accountId))
        }

        for (account in accounts) {
            account.login = encryptedPreferences.getString(account.loginKey, null)
            account.password = encryptedPreferences.getString(account.passwordKey, null)

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

            syncResults[account] = syncResult
        }

        return syncResults
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