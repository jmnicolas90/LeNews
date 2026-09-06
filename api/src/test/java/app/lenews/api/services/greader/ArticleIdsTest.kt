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

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ArticleIdsTest {

    @Test
    fun theTwoFormsOfOneIdParseToTheSameNumber() {
        val longForm = "tag:google.com,2005:reader/item/0005c62466ee28fe"
        val decimal = "1625234531559678"

        assertEquals(ArticleIds.fromLongForm(longForm), ArticleIds.fromDecimal(decimal))
        assertEquals(1625234531559678L, ArticleIds.fromLongForm(longForm))
    }

    @Test
    fun theDecimalIsWhatGoesBackToTheServer() {
        assertEquals("1625234531559678", ArticleIds.toDecimal(1625234531559678L))
    }

    @Test
    fun aLongFormIdIsReadAsUnsignedHexadecimal() {
        // sixteen digits cover the whole 64-bit range; a signed parse would
        // fail on anything past the halfway point
        assertEquals(-1L, ArticleIds.fromLongForm(ArticleIds.LONG_FORM_PREFIX + "ffffffffffffffff"))
    }

    /**
     * The two forms have to agree over the whole 64-bit range, not only over the
     * half a signed parse reaches: an id the server sends in decimal past 2^63
     * has to become the same number as its hexadecimal form, and go back out as
     * the digits the server sent.
     */
    @Test
    fun anIdPastTheSignedRangeMakesTheRoundTripInBothForms() {
        val justPastTheHalfWay = "9223372036854775808" // 2^63
        val theLastId = "18446744073709551615" // 2^64 - 1

        assertEquals(Long.MIN_VALUE, ArticleIds.fromDecimal(justPastTheHalfWay))
        assertEquals(justPastTheHalfWay, ArticleIds.toDecimal(Long.MIN_VALUE))

        assertEquals(-1L, ArticleIds.fromDecimal(theLastId))
        assertEquals(theLastId, ArticleIds.toDecimal(-1L))
    }

    @Test
    fun theTwoFormsAgreeOnAnIdPastTheSignedRange() {
        assertEquals(
            ArticleIds.fromLongForm(ArticleIds.LONG_FORM_PREFIX + "8000000000000000"),
            ArticleIds.fromDecimal("9223372036854775808")
        )
        assertEquals(
            ArticleIds.fromLongForm(ArticleIds.LONG_FORM_PREFIX + "ffffffffffffffff"),
            ArticleIds.fromDecimal("18446744073709551615")
        )
    }

    @Test
    fun aDecimalIdPastTheWholeRangeIsRefused() {
        assertFailsWith<NumberFormatException> {
            ArticleIds.fromDecimal("18446744073709551616") // 2^64
        }
    }

    @Test
    fun aLongFormIdWithoutItsPrefixIsRefused() {
        assertFailsWith<IllegalArgumentException> {
            ArticleIds.fromLongForm("0005c62466ee28fe")
        }
    }

    @Test
    fun aLongFormIdWithNoDigitsIsRefused() {
        assertFailsWith<IllegalArgumentException> {
            ArticleIds.fromLongForm(ArticleIds.LONG_FORM_PREFIX)
        }
    }

    @Test
    fun aDecimalIdThatIsNotANumberIsRefused() {
        assertFailsWith<NumberFormatException> {
            ArticleIds.fromDecimal("not a number")
        }
    }
}
