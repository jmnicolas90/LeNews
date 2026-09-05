# 11 — Measure the slowdown on a year-sized database

Type: task
Status: open
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
