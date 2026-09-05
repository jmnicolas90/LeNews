package com.readrops.app.util.accounterror

import android.content.ContextWrapper
import android.content.res.Resources
import com.readrops.api.utils.exceptions.HttpException
import com.readrops.app.R
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * What a FreshRSS failure is reported as. The messages themselves are resources,
 * so the fake context hands back the resource id and the assertions name the id
 * rather than an English sentence.
 */
class GReaderErrorTest {

    private val error = GReaderError(FakeContext())

    @Test
    fun addingAFeedFreshRssRefusedDoesNotClaimItAlreadyExists() {
        // FreshRSS answers 400 both when the URL is not a feed it can read and when
        // it is already subscribed, and says which nowhere. The message must not
        // pick one.
        assertEquals(
            R.string.freshrss_feed_not_added.asMessage(),
            error.newFeedMessage(httpException(400))
        )
    }

    @Test
    fun otherHttpCodesKeepTheGenericMessages() {
        assertEquals(R.string.http_error_401.asMessage(), error.newFeedMessage(httpException(401)))
        assertEquals(R.string.http_error_404.asMessage(), error.newFeedMessage(httpException(404)))
    }

    @Test
    fun refusingToRenameOrDeleteAFeedStillMeansTheServerDoesNotKnowIt() {
        // Same endpoint, but with ac=edit or ac=unsubscribe a 400 has one cause.
        val unknownFeed = R.string.feed_doesnt_exist.asMessage()

        assertEquals(unknownFeed, error.updateFeedMessage(httpException(400)))
        assertEquals(unknownFeed, error.deleteFeedMessage(httpException(400)))
        assertNotEquals(unknownFeed, error.newFeedMessage(httpException(400)))
    }

    private fun httpException(code: Int) = HttpException(
        Response.Builder()
            .request(
                Request.Builder()
                    .url("https://freshrss.example/api/greader.php/reader/api/0/subscription/edit")
                    .build()
            )
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("Bad Request")
            .build()
    )

    private fun Int.asMessage() = "string:$this"

    /**
     * Context.getString is final and reads through getResources, so naming the
     * resource is all a fake has to do.
     */
    private class FakeContext : ContextWrapper(null) {

        private val fakeResources = object : Resources(null, null, null) {

            override fun getString(id: Int): String = "string:$id"

            override fun getString(id: Int, vararg formatArgs: Any?): String = "string:$id"
        }

        override fun getResources(): Resources = fakeResources
    }
}
