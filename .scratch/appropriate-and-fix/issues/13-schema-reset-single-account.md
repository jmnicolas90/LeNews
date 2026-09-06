# 13 — Reset the schema: single account, one read state, real indexes

Type: task
Status: resolved
Blocked by: 12

## Question

Implement the entities of `docs/article-store.md`. Room restarts at version 1, all `MigrationFromXToY` objects and `db/schemas/*.json` up to 6 are deleted, the schema export starts fresh. The account layer collapses to one FreshRSS account as the model says (one-row table or preferences); `ItemState` disappears or becomes what the model says; `Item` gets the identity constraint and the indexes the model lists; `useSeparateState` and every `separateState` branch in the query builders, DAOs and repositories is deleted, along with the account-scoped joins the review flagged (they have nothing to scope any more).

UI follow-through, kept minimal: the account selection screen becomes a FreshRSS login screen; the account tab loses add/switch/delete; `TabScreenModel.accountEvent` and the notification code that keys on account id are simplified but notifications keep working.

Test-first (`/tdd`): DAO and query-builder tests under `db/src/androidTest` for the identity rule (inserting the same id twice leaves one row), the timeline query against a seeded 100k store (reuse ticket 11's seeder) with a time budget, and the drawer counts. Re-run ticket 11's measurements against the new schema and record them.

Do not implement the sync changes (ticket 14), retention (15) or the history list (16) here, even though this ticket creates the columns they need.

**Done when** the gate is green through G7, `grep -rn "separateState\|ItemState" --include=*.kt` returns nothing (or only what the model kept), and the timeline query on the 100k seeded store runs inside the budget the model set.

## From the global review (2026-09-06)

Two adversarial reviews — one on the repo's own standards, one on what the
tickets asked for — read everything committed since the fork point. The upstream
defects below are still in the tree at HEAD and belong to this ticket rather
than to the round that found them, so they are recorded here and nothing was
changed for them.

- **`ItemState` is joined on `remote_id` alone, never on the account.**
  `db/src/main/java/app/lenews/db/queries/ItemsQueryBuilder.kt:48-49`
  (`SEPARATE_STATE_JOIN`), and the same join in the count, the item-detail and
  the pending-change queries (`db/.../dao/ItemStateChangeDao.kt:16-21`,
  `db/.../queries/ItemSelectionQueryBuilder.kt:40`). Two accounts holding the same
  FreshRSS article would read each other's state. The map lists multi-account
  under *Out of scope*, so this is not a bug to scope: this ticket deletes the
  join instead of fixing it, and that is the note the map asked for.
- **The indexes ticket 11 measured are this ticket's to create**:
  `Item(remote_id)` (the unread timeline, 77.7 ms to 2.8 ms),
  `ItemState(account_id)` or `ANALYZE` for the drawer, and whatever the article
  store model settles on for the timeline order. Re-run ticket 11's
  measurements against the new schema, as this ticket already says.


## Answer (2026-09-06)

The schema restarts at version 1 with the entities of `docs/article-store.md`
§1, the account layer is one row, and the timeline is flat in the number of
articles stored. Room's exported schema is a single
`db/schemas/app.lenews.db.Database/1.json`; the six inherited ones and the four
`MigrationFromXToY` objects are gone. `DbModule` adds
`fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)`, so a phone
still holding one of the six drops it and refills from the next sync rather
than crashing.

### The store

`Article` is the table (the Kotlin class kept the name `Item`: renaming it
would have touched some forty files for no behaviour change, and the ticket said
to prefer the smaller diff). Its primary key is the FreshRSS id as a `Long`, not
autoincrement, with no `remote_id`; `read`, `starred` (not null, default false)
and `read_at` (nullable epoch millis) are columns of it. `PendingChange`
(`article_id` primary key, foreign key to `Article`, cascade; nullable `read`
and `starred`) is the queue. `Account` is one row whose key is always `1`, with
no `type` and no `current_account`, and its `last_modified` is now `cursor`,
which is the word `CONTEXT.md` uses. `Folder` and `Feed` lost `account_id` and
gained a unique index on `remote_id`. `ItemState`, `ItemStateChange`, `Tag`,
`TagJoin`, `AccountType` and `AccountConfig.useSeparateState` are deleted, with
every `separateState` branch and every account-scoped join —
`grep -rn "separateState\|ItemState" --include=*.kt` returns nothing.

The indexes are §6's, exactly those: the primary key, `Article(pub_date)`,
`Article(feed_id, pub_date)`, `Article(read, pub_date)`,
`Article(starred, pub_date)`, `Article(read_at)`, `PendingChange(article_id)`,
`Feed(folder_id)`, `Feed(remote_id)` unique, `Folder(remote_id)` unique.
`PRAGMA optimize` runs once when the database is created, from a
`RoomDatabase.Callback` in `Database.kt` that `DbModule` and the two seeded
tests all add.

**The timeline query names its own join order.** `ItemsQueryBuilder` builds
`FROM Article CROSS JOIN Feed … LEFT JOIN Folder …`, which is how SQLite is told
not to reorder the loops. That is ticket 11's own recommendation — "either
`ANALYZE` runs, or the query is rewritten so the right plan is the only plan" —
and it is what makes the budget hold with no statistics at all. The 24-hour
filter became `pub_date >= (strftime('%s','now','-1 day') * 1000)` instead of
converting every row's date to text, so it can walk an index; the bound is one
constant in `db/queries/TimeWindow.kt` that the three query builders share.

### Identity on the way in and out

`ArticleIds` in the `api` module is the one place the two forms meet:
`fromLongForm` strips `tag:google.com,2005:reader/item/` and reads sixteen
hexadecimal digits **unsigned** (sixteen digits cover the whole 64-bit range and
a signed parse stops halfway; real ids are far below it, so the two forms agree
on every id either way), `fromDecimal` parses what `stream/items/ids` sends, and
`toDecimal` is what goes back to the server. Both adapters go through it, so the
two paths cannot disagree. `GReaderItemsIdsAdapter` returns `List<Long>` and the
Retrofit service with it. Fixtures were already consistent — the long form
`…/0005c62466ee28fe` and the decimal `1625234531559678` are the same article —
and the tests now assert that they parse to the same number.

### The sync, changed as little as the ticket allows

`GReaderRepository.synchronize` keeps upstream's sequence. What changed:
`insertItems` resolves the feed and the read time and then calls
`ItemDao.upsertArticles`, which inserts new ids and overwrites **content columns
only** for ids the store already holds, so state and pending changes survive a
re-delivery; the workaround that discarded articles arriving starred from the
main stream is gone, as §2 says; `insertItemsIds` became `applyItemStates`,
which marks the unread ids unread, marks the read ones read with the sync's
clock, and stars and unstars from the starred list; the pending changes are read
from `PendingChange` and cleared at the end, as `resetStateChanges` did.
Everything else in §3 — one transaction, batching, paging, the three id-list
walks, the starred-content fetch — is ticket 14's, and so is retention (15) and
the history screen (16).

`BaseRepository` is where becoming read is written: one transaction that sets
`read` and `read_at` on the article and queues the decision. `read_at` is
stamped here rather than left to ticket 16 because invariant 2 (`read = 1` if
and only if `read_at` is not null) would otherwise be false from the first swipe.
Mark-all-read queues **only the unread articles** — one row per article the user
is actually changing, not one per article stored, which is the unbounded write
ticket 11 measured on `ItemState`.

### The account layer

`AccountSelectionScreen`, `AccountSelectionScreenModel` and
`AccountSelectionDialog` are deleted: with one service there is nothing to
choose, so `MainActivity` opens `AccountCredentialsScreen` directly when there is
no account, and that screen is the FreshRSS login screen (FreshRSS icon and name,
no back arrow at the root). `AccountCredentialsScreenModel.login()` calls
`accountDao().upsert`, which replaces the one row instead of inserting a second.
`AccountTab` lost the add-account button, the delete row and the "Other accounts"
switcher, and kept rename, credentials and notifications. `TabScreenModel`
collects one account instead of "the current account" and builds `GReaderError`
directly. `SyncWorker` lost `ACCOUNT_ID_KEY` — the notification intent carries
`FROM_SYNC_KEY` and, when there is a single new article, `ITEM_ID_KEY` as a
`Long` — and `Synchronizer.synchronizeAccounts` became `synchronize`, returning
the account and its result. `SyncAnalyzer` takes one account. Notifications work:
`SyncWorkerTest.autoWorkerWithNotificationsTest` posts one, triggers both
actions, and checks the article row and the queue.

`Migrations.kt` went with them: its only job was moving per-account credential
keys into the encrypted preferences for a version of Readrops that predates this
fork, and those keys no longer exist. `Preferences.lastVersionCode`, which
nothing else read, went with it.

Six strings orphaned by the removal were deleted from English and the thirteen
locale files that had them — `add_account`, `choose_account`, `delete_account`,
`delete_account_question`, `new_account`, `other_accounts` — along with
`ic_add_account.xml`. `ic_freshrss.xml` and a new `freshrss` string moved from
the `db` module to `app`, because the enum that referenced them is gone and a
library resource nothing in the library uses is a lint finding waiting to
happen.

### The numbers, re-measured on the new schema

`TimelineSlownessBenchmarkTest` was rewritten for this schema: same shape, two
passes instead of five (there is no "before the index" any more — the indexes
are the schema), the control at 10,000 articles and an opt-in run at 100,000
behind `-Pandroid.testInstrumentationRunnerArguments.lenewsBenchmark=full`. The
seeder moved into `ArticleStoreSeeder`, shared with the budget test. Warm
medians in milliseconds on `bench-pixel6-aosp`:

| Query | 10,000 | 100,000 | ticket 11 at 110,000, before |
| --- | ---: | ---: | ---: |
| timeline all — page 1 | 0.3 | **0.4** | 90.2 |
| timeline all — page 2 | 0.3 | 0.4 | 91.4 |
| timeline all — deep page (OFFSET 5000) | 2.1 | 2.3 | 162.6 |
| timeline all — Paging `COUNT(*)` | 4.0 | **46.6** | 125.3 |
| timeline unread — page 1 | 0.3 | **0.4** | 77.5 |
| timeline unread — Paging `COUNT(*)` | 0.9 | 1.0 | 75.3 |
| timeline one feed — page 1 | 0.3 | **0.4** | not measured |
| timeline starred — page 1 | 0.3 | **0.4** | not measured |
| history — page 1 | 0.1 | 0.1 | did not exist |
| drawer — unread count per feed | 1.0 | **1.1** | 73.4 |
| drawer — unread count of the last 24 hours | 1.5 | **1.1** | 26.4 |
| sync — upsert of 1000 re-delivered articles | 82.3 | 67.0 | 1655.6 for the state rewrite |
| sync — upsert of 1000 new articles | 35.7 | 36.1 | — |
| mark all read — queue the pending changes | 2.2 | 2.3 | 333.7 to grow `ItemState` |
| mark all read — mark and stamp the articles | 12.7 | 16.1 | 142.6 on the next sync's delete |

Every timeline and drawer number is now **flat in the number of articles
stored** — ticket 11's whole finding was that they were linear in it. The one
that still grows is Paging's `COUNT(*)`, which is exactly what ticket 11 said no
index reaches and only retention bounds; at the 20,000 articles retention will
leave it is 8.8 ms, inside §6's 20 ms budget. The 1.6 s per sync that
`insertItemsIds` spent matching id strings is gone with the table it filled.
Seeding: 311.8 ms for 10,000 articles (23 MB), 1690.8 ms for 100,000 (229 MB).

**The benchmark found one thing worth stopping for.** With `PRAGMA optimize`
run — which the model puts at the end of every sync, so the app will be in that
state from ticket 14 — the drawer's per-feed unread count went from **1.1 ms to
68.4 ms** on 100,000 articles: given statistics, SQLite prefers
`index_Article_feed_id_pub_date`, because that hands the rows back already
grouped and saves the temporary b-tree, and then walks all hundred thousand of
them instead of the few thousand unread ones. `FeedUnreadCountQueryBuilder` now
names the index it walks (`Indexed By index_Article_read_pub_date`), which holds
the plan whether the planner has statistics or not, and
`TimelineTimeBudgetTest` asserts the budget **in both states** so the next such
reversal fails the gate instead of shipping.

### Tests

Test-first where there was a seam. New: `ItemDaoTest` for the identity rule (the
same id twice leaves one row with the second content and the first state, and
the pending change untouched; the same id twice in one response; an article
arriving starred; reading, re-reading and unreading and what each does to
`read_at`), `ArticleIdsTest` for the two parsings and the way out, and
`TimelineTimeBudgetTest` for §6's budget — the four first pages and the two
drawer counts under 10 ms on 100,000 articles, Paging's `COUNT(*)` under 20 ms on
20,000, each measured as the median of seven warm runs, each asserted before and
after `PRAGMA optimize`, with a check that the seeded store really holds what it
should so a budget cannot be met on an empty table. What it measures, logged
under the tag `LeNewsBudget` on every run, before and after the optimize:
0.48 / 0.34 ms for the all-articles page, 0.48 / 0.33 for unread, 0.37 / 0.36
for one feed, 0.34 / 0.49 for starred, 1.10 / 1.08 for the per-feed unread
count and 1.01 / 1.00 for the 24-hour count on 100,000 articles, and
8.84 / 8.85 ms for `COUNT(*)` on 20,000. `SynchronizerTest`'s
`syncStoresFourRowsForOneArticle_knownDuplicateDefectUntilTicket14` became
`syncStoresOneRowForOneArticle`: the fixture still delivers the same article four
times and the store now holds one row. `MigrationsTest` and `TagDaoTest` are
deleted; `GetFoldersWithFeedsTest`, `SyncAnalyzerTest`, `SyncWorkerTest`,
`FeedDaoTest`, `FolderDaoTest` and the two query-builder tests were adapted.

### Lint

`app/lint-baseline.xml` was **not** regenerated: lint is green without it. Before
and after it is the same file — 411 entries, 347 errors, 64 warnings. What
changed is that 341 of those errors are still found and **9 entries no longer
match anything**, because the deleted strings took their `MissingTranslation` and
`ExtraTranslation` findings with them. No new error of any kind; one new
unfiltered warning, `VectorRaster` on `ic_freshrss.xml`, which followed the file
from `db` (where warnings print and stop nothing) into `app`.

### What was consciously left out

- **The rest of §3.** One transaction around the whole sync, batching the
  uploads, paging to the end of `continuation`, the three id-list walks as the
  model describes them and the starred-content fetch are ticket 14's. The sync
  still advances its cursor outside a transaction and still clears the whole
  pending-change queue rather than the halves the server accepted.
- **Retention (§4) and the history screen (§5, §8).** The columns and the
  indexes they need exist and are measured; nothing deletes an article and
  nothing shows the history. The history query is measured in the benchmark
  because the index is this ticket's, but no production code runs it yet.
- **`ItemScreenModel`'s state-change buffer** was left as it is. It is upstream's
  way of not writing to the database while the user pages through articles, it
  now carries `Long` ids, and rewriting it is not this ticket's.
- **`FoldersAndFeedsQueryBuilder` with "hide read feeds"** was not measured. It
  is the same `GROUP BY` shape as the per-feed count and could in principle flip
  its plan the same way; it drives from `Feed` (a hundred rows) rather than from
  `Article`, so it is not the same risk, but nobody has put a number on it.
- **The `Item` class was not renamed to `Article`.** The table is `Article`; the
  class is not, and the mismatch is documented on the entity.
