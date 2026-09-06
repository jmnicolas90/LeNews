# The article store

The model behind the three pain points: what an article *is* on the phone,
what state it carries, how a sync changes the store, what is kept and what is
dropped, and what the timeline, the drawer, the item screen, the history list
and the sync need from the database. Decided on 2026-09-06 in ticket 12 of the
*appropriate-and-fix* map, by Jean-Michel Nicolas with Fable 5.1. Tickets 13
(schema), 14 (sync), 15 (retention) and 16 (history) implement it; a test can
be written from any rule below without asking anything else.

The words are those of `CONTEXT.md`. Server facts come from
`docs/research/freshrss-greader-api.md` (ticket 09), measurements from ticket
11. The one decision this document records with its reasons is in
`docs/adr/0001-freshrss-id-is-the-article-key.md`.

Naming: this document says *article* as the glossary does. The Kotlin entity
today is `Item`, and this model does not require the class to be renamed;
whether the rename is worth its diff is ticket 13's call. Every other name below
(columns, tables, indexes) is the name to use.

## 1. Entities

Six tables. Nothing else holds article state.

*Amended 2026-09-06 after the global review:* there were five. **HorizonDropped**
is the sixth, below.

**Account** — one row. Server URL, displayed user name, auth token, write
token, the notifications flag, and the **cursor** (see §3). No `type`, no
`current_account`, and no other table carries an `account_id`: there is one
account, and a column that can only ever hold one value is not a column.
Credentials stay outside the database, where they are today.

**Folder** — `id` (autoincrement), `name`, `remote_id` (unique). A FreshRSS
category. Flat.

**Feed** — as today minus `account_id`: `id` (autoincrement), `name`,
`description`, `url`, `image_url`, `siteUrl`, `last_updated`, `color`,
`icon_url`, `etag`, `last_modified`, `folder_id` (foreign key to Folder, set
null on delete), `remote_id` (unique), `notification_enabled`, `open_in`,
`open_in_ask`.

**Article** — one row per article, keyed by FreshRSS's own id.

| Column | Type | Meaning |
|---|---|---|
| `id` | 64-bit integer, **primary key**, not autoincrement | The FreshRSS id, in its decimal form. §2. |
| `feed_id` | integer, foreign key to Feed, **cascade on delete** | The feed that delivered it. |
| `title`, `description`, `clean_description`, `content`, `link`, `image_link`, `author`, `pub_date`, `read_time` | as today | **Content columns**: what the server sends. Overwritten on re-delivery. |
| `read` | boolean, not null, default false | Read or Unread. |
| `starred` | boolean, not null, default false | Starred. |
| `read_at` | integer (epoch milliseconds), nullable | When the article **became read**, on the phone's clock. §5. |

There is no `remote_id` column: the id *is* the remote id. There is no
`ItemState` table and no `ItemStateChange` table.

**PendingChange** — a read or starred decision made on the phone that FreshRSS
has not yet been told.

| Column | Type | Meaning |
|---|---|---|
| `article_id` | 64-bit integer, **primary key**, foreign key to Article, cascade on delete | One row per article at most. |
| `read` | boolean, nullable | The value the phone decided, or null if read did not change. |
| `starred` | boolean, nullable | Same for starred. |

A row exists only while at least one of its two columns is not null. A pending
value is the phone's decision and wins over the server's answer until it has
been uploaded (§3, step 1 and step 4d).

**HorizonDropped** — *Amended 2026-09-06 after the global review.* The ids the
horizon branch of §4 deleted, one column, `id` (64-bit integer, primary key).
No foreign key: the article it names is exactly the one that is no longer there.

It exists because §2 says a re-delivered article is an update, and an article
the horizon dropped has nothing left to update. FreshRSS re-delivers an article
whose content it saw change, however old that article is (`lastModified >= ot`),
so the content of an article read thirty-one days ago and deleted for it comes
back on a later sync. Stored as it stands that delivery is a **new** row: step 4b
inserts it neutral, step 4c finds the server does not call it unread and stamps
it read at that sync, and it sits thirty more days in the history under a date
on which nothing happened — reported as a new article on top of that. Repeating
a sync would then not leave the store identical, which is invariant 5.

So the horizon writes down what it removes, step 4b consults it, and two things
clear a row: the server calling the article unread or starred again, which is
the reader asking for it back, and the server no longer holding it at all, which
is the mirror rule bounding the ledger by the server's own retention. The mirror
branch writes nothing down — what the server has dropped it cannot deliver.

**Gone with the reset**: `Account.type`, `Account.current_account`, every
`account_id` column, `Item.remote_id`, `ItemState`, `ItemStateChange`, `Tag`,
`TagJoin`, `AccountConfig.useSeparateState` and every branch on it, every
`MigrationFromXToY` and every exported schema before version 1. Tags are
dropped from the model: upstream never finished the feature, it is outside the
three pain points, and the per-article tag query was the one per-row query the
timeline still paid (6 to 11 ms a page, ticket 11). Folders stay; they are a
different thing.

### Invariants

Every rule below preserves these; a test that finds one broken has found a bug.

1. **One row per article.** The primary key is the FreshRSS id, so a second
   row for the same article cannot exist. *Duplicate* is a defect the schema
   makes impossible, not a state to handle.
2. **`read` and `read_at` agree.** `read = 1` if and only if `read_at` is not
   null. Every write that touches one touches the other.
3. **A starred article is never deleted by retention.** Only the loss of its
   feed (§4, last paragraph) or an unstar followed by retention can remove it.
4. **The cursor never runs ahead of the content.** It is written in the same
   transaction as the articles it accounts for, so a store whose cursor says
   *T* holds everything the server had returned for *T* at the time.
5. **Repeating a sync changes nothing.** The same server answers applied twice
   leave the store identical: the upsert rewrites the same content, no state
   flips a second time, so no `read_at` is stamped again, and nothing is left
   to delete.

## 2. Identity

An article is identified by the 64-bit integer FreshRSS assigns it
(`_entry.id`): unique server-wide by primary key, stable across content updates,
assigned once at discovery. The store keeps it as the article's primary key,
**in decimal form, as an integer**, never as the 48-character
`tag:google.com,2005:reader/item/<16 hex digits>` string.

- **On the way in**, `stream/contents` sends the long hex form and
  `stream/items/ids` sends the decimal; both are parsed to the same integer
  (strip the prefix, parse 16 hex digits). This is what the server itself does
  with every id it receives.
- **On the way out**, every write endpoint accepts the decimal, so the decimal
  is what is sent.
- **The id is unique on its own.** `(feed_id, id)` is not the key: FreshRSS
  gives the same upstream item in two subscribed feeds two ids, and an article
  purged then rediscovered a new one. From the phone's point of view each of
  those is a new article, which is what the server says it is.
- **A re-delivered article is an update, never an insert and never ignored.**
  Ticket 09 established that `stream/contents?ot=` re-delivers the boundary
  article on every sync and any article edited upstream since. When a sync
  returns an id the store holds, the **content columns** are overwritten and
  **every other column is untouched**: `read`, `starred`, `read_at` and the
  pending change survive. Within one response, the last occurrence of an id
  wins.
- **The id encodes the discovery time**: `id / 10⁶` is the Unix second FreshRSS
  first saw the article. Nothing in this model depends on that; it is noted
  because it explains why `ot` and the id compare the way they do.

## 3. Sync

A sync is one exchange: push the pending changes, pull what changed, apply it.
Every network call happens **before** the transaction and none happens inside
it. The transaction is short and holds no wait.

**Step 0 — take the clock.** `syncStart` = now, in seconds, before the first
pull request. It becomes the new cursor if the sync succeeds. The same instant,
in milliseconds, is `now` for every `read_at` stamp and for the horizon in this
sync.

**Step 1 — upload the pending changes.** Snapshot the `PendingChange` table.
Send four lists to `edit-tag`, one state per request, **in batches of at most
998 ids**: read, unread, starred, unstarred. After each batch the server
accepted, clear the uploaded half of each row *only if it still holds the
value that was uploaded*:

```
UPDATE PendingChange SET read = NULL
  WHERE article_id IN (batch) AND read = <value uploaded>
```

and the same for `starred`; then delete rows where both columns are null. A
change the user made while the batch was in flight therefore stays queued,
because its value no longer matches. A batch that fails (transport error, non-2xx)
fails the sync: nothing is pulled, nothing is deleted, the queue is intact and
the next sync retries it. `OK` from the server means *accepted*, not applied
(ticket 09); the id lists of the same sync are the confirmation, and a change
the server did not take shows up as a mismatch the next time the user acts on
the article, never as lost data.

**Step 2 — pull.** In parallel, all paged to the end of `continuation`:

- `subscription/list` and `tag/list`: feeds and folders.
- **Content.** Incremental sync: `stream/contents` for the reading list with
  `ot = cursor`, no `xt`, `n = 1000` a page, until no `continuation` comes
  back. Read and unread alike: an article read on the web before the phone ever
  saw it still enters the store and the history. Initial sync (no cursor yet):
  see §7.
- **State.** Three id lists from `stream/items/ids`, `n = 10000` a page:
  the reading list with no filter (**every id the server still holds** in
  non-hidden feeds, read or not), the reading list with `xt = read` (the unread
  ids), and the starred stream (the starred ids). These three lists are the
  only source of read and starred state. `stream/contents` is the source of
  content only; the `read`/`starred` flags it carries per item are ignored.
- **Starred and unread articles the store lacks.** *Amended 2026-09-06 after
  the global review: this bullet said starred only.* Starred ids **and unread
  ids** that are neither in the store nor in the content just fetched have
  their content fetched with `stream/items/contents`, at most 998 ids a
  request.
  - **Starred**: an article starred on the web that the phone never held, or
    one the horizon dropped. Without this, *starred articles survive both
    rules* could not be honoured for them. Today's workaround, which discards
    any article arriving starred from the main stream, goes: an article
    arriving starred is stored like any other.
  - **Unread**: the same article from the other side, and the reason the
    amendment was needed. Marking an article unread on the FreshRSS web
    interface moves neither its discovery time nor its last-modified time, so
    `stream/contents?ot=` never delivers it again; step 4c writes state on rows
    the store holds and on no others. An article the horizon dropped, or one an
    initial sync skipped for being read (§7), would therefore be named unread
    by every sync from then on and never be there. It composes with
    HorizonDropped (§1): an unread id in the ledger is what clears its row.
  - An unread id the server's **full** list does not name is left out: the
    mirror rule deletes an unread article the server no longer holds, in the
    same transaction, so its content would be fetched for a row that does not
    survive the sync. A starred id is not filtered that way, because a starred
    article is kept whatever the full list says.

**Step 3 — nothing.** No database write has happened yet. A failure anywhere
above leaves the store exactly as the previous sync left it.

**Step 4 — one transaction**, in this order:

- **4a. Folders and feeds.** Upsert by `remote_id`; delete the ones the server
  no longer lists. Deleting a feed cascades to its articles and their pending
  changes (see §4 for what that means for starred articles).
- **4b. Articles.** Upsert every article from step 2 by id: insert new ones
  with `read = 0`, `starred = 0`, `read_at = NULL`; for ids already held,
  update the content columns only (§2). Remember which ids were *inserted*:
  they are the sync's new articles, and what the new-article notification
  reports. Updated rows are not new.
  *Amended 2026-09-06 after the global review:* an id **HorizonDropped** (§1)
  names is left out of the upsert entirely — it is a re-delivery of an article
  the horizon deleted, not a new article — **unless** this sync's unread list
  or starred list names it, in which case its ledger row is deleted and the
  article is stored like any other, neutral, and its thirty days start over
  from its next read.
- **4c. Read state**, from the lists, for articles that are in the server's
  full id list and have **no pending `read` value**:
  - present in the unread list and `read = 1` → `read = 0`, `read_at = NULL`
    (read on the phone, then unread on the web: it leaves the history);
  - absent from the unread list and `read = 0` → `read = 1`, `read_at = now`
    (**a read learned at sync**, stamped with the sync time — the only stamp
    the server allows, since no API output carries when it happened).
- **4d. Starred state**, for articles with **no pending `starred` value**:
  every held article in the starred list → `starred = 1`; every held article
  in the full list and not in the starred list → `starred = 0`. An article in
  neither list is not touched by 4c or 4d: the server has nothing to say about
  it, and §4 decides whether it stays.
- **4e. Retention.** The delete of §4, against the full id list of this sync.
- **4f. Cursor.** `Account.cursor = syncStart`.
- **4g. Statistics.** `PRAGMA optimize` (§6).

Then commit. A failure inside the transaction rolls back 4a to 4g together:
no article, no state, no deletion, no cursor. The next sync repeats the same
pull with the same cursor, which by §2 is harmless.

**Duplicates inside a response** (the same id twice in one page, or on two
pages) are resolved by the upsert: last wins, one row.

**An offline change and a stale server.** The user reads an article on the
phone while offline: `read = 1`, `read_at = now`, `PendingChange(read = true)`.
The next sync uploads it in step 1 and clears the row; the lists of step 2 were
fetched after the upload, so they already say read. If the user unread it again
between the snapshot and the clear, the row now says `read = false`, the clear
does not match, and the change waits for the next sync; meanwhile 4c skips the
article because a pending `read` exists. The phone's decision is never
overwritten by a server answer that predates it.

**Consequence worth knowing.** A phone that was off for a month pulls a
month of articles on its next sync; the ones read on the web meanwhile are
stamped with that sync's time and sit in the history for thirty days from
then. That is the settled rule ("a read learned at sync is stamped with the
sync time"), not an accident: the server has no better date to offer.

## 4. Mirror and horizon

Two rules, one exception, one predicate. The prose is the same as the
*Retention* bullet of the map, `CONTEXT.md` and `CLAUDE.md`; this section adds
the operational meaning.

- **Mirror.** The phone holds what FreshRSS holds, no more. *FreshRSS no
  longer returns it* means: **the id is absent from the full reading-list id
  set fetched in this sync** (step 2, the list with no filter, paged to the
  end). Absence is the only observable; the server's purge policy is not
  exposed. An article absent from that set is dropped, unless it is starred or
  still within the horizon. An **unread** article absent from the server is
  dropped too: the horizon is measured from becoming read, so an unread article
  is never "within" it.
- **Horizon.** Thirty days, a named constant, **measured from `read_at`**, not
  from `pub_date` and not from when the article was fetched. Past it a read
  article is dropped whatever the server still holds. Not a setting: a setting
  is a feature beyond the three pain points, and it can become one later
  without touching this model.
- **Starred** articles survive both rules.

As one delete, run in step 4e of the sync transaction and nowhere else, so
that a failed sync deletes nothing:

```
DELETE FROM Article
WHERE starred = 0
  AND (   (read = 1 AND read_at < now − 30 days)
       OR (read = 0 AND id NOT IN <server's full id set>) )
```

Read within the horizon and absent from the server: kept. Read past the
horizon and still on the server: dropped. Unread and on the server: kept.
Unread and absent: dropped. Starred: kept in all four cases. The two branches
say exactly what the prose says, and invariant 2 (`read = 1` ⇔ `read_at` not
null) is what lets the second branch test `read = 0` rather than `read_at IS
NULL`. Cascade removes the pending change of a deleted article with it.

Implementation note for ticket 15: the server's id set is tens of thousands
of ids, far past SQLite's bind-variable limit for `NOT IN (?, ?, …)`. Load it
into a temporary table inside the transaction and write the branch as
`NOT EXISTS (SELECT 1 FROM server_ids WHERE server_ids.id = Article.id)`.

**The horizon branch also keeps a ledger.** *Amended 2026-09-06 after the global
review.* Three statements, in this order, all inside the same transaction as the
delete:

1. **Write down what the horizon is about to drop**, before the delete, because
   afterwards there is no row left to read the ids from:

   ```
   INSERT OR IGNORE INTO HorizonDropped(id)
   SELECT id FROM Article WHERE starred = 0 AND read = 1 AND read_at < now − 30 days
   ```

   The `WHERE` is the horizon branch of the delete, word for word: what is
   written down is exactly what that branch removes, and nothing the mirror
   branch removes. `OR IGNORE` because an article can be dropped, come back
   because the reader marked it unread on the web, be read again and be dropped
   a second time.

2. **The delete** above.

3. **Prune the ledger** of every id the server's full set does not name:

   ```
   DELETE FROM HorizonDropped
   WHERE NOT EXISTS (SELECT 1 FROM server_ids WHERE server_ids.id = HorizonDropped.id)
   ```

   The ledger defends against a re-delivery, and the server can only deliver
   what it still holds, so the ledger is bounded by the server's own retention
   instead of growing for ever. Running it *after* the write means an article
   both past the horizon and absent from the server leaves nothing behind at
   all.

§1 says why the ledger exists; §3, step 4b says what reads it.

**The one case a starred article does not survive**: its feed is unsubscribed
or hidden on the server. The feed leaves `subscription/list`, step 4a deletes
it, and the foreign key cascades to every article of the feed, starred ones
included. That is accepted: the server has deleted them too, unsubscribing is
a deliberate act, and an article without a feed has no origin to show. The
timeline's inner join on `Feed` stays an inner join.

## 5. Becoming read

Every transition from Unread to Read is dated, by every route, and the date is
`read_at`. The routes are: opening an article, swiping it, scroll-to-read when
that preference is on, mark-all-read for the list, a feed, a folder, the stars
or the last 24 hours, and a read learned at sync (§3, 4c).

- **Becoming read** (any route but sync): `read = 1`, `read_at = now` if
  `read` was 0, and `PendingChange.read = true`. A route that finds the article
  already read changes nothing and stamps nothing.
- **Learned at sync**: `read = 1`, `read_at = syncStart`; no pending change,
  the server already knows.
- **Marking unread**: `read = 0`, `read_at = NULL`, `PendingChange.read =
  false`. The article leaves the history and the horizon clock stops.
- **Read again** after unread: a fresh `read_at`. The history shows the latest
  time it became read, once.
- **Starring and unstarring**: `starred`, `PendingChange.starred`. Neither
  touches `read` or `read_at`.

**History** is `SELECT … FROM Article WHERE read_at IS NOT NULL ORDER BY
read_at DESC`, joined to `Feed` for the name, paged like the timeline. One row
per article, because one article has one `read_at`. An article the horizon
dropped is simply gone from it, which is what *bounded by the horizon* means; a
starred article read a year ago is still there, because it was never dropped.
There is no history table.

## 6. Indexes and statistics

From ticket 11's plans, on the table §1 defines:

| Index | Serves |
|---|---|
| `Article` primary key (the FreshRSS id, a rowid alias) | upserts, the state lists, the mirror set difference, and the *by id* timeline order for free |
| `Article(pub_date)` | the all-articles timeline and the 24-hour filter, with no sort step |
| `Article(feed_id, pub_date)` | the per-feed and per-folder timelines, the feed cascade |
| `Article(read, pub_date)` | the unread timeline and the drawer's per-feed unread counts |
| `Article(starred, pub_date)` | the stars filter |
| `Article(read_at)` | the history list and the horizon branch of the delete |
| `PendingChange` primary key (`article_id`) | the queue |
| `HorizonDropped` primary key (`id`, a rowid alias) | step 4b's lookup and the pruning of §4 — *added 2026-09-06 after the global review* |
| `Feed(folder_id)`, `Feed(remote_id)` unique, `Folder(remote_id)` unique | as today, plus the upsert keys |

Nothing on `Feed.account_id` or `ItemState`, since both are gone. Ticket 11
built all five of its candidates in 137 ms on 110,000 rows; these cost the
same order.

**Statistics.** Ticket 11 proved an index changes nothing until the planner
has statistics: the first page stayed at 90 ms with `Article(pub_date)` in
place and fell to 0.4 ms after `ANALYZE`. So the model says when: **`PRAGMA
optimize` at the end of every sync transaction (step 4g) and once when the
database is created.** It runs `ANALYZE` on the tables whose statistics are
stale and nothing otherwise; 23 ms on a 219 MB database, and the database
retention leaves is a tenth of that.

**Time budget** (ticket 13 tests against it, warm, on `bench-pixel6-aosp`,
using ticket 11's seeder adapted to this schema):

| Store | Query | Budget |
|---|---|---|
| 100,000 articles | first page of each timeline: all, unread, one feed, stars | under 10 ms each |
| 100,000 articles | the drawer: unread count per feed, unread count of the last 24 hours | under 10 ms each |
| 100,000 articles | first page of the history list | under 10 ms |
| 20,000 articles (what retention actually leaves) | Paging's `SELECT COUNT(*)` for the all-articles timeline | under 20 ms |

`COUNT(*)` has no 100,000-row budget on purpose: ticket 11 showed it visits
every row whatever the indexes, and only retention bounds it. Ticket 11's
numbers after `ANALYZE` (0.4 ms, 2.8 ms, 1.2 ms) sit far inside these figures,
so the budget catches a regression without being tuned to one emulator.

## 7. Initial sync

The first sync, when the account has no cursor, pulls **all unread articles
and all starred articles**, each paged to the end with no cap
(`stream/contents` for the reading list with `xt = read`, and for the starred
stream), and **no read article at all**. Step 2's id lists, step 4 and the
retention delete then run exactly as for any sync, and the cursor is set to
`syncStart`.

Why not everything the server holds: *Mirror* says "no more", not "no less";
pulling the read articles would stamp every one of them "became read at
install" and fill the history with thousands of entries dated the same minute;
and unread plus starred is what a reader wants on a new phone. Why no cap:
the 2,500 and 1,000 limits the fork inherited made the sync advance the cursor
past content it never fetched and turned unread articles into read ones
(ticket 12, *From the global review*). FreshRSS paginates with `continuation`
and has no server-side cap (ticket 09); the maintainer's own recommendation is
`n = 1000` for contents and `n = 10000` for ids.

## 8. What the screens need, and nothing more

The model serves the timeline, the drawer, the item screen, the history list
and the sync. Nothing was designed for anything else.

- **Timeline**: `Article ⋈ Feed ⟕ Folder`, filtered by `read`, `starred`,
  the 24-hour window, `feed_id` or `folder_id`, ordered by `pub_date` or by
  `id` (which is discovery order), paged. State comes from the row itself; no
  join for it.
- **Drawer**: unread count per feed, `GROUP BY feed_id WHERE read = 0`, and
  the 24-hour unread count.
- **Item screen**: one row by id, plus its feed. Read and starred toggles go
  through §5.
- **History list**: §5.
- **Sync**: §3, §4, §7.

## 9. Tests this document implies

Each is one sentence here and one test in the ticket that owns it.

- Inserting the same id twice leaves one row with the second content and the
  first state (13).
- The same server answers applied twice leave the store identical (14).
- A response holding the same article twice inserts one row (14).
- A failure injected after 4b and before 4f leaves cursor, articles and
  deletions all unchanged (14).
- An article read offline, then synced while the server still says unread,
  is read on both sides and its pending change is gone (14).
- A change made between the queue snapshot and the clear is still queued
  after the sync (14).
- An article arriving starred from the main stream is stored, starred (14).
- A starred id the store lacks has its content fetched and stored (14).
- *Added 2026-09-06 after the global review:* an unread id the store lacks has
  its content fetched and stored, unread (14).
- *Added 2026-09-06 after the global review:* an article the horizon dropped,
  whose content the server delivers again, leaves the store exactly as it was —
  no row, no history entry, not reported as new (14, 15).
- *Added 2026-09-06 after the global review:* the same article marked unread or
  starred on the web comes back, once, unread and unstamped (14).
- *Added 2026-09-06 after the global review:* the horizon branch writes the
  ledger and the mirror branch does not; the ledger forgets what the server no
  longer holds (15).
- An article absent from the server's full id list is gone after sync; a
  starred one stays; an unread one is gone (15).
- Read 31 days ago: gone. Read 29 days ago: stays. Read 31 days ago and
  starred: stays (15).
- A failed sync deletes nothing (15).
- Every becoming-read route produces exactly one `read_at`, opening an already
  read article produces none, unread clears it, reading again sets a new one
  (16).
- The history list is ordered by `read_at` descending and holds one row per
  article (16).
- Every query of §6 is inside its budget on the seeded store (13, 16).
