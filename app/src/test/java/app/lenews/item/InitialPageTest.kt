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

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which page the item screen opens on. */
class InitialPageTest {

    /**
     * The list the pager gets is built again when the screen is created, so a
     * sync that arrived meanwhile can have put articles above the one the
     * reader tapped. The id is what says where it went, whatever position it
     * was given.
     */
    @Test
    fun theArticleIsFoundByItsIdAndNotAtThePositionItWasOpenedFrom() {
        val ids = listOf(NEW_ARTICLE, ANOTHER_NEW_ARTICLE, THE_ARTICLE, ONE_MORE)

        assertEquals(2, initialPage(ids, itemId = THE_ARTICLE, articlePosition = 0))
    }

    /** The ordinary case: nothing moved, and the two answers agree. */
    @Test
    fun theArticleIsWhereTheTimelineSaidItWas() {
        val ids = listOf(NEW_ARTICLE, THE_ARTICLE, ONE_MORE)

        assertEquals(1, initialPage(ids, itemId = THE_ARTICLE, articlePosition = 1))
    }

    /**
     * Beyond the pages the pager has loaded there are placeholders, and the
     * article the screen was opened on can be one of them. The position counted
     * in the store is then the answer, and it is a position in the same list.
     */
    @Test
    fun aPositionIsWhatIsLeftWhenTheArticleHasNotLoadedYet() {
        val ids = listOf(NEW_ARTICLE, ONE_MORE, null, null, null)

        assertEquals(3, initialPage(ids, itemId = THE_ARTICLE, articlePosition = 3))
    }

    /**
     * A screen showing one article — the one a notification opened — has no
     * list and no position, and the one article is the first page.
     */
    @Test
    fun aScreenWithNoListOpensOnItsOnlyPage() {
        assertEquals(0, initialPage(listOf(THE_ARTICLE), itemId = THE_ARTICLE, articlePosition = -1))
        assertEquals(0, initialPage(emptyList(), itemId = THE_ARTICLE, articlePosition = -1))
    }

    /**
     * A position past the end of the list is no position at all: the list can
     * have shrunk since the screen was opened, and asking the pager for a page
     * that is not there is how the reader gets an empty screen or worse.
     */
    @Test
    fun aPositionPastTheEndOfTheListIsBroughtBackIntoIt() {
        val ids = listOf(NEW_ARTICLE, ONE_MORE)

        assertEquals(1, initialPage(ids, itemId = THE_ARTICLE, articlePosition = 40))
    }

    private companion object {
        const val THE_ARTICLE = 1_625_234_531_559_678L
        const val NEW_ARTICLE = 1_625_234_531_559_679L
        const val ANOTHER_NEW_ARTICLE = 1_625_234_531_559_680L
        const val ONE_MORE = 1_625_234_531_559_681L
    }
}
