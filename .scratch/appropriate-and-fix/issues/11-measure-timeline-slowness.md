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

## Answer (2026-09-06)

Measured, not guessed. `db/src/androidTest/java/app/lenews/db/benchmark/TimelineSlownessBenchmarkTest.kt`
seeds a real on-disk Room database on `bench-pixel6-aosp` and times the timeline
as Paging executes it, the two drawer counts, one sync's `ItemState` rewrite and
the per-article tag query, cold and warm, with `EXPLAIN QUERY PLAN` for each.

**The headline: the timeline is linear in the number of articles stored, and
adding the indexes changes nothing until the planner is also given statistics.**
The first page of the timeline costs 7 ms on a 10,000-article database and 90 ms
on a 110,000-article one. Creating an index on `Item.pub_date` leaves it at
90 ms. Running `ANALYZE` after creating it takes it to **0.4 ms**.

### What was seeded

One FreshRSS account, 10 folders, 100 feeds, articles spread over 365 days with
a few days of jitter, inserted oldest first as a year of daily syncs would leave
them. `remote_id` in the long `tag:google.com,2005:reader/item/<16 hex>` form the
tree stores. One article in five carries two of ten tags.

`ItemState` holds what one classic sync actually leaves: the data source caps
`stream/items/ids` at `MAX_ITEMS` = 2500 for the reading list and
`MAX_STARRED_ITEMS` = 1000 for the starred stream, so `ItemState` is about 6,000
rows **whatever the size of `Item`**. That count is therefore the same in the
control and in the year-sized run — the only thing that grows between the two is
the article table, which is the variable under test.

| | Articles | Feeds | Folders | ItemState | TagJoin | Seed time | On disk |
|---|---:|---:|---:|---:|---:|---:|---:|
| Control | 10,000 | 100 | 10 | 5,500 | 4,000 | **0.39 s** | 21 MB |
| Year-sized | 110,000 | 100 | 10 | 5,955 | 44,000 | **2.3 s** | 219 MB |

Seeding uses compiled statements in transactions of 10,000 rows, which is why a
year of articles seeds in under three seconds.

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
| tags — `selectAllByItem` x 50 (one page) | 1.0 | 0.9 | x0.9 — flat |
| sync — `deleteItemStates` | 1.6 | 1.8 | x1.1 — flat |
| sync — reinsert every `ItemState` row | 18.9 | 23.1 | x1.2 — flat |

Every timeline and drawer number is **linear in the total article count**. Note
the unread timeline: only ~2,500 articles are unread whatever the database size,
yet its first page still goes from 6.7 ms to 77.5 ms. The cost is in what the
query walks, not in what it returns.

### Before and after, on the year-sized database (110,000 articles)

"After" is the five indexes **plus** `ANALYZE`. The indexes-only column is there
because it is the surprise: on its own it moves nothing that matters.

| Query | Rows out | Cold before | Warm before | Warm, indexes only | Cold after | Warm after | Plan before | Plan after |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |
| timeline all — Paging `COUNT(*)` | 1 | 115.0 | 125.3 | 126.0 | 77.5 | **77.2** | `SEARCH Feed` -> `SEARCH Item USING index_Item_feed_id` -> `SEARCH ItemState` -> **`USE TEMP B-TREE FOR ORDER BY`** | `SCAN Item USING bench_Item_pub_date` -> `BLOOM FILTER ON Feed` -> `SEARCH Feed` (no sort) |
| timeline all — page 1 | 50 | 90.7 | 90.2 | 90.2 | 0.6 | **0.4** | same, **temp b-tree** | `SCAN Item USING bench_Item_pub_date` -> rowid searches, no sort |
| timeline all — page 2 | 50 | 91.3 | 91.4 | 91.0 | 0.6 | **0.5** | same | same |
| timeline all — deep page (OFFSET 5000) | 50 | 164.1 | 162.6 | 162.1 | 4.5 | **4.0** | same | same |
| timeline unread — Paging `COUNT(*)` | 1 | 76.7 | 75.3 | 76.2 | 2.0 | **1.7** | same | `SCAN ItemState` -> `SEARCH Item USING bench_Item_remote_id` |
| timeline unread — page 1 | 50 | 78.0 | 77.5 | 77.7 | 2.9 | **2.9** | same | `SCAN ItemState` -> `SEARCH Item USING bench_Item_remote_id` -> temp b-tree over 2,500 rows |
| timeline unread — page 2 | 50 | 78.9 | 78.3 | 78.0 | 3.6 | **3.5** | same | same |
| timeline unread — deep page (OFFSET 5000) | 0 | 83.9 | 82.9 | 82.9 | 8.2 | **6.3** | same | same |
| drawer — unread count per feed | 100 | 73.7 | 73.4 | **1.3** | 1.4 | **1.2** | **`SCAN Item USING index_Item_feed_id`** -> `SEARCH ItemState` | `SCAN ItemState` -> `SEARCH Item USING bench_Item_remote_id` |
| drawer — unread count of the last 24 hours | 1 | 28.0 | 26.4 | **1.2** | 1.3 | **1.1** | **`SCAN Item`** -> `SEARCH ItemState` | `SCAN ItemState` -> `SEARCH Item USING bench_Item_remote_id` |
| tags — `selectAllByItem` x 50 | 18 | 1.0 | 0.9 | 0.9 | 0.9 | 0.9 | `SEARCH TagJoin USING index_TagJoin_item_id` -> `SEARCH Tag` by rowid | unchanged |
| sync — `deleteItemStates` (5,955 rows) | — | 2.5 | 1.8 | 2.6 | 3.9 | 2.4 | `SCAN ItemState` | `SEARCH ItemState USING bench_ItemState_account_id` (and back to `SCAN` once `ANALYZE` knows every row matches) |
| sync — reinsert every `ItemState` row (5,955) | — | 21.7 | 23.1 | 23.5 | 23.6 | 24.0 | no plan (three `insert(List)` calls, one transaction each) | unchanged |

Indexes created on the test database only, in 208 ms:
`Item(remote_id)`, `Item(pub_date)`, `Item(read)`, `Item(feed_id, pub_date)`,
`ItemState(account_id)`. `ANALYZE` on top of them took 16.5 ms.

The full report the test writes, with every SQL string and every plan in full,
is pulled from the device:

```
adb -s emulator-5554 pull \
    /sdcard/Android/data/app.lenews.db.test/files/timeline-slowness-110000.md
```

### The four things the numbers actually say

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
nothing.** Either `ANALYZE` runs (it costs 16 ms on a 219 MB database and Room
can run it after a sync), or the query is rewritten so the right plan is the only
plan.

**3. Paging's `COUNT(*)` is the one thing an index cannot fix.** Room's
`LimitOffsetPagingSource` wraps the query as `SELECT COUNT(*) FROM ( <query> )`
and `SELECT * FROM ( <query> ) LIMIT ? OFFSET ?`. The count runs on the *initial*
load of every `PagingSource` — and a new `PagingSource` is made on every
invalidation of `Item`, `Feed`, `Folder` or `ItemState`, which is to say **every
time an article is marked read and after every sync**. It still costs 77 ms at
110,000 articles with every index and statistics in place, because counting the
account's articles genuinely means visiting all of them. Before the indexes the
full price of one swipe is `COUNT(*)` 125 ms + page 90 ms = **215 ms of database
work per article marked read**, on an emulator. That is the slowness the user
reports.

**4. Deep scrolling is quadratic in the page number.** `OFFSET 5000` costs
162 ms before and 4.0 ms after, because `LIMIT/OFFSET` paging re-walks every row
it skips. Even fixed, page 100 costs ten times page 10.

### The suspects from the map, one by one

- **`Item` has a single index (`feed_id`) — CONFIRMED, and it is the main one.**
  It is not that a scan happens where a search should; it is that the only index
  worth having for the timeline's `ORDER BY pub_date DESC` does not exist, so
  every page sorts the account. 90 ms -> 0.4 ms at 110,000 articles once
  `Item(pub_date)` exists *and* the planner uses it. For a design ticket: the
  article store needs an index that serves the timeline order directly, and the
  timeline query has to be shaped so that index is the only sensible plan
  (`Item` driving, not `Feed`), rather than relying on statistics that nothing
  refreshes.

- **Unindexed `remote_id` join — CONFIRMED for the drawer, CLEARED for the
  timeline.** The timeline's `LEFT JOIN ItemState ON Item.remote_id =
  ItemState.remote_id` was already fine: `ItemState` carries a unique index on
  `(remote_id, account_id)` and the plan searches it. But the two drawer counts
  join the other way round, from `ItemState` into `Item`, and there `Item.remote_id`
  is unindexed: `SCAN Item` over 110,000 rows to count 2,500 unread. That is
  73 ms and 26 ms of the drawer opening, and a single index on `Item.remote_id`
  takes them to 1.3 ms and 1.2 ms — **the one place where the index alone is the
  whole fix**. For a design ticket: this is an artefact of read state living in a
  second table keyed by a 48-character string. Ticket 13's collapse removes the
  join rather than indexing it.

- **Items are never deleted — CONFIRMED as the driver of everything above.**
  Every number that grows grows with `Item`'s row count and nothing else: the
  unread timeline has the same ~2,500 unread rows at both sizes and still goes
  from 6.7 ms to 77.5 ms. The retention rule (ticket 15) is not only about disk:
  219 MB for a year is survivable, 90 ms per page is not. It is also the only fix
  that bounds `COUNT(*)`, which no index reaches.

- **Full `ItemState` delete-and-reinsert per sync — CLEARED as a growth
  problem.** 1.8 ms to delete 5,955 rows and 23 ms to put them back, and it is
  flat between 10,000 and 110,000 articles because the API caps keep `ItemState`
  at about 6,000 rows however many articles are stored. 25 ms once per sync is
  not what the user feels. It remains wrong for other reasons that are not this
  ticket's — it is not one transaction, so a failure between the delete and the
  inserts loses every read state, and the caps mean an unread article older than
  the newest 2,500 quietly stops having a state row at all — but it is not the
  slowness.

- **Per-article tag query — CLEARED.** 0.9 ms for all fifty articles of a page,
  identical at 10,000 and 110,000, plan `SEARCH TagJoin USING index_TagJoin_item_id`
  then `Tag` by rowid. Both indexes it needs already exist. The code review's
  "one tag query per visible article" is real as a shape but costs under a
  millisecond a page; it is a tidiness finding, not a performance one, and a
  design ticket should spend its budget elsewhere.

### Caveats on the absolutes

- `bench-pixel6-aosp` is an **x86_64 emulator on a fast desktop**, with the
  host's file cache under it. A phone's storage and CPU are slower, so every
  absolute here is optimistic — probably by several times. **The ratios are the
  finding**, not the milliseconds: x12 for x11 articles, and 200x from one plan
  change.
- **Cold and warm are close** (90.7 vs 90.2 ms for page 1) for the same reason:
  reopening the database drops SQLite's page cache and its prepared statements,
  but the host still holds the 219 MB file in RAM. On a phone the cold numbers
  would separate from the warm ones; here they mostly measure CPU.
- The emulator has 2.5 GB of RAM and the database is 219 MB, so it does not all
  sit in the app's cache — but it does sit in the host's.
- `ANALYZE` was deliberately **not** run before the third pass, because the app
  never runs one either. That is the point of the second pass existing.
- The unread deep page returns 0 rows: only ~2,500 articles are unread, so
  `OFFSET 5000` is past the end. It is kept in the table because it still pays
  the full walk-and-sort, which is itself the finding.

### Running it

Default, and what the gate pays: the control only. The year-sized test is
`Assume`d out, so G7 reports it skipped.

```
scripts/check.sh              # G7 runs controlDatabaseOf10000Articles: ~3 s
```

The full run, with `bench-pixel6-aosp` already booted on `emulator-5554`
(18.0 s for the year-sized test, 21.5 s for the class):

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
