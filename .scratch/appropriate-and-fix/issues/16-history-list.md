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
