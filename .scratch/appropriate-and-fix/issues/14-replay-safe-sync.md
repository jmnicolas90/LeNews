# 14 — Make sync replay-safe: one transaction, idempotent inserts

Type: task
Status: open
Blocked by: 13, 22

## Question

Implement the sync sequence of `docs/article-store.md` in `GReaderRepository.synchronize()` and `GReaderDataSource.synchronize()`: push local changes, pull, then apply articles, state, tags and the cursor **in one Room transaction**, with inserts that are idempotent under the identity rule (upsert or ignore, as the model says), and response-batch deduplication before insert. Remove `insertItemsIds`'s delete-and-reinsert in favour of the model's state application. Fix the review's `SyncWorker` finding while here: `Log.e(TAG, "Synchronization failed", e)` and a stable error carried in `Data`, not `printStackTrace()` plus `Exception(e.cause)`.

Test-first (`/tdd`), against MockWebServer as the existing `SynchronizerTest` does: the same response batch applied twice leaves the store identical; a response containing the same article twice inserts one row; a failure injected after the article insert and before the cursor write leaves the cursor unchanged and the next sync produces no duplicate; an article read offline and synced when the server still says unread ends up read on both sides. Ticket 09's findings say which of these is normal and which exceptional; test both.

**Done when** the gate is green through G7, those four tests pass, and a debug build synced against the debug account on `rss.lan` (ticket 22) for a week shows no duplicate row — checked by the agent with `adb shell run-as` on the debug build's database, never on the user's phone or account.
