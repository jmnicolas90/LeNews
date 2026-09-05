package com.readrops.api.utils

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test

class ApiUtilsTest {

    @Test
    fun feedContentTypeTest() {
        assertTrue(ApiUtils.isFeedContentType("application/rss+xml"))
        assertTrue(ApiUtils.isFeedContentType("application/atom+xml"))
        assertTrue(ApiUtils.isFeedContentType("application/rdf+xml"))
        assertTrue(ApiUtils.isFeedContentType("application/feed+json"))

        assertFalse(ApiUtils.isFeedContentType("text/html"))
        assertFalse(ApiUtils.isFeedContentType(null))
    }

    @Test
    fun handleRssSpecialCases() {
        assertEquals("https://example.com", ApiUtils.handleRssSpecialCases("https://example.com"))
        assertEquals(
            "https://www.youtube.com/@user",
            ApiUtils.handleRssSpecialCases("https://www.youtube.com/@user")
        )
        val playlistId = "qog2gifixwn3vitjneusb9xl"
        assertEquals(
            "https://www.youtube.com/feeds/videos.xml?playlist_id=$playlistId",
            ApiUtils.handleRssSpecialCases("https://www.youtube.com/watch?v=qjshdbmlk&list=$playlistId")
        )
        assertEquals(
            "https://www.youtube.com/feeds/videos.xml?playlist_id=$playlistId",
            ApiUtils.handleRssSpecialCases("https://youtu.be/watch?v=qjshdbmlk&list=$playlistId")
        )
    }
}
