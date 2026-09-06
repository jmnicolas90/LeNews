# 16 — The history list

Type: task
Status: open
Blocked by: 13, 22

## Question

Record the moment every article becomes read, by every route, and show the list. Storage is what `docs/article-store.md` decided (ticket 13 created it). This ticket wires the routes and builds the screen:

- **Routes.** Opening an article (`ItemScreenModel`), swiping it (`TimelineScreenModel` swipe actions), scroll-to-read if the preference is on, mark-all-read for the list, a folder or a feed (`setAllItemsRead*`), and a read learned at sync (ticket 14's state application) stamped with the sync time. Marking unread and reading again is dated as the model says.
- **The list.** Every read article within the horizon, ordered by the moment it became read, newest first. Reachable from the drawer or as a main filter alongside All, New and Stars — take the cheapest that fits the existing `MainFilter` plumbing unless the *Not yet specified* entry on the map has since graduated into a prototype with a decided look. Show the feed and when it became read; tapping opens the article as the timeline does. Paged like the timeline. No search in this ticket.

Upstream issue #341 reports that mark-all-read does not work with FreshRSS accounts, still open. Reproduce it against the debug account on `rss.lan` (ticket 22) before wiring that route; if it is real, the fix is part of this ticket or ticket 14, whichever owns the broken step.

Test-first (`/tdd`): each route produces exactly one dated history entry; the list query returns the expected order; the list query on the seeded 100k store is inside budget.

**Done when** the gate is green through G7, every route is covered by a test, and the user can find an article they swiped away this morning in under three taps.

## From the global review (2026-09-06)

Two adversarial reviews — one on the repo's own standards, one on what the
tickets asked for — read everything committed since the fork point. The upstream
defects below are still in the tree at HEAD and belong to this ticket rather
than to the round that found them, so they are recorded here and nothing was
changed for them.

- **Mark-all-starred-read reads the wrong column.**
  `db/src/main/java/app/lenews/db/dao/ItemStateDao.kt:43-45`
  (`setAllStarredItemsRead`) and the matching bulk queries in
  `db/src/main/java/app/lenews/db/dao/ItemStateChangeDao.kt:169-176` select the
  articles to mark with `Where account_id = :accountId And starred = 1`, and
  the only `starred` column in scope there is `Item.starred`. For a FreshRSS
  account `Item.starred` is never written — the star lives in `ItemState`, as
  `CLAUDE.md` says — so the statement matches nothing and marking the starred
  list read does nothing at all. That is the shape of upstream issue #341,
  which this ticket already has to reproduce.

## From the review of ticket 15 (2026-09-06)

The adversarial review of ticket 15 found a third defect, in the item screen
rather than in retention. It belongs here, because this ticket wires the routes
by which an article becomes read anyway. Nothing was changed for it in
ticket 15.

- **Read and star decisions must be written the moment the user makes them, and
  the buffer goes.** `app/src/main/java/app/lenews/item/ItemScreenModel.kt`
  keeps every read and star decision in memory while the article is open (the
  buffer around lines 243-257) and writes them all when the screen is disposed
  (around lines 359-372). Retention now deletes articles inside every sync
  (ticket 15), and an article that is open but not yet written is still
  `read = 0`, `starred = 0` in the store — so a background sync can drop the
  very article the reader has just starred, and the disposal write then queues a
  pending change for a row that is gone, which fails on the foreign key. The
  star the reader saw is lost and nothing on screen says so. `docs/article-store.md`
  §5 says every route writes the state, the date and the pending change in one
  transaction as it happens; this ticket makes that true for the item screen
  too.

  The regression to add: open an article, star it, run a sync whose full id list
  no longer names it, then leave the screen. With the decision written as it is
  made the article is starred before that sync runs, so retention keeps it —
  starred articles survive both rules — and nothing throws on the way out.
