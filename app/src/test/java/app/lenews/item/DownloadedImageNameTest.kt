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
package app.lenews.item

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadedImageNameTest {

    @Test
    fun `the name comes from the last part of the address`() {
        assertEquals(
            DownloadedImageName("photo.jpg", "image/jpeg"),
            downloadedImageName("https://example.com/media/photo.jpg", reportedMimeType = null)
        )
    }

    @Test
    fun `a query string and a fragment are not part of the name`() {
        assertEquals(
            DownloadedImageName("photo.png", "image/png"),
            downloadedImageName("https://example.com/media/photo.PNG?w=600#top", null)
        )
    }

    @Test
    fun `an address with no extension is saved as a jpeg`() {
        assertEquals(
            DownloadedImageName("photo.jpg", "image/jpeg"),
            downloadedImageName("https://example.com/media/photo", null)
        )
    }

    @Test
    fun `the type that was reported decides the extension, not the address`() {
        assertEquals(
            DownloadedImageName("photo.png", "image/png"),
            downloadedImageName("https://example.com/media/photo.jpg", "image/png")
        )
    }

    @Test
    fun `a type nobody can write falls back to the address, then to jpeg`() {
        assertEquals(
            DownloadedImageName("photo.png", "image/png"),
            downloadedImageName("https://example.com/media/photo.png", "image/gif")
        )
        assertEquals(
            DownloadedImageName("photo.jpg", "image/jpeg"),
            downloadedImageName("https://example.com/media/photo.gif", "image/gif")
        )
    }

    @Test
    fun `path separators are removed rather than kept`() {
        assertEquals(
            DownloadedImageName("download.png", "image/png"),
            downloadedImageName("https://example.com/a/down\\load.png", null)
        )
        assertEquals(
            DownloadedImageName("etcpasswd.jpg", "image/jpeg"),
            downloadedImageName("https://example.com/a/\\etc\\passwd", null)
        )
    }

    @Test
    fun `control characters are removed`() {
        assertEquals(
            DownloadedImageName("photoname.png", "image/png"),
            downloadedImageName("https://example.com/a/pho\u0007to\u001fname.png", null)
        )
    }

    @Test
    fun `the name never starts with a dot`() {
        assertEquals(
            DownloadedImageName("hidden.png", "image/png"),
            downloadedImageName("https://example.com/a/.hidden.png", null)
        )
        assertEquals(
            DownloadedImageName("image.png", "image/png"),
            downloadedImageName("https://example.com/a/.png", null)
        )
    }

    @Test
    fun `a name reserved by some file systems is not used`() {
        assertEquals(
            DownloadedImageName("image.png", "image/png"),
            downloadedImageName("https://example.com/a/CON.png", null)
        )
        assertEquals(
            DownloadedImageName("image.jpg", "image/jpeg"),
            downloadedImageName("https://example.com/a/com1", null)
        )
    }

    @Test
    fun `a very long name is cut`() {
        val name = downloadedImageName(
            "https://example.com/a/" + "a".repeat(400) + ".png",
            null
        )

        assertEquals("image/png", name.mimeType)
        assertTrue(name.displayName.length <= 68, "was ${name.displayName.length}")
        assertTrue(name.displayName.endsWith(".png"))
    }

    @Test
    fun `an address with nothing after the last slash is still named`() {
        assertEquals(
            DownloadedImageName("image.jpg", "image/jpeg"),
            downloadedImageName("https://example.com/", null)
        )
    }

    @Test
    fun `an image written into the article itself is named from its declared type`() {
        assertEquals(
            DownloadedImageName("image.png", "image/png"),
            downloadedImageName("data:image/png;base64,iVBORw0KGgoAAAANSUhEUg==", null)
        )
        assertEquals(
            DownloadedImageName("image.jpg", "image/jpeg"),
            downloadedImageName("data:image/svg+xml,<svg/>", null)
        )
    }

    // A question mark and a hash never reach the name: they end the path, and
    // the test above covers them.
    @Test
    fun `characters a file system reserves are removed`() {
        assertEquals(
            DownloadedImageName("photo.jpg", "image/jpeg"),
            downloadedImageName("https://example.com/a/p:h*o\"t<o>|.jpg", null)
        )
    }
}
