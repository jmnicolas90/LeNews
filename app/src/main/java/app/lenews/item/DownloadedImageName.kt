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

/**
 * The file name and the type an image from an article is saved under.
 *
 * The two always agree: the extension is chosen first, and the type is the type
 * of that extension, so a file called `.png` never holds a JPEG.
 */
data class DownloadedImageName(val displayName: String, val mimeType: String)

/** The longest a name may be before its extension. */
private const val MAX_BASE_LENGTH = 64

/** What an image with no usable name of its own is called. */
private const val FALLBACK_BASE = "image"

/** The types the app can actually write a bitmap as. */
private val extensionsByType = mapOf(
    "image/jpeg" to "jpg",
    "image/jpg" to "jpg",
    "image/png" to "png",
    "image/webp" to "webp"
)

private val typesByExtension = mapOf(
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "png" to "image/png",
    "webp" to "image/webp"
)

/**
 * Names a file system reserves whatever the extension. None of them is a
 * problem on the phone's own storage, but a Downloads folder is a place files
 * are copied out of, and a file nobody can open on the other side is not worth
 * the two lines it costs to avoid.
 */
private val reservedNames = buildSet {
    addAll(listOf("CON", "PRN", "AUX", "NUL"))
    for (number in 1..9) {
        add("COM$number")
        add("LPT$number")
    }
}

/** Characters that are a path, a stream or a wildcard rather than a name. */
private const val NOT_IN_A_NAME = "/\\:*?\"<>|"

/**
 * Decides what to call the image at [sourceUrl] once it is saved, and what type
 * to write it as.
 *
 * The extension comes from [reportedMimeType] when the loader knows the type,
 * from the address itself when it does not, and is `jpg` when neither says
 * anything the app can write — a GIF saved from a bitmap is a JPEG of its first
 * frame, and calling it `.gif` would be a lie. Everything the address cannot be
 * trusted with is taken out of the name: path separators, control characters,
 * the characters a file system reserves, a leading dot that would hide the file
 * and a length no one has a use for. An image written into the article itself
 * (a `data:` address, which the sanitiser allows for images) has no name to
 * take, so it gets the plain one; MediaStore makes it unique.
 */
fun downloadedImageName(sourceUrl: String, reportedMimeType: String?): DownloadedImageName {
    val writtenIntoTheArticle = sourceUrl.startsWith("data:", ignoreCase = true)
    val lastSegment = if (writtenIntoTheArticle) "" else lastPathSegment(sourceUrl)
    val declaredType = if (writtenIntoTheArticle) declaredTypeOfDataUri(sourceUrl) else null

    val extension = extensionOf(reportedMimeType)
        ?: extensionOf(declaredType)
        ?: extensionOfName(lastSegment)
        ?: "jpg"

    val base = safeBase(lastSegment.substringBeforeLast('.', missingDelimiterValue = lastSegment))

    return DownloadedImageName(
        displayName = "$base.$extension",
        mimeType = typesByExtension.getValue(extension)
    )
}

private fun lastPathSegment(url: String): String =
    url.substringBefore('#').substringBefore('?').substringAfterLast('/')

private fun extensionOf(mimeType: String?): String? =
    extensionsByType[mimeType?.substringBefore(';')?.trim()?.lowercase()]

private fun extensionOfName(name: String): String? {
    val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()

    return if (typesByExtension.containsKey(extension)) extension else null
}

/** The type a `data:` address declares, before its parameters and its payload. */
private fun declaredTypeOfDataUri(url: String): String =
    url.substring("data:".length).substringBefore(',').substringBefore(';')

private fun safeBase(rawName: String): String {
    val cleaned = rawName
        .filterNot { Character.isISOControl(it) || it in NOT_IN_A_NAME }
        .trim { it.isWhitespace() || it == '.' }
        .take(MAX_BASE_LENGTH)
        .trimEnd { it.isWhitespace() || it == '.' }

    return when {
        cleaned.isEmpty() -> FALLBACK_BASE
        cleaned.uppercase() in reservedNames -> FALLBACK_BASE
        else -> cleaned
    }
}
