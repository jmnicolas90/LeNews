# 18 — One authenticated client for FreshRSS, one plain client for everything else

Type: task
Status: open
Blocked by: 04

## Question

Review finding (high), `api/.../utils/AuthInterceptor.kt:8-19`. Credentials are a mutable field on a singleton interceptor attached to the shared `OkHttpClient`; sync, login and the image loader all reuse it (`ReadropsApp.newImageLoader()` builds from `client.newBuilder()`, which keeps the interceptor despite the comment), so article images from arbitrary hosts can be sent the FreshRSS authorization header, and a request can clear credentials mid-sync.

After ticket 04 there is one service. Build two clients: an authenticated one whose credentials are immutable for its lifetime, bound to the FreshRSS host (the interceptor refuses to attach the header to any other host), rebuilt on login; and a plain one with no auth interceptor for the image loader and `FeedColors`. Delete the mutable `credentials` field and every write to it. Koin bindings accordingly.

Two small upstream reports belong to the same client: #360, the default `okhttp/4.12.0` User-Agent gets 403'd by common blocklists — set a `LeNews/<version>` User-Agent on both clients; and #334, `ClientLogin` is sent as `multipart/form-data`, which stricter Google Reader servers reject — send it `application/x-www-form-urlencoded` as the protocol specifies (FreshRSS accepts both).

Test-first (`/tdd`): the authenticated client attaches the header to the FreshRSS host and not to another host; the plain client never attaches it; a login replaces the client rather than mutating it.

**Done when** the gate is green, `grep -rn "credentials = " --include=*.kt` returns nothing, and images still load in the debug build.

## From the global review (2026-09-06)

Two adversarial reviews — one on the repo's own standards, one on what the
tickets asked for — read everything committed since the fork point. The upstream
defects below are still in the tree at HEAD and belong to this ticket rather
than to the round that found them, so they are recorded here and nothing was
changed for them.

- **Confirmed still true at HEAD, with the line numbers.**
  `api/src/main/java/app/lenews/api/utils/AuthInterceptor.kt:10-20` adds the
  `Authorization` header to *every* request that goes through the shared
  client, with no test on the host. Two paths reach unrelated hosts on that
  same client: the new-feed screen's discovery, which fetches whatever URL the
  user typed, and the image loader —
  `app/src/main/java/app/lenews/LeNewsApp.kt:68-78` builds it with
  `client.newBuilder()`, which keeps every interceptor, so an article image
  hosted anywhere is fetched with the FreshRSS credentials attached. The
  comment beside that call says the opposite of what the code does.
