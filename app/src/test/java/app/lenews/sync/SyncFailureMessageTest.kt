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

import androidx.work.Data
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What a failed sync reports, and the two properties of the way it reports it:
 * it fits in a `Data`, and one sync's answer is not another's.
 *
 * The last two tests go through `SyncFailureMessage.failureData`, which is the
 * whole of what `SyncWorker.failureData` does, and read the answer back out of
 * the bytes WorkManager would store rather than out of the object in memory —
 * otherwise removing the bound from the worker would leave them green.
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
     * [Data.MAX_DATA_BYTES], and it refuses it by throwing after the worker has
     * returned. So a message of a size no sentence has any business being — a
     * statement with a thousand bound ids, a page of HTML — has to come out of
     * the encoder the app ships small enough to store, in the bytes the
     * serialized `Data` actually occupies and not merely in characters.
     *
     * The letter is three bytes in UTF-8, so the unbounded message alone is
     * 60 KB: take the bound out of `failureData` and this test goes red, either
     * on the size or on `toByteArray` throwing.
     */
    @Test
    fun anOversizedMessageStillFitsTheOutputData() {
        val threeBytesACharacter = "\u3042".repeat(20_000)

        val bytes = SyncFailureMessage.failureData(threeBytesACharacter) { cutNotice(it) }
            .toByteArray()

        assertTrue(
            bytes.size <= Data.MAX_DATA_BYTES,
            "the serialized output is ${bytes.size} bytes, over the ${Data.MAX_DATA_BYTES} " +
                "WorkManager stores"
        )

        val restored = Data.fromByteArray(bytes)
        val message = assertNotNull(
            restored.getString(SyncWorker.SYNC_FAILURE_MESSAGE_KEY),
            "the restored output carries no message"
        )
        assertTrue(
            message.startsWith("\u3042".repeat(SyncFailureMessage.MAX_CHARACTERS)),
            "the message was not kept from its beginning: $message"
        )
        assertEquals(true, restored.getBoolean(SyncWorker.SYNC_FAILURE_KEY, false))
    }

    /**
     * Two syncs failing at once, and each answer surviving on its own the trip
     * through the bytes WorkManager keeps.
     *
     * This is worth a test because it once was not true: the message used to
     * travel through a map shared by every `Data` instance in the process, so
     * whichever sync finished last answered for both, and nothing survived the
     * process being killed. Ticket 14 deleted that map; this holds the door
     * shut, by building both answers the way the worker builds its own and
     * reading them back out of `Data.fromByteArray`, which is what a process
     * that has been killed and restarted does.
     */
    @Test
    fun twoFailuresAtOnceKeepTheirOwnAnswerThroughSerialization() {
        val first = SyncFailureMessage.failureData(
            "the first sync could not reach the server"
        ) { cutNotice(it) }
        val second = SyncFailureMessage.failureData(
            "the second sync was refused the token"
        ) { cutNotice(it) }

        val firstRestored = Data.fromByteArray(first.toByteArray())
        val secondRestored = Data.fromByteArray(second.toByteArray())

        assertEquals(
            "the first sync could not reach the server",
            firstRestored.getString(SyncWorker.SYNC_FAILURE_MESSAGE_KEY)
        )
        assertEquals(
            "the second sync was refused the token",
            secondRestored.getString(SyncWorker.SYNC_FAILURE_MESSAGE_KEY)
        )
        assertEquals(true, firstRestored.getBoolean(SyncWorker.SYNC_FAILURE_KEY, false))
        assertEquals(true, secondRestored.getBoolean(SyncWorker.SYNC_FAILURE_KEY, false))
    }
}
