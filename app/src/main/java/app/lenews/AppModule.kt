package app.lenews

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.lenews.api.services.Credentials
import app.lenews.account.AccountScreenModel
import app.lenews.account.credentials.AccountCredentialsScreenMode
import app.lenews.account.credentials.AccountCredentialsScreenModel
import app.lenews.feeds.FeedScreenModel
import app.lenews.feeds.color.FeedColorScreenModel
import app.lenews.feeds.newfeed.NewFeedScreenModel
import app.lenews.item.ItemScreenModel
import app.lenews.more.preferences.PreferencesScreenModel
import app.lenews.notifications.NotificationsScreenModel
import app.lenews.repositories.BaseRepository
import app.lenews.repositories.GReaderRepository
import app.lenews.repositories.GetFoldersWithFeeds
import app.lenews.sync.SyncAnalyzer
import app.lenews.sync.Synchronizer
import app.lenews.timelime.TimelineScreenModel
import app.lenews.util.ApplicationScope
import app.lenews.util.DataStorePreferences
import app.lenews.util.Preferences
import app.lenews.db.entities.Feed
import app.lenews.db.entities.account.Account
import app.lenews.db.filters.QueryFilters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module

val appModule = module {

    factory { TimelineScreenModel(get(), get(), get(), androidContext()) }

    factory { FeedScreenModel(get(), get(), androidContext()) }

    factory { (url: String?) -> NewFeedScreenModel(get(), androidContext(), url) }

    factory { AccountScreenModel(get(), androidContext()) }

    factory { (itemId: Long, itemIndex: Int, queryFilters: QueryFilters) ->
        ItemScreenModel(
            itemId = itemId,
            itemIndex = itemIndex,
            queryFilters = queryFilters,
            database = get(),
            preferences = get(),
            applicationScope = get()
        )
    }

    factory { (accountType: Account, mode: AccountCredentialsScreenMode) ->
        AccountCredentialsScreenModel(accountType, mode, get(), context = androidContext())
    }

    factory { (account: Account) -> NotificationsScreenModel(account, get(), get(), get()) }

    factory { PreferencesScreenModel(get(), get(), get()) }

    factory { (feed: Feed) -> FeedColorScreenModel(feed, get()) }

    single { GetFoldersWithFeeds(get()) }

    // One per process, and never cancelled: it carries the writes that have to
    // finish after the screen that started them is gone. See ApplicationScope.
    single { ApplicationScope() }

    factory<BaseRepository> { (account: Account) ->
        GReaderRepository(
            database = get(),
            account = account,
            dataSource = get(parameters = { parametersOf(Credentials.toCredentials(account)) })
        )
    }

    single {
        val masterKey = MasterKey.Builder(androidContext())
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            androidContext(),
            "account_credentials",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    single {
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler(
                produceNewData = { emptyPreferences() }
            ),
            migrations = listOf(SharedPreferencesMigration(get(), "settings")),
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { get<Context>().preferencesDataStoreFile("settings") }
        )
    }

    single { DataStorePreferences(get()) }

    single { Preferences(get()) }

    single { NotificationManagerCompat.from(get()) }

    single { Synchronizer(get(), get(), get(), get()) }

    single { SyncAnalyzer(get(), get()) }
}