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

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist

/**
 * An article's HTML, made safe to render.
 *
 * A feed is someone else's HTML. What arrives can carry script, event handlers,
 * embedded players and links to anything at all, and the reader renders it in a
 * WebView. So nothing is rendered as it arrived: everything goes through the
 * safelist below, which names what may survive rather than trying to name what
 * may not.
 *
 * What is kept: text, headings, lists, emphasis, links, images, `pre`/`code`,
 * quotes, tables and `figure`/`figcaption`, which is what an article is made
 * of. What is dropped, with the code or the rules inside it: `script`, `style`,
 * `iframe`, `object`, `embed`, `form`, `svg`, `math`, `base`, `meta`, every
 * `on*` handler and every attribute the safelist does not name.
 *
 * Two decisions worth stating rather than reading out of the code:
 *
 * - **`video` and `audio` are dropped.** They are not in jsoup's relaxed
 *   safelist and nothing adds them here. A player in the reader would fetch
 *   media from a third party on its own; the bottom bar opens the article on
 *   the site in one tap, which is where its media belongs.
 * - **`srcset` and `sizes` are dropped**, for the same reason they are not in
 *   the relaxed safelist: they are a second list of image URLs, and the point
 *   of this file is that every URL rendered has been through one check. The
 *   image loads from its `src`, which has. An image is allowed to carry its
 *   source in `srcset` alone, though, and that is ordinary HTML rather than a
 *   trick: such an image is given the first candidate of its `srcset` as a
 *   `src` **before** the clean, so that one URL goes through exactly the same
 *   check as any other source and the picture survives instead of vanishing.
 *
 * URLs are checked by scheme: `http` and `https` on a link, those two plus
 * `data:image/` on an image. Everything is resolved to an absolute URL against
 * the article's own address before the check, and an image that cannot be
 * resolved is dropped rather than kept relative — the page is loaded with
 * `file:///android_asset/` as its base URL, so a relative URL left in place
 * would point at the app's own assets.
 *
 * A `data:image/svg+xml` image may carry script in its source. It cannot run
 * it: an image document runs no script in any engine, and the reader's WebView
 * has JavaScript switched off besides.
 *
 * One behaviour of the cleaner is worth knowing before reading the tests: a tag
 * that is not on the safelist is *unwrapped*, not cut out. Its attributes and
 * any code it held go, and the plain text it wrapped stays behind as escaped
 * text. So `<object>` leaves its fallback sentence and `<button>` leaves its
 * label, while `<script>` and `<style>` leave nothing at all, because what they
 * hold is data rather than text.
 */
object ArticleHtml {

    private const val DATA_IMAGE_PREFIX = "data:image/"

    /**
     * The tags and attributes an article may use. Built once from jsoup's
     * relaxed safelist, which already holds headings, lists, `a`, `img`, `pre`,
     * `code`, `blockquote` and tables.
     */
    private val safelist: Safelist = Safelist.relaxed()
        // Captioned images are ordinary in a feed and carry nothing active.
        .addTags("figure", "figcaption", "hr", "s", "del", "ins", "mark")
        // Relaxed allows ftp and mailto on a link. Only a web page leaves the
        // article, so those two go: see ArticleLinks.
        .removeProtocols("a", "href", "ftp", "mailto")
        // Inline images are how many feeds ship a logo or a chart. jsoup can only
        // say "data:", so the check for "data:image/" is made below, after the
        // clean, once the URL is final.
        .addProtocols("img", "src", "data")

    /**
     * The article's HTML, cleaned. [articleUrl] is what relative URLs resolve
     * against — the article's own link. When it is not a web address, nothing
     * relative can be resolved and everything relative is dropped.
     */
    fun sanitise(html: String, articleUrl: String?): String {
        val trimmedUrl = articleUrl?.trim().orEmpty()
        val baseUrl = if (ArticleLinks.mayOpen(trimmedUrl)) trimmedUrl else ""
        val parsed = Jsoup.parseBodyFragment(html, baseUrl)

        // Before the clean, because the clean is what resolves and checks a
        // source and what removes srcset.
        useSrcsetWhereThereIsNoSource(parsed)

        val cleaned = Cleaner(safelist).clean(parsed)

        // The output is read by a WebView, not by a person, and every test in
        // this repo asserts exact markup. Pretty printing would add whitespace
        // of its own and change what is asserted for no gain on screen.
        cleaned.outputSettings().prettyPrint(false)

        dropImagesWithoutACheckedSource(cleaned)

        return cleaned.body().html()
    }

    /**
     * True when the text carries no HTML at all, so the caller can turn its line
     * breaks into paragraphs instead of rendering one long line. A feed is
     * allowed to send plain text in a field that says HTML, and several do.
     */
    fun looksLikePlainText(text: String): Boolean {
        val body = Jsoup.parse(text).body()

        // The body element itself is the first of the stream, so skip it: what
        // is asked is whether anything *inside* it is a real HTML tag.
        return body.stream().skip(1).allMatch { !it.tag().isKnownTag }
    }

    /**
     * Removes every image whose source did not come out of the clean as an
     * absolute http or https URL, or as inline image data. The safelist has
     * already dropped the sources it could judge by scheme; what is left to
     * catch here is inline data that is not an image (`data:text/html`, which
     * the safelist can only see as "data:") and an image whose relative source
     * could not be resolved, whose `src` attribute the clean removed and which
     * would otherwise sit in the page as a broken image with no source at all.
     */
    /**
     * Gives an image that has no `src` of its own the first source listed in its
     * `srcset`, so that it has one when the clean resolves and checks sources.
     * Without this the image would lose its `srcset` to the safelist and then be
     * dropped by [dropImagesWithoutACheckedSource] for having no source at all,
     * which is how a perfectly ordinary picture disappears from an article.
     *
     * The **first** candidate is taken. A browser chooses by viewport and pixel
     * density, which is a decision this reader cannot make while cleaning text,
     * and the first entry is the smallest in almost every list a feed sends —
     * the cheaper file over mobile data. An image that already has a `src` keeps
     * it: that source is the one the feed nominated for a reader that cannot
     * choose.
     */
    private fun useSrcsetWhereThereIsNoSource(document: Document) {
        for (image in document.select("img[srcset]")) {
            if (image.attr("src").isNotBlank()) continue

            val candidate = firstSourceInSrcset(image.attr("srcset")) ?: continue
            image.attr("src", candidate)
        }
    }

    /**
     * The URL of the first candidate in a `srcset`, or null when the list holds
     * none. A `srcset` is a comma-separated list of `URL descriptor` pairs, and
     * a URL is allowed to contain a comma of its own, so a candidate's URL ends
     * at the first whitespace rather than at the first comma; commas trailing
     * the URL are the separator and belong to no address.
     */
    private fun firstSourceInSrcset(srcset: String): String? {
        val firstCandidate = srcset
            .dropWhile { it.isWhitespace() || it == ',' }
            .takeWhile { !it.isWhitespace() }
            .trimEnd(',')

        return firstCandidate.ifEmpty { null }
    }

    private fun dropImagesWithoutACheckedSource(document: Document) {
        for (image in document.select("img")) {
            val source = image.attr("src")
            val isCheckedSource = ArticleLinks.mayOpen(source) ||
                    source.startsWith(DATA_IMAGE_PREFIX, ignoreCase = true)

            if (!isCheckedSource) image.remove()
        }
    }
}
