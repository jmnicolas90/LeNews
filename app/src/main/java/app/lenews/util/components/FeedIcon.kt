package app.lenews.util.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import app.lenews.R

@Composable
fun FeedIcon(
    iconUrl: String?,
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp
) {
    AsyncImage(
        model = iconUrl,
        error = painterResource(id = R.drawable.ic_rss_feed_grey),
        placeholder = painterResource(R.drawable.ic_rss_feed_grey),
        fallback = painterResource(id = R.drawable.ic_rss_feed_grey),
        contentDescription = name,
        modifier = modifier.size(size)
    )
}