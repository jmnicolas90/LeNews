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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which URLs are allowed to leave the article. Everything the article WebView
 * would navigate to goes through this rule first, so anything that is not a web
 * page never reaches `startActivity` — that is upstream's `FileUriExposedException`
 * crash (issues #353 to #358) and every custom scheme with it.
 */
class ArticleLinksTest {

    @Test
    fun webPagesOpen() {
        assertTrue(ArticleLinks.mayOpen("https://example.com/article"))
        assertTrue(ArticleLinks.mayOpen("http://example.com/article"))
    }

    @Test
    fun theSchemeIsReadWithoutRegardToCase() {
        assertTrue(ArticleLinks.mayOpen("HTTPS://example.com"))
        assertTrue(ArticleLinks.mayOpen("HtTp://example.com"))
    }

    @Test
    fun surroundingWhitespaceDoesNotDecideAnything() {
        assertTrue(ArticleLinks.mayOpen("  https://example.com/article  "))
        assertTrue(ArticleLinks.mayOpen("\nhttps://example.com/article\n"))
    }

    @Test
    fun scriptLinksAreDropped() {
        assertFalse(ArticleLinks.mayOpen("javascript:alert(1)"))
        assertFalse(ArticleLinks.mayOpen("JaVaScRiPt:alert(1)"))
        assertFalse(ArticleLinks.mayOpen("java\tscript:alert(1)"))
    }

    @Test
    fun localFilesAndAppContentAreDropped() {
        assertFalse(ArticleLinks.mayOpen("file:///data/data/app.lenews/databases/database"))
        assertFalse(ArticleLinks.mayOpen("file:///android_asset/fonts/Inter-Regular.woff2"))
        assertFalse(ArticleLinks.mayOpen("content://app.lenews.provider/whatever"))
    }

    @Test
    fun intentsAndCustomSchemesAreDropped() {
        assertFalse(ArticleLinks.mayOpen("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(ArticleLinks.mayOpen("myapp://do-something"))
        assertFalse(ArticleLinks.mayOpen("market://details?id=app.lenews"))
    }

    @Test
    fun phoneNumbersMailAndInlineDataAreDropped() {
        assertFalse(ArticleLinks.mayOpen("tel:+15551234567"))
        assertFalse(ArticleLinks.mayOpen("mailto:noreply@example.com"))
        assertFalse(ArticleLinks.mayOpen("data:text/html,<script>alert(1)</script>"))
    }

    @Test
    fun nothingAtAllIsDropped() {
        assertFalse(ArticleLinks.mayOpen(null))
        assertFalse(ArticleLinks.mayOpen(""))
        assertFalse(ArticleLinks.mayOpen("   "))
        assertFalse(ArticleLinks.mayOpen("https://"))
        assertFalse(ArticleLinks.mayOpen("example.com"))
    }
}
