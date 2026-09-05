package app.lenews.api.utils

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType

object ApiUtils {
    val MediaType.isHtml: Boolean
        get() = type == "text" && subtype == "html"

    const val CONTENT_TYPE_HEADER = "content-type"

    private val FEED_CONTENT_TYPES = setOf(
        "application/rdf+xml", // RSS 1
        "application/rss+xml", // RSS 2
        "application/atom+xml",
        "application/feed+json"
    )

    /**
     * Tells whether a content type announces a feed, whatever its format.
     */
    fun isFeedContentType(type: String?): Boolean = type != null && type in FEED_CONTENT_TYPES

    /**
     * Handles known special cases where the feed url can not be deduced by the standard
     * methods but where the way to deduce it is known. Currently used to deduce the feed
     * of a Youtube playlist.
     */
    fun handleRssSpecialCases(url: String): String {
        val uri = url.toHttpUrlOrNull() ?: return url

        val domain = uri.host.split(".").let { it.getOrNull(it.size - 2) }

        if (domain == "youtube" || uri.host.endsWith("youtu.be")) {
            return uri.queryParameter("list")?.let {
                "https://www.youtube.com/feeds/videos.xml?playlist_id=$it"
            } ?: url
        }

        return url
    }
}
