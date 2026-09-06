package app.lenews.util.extensions

import androidx.annotation.ColorInt
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils

fun TextStyle.toDp(): Dp = fontSize.value.dp

fun Int.canDisplayOnBackground(@ColorInt background: Int, threshold: Float = 1.75f): Boolean =
    ColorUtils.calculateContrast(this, background) > threshold

