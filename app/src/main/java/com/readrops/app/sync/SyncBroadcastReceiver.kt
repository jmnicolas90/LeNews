package com.readrops.app.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.readrops.app.repositories.BaseRepository
import com.readrops.db.Database
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
 * Both go through the account's repository rather than writing the Item row
 * directly. A FreshRSS account keeps its read and starred state in its own table,
 * ItemState, which is what the timeline reads, and records the change in
 * ItemStateChange so the next sync uploads it. Writing Item.read would change a
 * column nothing reads and send nothing to the server.
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

        val itemId = intent.getIntExtra(SyncWorker.ITEM_ID_KEY, -1)
        val accountId = intent.getIntExtra(SyncWorker.ACCOUNT_ID_KEY, -1)
        if (itemId < 0 || accountId < 0) {
            return
        }

        // the work outlives onReceive, so the system is asked to keep the process
        // alive until it is done
        val pendingResult = goAsync()

        GlobalScope.launch {
            try {
                val account = database.accountDao().select(accountId)
                val repository = get<BaseRepository> { parametersOf(account) }
                val item = database.itemDao().select(itemId)

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
