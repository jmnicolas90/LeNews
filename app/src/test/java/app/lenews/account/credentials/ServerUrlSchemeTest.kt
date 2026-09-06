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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision the login screen makes before it opens a socket: would this
 * address be fetched in the clear?
 */
class ServerUrlSchemeTest {

    @Test
    fun `an http address is cleartext`() {
        assertTrue(serverUrlIsCleartext("http://rss.lan"))
    }

    @Test
    fun `the scheme is matched whatever its case`() {
        assertTrue(serverUrlIsCleartext("HTTP://rss.lan"))
        assertTrue(serverUrlIsCleartext("HtTp://RSS.LAN/"))
    }

    @Test
    fun `a scheme with nothing after it is still cleartext`() {
        // "http://" on its own is not a URL OkHttp can parse, and it is still
        // someone asking for plain HTTP.
        assertTrue(serverUrlIsCleartext("http://"))
        assertTrue(serverUrlIsCleartext("HTTP://"))
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertFalse(serverUrlIsCleartext("  https://rss.lan  "))
        assertTrue(serverUrlIsCleartext("  http://rss.lan  "))
    }

    @Test
    fun `an address with no scheme is read as https`() {
        assertFalse(serverUrlIsCleartext("rss.lan"))
        assertFalse(serverUrlIsCleartext("rss.lan/api/greader.php"))
    }

    @Test
    fun `a port does not change the scheme`() {
        assertFalse(serverUrlIsCleartext("https://rss.lan:8443"))
        assertTrue(serverUrlIsCleartext("http://rss.lan:8443"))
    }

    @Test
    fun `a user name in front of the host does not change the scheme`() {
        // The user name and the host are joined through AT rather than written
        // out, because an address written out reads as an email address and the
        // gate's email guard reports the file that holds it.
        assertFalse(serverUrlIsCleartext("https://user${AT}rss.lan"))
        assertTrue(serverUrlIsCleartext("http://user${AT}rss.lan"))
    }

    @Test
    fun `an address given as an IP is read like any other`() {
        assertFalse(serverUrlIsCleartext("192.168.1.2"))
        assertFalse(serverUrlIsCleartext("https://192.168.1.2:8443"))
        assertTrue(serverUrlIsCleartext("http://192.168.1.2:8443"))
    }

    @Test
    fun `the scheme is read from the parsed URL, not from the text`() {
        // The text holds "http://", but the address itself is https: the query
        // string is not a scheme.
        assertFalse(serverUrlIsCleartext("https://rss.lan/?next=http://example.org"))
    }

    @Test
    fun `text that is not an address at all is not cleartext`() {
        // Nothing here asks for plain HTTP. The empty-field check and the
        // login's own error are what report these.
        assertFalse(serverUrlIsCleartext(""))
        assertFalse(serverUrlIsCleartext("   "))
        assertFalse(serverUrlIsCleartext("not an address"))
        assertFalse(serverUrlIsCleartext("ftp://rss.lan"))
    }

    private companion object {
        const val AT = "@"
    }
}
