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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What survives an article's HTML on its way into the WebView. A feed is
 * someone else's HTML, so the corpus below is the hostile half — script,
 * handlers, embedded players, links that are not web pages — and the benign
 * half an article actually needs: an image, a code block, a table, a quote.
 *
 * Every assertion names the exact markup that comes out, because "the script is
 * gone" and "the paragraph around it is gone too" are both failures.
 */
class ArticleHtmlTest {

    private val articleUrl = "https://site.example/news/one"

    private fun sanitise(html: String, url: String? = articleUrl) =
        ArticleHtml.sanitise(html, url)

    // --- active content ---------------------------------------------------

    @Test
    fun scriptGoesAndTakesItsCodeWithIt() {
        assertEquals(
            "<p>Before</p><p>After</p>",
            sanitise("<p>Before</p><script>alert('stolen')</script><p>After</p>")
        )
    }

    @Test
    fun styleGoesAndTakesItsRulesWithIt() {
        assertEquals(
            "<p>Text</p>",
            sanitise("<style>body { background: url(https://tracker.example/x) }</style><p>Text</p>")
        )
    }

    @Test
    fun eventHandlersAreRemovedFromWhateverCarriesThem() {
        assertEquals(
            "<p>Tap me</p>",
            sanitise("<p onclick=\"steal()\" onmouseover=\"steal()\">Tap me</p>")
        )
        assertEquals(
            "<img src=\"https://site.example/photo.png\">",
            sanitise("<img src=\"https://site.example/photo.png\" onload=\"steal()\">")
        )
    }

    @Test
    fun embeddedPlayersAndFramesAreRemoved() {
        assertEquals(
            "<p>Around</p>",
            sanitise("<p>Around</p><iframe src=\"https://video.example/embed\"></iframe>")
        )
        assertEquals(
            "<p>Around</p>",
            sanitise("<p>Around</p><embed src=\"https://video.example/movie.swf\">")
        )
    }

    @Test
    fun objectIsRemovedAndOnlyItsFallbackTextRemains() {
        assertEquals(
            "Fallback text",
            sanitise("<object data=\"https://video.example/movie.swf\">Fallback text</object>")
        )
    }

    @Test
    fun svgAndMathAreRemovedWithTheirHandlers() {
        assertEquals("<p>Around</p>", sanitise("<p>Around</p><svg onload=\"steal()\"></svg>"))
        // The math element goes and its handlers with it; the letter it wrapped
        // stays behind as text, which is what an unwrapped element leaves.
        assertEquals("<p>Around</p>x", sanitise("<p>Around</p><math><mi>x</mi></math>"))
    }

    @Test
    fun formsAreRemoved() {
        // Nothing is left that can be typed into or submitted. The button's own
        // label stays behind as text, as any unwrapped element's text does.
        assertEquals(
            "<p>Around</p>Send",
            sanitise(
                "<p>Around</p><form action=\"https://phish.example/post\">" +
                        "<input name=\"password\"><button>Send</button></form>"
            )
        )
    }

    @Test
    fun baseAndMetaCannotChangeWhereAnythingPointsOrWhereThePageGoes() {
        assertEquals(
            "<p>Text</p>",
            sanitise(
                "<base href=\"file:///android_asset/\">" +
                        "<meta http-equiv=\"refresh\" content=\"0;url=https://phish.example/\">" +
                        "<p>Text</p>"
            )
        )
    }

    @Test
    fun videoAndAudioAreRemoved() {
        // Decided rather than inherited: no player in the reader. Article media
        // is watched on the site, which the bottom bar opens in one tap.
        assertEquals(
            "<p>Around</p>",
            sanitise(
                "<p>Around</p><video controls src=\"https://site.example/clip.mp4\"></video>" +
                        "<audio controls src=\"https://site.example/sound.mp3\"></audio>"
            )
        )
    }

    // --- links ------------------------------------------------------------

    @Test
    fun scriptLinksLoseTheirDestinationAndKeepTheirText() {
        val expected = "<a>Tap</a>"

        assertEquals(expected, sanitise("<a href=\"javascript:alert(1)\">Tap</a>"))
        assertEquals(expected, sanitise("<a href=\"JaVaScRiPt:alert(1)\">Tap</a>"))
        assertEquals(expected, sanitise("<a href=\"java\tscript:alert(1)\">Tap</a>"))
        assertEquals(expected, sanitise("<a href=\"java&#115;cript:alert(1)\">Tap</a>"))
    }

    @Test
    fun inlineDocumentsAndLocalFilesAreNotLinkedTo() {
        val expected = "<a>Tap</a>"

        assertEquals(expected, sanitise("<a href=\"data:text/html,<b>hi</b>\">Tap</a>"))
        assertEquals(expected, sanitise("<a href=\"file:///android_asset/fonts/Inter-Regular.woff2\">Tap</a>"))
        assertEquals(expected, sanitise("<a href=\"content://app.lenews.provider/x\">Tap</a>"))
        assertEquals(expected, sanitise("<a href=\"intent://scan/#Intent;scheme=zxing;end\">Tap</a>"))
        assertEquals(expected, sanitise("<a href=\"tel:+15551234567\">Tap</a>"))
    }

    @Test
    fun webLinksSurviveAndLoseTheirTarget() {
        assertEquals(
            "<a href=\"https://other.example/page\">Tap</a>",
            sanitise("<a href=\"https://other.example/page\" target=\"_blank\" rel=\"noopener\">Tap</a>")
        )
    }

    @Test
    fun aRelativeLinkResolvesAgainstTheArticle() {
        assertEquals(
            "<a href=\"https://site.example/news/two\">Tap</a>",
            sanitise("<a href=\"two\">Tap</a>")
        )
    }

    // --- images -----------------------------------------------------------

    @Test
    fun aRelativeImageResolvesAgainstTheArticleAndNeverAgainstTheAssets() {
        assertEquals(
            "<img src=\"https://site.example/news/photo.png\" alt=\"A photo\">",
            sanitise("<img src=\"photo.png\" alt=\"A photo\">")
        )
    }

    @Test
    fun withNoArticleUrlARelativeImageIsDroppedRatherThanPointedAtTheAssets() {
        assertEquals(
            "<p>Around</p>",
            sanitise("<p>Around</p><img src=\"fonts/Inter-Regular.woff2\">", url = null)
        )
    }

    @Test
    fun aProtocolRelativeImageTakesTheArticleScheme() {
        assertEquals(
            "<img src=\"https://cdn.example/photo.png\">",
            sanitise("<img src=\"//cdn.example/photo.png\">")
        )
    }

    @Test
    fun inlineImageDataSurvivesButNoOtherInlineData() {
        val pixel = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAAAAAA6fptVAAAACklEQVR4nGMAAQAABQABDQottAAAAABJRU5ErkJggg=="

        assertEquals("<img src=\"$pixel\">", sanitise("<img src=\"$pixel\">"))
        assertEquals("", sanitise("<img src=\"data:text/html;base64,PHNjcmlwdD48L3NjcmlwdD4=\">"))
    }

    @Test
    fun srcsetIsRemovedSoOnlyTheCheckedSourceIsLoaded() {
        assertEquals(
            "<img src=\"https://site.example/photo.png\">",
            sanitise(
                "<img src=\"https://site.example/photo.png\" " +
                        "srcset=\"//cdn.example/photo-2x.png 2x\" sizes=\"100vw\">"
            )
        )
    }

    // --- the article itself -----------------------------------------------

    @Test
    fun aBenignArticleComesThroughUnchanged() {
        val article = "<p>An <em>ordinary</em> article with a " +
                "<a href=\"https://other.example/page\">link</a>.</p>" +
                "<figure><img src=\"https://site.example/photo.png\" alt=\"A photo\">" +
                "<figcaption>The caption</figcaption></figure>" +
                "<blockquote><p>Something quoted</p></blockquote>" +
                "<pre><code>val answer = 42</code></pre>" +
                "<table><thead><tr><th>Head</th></tr></thead>" +
                "<tbody><tr><td>Cell</td></tr></tbody></table>" +
                "<ul><li>One</li><li>Two</li></ul>"

        assertEquals(article, sanitise(article))
    }

    @Test
    fun textWithNoTagsIsRecognisedAsPlainText() {
        assertTrue(ArticleHtml.looksLikePlainText("Just a sentence.\nAnd another."))
        assertFalse(ArticleHtml.looksLikePlainText("<p>A paragraph.</p>"))
    }
}
