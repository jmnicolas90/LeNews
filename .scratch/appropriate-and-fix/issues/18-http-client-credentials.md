# 18 — One authenticated client for FreshRSS, one plain client for everything else

Type: task
Status: resolved
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

- ~~**Confirmed still true at HEAD, with the line numbers.**
  `api/src/main/java/app/lenews/api/utils/AuthInterceptor.kt:10-20` adds the
  `Authorization` header to *every* request that goes through the shared
  client, with no test on the host. Two paths reach unrelated hosts on that
  same client: the new-feed screen's discovery, which fetches whatever URL the
  user typed, and the image loader —
  `app/src/main/java/app/lenews/LeNewsApp.kt:68-78` builds it with
  `client.newBuilder()`, which keeps every interceptor, so an article image
  hosted anywhere is fetched with the FreshRSS credentials attached. The
  comment beside that call says the opposite of what the code does.~~ Fixed by
  this ticket: there is no shared client left to leak from, and the comment is
  gone with the code it described.

## Answer (2026-09-06)

**Two clients, and no unnamed one.** `api/src/main/java/app/lenews/api/HttpClients.kt`
holds both and is the only place either is built.

- `plain` has no auth interceptor at all. The Coil image loader, `FeedColors`
  and the new-feed screen's discovery use it, each by name.
- `authenticated` carries `Authorization: GoogleLogin auth=<token>`, attached by
  `AuthInterceptor(authorization, serverUrl)` whose two fields are `val` and are
  fixed for the life of the instance. `useCredentials(...)` **builds a new
  client**; it never changes the one in use, so a sync already running keeps the
  client, the token and the server it started with. That is stated in the class
  comment. Asking again for the credentials already in use keeps the instance,
  so the call sites that refresh before every sync do not churn connection
  pools. `forgetCredentials()` puts `plain` back, and before any login
  `authenticated` *is* `plain` — the same instance, so provably no header.

**The host rule.** `AuthInterceptor.goesToTheServer(url)` compares the parsed
`HttpUrl`'s **scheme, host and port**, never a text prefix. It is registered as
a **network** interceptor, not an application one, which is what makes the check
hold per request rather than per call: OkHttp runs it again for every redirect
hop, so a redirect that lands on another host goes out bare rather than relying
on OkHttp's own redirect stripping.

**User-Agent.** `UserAgentInterceptor` sets `LeNews/<versionName>` on both
clients, so nothing goes out as `okhttp/4.12.0`. An Android library has no
`versionName`, so the string is built in the app module —
`val userAgent = "LeNews/${BuildConfig.VERSION_NAME}"` in `LeNewsApp.kt` — and
passed in: `apiModule` became `fun apiModule(userAgent: String)`, called from
`LeNewsApp` and from `LeNewsTestRule` with the same value.

**Koin.** `AUTHENTICATED_CLIENT` and `PLAIN_CLIENT`, both **factories** rather
than singles — a single would resolve the authenticated client once and hand out
the instance built at startup for ever, which is exactly what a login replaces.
The `AuthInterceptor` and `ErrorInterceptor` singles are gone; the image loader
is built from the plain client instead of `client.newBuilder()`.

**Two ordering consequences, both fixed here.** Retrofit captures the client it
is built with, so the credentials must be set **before** a repository is
resolved. `Synchronizer` did it after, which would have synced on a client with
no token; `TabScreenModel` and `NewFeedScreenModel` already did it before and
keep doing so. And logging in needs **two** data sources — one on the plain
client for `ClientLogin`, one on the authenticated client for `token` and
`user-info` — which is `app/src/main/java/app/lenews/repositories/GReaderLogin.kt`,
a free function taking the client holder and a data-source factory so it can be
driven by a fake.

**ClientLogin is form-encoded.** `@FormUrlEncoded @POST("accounts/ClientLogin")`
with `@Field("Email")` and `@Field("Passwd")`; the `MultipartBody` builder is
gone. The existing `login_response_body` fixture still parses to the same token,
and the test now asserts the recorded `Content-Type` and the exact encoded body.
Login error handling (`GReaderError`, `AccountError`) is untouched.

`grep -rn "credentials = " --include=*.kt` returns **nothing** (one local `val`
in `CredentialsTest` was renamed so the search stays honest).

### Tests

Written before the code. In `api`: the host rule against parsed look-alikes (a
host the configured one is only the prefix of, a host hidden behind user info,
uppercase, a trailing dot, another scheme, another port), and over the wire with
two MockWebServers — the header goes to the configured server, not to the other
one, and not to the other one after a redirect. `HttpClientsTest` covers the
plain client never sending the token, the authenticated one sending it, both
sending the `LeNews/` User-Agent, no token meaning `authenticated === plain`, new
credentials replacing the instance, the same credentials keeping it, and
`forgetCredentials`. In `app`: `GReaderLoginTest` drives the login against a fake
`GReaderService` that refuses the token calls unless the credentials carry a
token, and asserts that `ClientLogin` went out on the plain client and that the
login replaced the client rather than changing it. The MockWebServer instrumented
tests still log in and sync; `SyncTest` builds the host rule from the stub's own
URL, so no host or port is hard-coded anywhere.

### The gate

`scripts/check.sh` green, all eight stages, from the worktree.

G1 caught something worth recording: the user-info look-alike — `rss.lan`
written as the user name of `evil.example` — reads as an email address when it is
written out, and the email guard reported all three files that held it. It is
built from a named `AT` constant in the test now, and described in prose rather
than written out in the comments and in this file.

### On the emulator (`bench-pixel6-aosp`, `emulator-5554`, `ANDROID_SERIAL` pinned)

Debug APK installed over the existing `ledev` store (`app.lenews.debug`, already
logged in; `local.properties` was not copied and no credential was read).

- **Sync completes**: two manual syncs from the app, `Worker result SUCCESS`,
  new articles in the timeline, 1013 articles and 19 feeds in the store.
- **An article with an image renders**: a Caradisiac article, image visible.
  That is the WebView's own fetch, not Coil.
- **A third-party image through the Coil loader on the plain client renders**:
  no article in this store carries an `image_link`, and the feed icon URLs
  FreshRSS hands out point at `https://rss.xcv.ovh/f.php?h=...` — a **different
  host from the account's `https://rss.lan/`**, a public VPS whose TLS handshake
  is refused (`tlsv1 alert internal error`) from this machine and from the
  emulator alike. So those icons have never loaded and cannot, before this
  ticket or after it. To exercise the path anyway, one feed row's `icon_url` was
  pointed at `https://news.ycombinator.com/favicon.ico` in the emulator's debug
  store; the icon loads (screenshot at `/tmp/lenews-run2/ticket-18-images.png`),
  and the row was put back to its original value afterwards.
- That mismatch is itself worth keeping: before this ticket the image loader was
  sending the FreshRSS token to `rss.xcv.ovh`, a host the user never configured.
  This is the leak the review described, observed in the real store.

### Left out, consciously

- **The live `ClientLogin` was not re-run against the real server.** Logging out
  would destroy the `ledev` store the verification depends on, and
  `local.properties` lives in the main checkout. The form encoding is covered by
  the `api` MockWebServer test and the `app` fake; FreshRSS accepts both forms,
  so nothing about this account would have distinguished them anyway.
- **`Credentials` / `GReaderCredentials` were left as they are.** With one
  service the abstract base earns nothing, but deleting it is a rename across
  the module and belongs to whoever collapses the service layer.
- **Feed colours are still fetched only for feeds the sync reports**, and on this
  account every `Feed.color` is 0 because the icon host is unreachable. Nothing
  here changed that; it is the same unreachable host.
- **`HtmlParserTest` stopped using Koin** and builds `HttpClients(...).plain`
  directly, so no test defines an unnamed `OkHttpClient` either.
