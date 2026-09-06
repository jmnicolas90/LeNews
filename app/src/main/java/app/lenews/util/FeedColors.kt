package app.lenews.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.palette.graphics.Palette
import app.lenews.api.PLAIN_CLIENT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.qualifier.named

object FeedColors : KoinComponent {

    suspend fun getFeedColor(feedUrl: String): Int {
        // use OkHttp directly instead of Coil as Coil doesn't respect OkHttp timeout
        // TODO retry with Coil3?
        // A feed icon is hosted by the feed, not by FreshRSS: the plain client.
        val response = get<OkHttpClient>(named(PLAIN_CLIENT)).newCall(
            Request.Builder()
                .url(feedUrl)
                .build()
        ).execute()

        val bitmap = BitmapFactory.decodeStream(response.body?.byteStream()) ?: return 0

        return getFeedColor(bitmap)
    }

    suspend fun getFeedColor(bitmap: Bitmap): Int = withContext(Dispatchers.Default) {
        val palette = Palette.from(bitmap).generate()

        palette.dominantSwatch?.rgb ?: 0
    }
}