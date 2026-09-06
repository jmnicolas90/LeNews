# 21 — UI crash and paging cluster

Type: task
Status: resolved
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

- ~~**Leaving the item screen after marking an article unread throws, and the
  queued changes go with it.**~~ `app/src/main/java/app/lenews/item/ItemScreenModel.kt:360-374`
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

## Answer (2026-09-06)

Five items, two fixed and three struck. What was left in the tree is named at
the end.

### 1. Paging errors — fixed

`isError()` checked only `loadState.append`, with the refresh check commented
out, so a refresh that failed fell through to the empty placeholder: the reader
was told their timeline held no article when in fact no query had answered, and
there was nothing to press.

The decision now lives in one pure function,
`pagedListState(loadState, itemCount)` in
`app/src/main/java/app/lenews/util/paging/PagedListState.kt`, which answers one
of four states — `Loading`, `Error`, `Empty`, `Content` — and in
`nextPageFailed(loadState)` beside it. Neither touches Compose, so both are
tested on the JVM (`app/src/test/java/app/lenews/util/paging/PagedListStateTest.kt`,
10 tests: refresh failed with and without articles on screen, an append that
failed with and without articles, a prepend that failed, the first page loading,
a next page loading, a refresh loading over articles already there, a settled
list with articles and a settled list with none).

The rules, in order:

- **A refresh that failed is `Error`**, articles in memory or not. Those
  articles are the answer to a query that has since been asked again and failed;
  the reader is told, and offered the retry, rather than left with a list that is
  quietly out of date.
- **The first page still loading with nothing on screen is `Loading`.**
- **Articles on screen is `Content`**, whatever else is loading or has failed.
- **Nothing on screen and an append or a prepend that failed is `Error`** —
  there is nothing to keep, so the placeholder is the honest answer.
- **Anything else with nothing on screen is `Empty`**: settled, no error, no
  article. That is the only case that still shows "no article".

On screen, `PagingErrorPlaceholder` and `PagingErrorFooter`
(`app/src/main/java/app/lenews/util/components/PagingError.kt`) are the two new
composables, both thin: the placeholder is the error icon, the message and a
retry button calling `LazyPagingItems.retry()`; the footer is the same message
and button as one more item at the bottom of the timeline's `LazyColumn`, under
the articles that did load. `TimelineTab` and `ItemScreen` read
`items.listState()` and nothing else. `isError()` is gone;
`isLoading()` now answers `listState() == Loading`, so there is one decision
rather than two.

**Prepend was consciously given no separate UI**, on the grounds that nothing
in this app opens a list in its middle — the timeline starting at the top, the
item screen loading whole pages from the first one — so a prepend never runs.
**That was wrong, and the second global review said so**: Room builds the list
again around the row the reader is on, so the pages held after any store change
start in the middle of the query and scrolling up prepends. See *From the global
review (2026-09-06, second run)* at the end of this file for what the timeline
does about it now.

The item screen showed the placeholder but no footer: it is a pager, not a list,
and there is no room under an article for a message about the next one. **What
it needed instead** — an unloaded page whose load failed saying so, in place of
a blank page — is the first finding of that same round.

### 2. Image download through MediaStore — fixed

`ItemScreenModel.downloadImage` wrote a `File` under
`Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)`, which
scoped storage has refused since API 29, then asked `MediaScannerConnection` to
index a file that was never written. The app holds no storage permission and
does not want one, so the write simply threw inside the coroutine.

It now goes through MediaStore, in `ImageDownload.saveInDownloads`
(`app/src/main/java/app/lenews/item/ImageDownload.kt`): insert into
`MediaStore.Downloads` with `DISPLAY_NAME`, `MIME_TYPE`, `RELATIVE_PATH`
(`Environment.DIRECTORY_DOWNLOADS`) and `IS_PENDING = 1`, write through
`contentResolver.openOutputStream(uri).use { }`, then clear `IS_PENDING`.
Anything that throws before that — the insert, the stream, the encode, the
update — deletes the row, so no pending row is left behind; a pending row is one
no other app can see and the reader cannot remove. The function takes the write
itself as a parameter rather than a bitmap, which is the seam the failing-write
tests use. No `File` write to public storage is left, and no permission was
added; `MediaScannerConnection` is gone with the file write.

The name is decided by a pure function,
`downloadedImageName(sourceUrl, reportedMimeType)`
(`app/src/main/java/app/lenews/item/DownloadedImageName.kt`), tested on the JVM
in 13 cases (`DownloadedImageNameTest`): the last path segment with the query
and the fragment cut off, path separators and control characters and the
characters a file system reserves removed, no leading dot, the names some file
systems reserve (`CON`, `NUL`, `COM1`…) replaced, the base cut to 64 characters,
and the extension from the type — the reported one first, the address second,
`jpg` when neither names a format a bitmap can be written as. The type in the
answer is the type of that extension, so a file called `.png` never holds a
JPEG, and the bitmap is compressed in the format that name promises.

Coil is asked for nothing here: `SuccessResult` in Coil 3 carries no MIME type,
so `reportedMimeType` is `null` at the only call site and the address decides.
That is written at the call site rather than left to be rediscovered.

A failure now surfaces as a snackbar rather than as an exception in a coroutine:
the new English string `error_image_save` ("The image could not be saved to
Downloads"), and `retry` for the two paging composables, both with
`tools:ignore="MissingTranslation"` like the other strings this fork has added.
Both the success and the failure message are **cleared once the reader has seen
them** (`messageShown()`), so a second download says so as loudly as the
first — before, `fileDownloadedEvent` and `error` were latched and only the first
one ever showed.

**`data:image/*` sources are decoded and saved like any other image**, which is
the choice this ticket had to make. Coil 3 carries a `DataUriFetcher` by default,
so nothing had to be written to decode them; they have no file name to take, so
they are saved as `image.png` (or whatever their declared type is), and
MediaStore makes the name unique. Refusing them would have meant a message for an
image the reader can plainly see.

`shareImage` was fixed in the same pass, because it had the same crash with a
sharper edge: it named the cache file with `URI.create(url).path`, and a `data:`
address has no path at all — `path` is `null` and the share ended in a
`NullPointerException` inside `screenModelScope`, which is a crash and not a
message. It uses the same sanitiser now, and a failure to write the cache file is
the same snackbar.

### 3. One tag query per visible article — struck

Ticket 13 dropped tags from the model: `Tag` and `TagJoin` are not entities any
more, and there is no tag query to run per row.
`grep -rni "tag" --include=*.kt app/src/main db/src/main` finds only WorkManager
work tags, log tags, jsoup's HTML tags in the article sanitiser, and `etag` on
`Feed`. FreshRSS's `tag/list` endpoint is still called, but that is the server's
name for **categories**, which this app calls folders — one call a sync, not one
a row.

### 4. `ItemState` indexes — struck

`ItemState` does not exist. Ticket 13 moved read and starred state onto the
article row; `db/src/main/java/app/lenews/db/entities/` holds `Feed`, `Folder`,
`Item`, `PendingChange` and the account, and nothing else.

### 5. Disposal after marking an article unread — struck, closed by ticket 16

Confirmed rather than taken on trust:
`grep -rn "onDispose\|require(items" --include=*.kt app/src/main` finds nothing
at all. The buffer, the `onDispose` override and `Repository.setItemsRead` with
its `require` are gone, and the item screen writes each decision as the reader
makes it.

### The two crashes ticket 04 was to have removed

`grep -rni "opml\|fever" --include=*.kt app db api` finds nothing in any of the
three modules. The OPML empty-folder crash and the Fever-only share crash went
out with the code that had them.

### On the emulator

`bench-pixel6-aosp`, `ANDROID_SERIAL=emulator-5554` throughout, the debug build
of this worktree installed over the existing `app.lenews.debug` store (the
`ledev` account, 1124 articles).

- **Instrumented**, `ImageDownloadTest`, 3 tests green: an image saved through
  the production function lands in Downloads with the display name asked for,
  `IS_PENDING` back to 0, `RELATIVE_PATH` starting with `Download`, and a stream
  that reads back as an 8×8 bitmap; a write that throws immediately leaves no
  row; a write that throws half way, after bytes have gone out, leaves no row
  either. The query that looks for the leftover row asks for pending rows
  explicitly, so it cannot pass by not looking.
- **By hand**: article with an image (Nails Magazine, "Announcing the MODERN
  SALON 100 Class of 2026"), long press, *Download image*. `Downloaded file!`
  on screen and `MS100_logo_2_vafl2g.png` (120 548 bytes) in
  `/sdcard/Download` — screenshot `/tmp/lenews-run2/ticket-21-download.png`.
  Downloaded a second time: the message showed again and MediaStore named the
  file `MS100_logo_2_vafl2g (1).png`
  (`/tmp/lenews-run2/ticket-21-download-twice.png`). Both files removed
  afterwards. *Share image* on the same image opens the system chooser with no
  crash (`/tmp/lenews-run2/ticket-21-share.png`).
- **The refresh error placeholder was not staged by hand.** Nothing in the
  shipped app can make a Room-backed refresh fail on demand, and the one lever
  from outside — making the debug database unreadable — crashes the app while
  Room opens it, long before any page loads
  (`SQLiteCantOpenDatabaseException` in the log, screenshot
  `/tmp/lenews-run2/ticket-21-refresh-error.png` is the launcher). The database
  permissions were put back and the app checked working again. The decision
  itself is what the 10 JVM tests cover; the composables above it are three
  lines each.

### Left out on purpose

- **No Compose UI test.** `app/src/androidTest` has no Compose test dependency,
  and adding one to assert that a `when` picks the right of three composables is
  more machinery than the assertion is worth. The branch is a pure function with
  tests; the composables are thin by design.
- **The download runs on the screen's own scope**, as it did before. A download
  the reader starts and then leaves the screen during is cancelled, and the
  pending row is removed by the same `finally` that removes it after a failure,
  so nothing is left behind — but the file is not written either. Moving it to
  `ApplicationScope` is a change about what a download *is*, not about where it
  is written, and belongs to whoever asks for it.
- **The image is still decoded to a bitmap and re-encoded**, rather than the
  bytes the server sent being streamed to the file. That is Coil's shape — it
  answers with a decoded image — and changing it means fetching the image a
  second time through the plain HTTP client. The name and the type now agree
  with what is actually written, which was the part that was wrong.
- **`fileDownloadedEvent` and `error` are still two fields** rather than one
  message. Clearing them was needed for the snackbar to work twice; merging them
  is tidying nobody asked for.

### Review round (2026-09-06)

An adversarial review of the commit above raised two findings, both accepted
and both fixed here.

**1. The retry for a failed next page was at the bottom of a list of blank
rows.** The footer was one more item after `items.itemCount`, and the timeline
pages with placeholders on, so that count is every article the query matches,
loaded or not. The timeline draws nothing for a row it has not loaded — there
is no skeleton article — but the list still spaces every one of them. That is
invisible while loading keeps up with scrolling, because a row is reached
moments before it fills. When the next page has *failed* nothing is going to
fill them: after fifty of a thousand articles, the reader had 950 blank rows
between the last article and the retry, which is thousands of dp of nothing
with no message and no way back.

**The fix keeps placeholders and stops the list at the last article that
loaded**, rather than switching placeholders off. Both were tried. Switching
them off does put the retry under the articles for free — the count becomes the
loaded count — but it also breaks opening an article, and the measurement is
worth writing down because the reasoning goes the other way:

- A paged list is rebuilt whenever the store changes, which here is every sync
  and every article marked read on scroll. Paging asks the source for a refresh
  key anchored on the row the reader is on, and Room's answer is that row's
  position **minus half a page**, so after the rebuild the pages held start in
  the middle of the query, not at its first article.
- Measured against the real paging machinery on the JVM: with a thousand
  articles and the reader at position 399, the rebuilt list holds fifty
  articles starting at 374 — **with placeholders**, the list is still 1,000
  long with 374 of them before the first loaded one, so a row's index is still
  the article's position in the query; **without placeholders**, the list is
  fifty long and the row's index is 0 to 49.
- That position is exactly what `TimelineTab` hands `ItemScreen` when the
  reader taps an article, and what `ItemScreenModel.buildPager` uses to decide
  how far to load so that `initialPage` can find the article by its id. Give it
  5 instead of 379 and it loads the first hundred articles, does not find the
  id, falls back to the index and opens the article at position 5 — one the
  reader did not tap. Ticket 16's `initialPage` was unchanged and still worked,
  because nothing about the index it was given had changed. (**Since the second
  global review the index no longer comes from the timeline at all**: the item
  screen counts the article's position in the store when it opens. Placeholders
  still stay on, for the reason above — a row's index is still the article's
  position in the query, which is what the timeline hands over and what the
  screen checks against the store.)

So the rule is a plain function of three numbers,
`timelineRowCount(itemCount, placeholdersAfter, nextPageFailed)` in
`app/src/main/java/app/lenews/util/paging/PagedListState.kt`: every matching
article while pages are still arriving, and only the articles actually loaded
once the next page has failed. `LazyPagingItems.rowCount()` reads the two
numbers off the list, `TimelineTab` counts its rows with it, and the footer is
the next row. Four tests in
`app/src/test/java/app/lenews/util/paging/TimelineRowCountTest.kt`: the three
cases of the function, and one that holds it to **placeholder-bearing
`PagingData` with a real append failure** — a thousand articles, the first page
loaded, the next page returning an error, and the answer 50 rather than 1,000.
The reason placeholders stay on is written in the function's own documentation,
where the next person to look at that blank space will read it.

**2. A newer download result was cleared by acknowledging an older snackbar.**
`ItemState` held one boolean for success and one string for failure, and
`messageShown()` cleared both. A failure arriving while a success was on screen
was therefore thrown away the moment the reader dismissed the success — the
download that failed said nothing at all — and two successes in a row were one
message.

The two fields are replaced by one queue, `ItemState.imageResults`, of
`ImageResult.Saved(id, fileName)` and `ImageResult.Failed(id, message)`. Every
result is its own event with its own id, taken from a counter outside the state
update because `MutableStateFlow.update` may run its block more than once. The
screen shows the first of the queue in one effect keyed on that event's id, and
calls `imageResultShown(id)`, which drops **that** event and leaves whatever
arrived behind it — so the next one is shown as soon as the first is done.

The success message now names the file: `image_saved_in_downloads`
("%1$s saved to Downloads") replaces `downloaded_file` ("Downloaded file!"),
whose eight inherited translations went with it, so two images saved one after
the other read as two files rather than as the same sentence twice. That is one
`MissingTranslation` less in the lint baseline; its entry was removed and the
counts in `CLAUDE.md` follow.

Three tests at the model, in `ItemScreenModelTest`, since that is where a
result becomes a message: two results in a row are two messages in the order
they happened; acknowledging the first leaves the second; and two identical
successes are two events with two ids rather than one.

**Left out on purpose.** The blank rows are only cut off when the next page has
failed — while pages are arriving they are what a paged list is made of, and
cutting them there would stop the list asking for the next page. And nothing
was done about `MarkItemsRead`, which compares a row index across rebuilds and
can mark articles read that were never on screen when a sync inserts articles
above the reader: it is an inherited defect of the same file, not something
this round's findings raised, and it wants a ticket of its own.

## From the global review (2026-09-06, second run)

Two findings about paging that the first round of this ticket had left open.
Both accepted, both fixed here.

**1. A page of the reader's pager that failed to load showed nothing at all.**
`pagedListState` answers `Content` for an append that failed with articles
already on screen — which is right — and the pager's page count stays every
article the query matches, loaded or not. So the reader could swipe past the
articles that had loaded onto a page whose article was null, and that page drew
nothing: a blank screen, no message, no retry, and the only way out was to leave
the screen. The timeline had gained a retry row in the first round; the reader
had gained nothing.

The decision is one more pure function beside the others in
`app/src/main/java/app/lenews/util/paging/PagedListState.kt`:
`articlePageState(articleIsLoaded, append, prepend)` answers `Article`,
`Loading` or `Failed`. An article that loaded is shown whatever else has failed;
a page with no article is `Loading` while a load is running and `Failed` once one
has failed, in either direction — the retry covers every load type at once, so
telling the two directions apart would change nothing the reader can act on.
`ItemScreen` reads it and does nothing else: the loaded page moved into a
private `LoadedArticlePage` composable, the loading page is
`CenteredProgressIndicator`, and the failed page is the same
`PagingErrorPlaceholder` the failed refresh already used — a pager page is a
whole screen, so the full placeholder is the right shape rather than a footer.
Six tests in `PagedListStateTest`.

**2. "A prepend never runs" was false, and the timeline had no recovery for
one.** The first round wrote that nothing in this app opens a list in its
middle. It does: Room builds the list again around the row the reader is on
whenever the store changes — every sync, every article marked read on scroll —
so the pages held afterwards start in the middle of the query, and scrolling up
from there is a prepend. Since the second-run fix to ticket 16 the item screen
opens its list at the tapped article on purpose, which asks for one immediately.
A failed prepend left leading placeholders that nothing would ever fill, with no
message and no retry — the mirror of the defect the first round fixed at the
bottom of the timeline.

`previousPageFailed(loadState)` joins `nextPageFailed`, and
`timelineRowCount` gained the other end: it now takes `placeholdersBefore` and
`previousPageFailed` as well, and `timelineFirstRow(placeholdersBefore,
previousPageFailed)` says where the list starts. `TimelineTab` draws the retry
row above the first article that loaded and offsets every row by that first row,
key included. `PagingErrorFooter` is `PagingErrorRow` now, because it is used at
both ends. The KDoc that claimed a prepend never runs is gone and says the
opposite, with the reason; the *Answer* above and the map line said the same
thing and are corrected in the same commit.

Tests in `TimelineRowCountTest`: the three cases of the function at the new end,
both ends failing at once, and one that holds it to **placeholder-bearing
`PagingData` with a real prepend failure** — a thousand articles, a list opened
at position 400, the page above returning an error, and the first row 400 rather
than 0. `PagedListStateTest` covers `previousPageFailed` and that a failed
append is not read as a failed prepend.

Left out: nothing was done about the retry row being the same component at both
ends of the list without saying which end failed. It says the load failed and
offers the retry, which retries both.

