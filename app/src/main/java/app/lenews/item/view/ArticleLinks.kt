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

import java.util.Locale

/**
 * The one rule about URLs that come out of a feed: a web page may leave the
 * article, nothing else may.
 *
 * It is deliberately a plain function of a string rather than a check spread
 * over the WebView client, because both places that need it need the same
 * answer — the sanitiser, deciding which `href` to keep, and the WebView,
 * deciding what to hand to `startActivity`. Handing anything else to
 * `startActivity` is what crashes upstream with `FileUriExposedException` when
 * an article links to a `file:` URL, and what would let a feed aim an intent at
 * another app.
 */
object ArticleLinks {

    private const val HTTP = "http://"
    private const val HTTPS = "https://"

    /**
     * True when [url] is an http or https address with something after the
     * scheme. The scheme is read without regard to case, surrounding whitespace
     * is ignored, and a control character anywhere is a refusal: a tab in the
     * middle of `java<tab>script:` is exactly how a scheme gets smuggled past a
     * check that only looks at the start.
     */
    fun mayOpen(url: String?): Boolean {
        val candidate = url?.trim() ?: return false

        if (candidate.any { it.isISOControl() }) return false

        val lowerCase = candidate.lowercase(Locale.ROOT)

        return (lowerCase.startsWith(HTTP) && candidate.length > HTTP.length) ||
                (lowerCase.startsWith(HTTPS) && candidate.length > HTTPS.length)
    }
}
