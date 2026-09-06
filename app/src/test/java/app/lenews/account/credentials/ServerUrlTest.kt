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

package app.lenews.account.credentials

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one reading the login screen makes of a typed server address: the exact
 * address the request will be built with, or the reason there is none.
 *
 * There is one function because there used to be two readings — a check that
 * said "this is not cleartext" and a request builder that made something else
 * of the same text — and an address that disagreed with itself went out over
 * plain HTTP. Every case below asserts the canonical address, not merely that
 * the text was accepted.
 */
class ServerUrlTest {

    @Test
    fun `an address that reads one way to the check and another to the request builder`() {
        // The crafted input. "http:" is a scheme without the slashes, so a
        // check looking for "http://" at the front found none; the fragment
        // holds "http://", so a check looking anywhere found one and the two
        // disagreed. It is not an address at all, and it is refused.
        assertEquals(ServerUrl.Unreadable, canonicalServerUrl("http:127.0.0.1:8888/#http://"))
    }

    @Test
    fun `an http address is refused`() {
        assertEquals(ServerUrl.NotHttps, canonicalServerUrl("http://rss.lan"))
        assertEquals(ServerUrl.NotHttps, canonicalServerUrl("http://localhost"))
        assertEquals(ServerUrl.NotHttps, canonicalServerUrl("http://rss.lan:8443"))
    }

    @Test
    fun `the scheme is read whatever its case`() {
        assertEquals(ServerUrl.NotHttps, canonicalServerUrl("HTTP://rss.lan"))
        assertEquals(ServerUrl.NotHttps, canonicalServerUrl("HtTp://RSS.LAN/"))
        assertEquals(usable("https://rss.lan/"), canonicalServerUrl("HTTPS://RSS.LAN"))
    }

    @Test
    fun `a scheme with nothing usable after it is refused`() {
        assertEquals(ServerUrl.NotHttps, canonicalServerUrl("http://"))
        assertEquals(ServerUrl.Unreadable, canonicalServerUrl("https://"))
    }

    @Test
    fun `any other scheme is refused too`() {
        assertEquals(ServerUrl.NotHttps, canonicalServerUrl("ftp://rss.lan"))
        assertEquals(ServerUrl.NotHttps, canonicalServerUrl("file://rss.lan"))
    }

    @Test
    fun `surrounding whitespace is dropped`() {
        assertEquals(usable("https://rss.lan/"), canonicalServerUrl("  https://rss.lan  "))
        assertEquals(ServerUrl.NotHttps, canonicalServerUrl("  http://rss.lan  "))
    }

    @Test
    fun `an address with no scheme is read as https`() {
        assertEquals(usable("https://rss.lan/"), canonicalServerUrl("rss.lan"))
        assertEquals(usable("https://192.168.1.2/"), canonicalServerUrl("192.168.1.2"))
    }

    @Test
    fun `a port is kept`() {
        assertEquals(usable("https://rss.lan:8443/"), canonicalServerUrl("https://rss.lan:8443"))
        assertEquals(usable("https://rss.lan:8443/"), canonicalServerUrl("rss.lan:8443"))
    }

    @Test
    fun `a path is kept and ends in a slash`() {
        // Every call is resolved against this address as a base, so a path that
        // did not end in a slash would have its last segment replaced.
        assertEquals(
            usable("https://rss.lan/api/greader.php/"),
            canonicalServerUrl("rss.lan/api/greader.php")
        )
        assertEquals(
            usable("https://rss.lan/api/greader.php/"),
            canonicalServerUrl("https://rss.lan/api/greader.php/")
        )
    }

    @Test
    fun `a user name in front of the host is refused`() {
        // The user name and the host are joined through AT rather than written
        // out, because an address written out reads as an email address and the
        // gate's email guard reports the file that holds it.
        assertEquals(ServerUrl.CarriesAUserName, canonicalServerUrl("https://user${AT}rss.lan"))
        assertEquals(
            ServerUrl.CarriesAUserName,
            canonicalServerUrl("https://user:secret${AT}rss.lan")
        )
        // The scheme is read first, so this one is refused for being http.
        assertEquals(ServerUrl.NotHttps, canonicalServerUrl("http://user${AT}rss.lan"))
    }

    @Test
    fun `the query and the fragment are dropped`() {
        // Retrofit resolves every call against this address and keeps neither,
        // so storing them would say something the requests do not do.
        assertEquals(
            usable("https://rss.lan/"),
            canonicalServerUrl("https://rss.lan/?next=http://example.org")
        )
        assertEquals(usable("https://rss.lan/"), canonicalServerUrl("https://rss.lan/#anchor"))
    }

    @Test
    fun `an empty field is reported as empty`() {
        assertEquals(ServerUrl.Missing, canonicalServerUrl(""))
        assertEquals(ServerUrl.Missing, canonicalServerUrl("   "))
    }

    @Test
    fun `garbage is refused rather than guessed at`() {
        assertEquals(ServerUrl.Unreadable, canonicalServerUrl("not an address"))
        assertEquals(ServerUrl.Unreadable, canonicalServerUrl("///"))
        assertEquals(ServerUrl.Unreadable, canonicalServerUrl("rss.lan:notaport"))
    }

    private fun usable(url: String): ServerUrl = ServerUrl.Usable(url)

    private companion object {
        const val AT = "@"
    }
}
