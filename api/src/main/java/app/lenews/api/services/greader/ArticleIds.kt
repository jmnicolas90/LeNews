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
package app.lenews.api.services.greader

/**
 * The two shapes FreshRSS gives an article's 64-bit id, and the one it is sent
 * back in.
 *
 * `stream/contents` writes the id as `tag:google.com,2005:reader/item/` followed
 * by sixteen hexadecimal digits; `stream/items/ids` writes the same number in
 * decimal. Both are parsed to the same [Long], which is what the store keys an
 * article on. On the way out every write endpoint accepts the decimal, so the
 * decimal is what is sent.
 *
 * Every form is read and written **unsigned**, because sixteen hexadecimal
 * digits cover the whole 64-bit range while a signed parse stops halfway.
 * FreshRSS ids in use are far below that (an id is the discovery time in Unix
 * seconds times a million), so the two forms agree on every real id either way;
 * being unsigned throughout means an id that did reach the top of the range
 * becomes the same number in both forms and goes back out as the digits the
 * server sent, instead of failing in one direction and being sent negative in
 * the other.
 */
object ArticleIds {

    const val LONG_FORM_PREFIX = "tag:google.com,2005:reader/item/"

    /** Parses the long form `stream/contents` sends. */
    fun fromLongForm(id: String): Long {
        require(id.startsWith(LONG_FORM_PREFIX)) {
            "An article id from stream/contents must start with $LONG_FORM_PREFIX"
        }

        val hexadecimal = id.substring(LONG_FORM_PREFIX.length)
        require(hexadecimal.isNotEmpty() && hexadecimal.length <= 16) {
            "An article id from stream/contents must carry one to sixteen hexadecimal digits"
        }

        return java.lang.Long.parseUnsignedLong(hexadecimal, 16)
    }

    /** Parses the decimal form `stream/items/ids` sends. */
    fun fromDecimal(id: String): Long = java.lang.Long.parseUnsignedLong(id.trim())

    /** The form every write endpoint takes. */
    fun toDecimal(id: Long): String = java.lang.Long.toUnsignedString(id)
}
