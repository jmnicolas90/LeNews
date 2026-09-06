# 21 — UI crash and paging cluster

Type: task
Status: open
Blocked by: 04

## Question

Four review findings in the UI layer that survive ticket 04, small enough to share one ticket. (The OPML empty-folder crash and the Fever-only share crash are gone with ticket 04; confirm and note.)

**Initial paging errors show as empty content** (medium), `app/.../util/extensions/LazyPagingItemsExtensions.kt:10-12`. `isError()` checks only `loadState.append`; the refresh check is commented out. Treat `refresh` as an error when it is `LoadState.Error`, show append errors as an inline retry footer keeping existing items.

**Image download writes to a blocked path** (medium), `app/.../item/ItemScreenModel.kt:284-305`. Direct `File` writes under public Downloads fail under scoped storage. Insert through `MediaStore.Downloads` with `DISPLAY_NAME`, `MIME_TYPE`, `RELATIVE_PATH`, write through the content URI, sanitise the filename, surface failure as a snackbar.

**One tag query per visible article** (medium), `app/.../timelime/TimelineScreenModel.kt:197-204` and `ItemScreenModel`. Batch-load tags for each page's item ids, or use a Room relation. Skip this item if the map has since decided to drop tags (see *Not yet specified*); say so in the resolution.

**`ItemState` indexes** (low) — moot after ticket 13; strike it.

Test what has a seam: the paging error helper for refresh and append states; the filename sanitiser.

**Done when** the gate is green and each item is fixed or struck with a reason in the resolution.

## From the global review (2026-09-06)

Two adversarial reviews — one on the repo's own standards, one on what the
tickets asked for — read everything committed since the fork point. The upstream
defects below are still in the tree at HEAD and belong to this ticket rather
than to the round that found them, so they are recorded here and nothing was
changed for them.

- **Leaving the item screen after marking an article unread throws, and the
  queued changes go with it.** `app/src/main/java/app/lenews/item/ItemScreenModel.kt:360-374`
  hands every state change whose `readChange` is set to
  `Repository.setItemsRead`, which starts with
  `require(items.all { it.isRead == false })`
  (`app/src/main/java/app/lenews/repositories/Repository.kt:104-106`). In a
  filtered timeline (`useStateChanges`) an article that was read and is marked
  unread on the item screen is in that list with `isRead` true, so `onDispose`
  throws inside a `GlobalScope.launch` — the exception is unhandled, and the
  star changes the same block would have written afterwards
  (`ItemScreenModel.kt:374-380`) are lost as well. A fifth item for this
  cluster. The review of ticket 15 (2026-09-06) added a second way for that same
  block to fail: retention now deletes articles at every sync, so whatever
  `onDispose` still writes has to tolerate an article dropped while the screen
  was open — a pending change for a row that is gone fails on the foreign key —
  even once ticket 16 has moved the decisions themselves out of the buffer.

  **Done by ticket 16 (2026-09-06), so this fifth item is closed.** The buffer,
  the `onDispose` override and `Repository.setItemsRead` — the `require` that
  threw — are all deleted; the item screen writes each decision through
  `BaseRepository` as the reader makes it, and marking an article unread goes
  down the same route as marking it read. The article dropped while the screen
  was open is handled too: `setItemReadState` and `setItemStarState` check the
  article is still held, inside the transaction that writes, and discard the
  decision if it is not, so nothing is queued against a row the foreign key no
  longer has. The four original items of this ticket are untouched.
