# 20 — Resource and lifecycle cluster

Type: task
Status: open
Blocked by: 04

## Question

Three review findings about ownership that survive ticket 04, small enough to share one ticket.

**HTTP responses are not closed** (medium). `FeedColors` fetches a favicon with a synchronous `execute()` and closes only on the happy path. Wrap every synchronous call in `.use { }` and keep all parsing inside the scope. (`LocalRSSDataSource`, `FeverFaviconFetcher` and `FeverDataSource.login` are deleted by ticket 04; confirm nothing similar remains with `grep -rn "\.execute()"`.)

**WorkManager output goes through one static map** (medium), `app/.../util/extensions/Extensions.kt:13-25`. The delegated property stores serializables in a `MutableMap` shared by every `Data` instance; concurrent syncs overwrite each other and nothing survives process death. Encode what the UI needs into `Data` proper: a bounded error code and message per failed feed as string arrays, or a JSON string. Delete the map and `clearSerializables()`.

**Disposal writes run in `GlobalScope` against a `lateinit`** (medium), `app/.../item/ItemScreenModel.kt:366-389`. `onDispose()` reads a `lateinit repository` that a flow assigns asynchronously and launches state writes in `GlobalScope`. Return early when there is nothing to write; derive the repository before the screen is shown; hand the write to an application-owned scope or a WorkManager one-off with error handling.

Test what has a seam: a `Data` round-trip with two concurrent results; a disposal before the repository is available.

**Done when** the gate is green and each of the three is fixed with the review's file reference struck through in the resolution.
