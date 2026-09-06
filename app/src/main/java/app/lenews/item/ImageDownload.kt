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
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.IOException
import java.io.OutputStream

/**
 * Saves an image the reader asked for into the phone's Downloads folder.
 *
 * The app has no storage permission and does not want one: it asks MediaStore
 * for a row of its own in Downloads and writes through the content URI that row
 * gives back. Writing a `File` under the public Downloads directory, which is
 * what this used to do, has not been allowed since scoped storage and failed
 * with nothing on screen to say so.
 *
 * The row is created pending, which keeps it invisible to every other app until
 * the bytes are there. Anything that goes wrong before that takes the row with
 * it: the caller is told with a `null`, and the reader gets a message rather
 * than a half-written file or a pending row nobody can see or delete.
 */
object ImageDownload {

    private const val TAG = "ImageDownload"

    /**
     * Writes an image into Downloads under [name] and returns the row it went
     * into, or `null` if nothing could be written.
     *
     * [writeImage] is given the open stream and has only to fill it; throwing
     * is how it reports it could not. It is a parameter rather than a bitmap so
     * that the failing write can be tested without an unwritable device.
     */
    fun saveInDownloads(
        context: Context,
        name: DownloadedImageName,
        writeImage: (OutputStream) -> Unit
    ): Uri? {
        val resolver = context.contentResolver
        val downloads = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        val pendingRow = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name.displayName)
            put(MediaStore.Downloads.MIME_TYPE, name.mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val uri = try {
            resolver.insert(downloads, pendingRow)
        } catch (error: Exception) {
            Log.w(TAG, "Downloads would not take a new file", error)
            null
        } ?: return null

        var saved = false

        try {
            val stream = resolver.openOutputStream(uri)
                ?: throw IOException("no stream for the row just inserted")

            stream.use { writeImage(it) }

            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)

            saved = true
        } catch (error: Exception) {
            Log.w(TAG, "the image could not be written to Downloads", error)
        } finally {
            if (!saved) {
                deleteQuietly(resolver, uri)
            }
        }

        return if (saved) uri else null
    }

    private fun deleteQuietly(resolver: ContentResolver, uri: Uri) {
        try {
            resolver.delete(uri, null, null)
        } catch (error: Exception) {
            Log.w(TAG, "the row of a download that failed could not be removed", error)
        }
    }
}
