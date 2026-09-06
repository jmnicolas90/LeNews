package app.lenews.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import app.lenews.repositories.BaseRepository
import app.lenews.db.Database
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
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
 */
class SyncBroadcastReceiver : BroadcastReceiver(), KoinComponent {

    private val notificationManager by inject<NotificationManagerCompat>()
    private val database by inject<Database>()

    @OptIn(DelicateCoroutinesApi::class)
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

        // the work outlives onReceive, so the system is asked to keep the process
        // alive until it is done
        val pendingResult = goAsync()

        GlobalScope.launch {
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
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_MARK_READ = "ACTION_MARK_READ"
        const val ACTION_SET_FAVORITE = "ACTION_SET_FAVORITE"
    }
}
