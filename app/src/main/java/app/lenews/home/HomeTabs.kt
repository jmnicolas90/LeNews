package app.lenews.home

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import cafe.adriel.voyager.navigator.tab.Tab
import app.lenews.R
import app.lenews.account.AccountTab
import app.lenews.feeds.FeedTab
import app.lenews.more.MoreTab
import app.lenews.timelime.TimelineTab

enum class HomeTabs(
    val tab: Tab,
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int,
) {
    TIMELINE(TimelineTab, R.string.timeline, R.drawable.ic_timeline),
    FEEDS(FeedTab, R.string.feeds, R.drawable.ic_rss_feed_grey),
    ACCOUNT(AccountTab, R.string.account, R.drawable.ic_account),
    MORE(MoreTab, R.string.more, R.drawable.ic_more_vert)
}