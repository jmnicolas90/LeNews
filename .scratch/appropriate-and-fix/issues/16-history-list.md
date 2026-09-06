# 16 — The history list

Type: task
Status: resolved
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

## Answer (2026-09-06)

Done. The routes were already writing state, date and pending change in one
transaction (ticket 13); this ticket held every one of them to its scope with a
test, took the buffer out of the item screen, and built the list.

### The look, and why

**A fourth `MainFilter`, `HISTORY`, beside `ALL`, `NEW` and `STARS`** — the
cheapest fit the ticket asked for, and the map's *Not yet specified* entry never
graduated into a prototype. Being a main filter means the history is the
timeline's own query, its own pager, its own item row, its own swipe actions and
its own way of opening an article: nothing new to build and nothing to keep in
step later. What it adds to `ItemsQueryBuilder` is a filter, an order and an
index:

```
Article Indexed By index_Article_read_at CROSS JOIN Feed … LEFT JOIN Folder …
Where 1 = 1 And Article.read_at Is Not Null Order By Article.read_at DESC
```

It **ignores `showReadItems`** — every article in the history is read, and the
checkbox left off would empty the list — and it **ignores the timeline's order**,
because the order is the whole point of the list. The index is named for the
reason the folder timeline names one: ticket 13 measured a planner changing its
mind for the worse the moment `PRAGMA optimize` gave it statistics, and this way
the plan is fixed. Measured on the seeded hundred thousand:
`SEARCH Article USING INDEX index_Article_read_at (read_at>?)`, **0.40 ms** for
the first page with no statistics and **0.42 ms** after `PRAGMA optimize`, well
inside §6's 10 ms.

**The sub-filter composes**: a feed or a folder narrows the history to that feed
or folder, exactly as it narrows All, New or Stars. That is the simplest
consistent rule — the main filter and the sub-filter are independent everywhere
else in this app, so making the history the one exception would be a special
case with nothing behind it, and "the history of this feed" is a sentence that
means something.

**What it shows**: the timeline item row as it is, with the badge showing *when
the article became read*, with the hour, instead of the publication date —
`TimelineItem` and its three sizes take a `becameReadAt: Long?`, null everywhere
but here. The hour matters: a day of reading falls on one date, and "this
morning" is what the reader is looking for. `DateUtils` gained
`fromEpochMillis` and `formattedDateTimeByLocal` for it, and `read_at` joined the
columns `ItemsQueryBuilder` selects.

**Where it is**: a fourth drawer entry under Favorites, with the stopwatch
drawable this fork inherited and had never used, renamed `ic_history`. **Three
taps** from the timeline to the article: the menu button, *History*, the article.
The mark-all-read button is not shown in the history — everything there is read
already — and `setAllItemsRead()` has a `HISTORY -> Unit` branch as the second
lock. One new English string, `history`, with `tools:ignore="MissingTranslation"`
so the baseline does not grow.

### The item screen writes as the reader acts

`ItemScreenModel`'s buffer is gone: `useStateChanges`, `StateChange`,
`updateStateChange`, `ItemState.stateChanges`, the `PagingData.map` that replayed
the buffer over the store, and the `GlobalScope` write in `onDispose` — the whole
`onDispose` override. Reading, marking unread and starring go straight through
`BaseRepository` on the model's own scope.

What replaces the buffer for the one thing it was good for: **a set of article
ids the list keeps showing**. `buildItemsQuery(filters, keptArticleIds)` relaxes
the *state* conditions — `read = 0`, `starred = 1`, `read_at Is Not Null` — for
those ids and nothing else, since the feed an article belongs to and the day it
was published do not change while it is open. The timeline passes an empty set
and gets byte for byte the query it had. So the store is never stale and the
list under the reader's finger does not shift by one when the open article
becomes read.

Two follow-on changes fell out of it:

- `setItemRead(itemWithFeed)`, the "the reader has swiped to this page" route,
  is **no longer a toggle**. The buffer used to be what stopped the pager
  marking the same page twice; without it a toggle would mark an article read
  and then unread. It now only ever marks read, which is idempotent at the
  statement level too.
- `setItemReadState` and `setItemStarState` **check the article is still
  there**, inside the transaction that writes. Retention drops articles inside
  every sync, so a screen open across one can hold an article that is gone; the
  update would change no row and the pending change would then point at nothing,
  which the foreign key refuses. The decision is dropped instead — the server
  was never told and there is nothing left to tell it about.

`BaseRepository.setItemsRead`, `ItemDao.markRead(ids, now)` and
`PendingChangeDao.queueReadForArticles` were the buffer's only callers and were
deleted with it.

### Upstream #341, reproduced and checked against the real server

The defect the global review recorded — `setAllStarredItemsRead` selecting on a
`starred` column FreshRSS accounts never wrote — **could no longer be reproduced
in this tree**, because ticket 13 deleted the table it read from. There is one
`starred` column now, on the article, and the sync writes it. Reproducing the
old behaviour would have meant building the fork point, which is out of
proportion; what was done instead is to prove the route works end to end against
the real server, which is what the issue is actually about.

On `bench-pixel6-aosp`, with a debug build of this branch and the `ledev`
account on `https://rss.lan` (`local.properties` copied into the worktree for
the build and deleted afterwards; no value printed anywhere):

| Step | `read` | `starred` | `read_at` | queued |
| --- | ---: | ---: | ---: | ---: |
| start, 766 articles | 0 | 0 | 0 | 0 |
| star one article from the timeline | 0 | 1 | 0 | 1 (`starred = 1`) |
| *Favorites* → mark all read | 1 | 1 | 1 | 1 (`read = 1`, `starred = 1`) |
| sync | 1 | 1 | 1 | 0 |
| sync again | 1 | 1 | 1 | 0 |
| unstar, swipe to unread, sync | 0 | 0 | 0 | 0 |

The second sync is the proof: had the server not taken the read, its unread list
would still name the article and step 4c would have put it back to unread. It
stayed read, with the **same** `read_at` — the moment the reader tapped, not
restamped. Exactly one article was marked, the starred one. The account was left
as it was found: nothing read, nothing starred, nothing queued, 885 rows and 885
distinct ids, no `read = 1` ⇔ `read_at` breach.

The history screen was looked at on the same run: the article appeared in it with
its feed, its folder and the badge "Sep 6, 2026, 3:08 PM", and left it when it
was marked unread.

A by-product worth recording, since ticket 22 wanted it: **119 new articles
arrived in the 1 h 47 min between two syncs**, which is well over a few hundred
a day. One more sample, still not a week.

### Tests

`db/src/androidTest`:

- `BecomingReadTest` — one file per statement pair, run in the order
  `BaseRepository` runs them: reading stamps once, reading again while read
  stamps nothing, unread clears the date, reading after that is a new date;
  starring touches neither; and each of the five bulk routes (list, feed, folder,
  starred, last day) marks and queues **only the unread articles in its scope**
  and leaves an already-read article's date alone.
- `HistoryListTest` — the list is every read article newest first and once each,
  ordered by `read_at` and not by publication date (the fixture makes the two
  orders opposite); it ignores `showReadItems` and the timeline's order; an
  article marked unread leaves it and comes back at the top when read again; a
  feed narrows it; and a kept id keeps an article in it.
- `ItemsQueryBuilderTest` — the history SQL: the index named, the filter, the
  order, and no `read = 0`; the kept ids relax the state condition and not the
  feed one; an empty kept set produces exactly the old query.
- `TimelineTimeBudgetTest` — the history measurement now runs the **production**
  query. `db/src/androidTest/.../HistoryQuery.kt`, the placeholder ticket 13 left
  for it, is deleted, and the slowness benchmark points at the same builder.

`app/src/androidTest`:

- `BecomingReadRoutesTest` — the same routes through `BaseRepository`, which is
  the seam both screen models call: state, date and pending change together, the
  date being the phone's clock; unread clears it; a second read is a later date;
  starring changes neither; each bulk route queues what it marks; #341 named; and
  a decision about an article the store no longer holds changes nothing and
  throws nothing.
- `SyncTest` — a read learned at sync is stamped with the sync's own clock and
  not stamped again by the next sync, and the article the server still calls
  unread has no date at all. Plus the regression the review of ticket 15 asked
  for: star an article, run a sync whose full id list no longer names it, and the
  article is still there and still starred, and acting on it afterwards works.

### Consciously left out

- **The history is not offered as the "default category" preference.** Opening
  the app on the list of things already read is not a reasonable default; the
  three existing entries are unchanged.
- **No search**, as the ticket says, and no grouping by day. The badge carries
  the date and the hour and the list is one flat page.
- **The "hide feeds with no unread article" preference is unchanged.** With it
  on, a feed whose articles are all read disappears from the drawer, so the
  history of that feed cannot be reached by tapping it — the whole history still
  shows it. Narrow enough, and off by default, to leave to a later ticket.
- **The `becameReadAt` badge is the only visual difference.** No separate item
  layout, no "read 3 hours ago" relative wording.
- **Scroll-to-read still writes one transaction per article.** The batch
  statement that could have done a run of them in one went with the buffer,
  because nothing called it; wiring the timeline to a batch is a change of its
  own.

### Review round (2026-09-06)

An adversarial review of the branch found three ways the item screen could lose
or undo a decision. All three were accepted and fixed here, each with a test
that fails without its fix.

**A list built again around the open article is not a page the reader turned
to.** In the history, marking the open article unread clears its `read_at`, so
it leaves the list; the list is built again in another order, and the pager
follows the article's key to its new index. The screen reports that as the page
the reader is on, and the "swiped to this page" route marked the article read
again, one moment after the reader asked for the opposite, with a date they
never chose. `ItemScreenModel` now remembers the id of the article the pager
last named and does nothing when the pager names it again; a page the reader
really turns to is a different id, and is still marked read.

**A decision outlives the screen it was made on.** The writes ran in
`screenModelScope`, which Voyager cancels when the screen is disposed. A write
waiting for Room's transaction executor — a sync holds it for as long as a sync
takes — was cancelled before it committed, and with the `onDispose` write gone
there was nothing left to write it later. They run in `ApplicationScope` now
(`app/src/main/java/app/lenews/util/ApplicationScope.kt`, one Koin singleton for
the process, a `SupervisorJob` on `Dispatchers.IO` with a handler that logs).
The screen's own scope still carries what the screen alone cares about: the
pager, the preferences, the image work. The same change made the repository a
flow rather than a `lateinit var`, so a decision made before the account has
arrived waits for it instead of throwing, which is the other half of what
ticket 20 asks about this file.

**The reader comes back to the article they were reading.** The set of ids the
list keeps showing lived only in memory. The article is marked read as soon as
it is opened, so after the process was killed the recreated screen asked the
unread timeline for a list that no longer held it, and opened whatever now sat
at the restored index — then marked that read too. The set is now seeded with
the id the screen was opened on, so the list still holds the article, and the
page the screen opens on is found by that id rather than by the index the
timeline passed (`initialPage` in `app/src/main/java/app/lenews/item/InitialPage.kt`,
used by `ItemScreen`), which also covers a sync having put newer articles above
it meanwhile.

Five new tests: `app/src/androidTest/java/app/lenews/item/ItemScreenModelTest.kt`
holds the model to all three — the reindex that must not write, the page turn
that must, the decision made just before disposal, and the recreated screen —
and `app/src/test/java/app/lenews/item/InitialPageTest.kt` covers the page
lookup itself, including the placeholder page and a position past the end of the
list. With the three fixes backed out, three of the four instrumented tests fail
and each says which of the three defects it caught.

Left out: nothing about the *live* screen was changed beyond these three. The
pager can still be renumbered under the reader for reasons other than their own
decision — a sync inserting an article above the one they are on — and the
screen follows it without complaint; that is the behaviour this fork inherited
and no test was written for it.
