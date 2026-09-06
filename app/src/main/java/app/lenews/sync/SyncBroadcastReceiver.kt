package app.lenews.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import app.lenews.repositories.BaseRepository
import app.lenews.util.ApplicationScope
import app.lenews.db.Database
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import org.koin.core.parameter.parametersOf


/**
 * The mark read and star actions carried by the new articles notification.
 *
 * Both go through the repository rather than writing the article row directly,
 * because becoming read is two writes: the state and its date on the article,
 * and a pending change so the next sync tells FreshRSS. Writing the row alone
 * would leave the server none the wiser.
 *
 * The write outlives `onReceive`, which is why there are two things holding it
 * up. [goAsync] asks the system to keep the process alive until the work says
 * it is done, and [ApplicationScope] — the application's own scope, the same
 * one the reading screen writes through — is what it runs in. Neither replaces
 * the other: the scope keeps the coroutine from being cancelled, the pending
 * result keeps the process from being killed under it.
 */
class SyncBroadcastReceiver : BroadcastReceiver(), KoinComponent {

    private val notificationManager by inject<NotificationManagerCompat>()
    private val database by inject<Database>()
    private val applicationScope by inject<ApplicationScope>()

    override fun onReceive(context: Context, intent: Intent) {
        notificationManager.cancel(SyncWorker.SYNC_RESULT_NOTIFICATION_ID)

        val action = intent.action
        if (action != ACTION_MARK_READ && action != ACTION_SET_FAVORITE) {
            return
        }

        val itemId = intent.getLongExtra(SyncWorker.ITEM_ID_KEY, -1L)
        if (itemId < 0) {
            return
        }

        val pendingResult = goAsync()

        applicationScope.launch {
            try {
                val account = database.accountDao().select() ?: return@launch
                val repository = get<BaseRepository> { parametersOf(account) }

                // the article can have been dropped by the retention of a sync
                // that ran after the notification was posted; the notification
                // is cancelled above, and the action has nothing left to act on
                val item = database.itemDao().select(itemId) ?: return@launch

                when (action) {
                    ACTION_MARK_READ -> repository.setItemReadState(item.apply { isRead = true })
                    ACTION_SET_FAVORITE ->
                        repository.setItemStarState(item.apply { isStarred = true })
                }
            } catch (error: Exception) {
                // there is no screen left to tell, and the notification this
                // action came from is already gone, so the log is the only
                // place this can be said
                Log.e(TAG, "$action failed for article $itemId", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private val TAG: String = SyncBroadcastReceiver::class.java.simpleName

        const val ACTION_MARK_READ = "ACTION_MARK_READ"
        const val ACTION_SET_FAVORITE = "ACTION_SET_FAVORITE"
    }
}
