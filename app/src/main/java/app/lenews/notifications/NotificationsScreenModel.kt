package app.lenews.notifications

import androidx.core.app.NotificationManagerCompat
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import app.lenews.util.Preferences
import app.lenews.db.Database
import app.lenews.db.entities.account.Account
import app.lenews.db.pojo.FeedWithFolder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class NotificationsScreenModel(
    private val account: Account,
    private val database: Database,
    private val preferences: Preferences,
    private val notificationManager: NotificationManagerCompat,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : StateScreenModel<NotificationsState>(
    NotificationsState(
        areNotificationsEnabled = notificationManager.areNotificationsEnabled(),
        areAccountNotificationsEnabled = account.isNotificationsEnabled
    )
) {

    init {
        screenModelScope.launch(dispatcher) {
            database.accountDao().selectAccountNotificationsState()
                .collect { isNotificationsEnabled ->
                    mutableState.update { it.copy(areAccountNotificationsEnabled = isNotificationsEnabled) }
                }
        }

        screenModelScope.launch(dispatcher) {
            database.feedDao().selectFeedsWithFolderName()
                .collect { feedsWithFolder ->
                    mutableState.update {
                        it.copy(
                            feedsWithFolderState = FeedsWithFolderState.Loaded(feedsWithFolder)
                        )
                    }
                }
        }

        screenModelScope.launch(dispatcher) {
            preferences.backgroundSynchronization.flow
                .collect { sync ->
                    mutableState.update { it.copy(isBackGroundSyncEnabled = sync != "manual") }
                }
        }
    }

    fun setAccountNotificationsState(enabled: Boolean) {
        screenModelScope.launch(dispatcher) {
            database.accountDao().updateNotificationState(enabled)
        }
    }

    fun setFeedNotificationsState(feedId: Int, enabled: Boolean) {
        screenModelScope.launch(dispatcher) {
            database.feedDao().updateFeedNotificationState(feedId, enabled)
        }
    }

    fun setAllFeedsNotificationsState(enabled: Boolean) {
        screenModelScope.launch(dispatcher) {
            database.feedDao().updateAllFeedsNotificationState(enabled)
        }
    }

    fun setBackgroundSyncDialogState(visible: Boolean) {
        when {
            visible && !state.value.isBackGroundSyncEnabled -> {
                mutableState.update { it.copy(showBackgroundSyncDialog = visible) }
            }
            !visible -> {
                mutableState.update { it.copy(showBackgroundSyncDialog = visible) }
            }
        }
    }

    fun refreshNotificationManager() {
        mutableState.update { it.copy(areNotificationsEnabled = notificationManager.areNotificationsEnabled()) }
    }
}

data class NotificationsState(
    val areAccountNotificationsEnabled: Boolean = false,
    val feedsWithFolderState: FeedsWithFolderState = FeedsWithFolderState.Loading,
    val showBackgroundSyncDialog: Boolean = false,
    val isBackGroundSyncEnabled: Boolean = false,
    val areNotificationsEnabled: Boolean = false
)

sealed class FeedsWithFolderState {
    data object Loading : FeedsWithFolderState()

    data class Loaded(val feedsWithFolder: List<FeedWithFolder>) : FeedsWithFolderState() {

        val allFeedNotificationsEnabled
            get() = feedsWithFolder.none { !it.feed.isNotificationEnabled }
    }
}