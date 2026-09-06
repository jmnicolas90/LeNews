# 11 — Measure the slowdown on a year-sized database

Type: task
Status: resolved
Blocked by: 03

## Question

The user reports the app gets slow after a while and is snappy on a clean install. The code names suspects but nobody has measured. Diagnose before designing: use `/diagnosing-bugs`. The real database cannot be pulled (store build), so reproduce on the emulator.

Write an instrumented test (or a small benchmark under `db/src/androidTest`) that seeds a Room database with a realistic year of one FreshRSS account: about 110,000 `Item` rows across ~100 feeds in ~10 folders, `ItemState` rows for the unread and starred ids as the sync leaves them (a few thousand unread, a few hundred starred), and tags on a fraction of items. Then time, cold and warm, on `bench-pixel6-aosp`:

- the timeline query `ItemsQueryBuilder.buildItemsQuery(filters, separateState = true)` as paging executes it: first page, a page at offset 50 and one deep at offset 5,000, unread-only and all, sorted by date;
- the unread-count queries (`FeedUnreadCountQueryBuilder`, `selectUnreadNewItemsCountByItemState`) that the drawer runs;
- one sync's `insertItemsIds`: `deleteItemStates` then reinsert of all state rows;
- the per-item tag query pattern (`selectAllByItem` per visible item) for a 50-item page.

For every query, capture `EXPLAIN QUERY PLAN` and note where it says `SCAN` rather than `SEARCH ... USING INDEX`. Then add the obvious missing indexes (`Item.remote_id`, `Item.pub_date`, `Item.read`) in the test database only, and re-time. The point is a number for "how slow, and does an index alone fix it", not a fix in the app — the fix lands with ticket 13's schema reset.

Record the numbers in the ticket's answer as a table: query, rows, time before, time after, plan before, plan after. Run with the same seeded size at 10k rows as a control so the growth is visible.

**Done when** the table exists, each pain-point suspect is confirmed or cleared by a measurement, and ticket 12 can be grilled against it.

## Answer (2026-09-06, revised the same day — see *Review round* at the end)

Measured, not guessed. `db/src/androidTest/java/app/lenews/db/benchmark/TimelineSlownessBenchmarkTest.kt`
seeds a real on-disk Room database on `bench-pixel6-aosp` and times the timeline
as Paging executes it, the two drawer counts, one sync's `ItemState` rewrite
**through the production code path**, mark-all-read and the sync that follows it,
and the per-article tag query as the screen model issues it, cold and warm, with
`EXPLAIN QUERY PLAN` for each.

**Two headlines.**

1. **The timeline is linear in the number of articles stored, and adding the
   indexes changes nothing until the planner is also given statistics.** The
   first page costs 7 ms on a 10,000-article database and 90 ms on a
   110,000-article one. Creating an index on `Item.pub_date` leaves it at 90 ms.
   Running `ANALYZE` after creating it takes it to **0.4 ms**.
2. **One sync spends over a second and a half in a Kotlin loop that never
   touches the database.** `GReaderRepository.insertItemsIds` matches every
   unread and read id against the starred list with `starredIds.any { … }` and
   `starredIds.remove(…)`: 5,000 ids against a 1,000-element list of
   48-character strings, **1,570 ms** on the year-sized fixture and **1,121 ms**
   on the control. It is not the timeline's slowness — it is per sync, not per
   swipe — but it is the single most expensive thing in this whole benchmark,
   and it costs the same whether the phone holds 10,000 articles or 110,000.

### What was seeded

One FreshRSS account, 10 folders, 100 feeds, articles spread over 365 days with
a few days of jitter, inserted oldest first as a year of daily syncs would leave
them. `remote_id` in the long `tag:google.com,2005:reader/item/<16 hex>` form the
tree stores. One article in five carries two of ten tags.

`ItemState` holds what one classic sync actually leaves: the data source caps
`stream/items/ids` at `MAX_ITEMS` = 2500 for the reading list and
`MAX_STARRED_ITEMS` = 1000 for the starred stream, so **a sync** leaves about
6,000 rows whatever the size of `Item`. The fixture sits exactly at those caps,
which is the worst case the API allows and an ordinary one for a heavy reader.

That bound is **only true of a sync**. Mark-all-read breaks it: see the
mark-all-read rows below, where `ItemState` goes to one row per stored article.

| | Articles | Feeds | Folders | ItemState after a sync | TagJoin | Seed time | On disk |
|---|---:|---:|---:|---:|---:|---:|---:|
| Control | 10,000 | 100 | 10 | 5,500 | 4,000 | **0.39 s** | 21 MB |
| Year-sized | 110,000 | 100 | 10 | 5,955 | 44,000 | **2.3 s** | 219 MB |

Seeding uses compiled statements in transactions of 10,000 rows, which is why a
year of articles seeds in under three seconds. Raw `executeInsert` is used for
fixture setup only; every measured write goes through the real DAO.

### How much of it is growth (warm median, milliseconds, no added index)

This is the table that answers "does it get slower as articles accumulate".

| Query | 10,000 | 110,000 | Growth for 11x the articles |
| --- | ---: | ---: | ---: |
| timeline all — Paging `COUNT(*)` | 7.6 | 125.3 | **x16** |
| timeline all — page 1 (LIMIT 50 OFFSET 0) | 7.2 | 90.2 | **x12.5** |
| timeline all — page 2 (LIMIT 50 OFFSET 50) | 7.9 | 91.4 | **x11.6** |
| timeline all — deep page (OFFSET 5000) | 35.4 | 162.6 | x4.6 |
| timeline unread — Paging `COUNT(*)` | 6.0 | 75.3 | **x12.6** |
| timeline unread — page 1 | 6.7 | 77.5 | **x11.6** |
| timeline unread — page 2 | 7.2 | 78.3 | x10.9 |
| timeline unread — deep page (OFFSET 5000) | 13.3 | 82.9 | x6.2 |
| drawer — unread count per feed | 5.1 | 73.4 | **x14.4** |
| drawer — unread count of the last 24 hours | 2.2 | 26.4 | x12 |
| tags — `TagDao.selectAllByItem` x 50, suspend, as the screen model | 10.9 | 6.6 | x0.6 — flat |
| tags — the same 50 queries as raw SQL | 0.9 | 0.8 | x0.9 — flat |
| sync — `deleteItemStates` | 2.0 | 2.3 | x1.2 — flat |
| sync — `insertItemsIds` id mapping, no database | **1120.6** | **1569.6** | x1.4 — not growth, see below |
| sync — the mapping plus the three `ItemStateDao.insert` calls | **1221.6** | **1655.6** | x1.4 |
| sync — the same rows by raw `executeInsert` (not the production path) | 18.9 | 20.8 | x1.1 — flat |
| mark all read — `ItemStateDao.setAllItemsRead` | 22.2 | **333.7** | **x15** |
| after mark all read — the next sync's `deleteItemStates` | 4.1 | **142.6** | **x35** |
| after mark all read — the next sync's `insertItemsIds` inserts | 1183.5 | 1646.6 | x1.4 |

Every timeline and drawer number is **linear in the total article count**. Note
the unread timeline: only ~2,500 articles are unread whatever the database size,
yet its first page still goes from 6.7 ms to 77.5 ms. The cost is in what the
query walks, not in what it returns.

The id mapping's x1.4 is **not** growth in the article table. The loop is
O(unread + read ids x starred ids) and both are capped by the API, so it does not
see `Item` at all. What differs between the two sizes is how many of the 1,000
starred ids fall inside the capped windows and are therefore `remove`d as the
loop runs: half of them in the control, 4% of them at a year's size, so the list
being scanned stays longer at 110,000. In other words the cost is set by **how
old your starred articles are**, not by how many articles you store. On an
initial sync `readIds` is empty (`GReaderDataSource` fetches it only for a
classic sync), so the initial sync pays about half of it.

### Before and after, on the year-sized database (110,000 articles)

Five passes, so that `Item.remote_id` can be told apart from the other four
indexes and statistics can be told apart from both. Warm medians in milliseconds.
The two `ANALYZE`-free passes are the surprise: on their own the indexes move
almost nothing.

| Query | Rows out | No index | `Item(remote_id)` | `Item(remote_id)` + `ANALYZE` | All five, no statistics | All five + `ANALYZE` |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| timeline all — Paging `COUNT(*)` | 1 | 124.4 | 125.3 | 125.6 | 125.0 | **80.8** |
| timeline all — page 1 | 50 | 90.6 | 90.5 | 90.6 | 90.3 | **0.4** |
| timeline all — page 2 | 50 | 90.8 | 91.8 | 92.1 | 92.0 | **0.5** |
| timeline all — deep page (OFFSET 5000) | 50 | 160.0 | 161.3 | 160.7 | 161.3 | **4.0** |
| timeline unread — Paging `COUNT(*)` | 1 | 75.3 | 76.7 | **1.6** | 76.1 | 1.6 |
| timeline unread — page 1 | 50 | 78.4 | 77.7 | **2.8** | 76.9 | 2.8 |
| timeline unread — page 2 | 50 | 78.0 | 78.8 | **3.5** | 77.6 | 3.4 |
| timeline unread — deep page (OFFSET 5000) | 0 | 82.6 | 83.3 | **6.2** | 82.5 | 6.3 |
| drawer — unread count per feed | 100 | 73.8 | 74.3 | **1.2** | **1.3** | 1.2 |
| drawer — unread count of the last 24 hours | 1 | 25.6 | 26.0 | **1.1** | **1.2** | 1.1 |
| tags — `TagDao.selectAllByItem` x 50 | 18 | 6.6 | 6.4 | 6.5 | 6.3 | 5.9 |
| tags — the same 50 queries as raw SQL | 18 | 0.8 | 0.8 | 0.8 | 0.8 | 0.9 |
| sync — `deleteItemStates` (5,955 rows) | — | 2.3 | 2.2 | 2.3 | 3.2 | 3.4 |
| sync — the same rows by raw `executeInsert` | — | 20.8 | 21.6 | 21.4 | 21.8 | 22.9 |
| sync — `insertItemsIds` id mapping, no database | — | 1569.6 | not run | not run | not run | 1566.6 |
| sync — the mapping plus the three `insert` calls | 5,955 | 1655.6 | not run | not run | not run | 1651.4 |
| mark all read — `setAllItemsRead` (5,955 rows before, **110,000** after) | 110,000 | 333.7 | not run | not run | not run | 330.2 |
| after mark all read — `deleteItemStates` (110,000 rows) | — | 142.6 | not run | not run | not run | 133.7 |
| after mark all read — `insertItemsIds` inserts | 5,955 | 1646.6 | not run | not run | not run | 1652.0 |
| timeline all — page 1, forced onto the `pub_date` index | 50 | — | — | — | 0.4 | 0.4 |
| timeline all — deep page, forced onto the `pub_date` index | 50 | — | — | — | 4.1 | 4.3 |

The three rows that cost seconds rather than milliseconds run in the first and
last pass only — none of them is sensitive to the index arrangement the way the
timeline is — which is what keeps the control cheap enough for the gate.

Cold and warm stay within a few percent of each other throughout (page 1: 91.1
cold against 90.6 warm; the id mapping: 1577.7 against 1569.6), for the reason
in the caveats below.

The plans, for the queries where they move:

| Query | No index / `remote_id` alone | `remote_id` + `ANALYZE` | All five + `ANALYZE` |
| --- | --- | --- | --- |
| timeline all — page 1 | `SEARCH Feed USING index_Feed_account_id` -> `SEARCH Item USING index_Item_feed_id` -> `SEARCH ItemState` -> **`USE TEMP B-TREE FOR ORDER BY`** | `SCAN Feed` -> `SEARCH Item USING index_Item_feed_id` -> **still the temp b-tree** | `SCAN Item USING bench_Item_pub_date` -> `BLOOM FILTER ON Feed` -> `SEARCH Feed`, **no sort** |
| timeline unread — page 1 | same, temp b-tree | `SCAN ItemState` -> **`SEARCH Item USING bench_Item_remote_id`** -> temp b-tree over 2,500 rows | identical to the column on its left |
| drawer — unread count per feed | **`SCAN Item USING index_Item_feed_id`** -> `SEARCH ItemState` | `SCAN ItemState` -> **`SEARCH Item USING bench_Item_remote_id`** | same (and with the indexes but no statistics: `SEARCH ItemState USING bench_ItemState_account_id` -> `SEARCH Item USING bench_Item_remote_id`) |
| drawer — 24 hours | **`SCAN Item`** -> `SEARCH ItemState` | `SCAN ItemState` -> `SEARCH Item USING bench_Item_remote_id` | same |
| mark all read | `SEARCH ItemState USING COVERING INDEX index_ItemState_remote_id_account_id` / `LIST SUBQUERY` over `SEARCH Feed` -> `SEARCH Item USING index_Item_feed_id`, then the same pair again for the `Insert Or Ignore … Select` | unchanged | unchanged |
| sync — `deleteItemStates` | `SCAN ItemState` | `SCAN ItemState` | `SCAN ItemState` (`SEARCH ItemState USING bench_ItemState_account_id` in the pass without statistics, and back to `SCAN` once `ANALYZE` knows every row matches) |
| tags | `SEARCH TagJoin USING index_TagJoin_item_id` -> `SEARCH Tag` by rowid | unchanged | unchanged |

`Item(remote_id)` was created alone in 55 ms; the other four
(`Item(pub_date)`, `Item(read)`, `Item(feed_id, pub_date)`,
`ItemState(account_id)`) took 137 ms together. `ANALYZE` costs 8 ms on a 219 MB
database with one added index and 23 ms with five. All of them exist inside the
benchmark's own database file only.

The full report the test writes, with every SQL string and every plan in full,
is pulled from the device:

```
adb -s emulator-5554 pull \
    /sdcard/Android/data/app.lenews.db.test/files/timeline-slowness-110000.md
```

### The six things the numbers actually say

**1. The timeline sorts the whole account on every page.** The plan before is
`SEARCH Feed USING index_Feed_account_id` -> `SEARCH Item USING index_Item_feed_id`
-> `USE TEMP B-TREE FOR ORDER BY`. It never says `SCAN Item`, and that is a trap:
it is a `SEARCH` executed once per feed, so it visits all 110,000 rows anyway and
then sorts them by `pub_date` in a temporary b-tree to hand back fifty. That is
the whole mechanism of "it gets slower as articles accumulate". Nothing about the
query is wrong at 2,000 articles and nothing about it is survivable at 110,000.

**2. An index alone does not fix it — the planner has to be told.** With
`Item(pub_date)` created and no `ANALYZE`, the timeline stayed at 90 ms and the
plan did not change by one line. SQLite has no statistics, guesses that
`Feed.account_id = 1` is selective, and keeps driving from `Feed`. Forcing it
(`FROM Item INDEXED BY ... CROSS JOIN Feed`, measured as its own row: 0.4 ms)
shows the index could always have served the query. After `ANALYZE` the planner
picks that plan by itself. **A design that adds the index and stops has fixed
nothing.** Either `ANALYZE` runs (it costs 23 ms on a 219 MB database and Room
can run it after a sync), or the query is rewritten so the right plan is the only
plan.

**3. Paging's `COUNT(*)` is the one thing an index cannot fix.** Room's
`LimitOffsetPagingSource` wraps the query as `SELECT COUNT(*) FROM ( <query> )`
and `SELECT * FROM ( <query> ) LIMIT ? OFFSET ?`. The count runs on the *initial*
load of every `PagingSource` — and a new `PagingSource` is made on every
invalidation of `Item`, `Feed`, `Folder` or `ItemState`, which is to say **every
time an article is marked read and after every sync**. It still costs 81 ms at
110,000 articles with every index and statistics in place, because counting the
account's articles genuinely means visiting all of them. Before the indexes the
full price of one swipe is `COUNT(*)` 125 ms + page 90 ms = **215 ms of database
work per article marked read**, on an emulator. That is the slowness the user
reports.

**4. Deep scrolling is quadratic in the page number.** `OFFSET 5000` costs
162 ms before and 4.0 ms after, because `LIMIT/OFFSET` paging re-walks every row
it skips. Even fixed, page 100 costs ten times page 10.

**5. The most expensive single operation in the app is not a query.** The id
mapping inside `insertItemsIds` is 1.6 s of pure CPU per sync, ten times the cost
of everything else the sync does to `ItemState` put together (delete 2.3 ms,
Room's inserts 86 ms). It is a nested scan of two lists, it is fixed by a
`HashSet`, and it is invisible to every `EXPLAIN QUERY PLAN` in this ticket.

**6. Mark-all-read unbounds `ItemState`.** It is the only operation that makes
the state table as large as the article table, and the cost lands on the *next*
sync, not on the button press.

### The suspects from the map, one by one

- **`Item` has a single index (`feed_id`) — CONFIRMED, and it is the main one
  for the timeline.** It is not that a scan happens where a search should; it is
  that the only index worth having for the timeline's `ORDER BY pub_date DESC`
  does not exist, so every page sorts the account. 90 ms -> 0.4 ms at 110,000
  articles once `Item(pub_date)` exists *and* the planner uses it. For a design
  ticket: the article store needs an index that serves the timeline order
  directly, and the timeline query has to be shaped so that index is the only
  sensible plan (`Item` driving, not `Feed`), rather than relying on statistics
  that nothing refreshes.

- **Unindexed `remote_id` join — measured in isolation, and it splits three
  ways.** The index was created on its own, before the other four, with and
  without `ANALYZE`:

  - **The all-items timeline: CLEARED.** `Item(remote_id)` changes it by
    nothing at all — 90.6 ms without the index, 90.5 with it, 90.6 with it and
    statistics. That timeline is `Item(pub_date)`'s problem and nobody else's.
  - **The unread timeline: CONFIRMED, and `Item(remote_id)` is the whole fix.**
    77.7 ms -> **2.8 ms**, with the plan turning into `SCAN ItemState` ->
    `SEARCH Item USING bench_Item_remote_id`. The other four indexes add
    nothing on top (2.8 ms either way). But it only happens **after `ANALYZE`**:
    with the index created and no statistics the query does not move (77.7 ms).
  - **The drawer: CONFIRMED, and the index is necessary but not sufficient.**
    73.8 ms -> 74.3 ms with `Item(remote_id)` alone, unchanged, still
    `SCAN Item`. It takes something to make the planner drive from `ItemState`
    instead: either statistics (1.2 ms) or an index on `ItemState(account_id)`
    (1.3 ms, `SEARCH ItemState USING bench_ItemState_account_id` ->
    `SEARCH Item USING bench_Item_remote_id`).

  For a design ticket: this is an artefact of read state living in a second
  table keyed by a 48-character string. Ticket 13's collapse removes the join
  rather than indexing it.

- **Items are never deleted — CONFIRMED as the driver of every growing number.**
  Every number that grows grows with `Item`'s row count and nothing else: the
  unread timeline has the same ~2,500 unread rows at both sizes and still goes
  from 6.7 ms to 77.5 ms. The retention rule (ticket 15) is not only about disk:
  219 MB for a year is survivable, 90 ms per page is not. It is also the only fix
  that bounds `COUNT(*)`, which no index reaches — and the only one that bounds
  what mark-all-read writes.

- **Full `ItemState` delete-and-reinsert per sync — CONFIRMED, but not in the
  half anyone was watching.** The SQL is cheap and flat: 2.3 ms to delete 5,955
  rows, 86 ms for Room to put them back (1655.6 ms for the mapping and the
  inserts, 1569.6 ms of which is the mapping). What costs is
  `insertItemsIds`'s own Kotlin: for each of 5,000 unread and read ids it runs
  `starredIds.any { starredId -> starredId == id }` over a 1,000-element list of
  48-character strings and then `starredIds.remove(id)`, which is another linear
  scan plus an array copy. **1,570 ms per sync at a year's size, 1,121 ms on the
  control** — flat in the article count, because both lists are capped by the
  API, but the largest single cost in this benchmark. The fix is a `HashSet` and
  is not this ticket's. Room's insert adapter costs a further 86 ms against the
  20.8 ms the same rows take through a compiled statement, which is a x4 tax on
  6,000 rows and worth knowing before anyone makes this list bigger.
  The other two things wrong with this path stand and are still not this
  ticket's: it is not one transaction, so a failure between the delete and the
  inserts loses every read state, and the caps mean an unread article older than
  the newest 2,500 quietly stops having a state row at all.

- **`ItemState` is bounded by the API caps — CLEARED for a sync, FALSE in
  general.** Mark-all-read is the exception, and it is a button on the timeline.
  `TimelineScreenModel.setAllItemsRead()` -> `Repository.setAllItemsRead()` ->
  `ItemStateDao.setAllItemsRead()`, whose second half is
  `Insert Or Ignore Into ItemState … Select … From Item Inner Join Feed`: a
  state row **for every stored article**. On the year-sized database `ItemState`
  goes from **5,955 rows to 110,000** in 333.7 ms, and the bill arrives on the
  next sync, which must now delete 110,000 rows instead of 5,955 —
  **142.6 ms against 2.3 ms, x62** — before reinserting the capped 6,000. One
  mark-all-read therefore turns the next sync's state rewrite from ~1.66 s into
  ~1.79 s and leaves a table that is ten times the size it needs to be until
  that sync runs. On the control the same thing happens at 10,000 rows (22.2 ms
  to grow, 4.1 ms to delete), so this is growth in `Item` again: it is bounded
  by nothing but retention. For a design ticket: mark-all-read should not
  materialise a row per article, and the state table should not be able to
  outgrow what the server will ever send back.

- **Per-article tag query — CLEARED as a growth problem, and it is not free.**
  Fifty `TagDao.selectAllByItem` calls — the real suspend DAO, awaited one after
  another from a coroutine, which is what `TimelineScreenModel.buildPager()`
  does inside its `PagingData.map` — cost **6 to 11 ms for a page of fifty**,
  identical at 10,000 and 110,000 articles. The same fifty queries as raw SQL on
  the open connection cost **0.8 ms**, so about 90% of it is Room and coroutine
  overhead per call rather than the query. The spread (10.9 ms on the first pass
  of a run, 5.9 ms on the fifth) is JIT warm-up, not database size. Both indexes
  the query needs already exist and the plan is
  `SEARCH TagJoin USING index_TagJoin_item_id` then `Tag` by rowid. The code
  review's "one tag query per visible article" is real, and at one page it is a
  few milliseconds on the Paging map rather than the sub-millisecond it looked
  like when measured as raw SQL — still not the slowness the user reports, still
  a tidiness finding, but a design ticket that keeps tags should batch it rather
  than call it fifty times.

### Caveats on the absolutes

- `bench-pixel6-aosp` is an **x86_64 emulator on a fast desktop**, with the
  host's file cache under it. A phone's storage and CPU are slower, so every
  absolute here is optimistic — probably by several times. **The ratios are the
  finding**, not the milliseconds: x12 for x11 articles, and 200x from one plan
  change.
- The exception is the id mapping, which touches no storage at all: it is
  ordinary CPU and object churn, so a phone's number will differ by the CPU
  ratio alone. It will not be smaller.
- **Cold and warm are close** (91.1 vs 90.6 ms for page 1) for the same reason:
  reopening the database drops SQLite's page cache and its prepared statements,
  but the host still holds the 219 MB file in RAM. On a phone the cold numbers
  would separate from the warm ones; here they mostly measure CPU.
- The emulator has 2.5 GB of RAM and the database is 219 MB, so it does not all
  sit in the app's cache — but it does sit in the host's.
- `ANALYZE` is run only where the table says so, because the app never runs one.
  Between the third pass and the fourth the statistics are deliberately emptied
  again (`Delete From sqlite_stat1`), so the "all five indexes, no statistics"
  column really has none rather than inheriting the pass before it.
- The unread deep page returns 0 rows: only ~2,500 articles are unread, so
  `OFFSET 5000` is past the end. It is kept in the table because it still pays
  the full walk-and-sort, which is itself the finding.
- The year-sized run was executed twice; the two agree within 2% on every row
  (id mapping 1569.6 and 1573.6; page 1 90.6 and 90.5; drawer 73.8 and 74.6).

### Running it

Default, and what the gate pays: the control only. The year-sized test is
`Assume`d out, so G7 reports it skipped.

```
scripts/check.sh              # G7 runs controlDatabaseOf10000Articles: 22 s
```

Most of those 22 seconds are the sync's id mapping, which costs over a second a
call at any database size and is measured twice in each of the two passes that
include it.

The full run, with `bench-pixel6-aosp` already booted on `emulator-5554`
(54 s for the year-sized test, 77 s for both tests):

```
ANDROID_SERIAL=emulator-5554 ./gradlew :db:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=app.lenews.db.benchmark.TimelineSlownessBenchmarkTest \
    -Pandroid.testInstrumentationRunnerArguments.lenewsBenchmark=full
adb -s emulator-5554 logcat -d -s LeNewsBench
adb -s emulator-5554 pull /sdcard/Android/data/app.lenews.db.test/files/timeline-slowness-110000.md
```

Nothing in the app's schema or entities changed. The indexes exist only inside
the benchmark's own database file, created by `CREATE INDEX` at run time; the
fix lands with ticket 13's schema reset, and ticket 12 now has numbers to be
grilled against.

## Review round (2026-09-06)

An adversarial review found four places where the benchmark was not measuring
what the app does. All four were accepted, the method was changed, and both
sizes were re-run — twice at 110,000 articles. Everything above is the revised
result. What follows is what changed and why; every number not listed here is
unchanged and reproduced on the re-run within 2%.

1. **The sync timer left out the production code.** The old benchmark
   precomputed the state rows and inserted them with `executeInsert`, and
   reported **23 ms** for "reinsert every `ItemState` row". That skipped both
   halves of what the app actually runs: `GReaderRepository.insertItemsIds` does
   its own id mapping first (the `starredIds.any` / `starredIds.remove` scans),
   and it inserts through `ItemStateDao.insert(List)`, where Room's generated
   adapter binds and executes one statement per row. The benchmark now carries a
   faithful copy of that mapping — the `db` module cannot depend on `app`, so
   the algorithm is copied with a comment naming
   `GReaderRepository.insertItemsIds` and commit `96f4c9e0` — and calls the real
   suspend DAO from a coroutine. Raw `executeInsert` is kept for fixture setup
   and as one clearly labelled comparison row.
   **The verdict flipped.** "Full `ItemState` delete-and-reinsert per sync —
   CLEARED as a growth problem, 25 ms once per sync is not what the user feels"
   becomes **CONFIRMED, at 1,656 ms per sync**, of which 1,570 ms is the id
   mapping and 86 ms is Room. It is still flat in the article count, so it is
   still not the *growth* the user describes — but it is the largest single cost
   in the benchmark and the old number was wrong by a factor of seventy.

2. **`ItemState` was said to be bounded by the API caps. It is not.** The
   earlier answer's "about 6,000 rows whatever the size of `Item`" is true of a
   sync and false of the app, because the timeline's mark-all-read button calls
   `ItemStateDao.setAllItemsReadByInsert()`, which writes a state row for every
   stored article. A scenario was added at both sizes: run the production
   mark-all-read, count the table, then time the sync that follows. At 110,000
   articles `ItemState` goes **5,955 -> 110,000 rows in 333.7 ms**, and the next
   sync's `deleteItemStates` costs **142.6 ms instead of 2.3 ms**. The seeding
   section now says "after a sync" where it used to say "whatever the size of
   `Item`", and a verdict was added.

3. **The tag measurement skipped Room.** It timed fifty raw cursors where the
   screen model awaits fifty suspend `TagDao.selectAllByItem` calls inside
   `PagingData.map`. Both are measured now, as separate rows. The verdict
   **"0.9 ms for all fifty articles of a page"** becomes **6 to 11 ms**, about
   90% of it Room and coroutine overhead rather than the query. Still flat in
   the article count, still cleared as a growth problem, but no longer
   "sub-millisecond".

4. **`Item.remote_id` was never isolated.** All five indexes were created
   together, so the unread timeline's 77.5 -> 2.9 ms could only be *attributed*
   to `SEARCH Item USING bench_Item_remote_id`, not shown. Two passes were added
   in front of the others: that index alone, without statistics and then with
   them. The result splits the old single verdict in three, and **corrects one
   claim outright**: "a single index on `Item.remote_id` takes them to 1.3 ms
   and 1.2 ms — the one place where the index alone is the whole fix" is wrong.
   The index alone leaves both drawer counts exactly where they were (73.8 ->
   74.3 ms), still `SCAN Item`; they need statistics, or the index on
   `ItemState(account_id)` as well, before the planner will drive from
   `ItemState`. The unread timeline *is* entirely `Item(remote_id)`'s to fix
   (2.8 ms, and the other four indexes add nothing) — but again only after
   `ANALYZE`. The all-items timeline is not helped by it at all.

The benchmark grew from three passes to five and from thirteen rows to nineteen,
and the control it runs in the gate went from about 3 s to 22 s. Most of that is
the id mapping, which is the finding. The three rows that cost seconds run in
the first and last pass only, since none of them is sensitive to the index
arrangement; that is what keeps the default under half a minute.

## From the global review (2026-09-06)

Two adversarial reviews — one on the repo's own standards, one on what the
tickets asked for — read everything committed since the fork point. The upstream
defects below are still in the tree at HEAD and belong to this ticket rather
than to the round that found them, so they are recorded here and nothing was
changed for them.

- **The three suspects the measurements left standing are still standing**, and
  they are still routed where this ticket routed them. `insertItemsIds` in
  `app/src/main/java/app/lenews/repositories/GReaderRepository.kt:203-245`
  matches the capped id lists against the starred list with `any`/`remove`,
  1.5 s of pure CPU per sync — the article store model and the sync are
  tickets 12 and 14. `ItemState` is unbounded by mark-all-read
  (`db/src/main/java/app/lenews/db/dao/ItemStateDao.kt:62-74`, and
  `GReaderRepository.kt:208`, which deletes and reinserts every row of it per
  sync) — the schema is ticket 13 and what bounds the rows is ticket 15's
  retention. Paging's `SELECT COUNT(*) FROM (query)` is the one cost no index
  reaches, so again only retention (15) bounds it. Nothing here is new; it is
  written down so the three files that own the fixes say the same thing this
  one does.
