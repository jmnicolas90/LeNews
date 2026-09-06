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

/**
 * Keeps the sentence a failed sync reports short enough for WorkManager to
 * carry.
 *
 * WorkManager stores a worker's output `Data` and refuses one whose serialized
 * form is over 10 KB — and it refuses it by throwing, from inside the machinery
 * that runs the worker, after the worker has returned. So an over-long message
 * does not merely arrive trimmed: it turns a sync that failed with something to
 * say into a sync that failed with nothing to say, or worse.
 *
 * Most of these messages are one sentence from `strings.xml`. Two of them end
 * in `exception.message`, which is whatever a library or a server put there — a
 * chain of causes, a statement with a thousand bound ids, a page of HTML.
 * That is the one that has to be bounded.
 *
 * [MAX_CHARACTERS] is several lines on a phone, far more than any message here
 * needs, and even at the four bytes a character UTF-8 spends in the worst case
 * it is a fifth of what `Data` allows — so the bound holds whatever alphabet
 * the message arrives in.
 */
object SyncFailureMessage {

    const val MAX_CHARACTERS = 500

    /**
     * [message] as it stands when it is short enough, and otherwise its first
     * [MAX_CHARACTERS] characters followed by [cutNotice] told how many
     * characters were dropped — so the reader sees that the sentence was cut
     * rather than that it simply stopped.
     *
     * [cutNotice] is a parameter because the notice is a sentence the reader
     * sees and therefore lives in `strings.xml`, while this rule is arithmetic
     * and has no business holding a `Context`.
     */
    fun bounded(message: String, cutNotice: (droppedCharacters: Int) -> String): String =
        if (message.length <= MAX_CHARACTERS) {
            message
        } else {
            message.take(MAX_CHARACTERS) + cutNotice(message.length - MAX_CHARACTERS)
        }
}
