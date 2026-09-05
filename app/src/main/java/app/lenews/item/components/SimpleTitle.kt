package app.lenews.item.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.lenews.R
import app.lenews.timelime.components.itemWithFeed
import app.lenews.util.DefaultPreview
import app.lenews.util.components.FeedIcon
import app.lenews.util.components.IconText
import app.lenews.util.extensions.displayColor
import app.lenews.util.theme.MediumSpacer
import app.lenews.util.theme.LeNewsTheme
import app.lenews.util.theme.ShortSpacer
import app.lenews.util.theme.spacing
import app.lenews.db.pojo.ItemWithFeed
import app.lenews.db.util.DateUtils
import kotlin.math.roundToInt

@Composable
fun SimpleTitle(
    itemWithFeed: ItemWithFeed,
    titleColor: Color,
    onBackgroundColor: Color,
    bottomPadding: Boolean,
) {
    val item = itemWithFeed.item
    val spacing = MaterialTheme.spacing.mediumSpacing
    val accentColor = itemWithFeed.displayColor(MaterialTheme.colorScheme.background.toArgb())

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = spacing,
                end = spacing,
                top = spacing,
                bottom = if (bottomPadding) spacing else 0.dp
            )
    ) {
        FeedIcon(
            iconUrl = itemWithFeed.feedIconUrl,
            name = itemWithFeed.feedName,
            size = 48.dp,
            modifier = Modifier.clip(CircleShape)
        )

        ShortSpacer()

        Text(
            text = itemWithFeed.feedName,
            style = MaterialTheme.typography.labelLarge,
            color = onBackgroundColor,
            textAlign = TextAlign.Center
        )

        ShortSpacer()

        Text(
            text = item.title!!,
            style = MaterialTheme.typography.headlineMedium,
            color = titleColor,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        if (item.author != null) {
            ShortSpacer()

            IconText(
                icon = painterResource(id = R.drawable.ic_person),
                text = itemWithFeed.item.author!!,
                style = MaterialTheme.typography.labelMedium,
                color = onBackgroundColor,
                tint = itemWithFeed.displayColor(MaterialTheme.colorScheme.background.toArgb())
            )
        }

        ShortSpacer()

        val readTime = if (item.readTime > 1) {
            stringResource(id = R.string.read_time, item.readTime.roundToInt())
        } else {
            stringResource(id = R.string.read_time_lower_than_1)
        }
        Text(
            text = "${DateUtils.formattedDateByLocal(item.pubDate!!)} ${stringResource(id = R.string.interpoint)} $readTime",
            style = MaterialTheme.typography.labelMedium,
            color = onBackgroundColor
        )

        if (itemWithFeed.item.tags.isNotEmpty()) {
            MediumSpacer()

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(
                    MaterialTheme.spacing.shortSpacing,
                    Alignment.CenterHorizontally
                ),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.shortSpacing)
            ) {
                for (tag in itemWithFeed.item.tags) {
                    TagSurface(
                        name = tag.name,
                        backgroundColor = accentColor
                    )

                }
            }
        }
    }
}

@DefaultPreview
@Composable
private fun SimpleTitlePreview() {
    LeNewsTheme {
        SimpleTitle(
            itemWithFeed = itemWithFeed,
            titleColor = MaterialTheme.colorScheme.primary,
            onBackgroundColor = MaterialTheme.colorScheme.onBackground,
            bottomPadding = true
        )
    }
}