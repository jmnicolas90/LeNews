# 14 — Make sync replay-safe: one transaction, idempotent inserts

Type: task
Status: resolved
Blocked by: 13, 22

## Question

Implement the sync sequence of `docs/article-store.md` in `GReaderRepository.synchronize()` and `GReaderDataSource.synchronize()`: push local changes, pull, then apply articles, state, tags and the cursor **in one Room transaction**, with inserts that are idempotent under the identity rule (upsert or ignore, as the model says), and response-batch deduplication before insert. Remove `insertItemsIds`'s delete-and-reinsert in favour of the model's state application. Fix the review's `SyncWorker` finding while here: `Log.e(TAG, "Synchronization failed", e)` and a stable error carried in `Data`, not `printStackTrace()` plus `Exception(e.cause)`.

Test-first (`/tdd`), against MockWebServer as the existing `SynchronizerTest` does: the same response batch applied twice leaves the store identical; a response containing the same article twice inserts one row; a failure injected after the article insert and before the cursor write leaves the cursor unchanged and the next sync produces no duplicate; an article read offline and synced when the server still says unread ends up read on both sides. Ticket 09's findings say which of these is normal and which exceptional; test both.

**Done when** the gate is green through G7, those four tests pass, and a debug build synced against the debug account on `rss.lan` (ticket 22) for a week shows no duplicate row — checked by the agent with `adb shell run-as` on the debug build's database, never on the user's phone or account.

## From the global review (2026-09-06)

Two adversarial reviews — one on the repo's own standards, one on what the
tickets asked for — read everything committed since the fork point. The upstream
defects below are still in the tree at HEAD and belong to this ticket rather
than to the round that found them, so they are recorded here and nothing was
changed for them.

- **Read and star uploads send the whole id list in one request.**
  `api/src/main/java/app/lenews/api/services/greader/GReaderDataSource.kt:99-105`
  and `:140-158` pass `syncData.readIds` / `unreadIds` / `starredIds` /
  `unstarredIds` to one `edit-tag` call each. Ticket 09 found that FreshRSS
  truncates request bodies at 1 MiB without saying so and applies the ids in
  non-atomic statements of at most 998, answering `OK` either way. So a large
  batch is partly applied and reported as a success — and
  `GReaderRepository.synchronize()` then clears every pending change
  unconditionally (`app/.../repositories/GReaderRepository.kt:81`), so what the
  server did not take is forgotten. Batch the uploads and only clear what was
  acknowledged.
- **The pending-change queue is read before the network calls and cleared
  after them.** `GReaderRepository.kt:42-54` builds `syncData` from
  `selectItemStateChanges`, the sync then runs for as long as it runs, and
  `:81` resets *all* state changes for the account. An article the user marks
  read while a sync is in flight has its change dropped without ever being
  uploaded.
- **A read change is silently skipped when a star change is already queued.**
  `app/src/main/java/app/lenews/repositories/Repository.kt:103-121` calls
  `upsertItemReadStateChange`, which updates the existing `ItemStateChange` row
  rather than adding to it; with a star change already queued for that article
  the read half can be lost, and the next sync uploads the star while the
  server's answer puts the article back to unread.
- **Articles are inserted, never upserted.** `GReaderRepository.kt:176-180`.
  Ticket 12 decides the identity and the constraint; the idempotent write is
  this ticket's, and so is paging the fetch if ticket 12 asks for it
  (`GReaderDataSource.kt:50-56` and `:73-78` discard `continuation`).


## Answer (2026-09-06)

The sync is `docs/article-store.md` §3 and §7: the pending changes go up in
batches, everything comes down paged, and one Room transaction writes the lot.
A failure anywhere before the transaction leaves the store as the previous sync
left it; a failure inside it rolls back the articles, the state and the cursor
together.

### What the sync does now

**Step 0.** `GReaderRepository.synchronize()` takes `syncStart` before anything
else. The same instant stamps every read this sync learns, and `syncStart / 1000`
becomes the cursor if the sync succeeds.

**Step 1.** The queue is snapshotted, then `GReaderDataSource.uploadPendingChanges`
sends read, unread, starred and unstarred to `edit-tag`, **one state a request and
at most 998 ids a request** — the size FreshRSS applies in one statement, and well
under the 1 MiB it truncates a body at without saying so. After each batch the
server took, a callback clears **only the half of each queued row that still holds
the value that went up** (`PendingChangeDao.clearUploadedRead` /
`clearUploadedStarred`, then `deleteEmpty`). A decision the user made while the
batch was in flight no longer matches and stays queued. A batch the server refuses
throws, so nothing is pulled and nothing is written.

**Step 2.** Six calls, in parallel, each paged to the end of `continuation`:
folders, feeds, the contents, and three `stream/items/ids` walks at `n = 10000` —
the whole reading list, the unread ids, the starred ids. The contents are
`stream/contents` for the reading list with `ot = cursor`, no `xt`, `n = 1000`;
on the **initial sync** (§7) they are the reading list with `xt = read` plus the
starred stream, both paged, no read article and no cap. The inherited 2500 and
1000 caps are gone. Then the starred ids that are in neither the store nor the
content just fetched have their content read from `stream/items/contents`, 998
ids a request — the one call this fork adds to the service.

**The three id lists are the only source of state.** The `read` and `starred`
categories `stream/contents` puts on an article are parsed and ignored, on the
initial sync too: `readIdsTheServerHolds`, which ticket 13 added to work around
the initial sync fetching no read id list, is deleted, because the initial sync
now fetches the same three lists as any other. An article read and starred on
the web still comes out of a first sync read and starred, and stamped with the
sync's clock.

**Step 4, one transaction**, with no network call inside it: folders and feeds
(4a), the article upsert (4b), read state (4c), starred state (4d), the retention
seam (4e), the cursor (4f) and `PRAGMA optimize` (4g). `Database.optimize()` is a
one-line method on the database class rather than a DAO query, because Room's
`@Query` takes statements and not pragmas.

**4e is a named empty seam**, `GReaderRepository.deleteWhatRetentionDrops(serverIds,
now)`, called where the model puts the delete and doing nothing. Ticket 15 fills it;
it already has the two things the delete needs.

### The large lists: chunked statements, no temporary table

The three id lists are tens of thousands of ids on a full account, far past what
one statement can bind, and the model warns about quadratic matching. What is
here instead of a temporary table:

- The **set differences are done in Kotlin** against a `HashSet`, one pass each.
- The **candidates are the rows whose state can actually change**, never the
  server's whole list. To learn what became read, the sync reads the ids this
  store holds as unread (`selectUnreadIds`) and keeps those the server still holds
  and no longer calls unread — a handful, not the tens of thousands of read ids.
  Unstarring works the same way from `selectStarredIds`, which is bounded by what
  the user has starred.
- Every statement that names ids is **chunked at 900**.

A temporary table would work too and is what ticket 15 will need for the mirror
delete, which cannot be expressed against the rows it is about. For 4c and 4d it
would have cost an insert of the whole server list to save nothing.

### The pending-change skip is a subquery, not a filtered list

`markReadFromSync`, `markUnreadFromSync`, `starFromSync` and `unstarFromSync` each
carry `Not Exists (Select 1 From PendingChange Where article_id = Article.id And
<half> Is Not Null)`. It is read when the statement runs — inside the transaction,
after the queue was cleared of what the server took — so a change the user made
during the sync is honoured, and only the half it concerns is skipped.

### The worker's failure

`SyncWorker` logs `Log.e(TAG, "Synchronization failed", e)` and puts a **String**
in the output `Data` under `SYNC_FAILURE_MESSAGE_KEY`, built by
`GReaderError.genericMessage(e)` where the exception was caught. What it replaces
was `printStackTrace()` inside a string template, and a `Data.putSerializable`
extension that kept the exception in a **process-wide map keyed by a String** and
never in the `Data` at all — two workers failing at once overwrote each other, and
the timeline cleared the map for everyone. That extension and its three companions
are deleted.

### Tests

Written before the code they check; for the instrumented ones the first red was a
compile error, since the seam they use did not exist yet.

`app/src/androidTest/.../sync/SyncTest.kt` drives the repository against
`FreshRSSStub`, a MockWebServer dispatcher that pages the way FreshRSS does and
answers the page a request asks for from the `c` it carries — so a client that
dropped a continuation gets page one again and the test sees it. It builds its
JSON from decimal ids and derives the hexadecimal long form from the same number,
so a fixture cannot mix the two forms. Nine tests: the same answers applied twice
leave every row identical, `read_at` included; one article delivered twice in one
response is one row with the last delivery; a failure injected after the articles
and before the cursor leaves cursor, articles and state unchanged and the retry
stores no duplicate; an article read offline is uploaded (the `edit-tag` body is
asserted) and its queue row goes, with `read_at` not restamped; a change made
while the batch is in flight stays queued and 4c leaves the article alone; an
article arriving starred from the main stream is stored starred, and unread
because the unread list says so whatever its content said; a starred id the store
lacks has its content fetched from `stream/items/contents`; every page is followed
for the contents and for each of the three id lists; and a refused upload fails the
sync before any pull, with the queue and the cursor intact.

The rollback test needs a failure between 4b and 4f, and nothing in the sequence
fails there on its own, so `GReaderRepository` has one protected open method,
`afterArticlesAreStored()`, that does nothing and is documented as being there for
that test.

`api`: `stateUploadsAreSplitInBatchesOfAtMost998` (2000 ids, three requests of
998/998/4, the right ids reported accepted), `eachStateGoesUpInItsOwnRequest`,
`contentOfNamedArticlesIsAskedForInBatches`, `idsAreReadToTheEndOfTheContinuation`
and `contentsAreReadToTheEndOfTheContinuation` (the second request carries the
first page's token), the initial sync asking for `xt=read` and no `ot`, a later
sync asking for `ot` and no `xt` and not walking the starred stream, and a refused
batch stopping before any pull. `db`: `PendingChangeDaoTest` for the clear that
only matches the uploaded value, and `ItemDaoTest.theStateASyncLearnedSkipsAnArticleWithAPendingValue`.

The two `SynchronizerTest` cases and `SyncWorkerTest` kept working after their
dispatchers learned the calls the new sync makes.

**Two of the tests were checked against a broken build**, because a test written
after the code it covers can pass for the wrong reason. Replacing
`database.withTransaction { }` with a plain block turns
`aFailureBeforeTheCursorIsWrittenRollsBackEverything` red and nothing else;
clearing the whole queue instead of the matching halves turns
`aChangeMadeWhileTheUploadIsInFlightStaysQueued` red and nothing else. Both
mutations were reverted.

### Against the real server

Done, three syncs, not a week. A debug build was installed on `bench-pixel6-aosp`
and synced against the `ledev` account on `https://rss.lan` after the Caddy
local-authority root was pushed to `/data/misc/user/0/cacerts-added/33c83ea1.0`
(mode 644, `system:system`); the app's network security config already trusts user
CAs, so nothing in the app had to change. The credentials came from the main
checkout's gitignored `local.properties`, copied into the worktree for the build
and deleted afterwards; no value was printed or written anywhere.

Read back with `adb -s emulator-5554 shell run-as app.lenews.debug`:

| After | articles | distinct ids | feeds | folders | pending | starred | `read = 1` ⇔ `read_at` breaches | cursor |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| the first sync | 746 | 746 | 19 | 6 | 0 | 0 | 0 | 1788693705 (11:21:45 UTC) |
| starring one article, then syncing | 746 | 746 | — | — | 0 | 1 | 0 | 1788693799 |
| unstarring it, then syncing | 746 | 746 | — | — | 0 | 0 | 0 | 1788693851 |

So: no duplicate row, the cursor advances and is a sane epoch second, invariant 2
holds, and the star round trip works both ways — the pending change was uploaded
and cleared, and the article stayed starred through step 4d, which would have
unstarred it had the server not taken the change. The account was left as it was
found.

**Still pending: the week.** Nobody can watch a week of syncing in one sitting.
The command that repeats the check, from this worktree, with the emulator booted
and the debug build installed:

```
adb -s emulator-5554 shell run-as app.lenews.debug cat databases/lenews-db > /tmp/lenews-db
sqlite3 /tmp/lenews-db "Select count(*), count(Distinct id) From Article;
  Select count(*) From Article Where (read = 1) <> (read_at Is Not Null);
  Select \"cursor\", datetime(\"cursor\", 'unixepoch') From Account Where id = 1;"
```

Also still pending, from ticket 22 and unchanged by this one: the root certificate
on the **phone**, and a second sample proving the feeds really produce a few
hundred articles a day. The 746 articles above are one snapshot, not a rate.

### What was consciously left out

- **Retention (§4).** The seam is there and named; nothing is deleted. Ticket 15.
- **The history screen (§5, §8).** Ticket 16. `read_at` is written by every route,
  including a read learned at sync, and nothing shows it.
- **No temporary table.** Explained above; ticket 15 will add one for the mirror
  delete, and 4c and 4d could move onto it then if that turns out simpler.
- **Feed colours still run after the transaction**, one HTTP request per new feed,
  as upstream wrote it. They are not article state and the model says nothing
  about them.
- **The `stream/items/contents` request sends the write token.** FreshRSS does not
  document whether it checks one for that endpoint; sending a valid token cannot
  hurt and not sending one might. Nobody read the server source for this.
- **`ArticleStateChange` is not a general vocabulary.** It exists so that the api
  module can batch the four uploads and tell the caller which one it just sent.
