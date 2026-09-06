# 15 — Drop what FreshRSS dropped, and everything read past the horizon

Type: task
Status: resolved
Blocked by: 14

## Question

Implement the mirror-and-horizon rule of `docs/article-store.md`: after a successful sync, delete articles FreshRSS no longer returns (as the model defines "returns", from ticket 09's facts about `stream/items/ids`) unless they are starred or still within the horizon, and delete read, unstarred articles that became read more than 30 days ago, whatever the server has. Starred articles are never deleted by either rule. That is the same predicate as the *Retention* bullet of the map, the **Mirror**, **Horizon** and **Starred** entries of `CONTEXT.md` and the retention paragraph of `CLAUDE.md`; the four are meant to read alike. Tags and history entries of a deleted article go with it (foreign keys with cascade, or the model's equivalent). The horizon is a constant or a setting as the model decided; if a setting, it lives with the other timeline preferences and defaults to 30.

Upstream issue #359 ("sync slows down because old items are never deleted") is another user stating pain point 1; ticket 09 established that `stream/items/ids` for the reading list includes read articles, has no cap and paginates with `continuation`, which is what makes the mirror rule implementable.

Run it where the model says (in the sync transaction, or its own step after it) and make sure a failed sync never deletes anything.

Test-first (`/tdd`): an article absent from the server's id list is gone after sync; a starred one absent from the list stays; an article read 31 days ago is gone, one read 29 days ago stays; a failed sync deletes nothing; the seeded 100k store shrinks to the expected size and the timeline query is measured again.

**Done when** the gate is green through G7, the tests pass, and the debug build's store on the emulator, synced against the debug account for a month or against a seeded server state, stabilises at the expected size (record the number).

## From the global review (2026-09-06)

Two adversarial reviews — one on the repo's own standards, one on what the
tickets asked for — read everything committed since the fork point. The upstream
defects below are still in the tree at HEAD and belong to this ticket rather
than to the round that found them, so they are recorded here and nothing was
changed for them.

- **The starred-exclusion workaround loses articles this rule then has to keep.**
  `app/src/main/java/app/lenews/repositories/GReaderRepository.kt:165-171`
  discards any article that arrives with `isStarred` set from the main items
  call, on every sync and not only the initial one. An article starred on the
  FreshRSS web UI between two syncs is therefore never stored, which makes
  "starred articles survive both rules" impossible to honour for it. The model
  question is ticket 12's; the rule that depends on the answer is this one's.
- **Retention is what bounds the two costs ticket 11 could not index away.**
  One mark-all-read takes `ItemState` from 5,955 to 110,000 rows and makes the
  next sync's delete-and-reinsert cost 143 ms instead of 2.3 ms, and Paging's
  `SELECT COUNT(*) FROM (query)` stays at 81 ms with every index in place.
  Nothing but dropping rows fixes either, which is this ticket.

## Answer (2026-09-06)

The mirror and the horizon are one delete, `deleteWhatRetentionDrops` in
`db/src/main/java/app/lenews/db/Retention.kt`, run at step 4e of the sync
transaction and nowhere else:

```
Delete From Article
Where starred = 0
And ((read = 1 And read_at < ?)
    Or (read = 0 And Not Exists (Select 1 From server_ids Where server_ids.id = Article.id)))
```

`?` is `syncStart − HORIZON_IN_MILLISECONDS`, the sync's own clock less thirty
days. The horizon is `HORIZON_IN_DAYS = 30`, a constant and not a setting, and it
is measured from `read_at`. Invariant 2 (`read = 1` if and only if `read_at` is
not null) is what lets the mirror branch ask `read = 0` rather than
`read_at Is Null`.

**The boundary is kept, not dropped.** An article that became read exactly
thirty days ago survives; the comparison is a strict `<`, because the horizon is
the age *past* which nothing is kept. The next sync, a moment later, drops it.
Which side it falls on cannot matter in practice — syncs do not land on a
millisecond — but a test names the choice so nobody has to read the SQL.

### The server's id list goes through a temporary table

`server_ids(id Integer Primary Key Not Null)` is created, filled 900 ids a
statement and dropped inside the caller's transaction, and the mirror branch is
a `Not Exists` against it. `id` is the rowid, so each lookup is one B-tree
probe. Nothing is bound past SQLite's 999-value limit, and 50,000 ids are a test
of their own.

The table is **dropped in the same transaction**, so one sync's answer can never
decide the next one's — an id left behind would keep an article the server has
since dropped, for ever. That is a test too.

The function **refuses to run outside a transaction** (`check(inTransaction())`).
Two reasons in one: the model says the delete belongs in the sync transaction so
that a failed sync deletes nothing, and a temporary table lives on one
connection — the connection a transaction pins. Outside one, Android's pool
could hand the fill and the delete two different connections and the delete
would silently see an empty table, which reads as "the server holds nothing" and
deletes every unread article.

**4c and 4d were left on their chunked statements.** Ticket 14 said moving them
onto the temporary table was welcome if it simplified things. It does not: they
work from the rows whose state can actually change (the store's unread ids, the
store's starred ids), which is a handful, while the table holds the server's
whole list. Rewriting them as joins against it would trade short statements for
a scan of tens of thousands of rows and change no behaviour. The one thing it
would save is the `HashSet` the repository already builds for other reasons.

**An empty server id list is an empty account, not a failure** — but only
because an *empty* answer and an answer the client could not read are now two
different things. Every call throws on a transport error or a non-2xx; since
ticket 14 a page walk that stops making progress throws as well; and since the
review round below the adapter refuses a page that carries no `itemRefs` or a
reference with no id, which is the case this paragraph originally missed. So a
partial or unreadable list never reaches the transaction, and the transaction
only opens on a complete answer. If that answer is an explicitly empty
`itemRefs` the account really holds nothing, and dropping the unread articles
the phone still has is exactly what Mirror asks for.

**Cascade.** A deleted article takes its `PendingChange` row with it through the
foreign key that was already in the schema; a test asserts it, and asserts the
queue of an article that stays is untouched. **The only exit for a starred
article is its feed leaving `subscription/list`** (step 4a, cascade on `Feed`).
Nothing else was added.

**Tags and a history table do not exist**, so nothing had to be cascaded to
them: ticket 12 dropped tags from the model and made the history `read_at` on
the article row. The ticket's sentence about them describes a store this fork no
longer has.

### The failure seam moved

`GReaderRepository.afterArticlesAreStored()` — ticket 14's do-nothing method,
there so the rollback test has somewhere to fail — is now
`afterTheStoreIsWritten()` and is called **after** 4e instead of after 4b. A
failure injected before the delete proves nothing about a delete rolling back.
Ticket 14's own rollback test still passes unchanged: the failure still lands
before the cursor.

### Tests

Written first; the first red was a compile error, as in ticket 14.

`db/src/androidTest/.../RetentionTest.kt`, thirteen cases on a store of a
handful of rows: unread and absent from the server → gone; starred and absent →
stays; read within the horizon and absent → stays (the horizon is measured from
becoming read, so a read article the server dropped is kept until it is up);
read 31 days ago → gone although the server still holds it; read 29 days ago →
stays; read 31 days ago and starred → stays; read exactly on the horizon →
stays, and gone one millisecond later; unread and still on the server → stays;
the pending change of a deleted article is gone and the one of a surviving
article is not; 50,000 server ids go through without hitting the bind limit; the
ids of one pass do not leak into the next; the same pass twice deletes nothing
more; and the delete refuses to run outside a transaction.

`db/src/androidTest/.../TimelineTimeBudgetTest.kt` gained the pass at size, on
the seeded 100,000-article store with the whole store's ids as the server's
list. What it leaves is checked twice over — against the survivors counted
before the pass by a query written as what is kept rather than as what goes, and
against the rules themselves, which nothing left may break — and then every page
and Paging's `COUNT(*)` are measured again on what is left, before and after the
`PRAGMA optimize` the sync runs next.

`app/src/androidTest/.../SyncTest.kt` gained four cases against MockWebServer:
an article the server's full list no longer names is gone after the sync; a
starred one stays; an article read past the horizon is gone although the server
still holds it; and **a failed sync deletes nothing** — the failure is injected
after the delete has run, the rolled-back store is identical, and the sync that
follows does delete the article, so the test cannot pass because there was
nothing to delete.

**One inherited test had to be corrected.**
`SynchronizerTest.syncStoresOneRowForOneArticle` had its stub answer the full id
list with ids that named *other* articles than the one whose content it
delivered. Nothing before this ticket read that list for anything but state, so
the contradiction was invisible; with the mirror rule, a server that sends an
article's content and then leaves it out of the list of what it holds is a
server saying it dropped that article, and the sync now drops it — the test went
red with an empty store, which is the rule working. The stub now names the
delivered article in the full list, as the other test in that file already did,
and keeps the unread list naming other articles so the article is read at the
sync. No production code changed for it.

**Each test was seen red for the right reason.** Switching the 4e call off turns
exactly three `SyncTest` cases red (the fourth, the starred one, asserts nothing
is deleted, so it stays green as it should); writing `read_at <= ?` turns only
the boundary case red; dropping `starred = 0` turns only the two starred cases
red. All three mutations were reverted.

### The numbers

On `bench-pixel6-aosp`, the seeded store of ticket 13 (100,000 articles, 100
feeds, a year, the newest 2,500 unread, 1,000 starred):

| | |
| --- | --- |
| one pass, 100,000 server ids, catching up a year | **90,630 dropped in 543 ms**, 9,370 left |
| the same pass again, same 100,000 ids, nothing to drop | **0 dropped in 50 ms** |

543 ms is a one-off: it deletes nine tenths of a 219 MB store. **50 ms is what a
sync pays from then on**, and most of that is filling the temporary table with
the server's whole list, not the delete.

The store left is 9,370 articles, not the model's "about 20,000": the seeder
spreads a hundred thousand articles over a year, which is 274 a day, so thirty
days of them is about 8,200, plus the unread and the starred spread over the
whole year. The model's 20,000 assumed a heavier reader. The 20 ms `COUNT(*)`
budget at 20,000 rows is still asserted, by the seeded 20,000-article test that
was already there (8.9 ms).

The timeline after the shrink, medians of seven warm runs, with statistics and
without (the two states differ by less than the noise):

| Query | 100,000 articles (ticket 13) | 9,370 after one pass |
| --- | ---: | ---: |
| timeline, all articles | 0.36 ms | **0.37 ms** |
| timeline, unread only | 0.40 ms | **0.39 ms** |
| timeline, one feed | 0.39 ms | **0.40 ms** |
| timeline, one folder | 3.80 ms | **2.58 ms** |
| timeline, a folder with no article | 0.01 ms | **0.01 ms** |
| timeline, starred | 0.39 ms | **0.39 ms** |
| history, first page | 0.05 ms | **0.05 ms** |
| drawer, unread count per feed | 1.07 ms | **1.06 ms** |
| drawer, unread count of the last 24 hours | 1.37 ms | **0.99 ms** |
| Paging `COUNT(*)`, all articles | 46.6 ms | **3.69 ms** |

The pages were already flat in the number of articles stored, so they do not
move. `COUNT(*)` is the one that only retention could fix, and it is the number
that changed: **46.6 ms to 3.7 ms**. That is what makes the `README.md` sentence
about the store growing without bound false, so it was rewritten.

### On the real store

The debug build from this worktree, installed on `bench-pixel6-aosp`, synced
against the `ledev` account on `https://rss.lan` — the same emulator, the same
user CA, the credentials copied from the main checkout's gitignored
`local.properties` into the worktree for the build and deleted afterwards, no
value printed anywhere.

| | articles | dropped |
| --- | ---: | ---: |
| the store ticket 14 left | 746 | — |
| after one sync with retention in it | 766 | **0** |

Nothing was dropped, and nothing should have been: every article was unread and
the server still held it. Twenty new ones had arrived in the meantime.

So the rules were then put in front of real data. Fifty-four rows were added to
the store by hand with ids the server does not hold, which is the one way to
exercise all four cases without writing anything to the account: fifty read
thirty-one days ago, one read twenty-nine days ago, one read thirty-one days ago
and starred, one unread, one unread and starred. After one sync against the real
server, with the real full id list deciding:

| | articles |
| --- | ---: |
| before the sync | 820 |
| after it | **769** |

**51 dropped** — the fifty past the horizon and the unread one the server does
not hold — and the three survivors were exactly the right three: read inside the
horizon, read past it but starred, and unread but starred. The 766 real articles
were untouched, `read = 1` still meant `read_at` not null everywhere, the queue
was empty and the cursor advanced. The three leftover rows were then deleted and
the store is as it was found: 766 articles, all unread, nothing starred.

**Still pending: the month.** Nobody can watch thirty days of syncing in one
sitting, and until an article has been read for thirty days the horizon branch
cannot fire on data nobody arranged. The command that repeats the check, from
this worktree, with the emulator booted and the debug build installed:

```
adb -s emulator-5554 shell "run-as app.lenews.debug sqlite3 databases/lenews-db 'select count(*) as articles, sum(read = 0) as unread, sum(starred = 1) as starred, sum(read = 1 and read_at < (unixepoch() - 30*86400) * 1000) as past_the_horizon from Article;'"
```

It answered `766|766|0|0` after the sync above. **The last number must always be
0 after a sync**: an article read past the horizon and still in the store is
this rule failing. The first number is what should stop growing once a month of
syncing has gone by, which is the part nobody can watch today.

Also still pending, from ticket 22 and unchanged here: the root certificate on
the **phone**, and a second sample proving the feeds really produce a few
hundred articles a day.

### What was consciously left out

- **The timeline, the item screen and the history list.** Nothing was touched
  beyond what compiles; the history screen is ticket 16.
- **4c and 4d stay on chunked statements**, for the reason above.
- **No setting for the horizon**, as the model decided.
- **No notification, count or log of what a sync dropped.** The delete returns
  the number of rows it removed and the sync ignores it. Nothing on screen shows
  articles leaving, and a user who wonders where an article went has only the
  rule to go on. If that turns out to matter it is a ticket of its own.
- **The 543 ms first pass is not amortised.** A phone upgrading into this
  version with a year of articles pays it once, inside one sync transaction. It
  was measured rather than split into batches, because a one-off half second is
  not worth the machinery.
- **`CHANGELOG.md` was not touched.** Its unreleased section has been stale
  since ticket 13 (it still says the account screen adds and switches accounts),
  and half-fixing it here would hide that rather than fix it.

### Review round (2026-09-06)

An adversarial review of this branch found three defects. Two are fixed here,
one is routed.

**A malformed page of the server's id list was read as an empty one, and
retention deleted against it.** `GReaderItemsIdsAdapter` accepted `{}` and an
error object sent with an HTTP 200 as `ids = []`, `continuation = null`, which
`everyPage` took for a completed walk. The sync then ran the mirror rule against
a list the server never sent: every unread, unstarred article the malformed
answer left out was deleted, and the cursor moved on, so an ordinary incremental
pull could not bring the older ones back. This is why the paragraph above about
an empty list had to be corrected: "every call throws" was true of transport
errors and stalled walks and not of a 200 carrying something else.

The adapter is strict now. `itemRefs` has to be there and every reference in it
has to carry an id; anything else is a `ParseException` and the sync fails
before it writes or deletes anything. An `itemRefs` that is there and empty is
still a real answer and still the only one that means the stream holds nothing —
`items_no_ids.json`, the fixture the tests use for an empty starred list, is
exactly that. A reference is also read field by field now, so one carrying more
than its id parses instead of failing.

Five cases in `GReaderItemsIdsAdapterTest`: `{}`, an error object, a reference
without an id, the explicit empty list, and a reference carrying more than its
id. Two more in `SyncTest` against MockWebServer, on a store the sync has
already filled: a malformed **first** page of the full id list and a malformed
**continuation** page each fail the sync with the articles, their state and the
cursor exactly as they were. Both were seen red — removing the `itemRefs` check
alone makes them report that the sync "completed successfully", which is the
defect in one line.

**A notification whose article retention deleted no longer crashes the app.**
`ItemDao.select` returned a non-null `Item` and Room throws when the row is
gone, so tapping the new-articles notification (`MainActivity`) or either of its
read and star actions (`SyncBroadcastReceiver`) after a sync had dropped that
article killed the app. Retention makes that an everyday case rather than a
corner one. The query returns `Item?` now, the tap opens the timeline and stops
there, and the actions do nothing but take the notification down.

**The stale notification is cancelled on every sync that posts nothing new**,
which is the simpler of the two options: `displaySyncResult` cancels
`SYNC_RESULT_NOTIFICATION_ID` when there is no new-article content to post,
rather than working out whether this sync deleted the article the last
notification named. It costs one line instead of carrying the previous
notification's article id around, and a notification about articles a sync ago
is stale anyway. It only runs for automatic syncs, as posting always did: a
manual sync shows its result on screen. So a stale notification can outlive a
manual sync, until the next automatic one — which cannot crash anything, because
of the nullable lookup above.

Two tests in `SyncWorkerTest`: the read and star actions on an article that
retention (the real `deleteWhatRetentionDrops`, run against an empty server
list) has dropped throw nothing, bring nothing back and queue nothing; and a
second sync with nothing new takes the previous notification down. The first was
seen red — with the non-null lookup it fails with a `NullPointerException`
raised in `SyncBroadcastReceiver`.

**Routed, not fixed here: the item screen buffers read and star decisions until
`onDispose`.** A background sync's retention can delete an article that is open
and still `read = 0`, `starred = 0` in the store, and the disposal write then
fails on the foreign key with the star the reader saw lost. Persisting every
decision the moment the user acts is what `docs/article-store.md` §5 says and
what ticket 16 wires next, so it is written into
`issues/16-history-list.md`; the disposal write's own duty to tolerate a missing
article is a sentence added to ticket 21's existing item about that block. The
buffer was not touched here.
