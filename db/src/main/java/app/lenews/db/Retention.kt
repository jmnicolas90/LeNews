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

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The horizon: the age past which a read article is no longer kept, whatever
 * FreshRSS still holds. Thirty days, measured from the moment the article
 * became read — `read_at` — and not from when it was published or fetched.
 *
 * A constant and not a setting, as `docs/article-store.md` §4 decided: a
 * setting is a feature beyond the three pain points, and it can become one
 * later without changing anything else here.
 */
const val HORIZON_IN_DAYS = 30L

const val HORIZON_IN_MILLISECONDS = HORIZON_IN_DAYS * 24 * 60 * 60 * 1000

/**
 * The mirror and horizon rules of `docs/article-store.md` §4, as the one delete
 * they are. Step 4e of the sync transaction, and the only place articles are
 * dropped for age or absence.
 *
 * - **Mirror**: an article FreshRSS no longer returns is dropped. *No longer
 *   returns* means its id is absent from [serverIds], the full reading-list id
 *   set of this sync, paged to its end — absence is the only observable, since
 *   the server's own purge policy is not exposed.
 * - **Horizon**: a read article that became read more than [HORIZON_IN_DAYS]
 *   days before [now] is dropped, whatever the server still holds.
 * - **Starred articles survive both.** The only thing that can remove one is
 *   its feed leaving `subscription/list`, which cascades from `Feed`.
 *
 * The two branches are one statement because they are one rule: a read article
 * the server has dropped is kept until the horizon is up (that is the way back
 * to an article that was swiped away), and an unread article is never "within"
 * a horizon measured from a read that never happened. Invariant 2 — `read = 1`
 * if and only if `read_at` is not null — is what lets the second branch ask
 * `read = 0` rather than `read_at Is Null`.
 *
 * The article that became read exactly [HORIZON_IN_DAYS] days ago is kept: the
 * horizon is the age *past* which nothing is kept, so the comparison is strict.
 *
 * [serverIds] is tens of thousands of ids on a full account, far past the 999
 * values SQLite binds to one statement, so it goes into a temporary table in
 * chunks and the delete asks that table. The table is created, filled and
 * dropped inside the caller's transaction, on the one connection that
 * transaction pins — which is why this refuses to run outside one, rather than
 * filling a table on a connection the next statement might not get.
 *
 * An empty [serverIds] is an empty account and not a failure: every call a sync
 * makes throws rather than returning part of an answer, and a page walk that
 * stops making progress fails the sync, so the full id list is either complete
 * or the transaction never opens.
 *
 * @param serverIds every id FreshRSS still holds, from this sync's full list
 * @param now the sync's own clock, in milliseconds
 * @return how many articles were dropped
 */
fun Database.deleteWhatRetentionDrops(serverIds: Collection<Long>, now: Long): Int {
    check(inTransaction()) {
        "The retention delete runs inside the sync transaction, so that a failed sync deletes " +
                "nothing and so that the temporary table it fills stays on one connection"
    }

    val connection = openHelper.writableDatabase

    connection.execSQL(CREATE_SERVER_IDS)
    connection.execSQL("Delete From $SERVER_IDS")
    fillServerIds(connection, serverIds)

    val dropped = connection.compileStatement(RETENTION_DELETE).use { statement ->
        statement.bindLong(1, now - HORIZON_IN_MILLISECONDS)
        statement.executeUpdateDelete()
    }

    // one sync's answer, and no other: ids left behind would keep an article
    // the server has since dropped
    connection.execSQL("Drop Table $SERVER_IDS")

    return dropped
}

private fun fillServerIds(connection: SupportSQLiteDatabase, serverIds: Collection<Long>) {
    for (chunk in serverIds.chunked(IDS_PER_STATEMENT)) {
        connection.compileStatement(insertOf(chunk.size)).use { statement ->
            chunk.forEachIndexed { index, id -> statement.bindLong(index + 1, id) }
            statement.executeInsert()
        }
    }
}

private fun insertOf(ids: Int): String =
    "Insert Or Ignore Into $SERVER_IDS(id) Values " + List(ids) { "(?)" }.joinToString(",")

private const val SERVER_IDS = "server_ids"

/**
 * `id` is the rowid, so the delete's `Not Exists` is one lookup an article and
 * the whole set is one B-tree.
 */
private const val CREATE_SERVER_IDS =
    "Create Temp Table If Not Exists $SERVER_IDS(id Integer Primary Key Not Null)"

private const val RETENTION_DELETE =
    """Delete From Article
    Where starred = 0
    And ((read = 1 And read_at < ?)
        Or (read = 0 And Not Exists (Select 1 From $SERVER_IDS Where $SERVER_IDS.id = Article.id)))"""

/**
 * How many ids one statement binds. Well under SQLite's 999, and the same
 * chunk the sync's own statements use.
 */
private const val IDS_PER_STATEMENT = 900
