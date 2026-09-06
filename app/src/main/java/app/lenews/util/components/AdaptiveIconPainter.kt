package app.lenews.util.components

import android.graphics.drawable.AdaptiveIconDrawable
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap

/**
 * Draws an adaptive icon, which `painterResource` cannot do on its own. Moved
 * here from the account selection screen when that screen was replaced by the
 * FreshRSS login screen; the code itself is upstream's, from
 * https://gist.github.com/tkuenneth/ddf598663f041dc79960cda503d14448
 */
@Composable
fun adaptiveIconPainterResource(@DrawableRes id: Int): Painter {
    val res = LocalContext.current.resources
    val theme = LocalContext.current.theme

    val adaptiveIcon = ResourcesCompat.getDrawable(res, id, theme) as? AdaptiveIconDrawable
    return if (adaptiveIcon != null) {
        BitmapPainter(adaptiveIcon.toBitmap().asImageBitmap())
    } else {
        // The drawable is not an adaptive icon, load it as a plain resource
        painterResource(id)
    }
}
