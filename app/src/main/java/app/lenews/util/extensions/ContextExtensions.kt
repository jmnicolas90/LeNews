@file:Suppress("DEPRECATION")

package app.lenews.util.extensions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.getSystemService
import androidx.core.net.toUri

/**
 * Opens a URL in whatever the phone uses for web pages. Does nothing when there
 * is nothing to open it with: a phone with no browser installed is unusual but
 * allowed, and a reader that dies on a tapped link is worse than one that
 * ignores it.
 */
fun Context.openUrl(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (exception: ActivityNotFoundException) {
        Log.w("openUrl", "no activity to open this link", exception)
    }
}

fun Context.openInCustomTab(url: String, theme: String?, color: Color) {
    val colorScheme = when (theme) {
        "light" -> CustomTabsIntent.COLOR_SCHEME_LIGHT
        "dark" -> CustomTabsIntent.COLOR_SCHEME_DARK
        else -> CustomTabsIntent.COLOR_SCHEME_SYSTEM
    }

    val customTab = CustomTabsIntent.Builder()
        .setDefaultColorSchemeParams(
            CustomTabColorSchemeParams
                .Builder()
                .setToolbarColor(color.toArgb())
                .build()
        )
        .setShareState(CustomTabsIntent.SHARE_STATE_ON)
        .setUrlBarHidingEnabled(true)
        .setColorScheme(colorScheme)
        .build()

    try {
        customTab.launchUrl(this, url.toUri())
    } catch (exception: ActivityNotFoundException) {
        // A custom tab needs a browser that supports them. With none installed,
        // fall back to whatever else can show a web page, and to nothing at all
        // if there is nothing else.
        openUrl(url)
    }
}

// TODO arbitrary value, we might want to use windowClasses in the future
fun Context.isTabletUi(): Boolean {
    val configuration = resources.configuration
    return configuration.smallestScreenWidthDp >= 720
}

@Composable
@ReadOnlyComposable
fun isTabletUi(): Boolean = LocalContext.current.isTabletUi()


// non depreciated APIs are only available from API 23
@Suppress("DEPRECATION")
fun Context.isConnected(): Boolean {
    val connectivityManager = getSystemService<ConnectivityManager>()!!
    val networkInfo: NetworkInfo? = connectivityManager.activeNetworkInfo

    return networkInfo?.isConnected == true
}