/*
 * Copyright (C) 2026 Jean-Michel Nicolas
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package app.lenews.util.extensions

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the two link helpers hand to the system. They are the last thing between
 * a URL that came out of a feed and `startActivity`, and every screen that opens
 * a link — the timeline, the article toolbar, the article WebView, the More tab —
 * goes through one of them, so the rule lives here rather than at each caller.
 *
 * The context is a wrapper that records instead of launching: what is asserted
 * is which intents would have left the app, not what a browser does with them.
 */
@RunWith(AndroidJUnit4::class)
class OpenUrlTest {

    private val notWebAddresses = listOf(
        "file:///sdcard/Download/article.html",
        "file:///android_asset/fonts/Inter-Regular.woff2",
        "content://app.lenews.provider/whatever",
        "intent://scan/#Intent;scheme=zxing;end",
        "javascript:alert(1)",
        "java\tscript:alert(1)",
        "myapp://do-something",
        "tel:+15551234567",
        "mailto:noreply@example.com",
        "data:text/html,<script>alert(1)</script>",
        ""
    )

    private fun recordingContext(): RecordingContext =
        RecordingContext(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test
    fun openUrlHandsAWebAddressToTheSystem() {
        val context = recordingContext()

        context.openUrl("https://site.example/news/one")

        assertEquals(1, context.launched.size)
        assertEquals(Intent.ACTION_VIEW, context.launched.single().action)
        assertEquals(
            "https://site.example/news/one",
            context.launched.single().data.toString()
        )
    }

    @Test
    fun openUrlRefusesEverythingThatIsNotAWebAddress() {
        for (url in notWebAddresses) {
            val context = recordingContext()

            context.openUrl(url)

            assertTrue("$url must not be launched", context.launched.isEmpty())
        }
    }

    @Test
    fun theCustomTabHandsAWebAddressToTheSystem() {
        val context = recordingContext()

        context.openInCustomTab("https://site.example/news/one", "dark", Color.Red)

        assertEquals(1, context.launched.size)
        assertEquals(
            "https://site.example/news/one",
            context.launched.single().data.toString()
        )
    }

    @Test
    fun theCustomTabRefusesEverythingThatIsNotAWebAddress() {
        for (url in notWebAddresses) {
            val context = recordingContext()

            context.openInCustomTab(url, "dark", Color.Red)

            assertTrue("$url must not be launched", context.launched.isEmpty())
        }
    }

    private class RecordingContext(base: Context) : ContextWrapper(base) {

        val launched = mutableListOf<Intent>()

        override fun startActivity(intent: Intent) {
            launched += intent
        }

        override fun startActivity(intent: Intent, options: Bundle?) {
            launched += intent
        }
    }
}
