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
