@file:Suppress("DEPRECATION")

package app.lenews.util.extensions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.Uri
import android.os.FileUriExposedException
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
import app.lenews.item.view.ArticleLinks

private const val OPEN_URL_LOG_TAG = "openUrl"

/**
 * Opens a URL in whatever the phone uses for web pages.
 *
 * Two things it does not do. It does not open anything that is not a web
 * address: a link in an article is someone else's text, and handing a `file:`
 * URL to `startActivity` is what crashes upstream with
 * `FileUriExposedException` (issues #353 to #358), while an `intent:` URL aims
 * at another app entirely. [ArticleLinks.mayOpen] is the rule, and it is applied
 * here rather than at each caller so that no screen can forget it — the article
 * toolbar hands over `Item.link` exactly as the feed wrote it.
 *
 * And it does not die when there is nothing to open the page with. A phone with
 * no browser installed is unusual but allowed, and a reader that dies on a
 * tapped link is worse than one that ignores it. The two exceptions below the
 * missing browser are the last line rather than the rule: a URL that got past
 * the check should not be able to reach them.
 */
fun Context.openUrl(url: String) {
    if (!ArticleLinks.mayOpen(url)) {
        // Never the URL itself: it says which article is being read.
        Log.w(OPEN_URL_LOG_TAG, "refused a link that is not a web address")
        return
    }

    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (exception: ActivityNotFoundException) {
        Log.w(OPEN_URL_LOG_TAG, "no activity to open this link", exception)
    } catch (exception: FileUriExposedException) {
        Log.w(OPEN_URL_LOG_TAG, "this link exposes a local file", exception)
    } catch (exception: SecurityException) {
        Log.w(OPEN_URL_LOG_TAG, "not allowed to open this link", exception)
    }
}

/**
 * Opens a URL in an in-app browser tab. Refuses anything that is not a web
 * address, for the reasons given on [openUrl]: a custom tab ends at
 * `startActivity` like everything else.
 */
fun Context.openInCustomTab(url: String, theme: String?, color: Color) {
    if (!ArticleLinks.mayOpen(url)) {
        Log.w(OPEN_URL_LOG_TAG, "refused a link that is not a web address")
        return
    }

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
    } catch (exception: FileUriExposedException) {
        Log.w(OPEN_URL_LOG_TAG, "this link exposes a local file", exception)
    } catch (exception: SecurityException) {
        Log.w(OPEN_URL_LOG_TAG, "not allowed to open this link", exception)
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