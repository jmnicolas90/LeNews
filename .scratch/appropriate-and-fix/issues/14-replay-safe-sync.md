# 14 — Make sync replay-safe: one transaction, idempotent inserts

Type: task
Status: open
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
