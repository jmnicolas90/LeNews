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

### Review round (2026-09-06)

An adversarial review of this branch found five things. All five were accepted
and are fixed here, in a second commit on the same branch.

**1. A new article was stored with the state its content carried.**
`GReaderItemsAdapter` sets `isRead` from the read category `stream/contents`
sends, and the insert wrote it through: an article read on the web arrived as
`read = 1` with `read_at` null, which invariant 2 forbids, and nothing repaired
it afterwards because marking read only touches rows that are unread. Such a row
is invisible to the history and to the horizon for ever. `ItemDao.upsertArticles`
now inserts every new article in the neutral state — unread, unstarred, no
`read_at` — which is step 4b of the model, and the state application decides what
it is. The guard is in the DAO rather than in the caller, so no route into the
store can write the inconsistent row.

The state application had to be right for the **initial sync** too, and there it
had nothing to work with: §7 pulls the unread and the starred articles and the
unread and starred id lists, and no read id list at all, so an article read and
starred on the web would have come out of a first sync unread — back in the
timeline the user had already cleared. `GReaderRepository.readIdsTheServerHolds`
takes the read ids from the content's own flag for that one case, and from
`stream/items/ids` for every later sync, which is where §3 says they come from.
Either way the read is stamped with the sync's clock.

**2. An article id past 2^63 did not survive the round trip.** `fromLongForm`
read sixteen hexadecimal digits unsigned, but `fromDecimal` parsed with
`toLong()` and `toDecimal` printed with `toString()`: the same id threw a
`NumberFormatException` coming from `stream/items/ids` and went back to the
server negative. `ArticleIds` is unsigned on all three sides now
(`java.lang.Long.parseUnsignedLong`, `java.lang.Long.toUnsignedString`), and
`ArticleIdsTest` makes the round trip at 2^63 and at 2^64 − 1 in both directions
and checks that the hexadecimal and the decimal of those two ids agree.

**3. The response was sorted before its duplicates were resolved.** The DAO keeps
the last occurrence of an id, but `GReaderRepository.insertItems` sorted the
articles by publication date first, so an article whose date the server corrected
backwards had its **stale** occurrence sorted last and its stale content stored.
The duplicates are now resolved in response order, before the sort, which is what
§2 asks for. The new fixture
`app/src/androidTest/resources/greader/items_one_id_twice_read_and_starred.json`
delivers one id twice, with different content and a decreasing date, and the
second delivery carrying the read and starred categories;
`SynchronizerTest.syncStoresAnArticleThatArrivesReadWithTheMomentItWasLearned`
syncs it and asserts one row, the second delivery's content, `starred`, and read
with a `read_at` inside the window the sync ran in — never read with nothing to
say when.

**4. The folder timeline read the whole store.** `Feed.folder_id = ?` can only be
tested once an article row has been read, because the article table is the outer
loop of the join, so the filter removed nothing: opening a folder walked
`Article(pub_date)` from the newest article down, and a folder with no article
walked all hundred thousand of them to return none — **39 ms**, against a 10 ms
budget. The filter is now `Article.feed_id In (Select id From Feed Where
folder_id = ?)` **with the index named**, `Article Indexed By
index_Article_feed_id_pub_date`, which is the same device the drawer's per-feed
count already uses. Naming it is not decoration: the subquery alone left the
choice to the planner, which picks the fast plan while it has no statistics and
the 39 ms one after `PRAGMA optimize`. The plan is now the same in both states:

```
SEARCH Article USING INDEX index_Article_feed_id_pub_date (feed_id=?)
LIST SUBQUERY 1
  SEARCH Feed USING COVERING INDEX index_Feed_folder_id (folder_id=?)
SEARCH Feed USING INTEGER PRIMARY KEY (rowid=?)
SEARCH Folder USING INTEGER PRIMARY KEY (rowid=?) LEFT-JOIN
USE TEMP B-TREE FOR ORDER BY
```

The temporary b-tree is what the folder costs: the feeds of the folder are read
first, each feed's articles come back already in date order, and merging them
back into one order is a sort — of the folder's own articles, not of the store,
and bounded by the page. A folder holding a tenth of a hundred thousand articles
takes 3.7 ms; the same folder left to the planner takes 0.5 ms with statistics
and the empty folder 39 ms, and a page that is sometimes forty times over budget
is worse than one that is always well inside it.

**5. The history first page had no asserted budget**, although §6 gives it one
and names this ticket. It is asserted now, in both statistics states, with a
check that the page comes back full and ordered by `read_at` descending — a
budget met by a query returning nothing would say nothing. The query itself moved
into `db/src/androidTest/java/app/lenews/db/HistoryQuery.kt` so the budget test
and the benchmark measure the same text; ticket 16 replaces it with the real
one.

`TimelineTimeBudgetTest` therefore asserts **seven pages and two drawer counts**
under 10 ms where it asserted four and two, plus `COUNT(*)` under 20 ms on 20,000
articles as before. Medians of seven warm runs on `bench-pixel6-aosp`, 100,000
articles, before and after `PRAGMA optimize`:

| Query | before optimize | after optimize |
| --- | ---: | ---: |
| timeline, all articles | 0.51 | 0.34 |
| timeline, unread only | 0.54 | 0.40 |
| timeline, one feed | 0.36 | 0.40 |
| **timeline, one folder** | **3.68** | **3.70** |
| **timeline, a folder with no article** | **0.02** | **0.02** |
| timeline, starred | 0.40 | 0.34 |
| **history, first page** | **0.05** | **0.05** |
| drawer, unread count per feed | 1.09 | 1.15 |
| drawer, unread count of the last 24 hours | 1.02 | 1.30 |
| Paging `COUNT(*)`, 20,000 articles | 8.92 | 8.99 |

The seeded store grew an eleventh folder holding one feed and no article, which
is the case the old query was worst at.

### What the review round left out

- **The benchmark was not given the folder pages.** `TimelineSlownessBenchmarkTest`
  still measures the timelines it measured, and the two folder numbers above come
  from the budget test, which is where they are asserted. Adding them to the
  benchmark would mean seeding its empty folder too, for a number nobody gates on.
- **The unread-only timeline inside a folder is not measured.** It goes through
  the same named index and costs what the folder costs, but no number was put on
  it.
- **Nothing else in §3 moved.** The initial sync still reads the read state from
  the content's flag rather than from a read id list, because §7's initial sync
  does not fetch one; whether it should is ticket 14's, along with the rest of the
  sync rewrite.

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

## From the global review (2026-09-06, second run)

Two adversarial reviews read everything committed since the fork point for a
second time, after tickets 13 to 21 had landed. One finding lands on this
ticket's schema.

**A sixth table: `HorizonDropped`.** The store had no way of telling a
re-delivery of an article the horizon deleted from a genuinely new article, so
such a delivery was inserted neutral, stamped read at that sync by step 4c, and
kept thirty more days under a date on which nothing happened — breaking
invariant 5. The defence is a ledger of the ids the horizon branch removed:
`db/src/main/java/app/lenews/db/entities/HorizonDropped.kt`, one column, `id`,
the primary key, no foreign key (the article it names is exactly the one that is
gone). `HorizonDroppedDao` is what the sync reads it with; the writes live in
`Retention.kt`, with the delete they belong to. `docs/article-store.md` §1 now
describes it and §4 says how it is written and pruned; ticket 15 has the
retention half and ticket 14 the sync half.

**Room stays at version 1**, as this ticket set it: nothing is released, so
there is no database in the world that has to survive the change. The exported
schema `db/schemas/app.lenews.db.Database/1.json` gained the table and its
identity hash changed with it.

**The consequence, which is worth knowing before running a debug build.** Room
compares the stored identity hash on open, and a mismatch at the *same* version
throws `IllegalStateException: Room cannot verify the data integrity` —
`fallbackToDestructiveMigrationOnDowngrade` in `DbModule.kt` does not cover it,
because this is not a downgrade. So a `lenews-db` written before this commit —
including the one on `bench-pixel6-aosp` that tickets 14, 15, 16 and 19 synced
against — has to be cleared before the app will open it:

```
adb -s emulator-5554 shell pm clear app.lenews.debug
```

That takes the debug login with it, so the account has to be entered again
afterwards, which a debug build built with `local.properties` in place fills in
by itself. The emulator's store was **left as it was found** by this round
rather than cleared, because clearing it is the user's call.
