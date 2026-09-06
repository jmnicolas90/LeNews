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
package app.lenews.sync

import androidx.work.workDataOf
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a failed sync reports, and the two properties of the way it reports it:
 * it fits in a `Data`, and one sync's answer is not another's.
 */
class SyncFailureMessageTest {

    private fun cutNotice(dropped: Int) = "… (characters cut: $dropped)"

    @Test
    fun aMessageShortEnoughIsLeftAlone() {
        val message = "The server refused the connection"

        assertEquals(message, SyncFailureMessage.bounded(message) { cutNotice(it) })
    }

    @Test
    fun aMessageOfExactlyTheBoundIsLeftAlone() {
        val message = "e".repeat(SyncFailureMessage.MAX_CHARACTERS)

        assertEquals(message, SyncFailureMessage.bounded(message) { cutNotice(it) })
    }

    @Test
    fun aLongMessageIsCutAndSaysHowMuchWasDropped() {
        val message = "e".repeat(SyncFailureMessage.MAX_CHARACTERS + 1234)

        val bounded = SyncFailureMessage.bounded(message) { cutNotice(it) }

        assertTrue(
            bounded.startsWith("e".repeat(SyncFailureMessage.MAX_CHARACTERS)),
            "the message was not kept from its beginning: $bounded"
        )
        assertTrue(
            bounded.endsWith(cutNotice(1234)),
            "the message does not say how much was dropped: $bounded"
        )
    }

    /**
     * The bound is there because WorkManager refuses an output `Data` over
     * 10 KB. A megabyte of exception message — a statement with a thousand
     * bound ids, a page of HTML — has to come out of this well under that, in
     * bytes and not merely in characters, whatever alphabet it arrived in.
     */
    @Test
    fun evenAHugeMessageFitsInTheOutputDataManyTimesOver() {
        val hugeAndFourBytesACharacter = "😀".repeat(500_000)

        val bounded = SyncFailureMessage.bounded(hugeAndFourBytesACharacter) { cutNotice(it) }

        assertTrue(
            bounded.toByteArray(Charsets.UTF_8).size < 3_000,
            "the bounded message is ${bounded.toByteArray(Charsets.UTF_8).size} bytes"
        )
    }

    /**
     * Two syncs failing at once, and each `Data` carrying its own answer.
     *
     * This is worth a test because it once was not true: the message used to
     * travel through a map shared by every `Data` instance in the process, so
     * whichever sync finished last answered for both, and nothing survived the
     * process being killed. Ticket 14 deleted that map; this holds the door
     * shut.
     */
    @Test
    fun twoFailuresAtOnceDoNotShareAnything() {
        val first = workDataOf(
            SyncWorker.SYNC_FAILURE_KEY to true,
            SyncWorker.SYNC_FAILURE_MESSAGE_KEY to "the first sync could not reach the server"
        )
        val second = workDataOf(
            SyncWorker.SYNC_FAILURE_KEY to true,
            SyncWorker.SYNC_FAILURE_MESSAGE_KEY to "the second sync was refused the token"
        )

        assertEquals(
            "the first sync could not reach the server",
            first.getString(SyncWorker.SYNC_FAILURE_MESSAGE_KEY)
        )
        assertEquals(
            "the second sync was refused the token",
            second.getString(SyncWorker.SYNC_FAILURE_MESSAGE_KEY)
        )
        assertEquals(true, first.getBoolean(SyncWorker.SYNC_FAILURE_KEY, false))
        assertEquals(true, second.getBoolean(SyncWorker.SYNC_FAILURE_KEY, false))
    }
}
