# 20 — Resource and lifecycle cluster

Type: task
Status: resolved
Blocked by: 04

## Question

Three review findings about ownership that survive ticket 04, small enough to share one ticket.

**HTTP responses are not closed** (medium). `FeedColors` fetches a favicon with a synchronous `execute()` and closes only on the happy path. Wrap every synchronous call in `.use { }` and keep all parsing inside the scope. (`LocalRSSDataSource`, `FeverFaviconFetcher` and `FeverDataSource.login` are deleted by ticket 04; confirm nothing similar remains with `grep -rn "\.execute()"`.)

**WorkManager output goes through one static map** (medium), `app/.../util/extensions/Extensions.kt:13-25`. The delegated property stores serializables in a `MutableMap` shared by every `Data` instance; concurrent syncs overwrite each other and nothing survives process death. Encode what the UI needs into `Data` proper: a bounded error code and message per failed feed as string arrays, or a JSON string. Delete the map and `clearSerializables()`.

**Disposal writes run in `GlobalScope` against a `lateinit`** (medium), `app/.../item/ItemScreenModel.kt:366-389`. `onDispose()` reads a `lateinit repository` that a flow assigns asynchronously and launches state writes in `GlobalScope`. Return early when there is nothing to write; derive the repository before the screen is shown; hand the write to an application-owned scope or a WorkManager one-off with error handling.

Test what has a seam: a `Data` round-trip with two concurrent results; a disposal before the repository is available.

**Done when** the gate is green and each of the three is fixed with the review's file reference struck through in the resolution.

## From the review of ticket 16 (2026-09-06)

**The application-owned scope this ticket's third item asks for exists**, as
`ApplicationScope` in `app/src/main/java/app/lenews/util/ApplicationScope.kt`,
registered as a Koin singleton in `AppModule`: a `SupervisorJob` on
`Dispatchers.IO` with a handler that logs what throws, for work that must finish
after the screen that started it is gone. Use it rather than adding a second
one. The rest of that item is already done as well — `ItemScreenModel` has no
`onDispose` write and no `GlobalScope`, and its repository is a flow the writes
wait on instead of a `lateinit var` — so what is left of the third item is to
check the same shapes nowhere else. The first two items are untouched.

## Answer (2026-09-06)

The three findings are closed. Two of them had already been half-closed by
later tickets, so what follows says for each what was true on arrival and what
this ticket changed.

Review references, struck through:
~~`FeedColors`, the favicon fetch closed only on the happy path~~ ·
~~`app/.../util/extensions/Extensions.kt:13-25`, the static serializable map~~ ·
~~`app/.../item/ItemScreenModel.kt:366-389`, the `GlobalScope` disposal write
against a `lateinit`~~.

### 1. HTTP responses closed

**On arrival**, `grep -rn "\.execute()" --include=*.kt` found two synchronous
calls in main sources. `HtmlParser.getHTMLHeadFromUrl` was already inside
`use { }` with the whole parse in the block — ticket 04 deleted the three
leaking data sources the review named, and this one had always been right.
`FeedColors.getFeedColor` was not: it decoded the body *after* the call, so a
non-2xx and bytes that are not an image both left the response open.

**Changed.** `FeedColors.getFeedColor(feedUrl, client)` now reads and decodes
inside `use { }`, on `Dispatchers.IO`, and takes the client as a parameter the
way `HtmlParser` does instead of looking it up through Koin — the caller says
which client the request goes out on, which is how a test can watch the one it
passed. `Synchronizer` resolves the plain client once and passes it; the icon
of a feed is hosted by the feed, so it must not carry the FreshRSS token.

Two more places drop a response, found by reading rather than by the grep, and
both are the same defect:

- **`ErrorInterceptor` threw `HttpException(response)` without closing it.**
  That is every refused request in the app — the caller is handed an exception
  and never sees the response, so nothing downstream could close it, and the
  body held its connection out of the pool until the garbage collector noticed.
  It closes the response before throwing. `HttpException` no longer keeps the
  `Response`, only the status code and the status line's text, which is all
  anything read from it; an exception that holds a response is an exception
  that holds it for ever.
- **`GReaderDataSource.login`** closed the body on the line after the parse, so
  a parse that threw skipped the close. It reads inside `use { }` now.

**Tested.** `ErrorInterceptorTest.aRefusedResponseIsClosedAndItsConnectionReused`
watches an `EventListener`: the body of a refused response reaches its end, and
the next request opens no second socket, which is the connection having gone
back to the pool. `FeedColorsTest` has the same listener over three cases — the
icon that decodes, the 404, and a body that is not an image — and asserts one
body ended in each. The last two are the paths that leaked.

### 2. WorkManager output through `Data` proper

**On arrival**, the static map was already gone: ticket 14 deleted
`Data.serializables` and `clearSerializables()` from
`util/extensions/Extensions.kt`, and `grep -rn "putSerializable\|clearSerializables\|serializables"`
finds nothing anywhere in the tree. `SyncWorker` already put its answer in the
output `Data` through `workDataOf`, and `TimelineScreenModel.refreshTimeline()`
already read exactly those keys. So nothing had to be moved out of shared
state.

What the UI needs, checked against the tree: three keys and no more —
`END_SYNC` (a sync finished), `SYNC_FAILURE` (a manual sync failed) and
`SYNC_FAILURE_MESSAGE` (the sentence the snackbar shows). **There are no
per-feed failures to carry**, and that is a property of the sync rather than an
omission: since ticket 14 a sync pulls every feed in the same calls and writes
them in one transaction, so it succeeds whole or fails whole. The one place
this app collects a failure per feed is adding feeds
(`Repository.insertNewFeeds`, an `ErrorResult` map), which runs in the screen
that asked for it and never goes near WorkManager. No arrays were invented for
failures that do not exist; the comment on `SYNC_FAILURE_MESSAGE_KEY` says so,
so the next reader does not go looking.

**Changed.** The message is bounded. `Data` refuses more than 10 KB
serialized, and it refuses by throwing from inside the machinery that runs the
worker, after the worker has returned — so an over-long message would not
arrive trimmed, it would turn a sync that failed with something to say into a
sync that failed with nothing to say. Most of these messages are one sentence
from `strings.xml`, but two end in `exception.message`, which is whatever a
library or a server put there. `SyncFailureMessage.bounded` cuts at **500
characters** and follows the cut with the number of characters dropped, so the
reader sees that the sentence was cut rather than that it stopped; the notice
is a new English string, `sync_failure_message_cut`, passed in as a lambda so
the rule itself stays arithmetic and holds no `Context`. 500 characters is
several lines on a phone and, at the four bytes a character UTF-8 spends in the
worst case, a fifth of what `Data` allows. Both failure paths in `SyncWorker`
go through one `failureData(message)`.

**Tested.** `SyncFailureMessageTest`, a JVM test: a short message is left
alone, a message of exactly the bound is left alone, and a long one is cut from
its beginning and says how much went. The last two tests go through
`SyncFailureMessage.failureData`, which is the whole of what
`SyncWorker.failureData` does, and read the answer back with
`Data.fromByteArray` out of the bytes `Data.toByteArray()` produced: 20,000
characters of a three-byte letter come out of the encoder small enough for
WorkManager to store, and two failures built one after the other each come back
from their own bytes with their own message and their own flag — which is what
the shared map could not do.

### 3. No `GlobalScope`, no disposal writes against a `lateinit`

**On arrival**, `ItemScreenModel` was already done: ticket 16 deleted its
`onDispose` write and its buffer, made the repository a flow the writes wait on
rather than a `lateinit var`, and added `ApplicationScope` — one Koin
singleton, a `SupervisorJob` on `Dispatchers.IO` with a handler that logs — for
writes that must outlive the screen. One `GlobalScope` was left, in
`SyncBroadcastReceiver`, which already called `goAsync()`.

**Changed.** The receiver injects `ApplicationScope` and launches there; the
`@OptIn(DelicateCoroutinesApi::class)` is gone. The two are not alternatives
and the class comment says which does what: `goAsync()` keeps the process alive
under the write, the application scope keeps the coroutine from being
cancelled. A failure is logged with the action and the article id, because
there is no screen left to tell and the notification the action came from has
already been cancelled.

`grep -rn "GlobalScope" --include=*.kt` over the whole tree now finds nothing,
and `grep -rn "lateinit" --include=*.kt` over the three modules' main sources
finds one comment and no declaration. The `lateinit var`s in test sources are
assigned in `@Before` before anything reads them, which is the shape the ticket
says is fine.

**Tested.** `SyncWorkerTest` covers both cases already — the normal article in
`autoWorkerWithNotificationsTest`, the article retention dropped in
`theActionsOfANotificationWhoseArticleIsGoneDoNothing` (ticket 15) — so they
were extended rather than duplicated. The normal case no longer waits a fixed
second for the write: `awaitArticle` reads the row until it has changed, which
is the honest way to wait for work that runs after `onReceive` has returned.
The deleted-article case keeps its fixed wait, because there the point is that
nothing is written and there is nothing to poll for.

### Left out on purpose

- **No per-feed failure keys in the output `Data`.** There is no such failure
  to report today (above). Inventing the arrays would mean writing a bound for
  a hundred failing feeds that nothing can ever produce.
- **`FeedColors` is still a Koin-free object with two overloads** rather than a
  class someone injects. Passing the client in was enough to make it testable;
  turning it into a dependency is a change to every caller for no fault this
  ticket found.
- **The `TODO retry with Coil3` note** on the favicon fetch stays. Whether Coil
  can respect the OkHttp timeout is a question for whoever looks at images
  next.
- **`GReaderService.getWriteToken()` returns a `ResponseBody` and is read with
  `.string()`**, which closes it. Left as it is: it is correct, and making it
  look like the others would be churn.

### Review round (2026-09-06)

One finding, accepted: the two `Data` tests **did not exercise the code the app
ships**. They built two `Data` by hand with `workDataOf` and read them back in
memory, and the large-message test weighed the bounded `String` rather than the
serialized `Data`, so deleting the bound from `SyncWorker.failureData` — the
very defect section 2 above closes — would have left both green.

**Changed.** The build of the output `Data` moved out of `SyncWorker` into
`SyncFailureMessage.failureData(message, cutNotice)`, which is now the one place
the two keys and the bound are put together; the worker's own `failureData` does
nothing but call it with the notice read out of `strings.xml`, so there is no
second encoder for a test to miss. Nothing about what the worker returns
changed.

The two tests were rewritten against it, and both now serialize with
`Data.toByteArray()` and restore with `Data.fromByteArray`, which is the trip
the answer really makes — WorkManager stores those bytes, and a process killed
and restarted reads the answer back out of them:

- **`anOversizedMessageStillFitsTheOutputData`** encodes 20,000 characters of a
  letter that is three bytes in UTF-8 — 60 KB unbounded, nearly six times what
  `Data` accepts — and asserts the serialized output is within
  `Data.MAX_DATA_BYTES` and that the restored message still begins with the
  message that arrived.
- **`twoFailuresAtOnceKeepTheirOwnAnswerThroughSerialization`** builds two
  failures through the same function and asserts each restored `Data` carries
  its own message and its own flag.

**The bound was removed to check the test is honest**: with
`failureData` putting the message in unbounded, `anOversizedMessageStillFitsTheOutputData`
fails with `IllegalStateException` from `toByteArray()` — WorkManager refusing
the payload, which is exactly the failure the bound exists to prevent. The bound
was put back and the whole gate run green.
