package app.lenews.sync

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.Action
import androidx.core.app.NotificationCompat.Builder
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.lenews.MainActivity
import app.lenews.R
import app.lenews.LeNewsApp
import app.lenews.util.accounterror.GReaderError
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit


class SyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params), KoinComponent {

    private val notificationManager by inject<NotificationManagerCompat>()

    override suspend fun doWork(): Result {
        val isManual = tags.contains(WORK_MANUAL)

        val infos = WorkManager.getInstance(applicationContext)
            .getWorkInfosByTagFlow(TAG).first()

        if (infos.any { it.state == WorkInfo.State.RUNNING && it.id != id }) {
            return if (isManual) {
                Result.failure(
                    failureData(
                        applicationContext.getString(R.string.background_sync_already_running)
                    )
                )
            } else {
                Result.retry()
            }
        }

        val notificationBuilder = Builder(applicationContext, LeNewsApp.SYNC_CHANNEL_ID)
            .setProgress(0, 0, true)
            .setSmallIcon(R.drawable.ic_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        return try {
            val synchronizer = get<Synchronizer>()

            val outcome = synchronizer.synchronize(notificationBuilder)

            notificationManager.cancel(SYNC_NOTIFICATION_ID)

            if (!isManual) {
                displaySyncResult(outcome)
            }

            return Result.success(workDataOf(END_SYNC_KEY to true))
        } catch (e: Exception) {
            Log.e(TAG, "Synchronization failed", e)

            notificationManager.cancel(SYNC_NOTIFICATION_ID)
            if (isManual) {
                Result.failure(failureData(GReaderError(applicationContext).genericMessage(e)))
            } else {
                Result.failure()
            }
        }
    }

    /**
     * What a failed sync tells whoever is watching the work.
     *
     * It goes in the output `Data`, which is what WorkManager delivers — there
     * is no state shared between the worker and the screen, so a second sync
     * running at the same time cannot overwrite this one's answer. The message
     * is bounded because `Data` is: see [SyncFailureMessage].
     */
    private fun failureData(message: String) =
        SyncFailureMessage.failureData(message) { droppedCharacters ->
            applicationContext.getString(R.string.sync_failure_message_cut, droppedCharacters)
        }

    /**
     * The new articles notification, or the removal of the last one.
     *
     * A sync with nothing new to say takes down the notification the previous
     * one posted, because retention may have dropped the very article that one
     * names and a notification about an article the store no longer holds can
     * only disappoint whoever taps it. It cancels on every sync that posts
     * nothing new rather than only when this sync deleted that article: one
     * line instead of carrying the previous notification's id around, and a
     * notification about articles a sync ago is stale anyway.
     */
    private suspend fun displaySyncResult(outcome: SyncOutcome) {
        val notificationContent = get<SyncAnalyzer>()
            .getNotificationContent(outcome.account, outcome.syncResult)

        if (notificationContent != null) {
            val intent = Intent(applicationContext, MainActivity::class.java).apply {
                putExtra(FROM_SYNC_KEY, true)

                if (notificationContent.item != null) {
                    putExtra(ITEM_ID_KEY, notificationContent.item.id)
                }
            }

            val notificationBuilder = Builder(applicationContext, LeNewsApp.SYNC_CHANNEL_ID)
                .setContentTitle(notificationContent.title)
                .setContentText(notificationContent.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(notificationContent.text))
                .setSmallIcon(R.drawable.ic_notifications)
                .setColor(notificationContent.color)
                .setContentIntent(
                    PendingIntent.getActivity(
                        applicationContext,
                        0,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .setAutoCancel(true)

            notificationContent.item?.let { item ->
                notificationBuilder
                    .addAction(getMarkReadAction(item.id))
                    .addAction(getMarkFavoriteAction(item.id))
            }

            notificationContent.largeIcon?.let { notificationBuilder.setLargeIcon(it) }

            if (notificationManager.areNotificationsEnabled()) {
                notificationManager.notify(SYNC_RESULT_NOTIFICATION_ID, notificationBuilder.build())
            }
        } else {
            notificationManager.cancel(SYNC_RESULT_NOTIFICATION_ID)
        }
    }

    private fun getMarkReadAction(itemId: Long): Action {
        val intent = Intent(applicationContext, SyncBroadcastReceiver::class.java).apply {
            action = SyncBroadcastReceiver.ACTION_MARK_READ
            putExtra(ITEM_ID_KEY, itemId)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Action.Builder(
            R.drawable.ic_done_all,
            applicationContext.getString(R.string.mark_read),
            pendingIntent
        )
            .setAllowGeneratedReplies(false)
            .build()
    }

    private fun getMarkFavoriteAction(itemId: Long): Action {
        val intent = Intent(applicationContext, SyncBroadcastReceiver::class.java).apply {
            action = SyncBroadcastReceiver.ACTION_SET_FAVORITE
            putExtra(ITEM_ID_KEY, itemId)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Action.Builder(
            R.drawable.ic_favorite_border,
            applicationContext.getString(R.string.add_to_favorite),
            pendingIntent
        )
            .setAllowGeneratedReplies(false)
            .build()
    }

    companion object {
        val TAG: String = SyncWorker::class.java.simpleName

        val WORK_AUTO = "$TAG-auto"
        val WORK_MANUAL = "$TAG-manual"

        const val SYNC_NOTIFICATION_ID = 2
        const val SYNC_RESULT_NOTIFICATION_ID = 3

        const val END_SYNC_KEY = "END_SYNC"
        const val SYNC_FAILURE_KEY = "SYNC_FAILURE"

        /**
         * What went wrong, already turned into the sentence the screen shows,
         * and cut to length if it was long (see [SyncFailureMessage]).
         *
         * A String rather than an exception because `Data` carries only
         * primitives, and the exception has to be read where it was caught
         * anyway to be turned into something a reader can act on.
         *
         * These three keys are the whole of what a sync reports. There is no
         * per-feed failure among them because a sync has none to report: it
         * pulls every feed in the same calls and writes them in one
         * transaction, so it succeeds whole or fails whole. The one place this
         * app does collect a failure per feed is adding feeds
         * (`Repository.insertNewFeeds`), which runs in the screen that asked
         * for it and never goes near WorkManager.
         */
        const val SYNC_FAILURE_MESSAGE_KEY = "SYNC_FAILURE_MESSAGE"


        /** Marks the intent a sync notification carries, so the app knows to open the timeline. */
        const val FROM_SYNC_KEY = "FROM_SYNC"
        const val ITEM_ID_KEY = "ITEM_ID"

        suspend fun startNow(context: Context, onUpdate: (WorkInfo) -> Unit) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .addTag(TAG)
                .addTag(WORK_MANUAL)
                .build()

            WorkManager.getInstance(context).apply {
                enqueueUniqueWork(WORK_MANUAL, ExistingWorkPolicy.KEEP, request)
                getWorkInfoByIdFlow(request.id)
                    .collect { workInfo ->
                        if (workInfo != null) {
                            onUpdate(workInfo)
                        }
                    }
            }
        }

        fun startPeriodically(context: Context, period: String) {
            val workManager = WorkManager.getInstance(context)

            val interval = when (period) {
                "0.30" -> 30L to TimeUnit.MINUTES
                "1" -> 1L to TimeUnit.HOURS
                "2" -> 2L to TimeUnit.HOURS
                "3" -> 3L to TimeUnit.HOURS
                "6" -> 6L to TimeUnit.HOURS
                "12" -> 12L to TimeUnit.HOURS
                "24" -> 1L to TimeUnit.DAYS
                else -> null
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            if (interval != null) {
                val request = PeriodicWorkRequest.Builder(
                    SyncWorker::class.java,
                    interval.first,
                    interval.second
                )
                    .addTag(TAG)
                    .addTag(WORK_AUTO)
                    .setConstraints(constraints)
                    .setInitialDelay(interval.first, interval.second)
                    .setBackoffCriteria(BackoffPolicy.LINEAR, interval.first, interval.second)
                    .build()

                workManager.enqueueUniquePeriodicWork(
                    WORK_AUTO,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request
                )
            } else {
                workManager.cancelAllWorkByTag(WORK_AUTO)
            }
        }
    }
}