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
