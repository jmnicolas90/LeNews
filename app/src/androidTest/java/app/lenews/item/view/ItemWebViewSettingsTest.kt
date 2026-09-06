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

package app.lenews.item.view

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the article WebView is allowed to do, asserted on a real one. The
 * settings are a security property and a default away from being wrong, so they
 * are checked against the class rather than against a copy of the list.
 */
@RunWith(AndroidJUnit4::class)
class ItemWebViewSettingsTest {

    @Test
    fun theArticleWebViewRunsNoScriptAndReadsNoLocalFiles() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        // A WebView belongs to the main thread, from its constructor onwards.
        instrumentation.runOnMainSync {
            val webView = ItemWebView(
                context = context,
                onUrlClick = {},
                onImageLongPress = {}
            )

            val settings = webView.settings

            assertFalse("JavaScript must be off", settings.javaScriptEnabled)
            assertFalse(
                "JavaScript must not be able to open windows",
                settings.javaScriptCanOpenWindowsAutomatically
            )
            assertFalse("file access must be off", settings.allowFileAccess)
            assertFalse("content provider access must be off", settings.allowContentAccess)
            assertFalse(
                "a file URL must not read other file URLs",
                settings.allowFileAccessFromFileURLs
            )
            assertFalse(
                "a file URL must not have universal access",
                settings.allowUniversalAccessFromFileURLs
            )
            assertFalse("DOM storage must be off", settings.domStorageEnabled)
            assertFalse(
                "a second window must not be openable",
                settings.supportMultipleWindows()
            )

            webView.destroy()
        }
    }
}
