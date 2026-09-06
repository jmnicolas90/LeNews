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
package app.lenews.db

import androidx.room.withTransaction
import app.lenews.db.entities.account.Account

/**
 * Writes the one account row after a successful login, and — when the login
 * named another server or another user — empties the store it fills in the same
 * transaction.
 *
 * Everything in the store belongs to the account it was synchronized from. The
 * articles are that account's, and so are their read and starred state, the
 * pending changes waiting to go up, the feeds, the folders, the ledger of ids
 * the horizon dropped and the cursor. Point the app at another server, or at
 * another user of the same one, and none of it is true any more: the next sync
 * would upload the previous account's pending ids to the new one, and its
 * cursor would make the new account's older articles look like content already
 * fetched, so they would never be asked for.
 *
 * So the two writes are one transaction. Either the new account is the account
 * and the store is empty, or nothing changed and the old pair is intact. A
 * store half emptied under a new account row is the state this exists to make
 * impossible.
 *
 * The cursor goes back to zero with the rest, which makes the first sync of the
 * new account the initial sync of `docs/article-store.md` §7 — every unread
 * article and every starred one, and no read article. The account object handed
 * in is not mutated: the row is written from a copy, so the caller's object
 * cannot be left saying something the store does not.
 *
 * A password-only change keeps everything: same server, same user, same
 * articles. Which of the two this is, is [theStoreBelongsToAnotherAccount]'s
 * caller to decide; deciding it is a comparison of the typed address and user
 * name against the stored ones, and it lives with the login screen that has
 * both.
 *
 * @param account the account the login filled in, tokens and all
 * @param theStoreBelongsToAnotherAccount whether this login replaced the server
 * or the user, in which case the store goes with them
 */
suspend fun Database.writeTheAccountAfterLogin(
    account: Account,
    theStoreBelongsToAnotherAccount: Boolean
) {
    if (!theStoreBelongsToAnotherAccount) {
        accountDao().upsert(account)
        return
    }

    withTransaction {
        // Articles first and feeds after, although the feed cascade would take
        // the articles anyway: this says what is emptied rather than leaving it
        // to a foreign key to be read three files away.
        itemDao().deleteEveryArticle()
        feedDao().deleteEveryFeed()
        folderDao().deleteEveryFolder()
        horizonDroppedDao().forgetEverything()

        // zero is the cursor of an account that has never synchronized
        accountDao().upsert(account.copy(cursor = 0))
    }
}
