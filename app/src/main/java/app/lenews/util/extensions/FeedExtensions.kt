package app.lenews.util.extensions

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import app.lenews.db.entities.Feed
import app.lenews.db.pojo.ItemWithFeed

fun Feed.getColorOrNull(): Color? = Color(color).takeIf { color != 0 }

@Composable
fun ItemWithFeed.displayColor(background: Int): Color {
    return if (color != 0 && color.canDisplayOnBackground(background)) {
        Color(color)
    } else {
        MaterialTheme.colorScheme.primary
    }
}