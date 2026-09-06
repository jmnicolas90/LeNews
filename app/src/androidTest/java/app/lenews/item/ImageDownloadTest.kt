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

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The image download, against the real MediaStore of the device.
 *
 * This is what cannot be tested on the JVM: that the app, holding no storage
 * permission at all, can put a file in the phone's public Downloads folder and
 * read it back, and that a write that fails leaves nothing behind — no pending
 * row, which is a row no other app can see and the reader cannot delete.
 *
 * Every row this test makes is named after a fresh identifier and removed
 * afterwards, so it never collides with a file the reader put there by hand.
 */
@RunWith(AndroidJUnit4::class)
class ImageDownloadTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val resolver: ContentResolver = context.contentResolver
    private val namesToClean = mutableListOf<String>()

    @After
    fun removeWhatWasWritten() {
        namesToClean.forEach { name ->
            resolver.delete(
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                arrayOf(name)
            )
        }
    }

    @Test
    fun anImageIsSavedInDownloadsAndCanBeReadBack() {
        val name = uniqueName("png", "image/png")
        val bitmap = smallBitmap()

        val uri = ImageDownload.saveInDownloads(context, name) { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }

        assertNotNull(uri, "the image was not saved")

        val row = rowFor(name.displayName)
        assertNotNull(row, "no row in Downloads for ${name.displayName}")
        assertEquals(0, row.isPending, "the row is still pending")
        assertEquals("image/png", row.mimeType)
        assertTrue(
            row.relativePath.startsWith("Download"),
            "the file went to ${row.relativePath} rather than to Downloads"
        )

        val readBack = checkNotNull(resolver.openInputStream(uri)) { "nothing to read back" }
            .use { BitmapFactory.decodeStream(it) }
        assertNotNull(readBack, "what was written back is not an image")
        assertEquals(bitmap.width, readBack.width)
        assertEquals(bitmap.height, readBack.height)
    }

    @Test
    fun aWriteThatFailsLeavesNoRowBehind() {
        val name = uniqueName("png", "image/png")

        val uri = ImageDownload.saveInDownloads(context, name) {
            throw IOException("the image could not be encoded")
        }

        assertNull(uri, "a failed write reported success")
        assertNull(
            rowFor(name.displayName),
            "a row was left in Downloads for an image that was never written"
        )
    }

    @Test
    fun aWriteThatStopsHalfWayLeavesNoRowBehind() {
        val name = uniqueName("png", "image/png")

        val uri = ImageDownload.saveInDownloads(context, name) { stream ->
            stream.write(ByteArray(64) { 1 })
            throw IOException("the source stopped half way")
        }

        assertNull(uri, "a failed write reported success")
        assertNull(
            rowFor(name.displayName),
            "a half-written file was left in Downloads"
        )
    }

    private fun uniqueName(extension: String, mimeType: String) = DownloadedImageName(
        displayName = "lenews-test-${UUID.randomUUID()}.$extension",
        mimeType = mimeType
    ).also { namesToClean += it.displayName }

    private fun smallBitmap(): Bitmap =
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }

    /**
     * The row Downloads holds under this name, pending rows included — a
     * pending row left behind is exactly what this test is looking for, and a
     * query that hid it would pass for the wrong reason.
     */
    private fun rowFor(displayName: String): DownloadRow? {
        val query = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.Downloads.DISPLAY_NAME} = ?"
            )
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(displayName))
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }

        val columns = arrayOf(
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.MIME_TYPE,
            MediaStore.Downloads.RELATIVE_PATH,
            MediaStore.Downloads.IS_PENDING
        )

        return resolver.query(downloadsUri(), columns, query, null)?.use { cursor ->
            if (!cursor.moveToFirst()) {
                return@use null
            }

            DownloadRow(
                mimeType = cursor.getString(1).orEmpty(),
                relativePath = cursor.getString(2).orEmpty(),
                isPending = cursor.getInt(3)
            )
        }
    }

    private fun downloadsUri(): Uri =
        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private data class DownloadRow(
        val mimeType: String,
        val relativePath: String,
        val isPending: Int
    )
}
