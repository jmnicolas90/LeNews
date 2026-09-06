package app.lenews.sync

import android.content.Context
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.toBitmap
import app.lenews.R
import app.lenews.repositories.SyncResult
import app.lenews.db.Database
import app.lenews.db.entities.Feed
import app.lenews.db.entities.Item
import app.lenews.db.entities.account.Account

data class NotificationContent(
    val title: String? = null,
    val text: String? = null,
    val largeIcon: Bitmap? = null,
    val item: Item? = null,
    val color: Int = 0
)

class SyncAnalyzer(
    val context: Context,
    val database: Database
) {

    /**
     * What the new articles notification says, or null when there is nothing to
     * say: notifications off for the account, no new article, or every new
     * article coming from a feed whose notifications are off.
     */
    suspend fun getNotificationContent(
        account: Account,
        syncResult: SyncResult
    ): NotificationContent? {
        if (!account.isNotificationsEnabled) {
            return null
        }

        val feedIds = syncResult.items.map { it.feedId }.distinct()
        val feeds = database.feedDao().selectFromIds(feedIds)

        val items = syncResult.items.filter { isFeedNotificationEnabledForItem(feeds, it) }
        val itemCount = items.size

        return when {
            // multiple new articles from several feeds
            feedIds.size > 1 && itemCount > 1 -> {
                NotificationContent(
                    title = account.name,
                    text = context.getString(R.string.new_items, itemCount.toString()),
                    largeIcon = ContextCompat.getDrawable(context, R.drawable.ic_freshrss)
                        ?.toBitmap()
                )
            }
            // multiple new articles from a single feed
            feedIds.size == 1 -> singleFeedCase(feedIds.first(), syncResult.items)
            // only one new article from a single feed
            itemCount == 1 -> singleFeedCase(items.first().feedId, items)
            else -> null
        }
    }

    private suspend fun singleFeedCase(
        feedId: Int,
        items: List<Item>
    ): NotificationContent? {
        val feed = database.feedDao().selectFeed(feedId)

        return if (feed.isNotificationEnabled) {
            val icon = feed.iconUrl?.let {
                val target = context.imageLoader
                    .execute(
                        ImageRequest.Builder(context)
                            .data(it)
                            .build()
                    )

                target.image?.toBitmap()
            }

            val (item, text) = if (items.size == 1) {
                val item = items.first()
                item to item.title
            } else {
                null to context.getString(R.string.new_items, items.size.toString())
            }

            NotificationContent(
                title = feed.name,
                text = text,
                largeIcon = icon,
                item = item,
                color = feed.color
            )
        } else {
            null
        }
    }

    private fun isFeedNotificationEnabledForItem(feeds: List<Feed>, item: Item): Boolean =
        feeds.find { it.id == item.feedId }?.isNotificationEnabled == true
}
