package app.lenews.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

object FeedColors {

    /**
     * The dominant colour of the icon at [feedUrl], or 0 when those bytes are
     * not an image.
     *
     * The response is closed on every path, decoding included: the body is read
     * inside `use`, so bytes that turn out not to be an image release the
     * connection there and then instead of holding it until the garbage
     * collector gets around to it. Nothing is parsed outside the block.
     *
     * [client] is the plain client — a feed icon is hosted by the feed and not
     * by FreshRSS, so the request must not carry the FreshRSS token. It is a
     * parameter rather than something looked up here, as in `HtmlParser`, so
     * that the caller says which client the request goes out on and a test can
     * watch the one it passed.
     */
    suspend fun getFeedColor(feedUrl: String, client: OkHttpClient): Int {
        // OkHttp directly rather than Coil, which does not respect the OkHttp
        // timeout
        val bitmap = withContext(Dispatchers.IO) {
            client.newCall(
                Request.Builder()
                    .url(feedUrl)
                    .build()
            ).execute()
                .use { response -> BitmapFactory.decodeStream(response.body?.byteStream()) }
        } ?: return 0

        return getFeedColor(bitmap)
    }

    suspend fun getFeedColor(bitmap: Bitmap): Int = withContext(Dispatchers.Default) {
        val palette = Palette.from(bitmap).generate()

        palette.dominantSwatch?.rgb ?: 0
    }
}
