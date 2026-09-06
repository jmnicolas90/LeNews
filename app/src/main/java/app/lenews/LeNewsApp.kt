package app.lenews

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import app.lenews.api.PLAIN_CLIENT
import app.lenews.api.apiModule
import app.lenews.util.CrashActivity
import app.lenews.db.dbModule
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.component.KoinComponent
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import org.koin.core.qualifier.named
import kotlin.system.exitProcess

open class LeNewsApp : Application(), KoinComponent, SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()

        if (!BuildConfig.DEBUG) {
            Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
                val intent = Intent(this, CrashActivity::class.java).apply {
                    putExtra(CrashActivity.THROWABLE_KEY, throwable)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }

                startActivity(intent)
                exitProcess(0)
            }
        }

        startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@LeNewsApp)

            modules(apiModule(userAgent), dbModule, appModule)
        }

        createNotificationChannels(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                // The plain client, not a copy of the authenticated one: an
                // article image comes from wherever its publisher hosts it, and
                // a copy of a client copies its interceptors, which is how the
                // FreshRSS token used to travel with those images.
                add(OkHttpNetworkFetcherFactory(callFactory = {
                    get<OkHttpClient>(named(PLAIN_CLIENT))
                }))
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(this.cacheDir.resolve("image_cache"))
                    .maximumMaxSizeBytes(1024 * 1024 * 100)
                    .build()
            }
            .crossfade(true)
            .build()
    }

    companion object {
        const val SYNC_CHANNEL_ID = "syncChannel"
    }
}

/**
 * What LeNews calls itself on every HTTP request, on both clients.
 *
 * It lives here rather than in the `api` module because an Android library has
 * no `versionName`: the version is the app module's, and `BuildConfig` is where
 * it can be read. `apiModule` takes it as a parameter, so the instrumented
 * tests pass the same string the app does.
 */
val userAgent: String = "LeNews/${BuildConfig.VERSION_NAME}"

/**
 * Creates the notification channels the app posts on.
 *
 * Top level rather than a private method of [LeNewsApp] because the
 * instrumented tests replace the Application with TestApplication, and a
 * notification posted on a channel that does not exist is dropped by the system
 * with nothing but a log line. That is how SyncWorkerTest came to assert on an
 * empty notification list. One definition, called from both applications.
 */
fun createNotificationChannels(context: Context) {
    val syncChannel = NotificationChannel(
        LeNewsApp.SYNC_CHANNEL_ID,
        context.getString(R.string.auto_synchro),
        NotificationManager.IMPORTANCE_LOW
    )
    syncChannel.description = context.getString(R.string.account_synchro)

    NotificationManagerCompat.from(context)
        .createNotificationChannel(syncChannel)
}
