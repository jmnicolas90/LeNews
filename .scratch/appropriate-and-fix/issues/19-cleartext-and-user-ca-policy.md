# 19 — Trust the user-installed CA for rss.lan only, and nothing in cleartext

Type: task
Status: resolved
Blocked by: 22

## Question

Review finding (medium), `app/src/main/res/xml/network_security_config.xml:3-12`. The base configuration permits cleartext HTTP and trusts user-installed certificate authorities for every host, in every build. With Basic/token authentication, an `http://` FreshRSS URL sends credentials in the clear; global user-CA trust widens interception exposure for every image the reader loads.

The fact the fix depends on is now known (2026-09-05): the user's FreshRSS is `https://rss.lan`, on the LAN and over VPN only, behind Caddy with its **local certificate authority** — 12-hour leaf certificates issued by "Caddy Local Authority - ECC Intermediate". No public CA will ever sign `.lan`, so the phone trusts it through the Caddy root installed as a **user CA**, and an Android app only honours user CAs for hosts its network security config says so for.

Policy: `network_security_config.xml` keeps the strict default (no cleartext, system CAs only) as the base, and adds one `domain-config` for `rss.lan` that trusts `user` certificates in addition to `system`, in every build type. No cleartext anywhere, including debug. The login screen refuses an `http://` URL outright rather than warning. Do not bundle the Caddy root in the app: the user's CA is theirs to rotate, and a user-CA pin would silently break the day they re-key. The plain image client from ticket 18 stays on the base config, so a feed image from an arbitrary host never gets user-CA trust.

Verify on the emulator against the debug account (ticket 22) with the Caddy root installed as a user CA in the AVD, and negatively: the same build with the root removed fails to log in with a certificate error, and an `http://rss.lan` URL is refused before any request is made.

**Done when** the gate is green, the base config has no cleartext or user-CA exception, the `rss.lan` domain config exists, and both verifications above are recorded in the resolution.

## Answer (2026-09-06)

Done. Review finding (medium),
~~`app/src/main/res/xml/network_security_config.xml:3-12`~~ — the base
configuration no longer permits cleartext and no longer trusts user-installed
authorities for every host.

### What the config says now

`app/src/main/res/xml/network_security_config.xml`, one file for every build
type, no `<debug-overrides>` block anywhere, and no `usesCleartextTraffic` in
the manifest (there never was one):

- **Base**: `cleartextTrafficPermitted="false"`, trust anchors `system` only.
- **One `<domain-config>` for `rss.lan`**, cleartext refused there too, trust
  anchors `system` **and** `user`. `includeSubdomains` is **false**: the
  FreshRSS server is exactly `rss.lan`, and other names under `.lan` are other
  machines on the same network with no claim on that trust.
- The Caddy root is **not** bundled. It is the user's, theirs to rotate, and a
  copy pinned in the app would break the day they re-key.
- **No cleartext exception of any kind**, not even for the loopback interface.
  See the section after this one for how the instrumented tests manage.

The clients that ship carry no `TrustManager`, no `hostnameVerifier` and no
`ConnectionSpec.CLEARTEXT`: `HttpClients` builds them from a builder nothing
outside itself touches, and the `configure` seam its constructor takes is
defaulted to do nothing and passed only by test Koin modules. What the review
round below *did* add to both is `protocols(listOf(Protocol.HTTP_1_1))`, because
two hosts may not share a connection when they are not allowed the same
authorities. The plain client of ticket 18 is otherwise unchanged and so stays on
the base configuration: a feed image from another host is checked against
preinstalled authorities alone.

### No cleartext exception at all, and how the tests get by without one

The first version of this work left one: a `<domain-config>` permitting
cleartext to `localhost` and `127.0.0.1`, because the instrumented tests run a
stub FreshRSS server (MockWebServer) on the device's loopback interface and the
app under test obeys this same file. The review round below took it out. It is
gone from the shipped file, and there is no second file it moved to.

What replaced it: **the stub servers serve TLS**, with a certificate generated
for the test run (`okhttp-tls`, a Square artifact — no Google dependency), and
the app under test is told to trust that one certificate through a seam in
`HttpClients`:

```kotlin
class HttpClients(
    private val userAgent: String,
    private val configure: (OkHttpClient.Builder) -> Unit = {}
)
```

`apiModule` passes nothing, so every client the app builds is the plain one. The
test Koin module in `LeNewsTestRule` is loaded last and declares
`HttpClients(userAgent, StubServerTls.trustTheStubServer)`, which trusts the
stub certificate **and nothing else** — not even the preinstalled authorities,
since no test here talks to any server but its own stub.
`app/src/androidTest/java/app/lenews/testutil/StubServerTls.kt` holds the
certificate, the server's socket factory and that block; `stubServerOverTls()`
builds a MockWebServer already serving it, and `MockWebServer.tlsUrl(path)`
names the host the certificate carries rather than trusting a reverse lookup of
the loopback address.

**No test had to stay on cleartext.** The five instrumented files that run a
stub server — `SyncTest`, `SyncWorkerTest`, `SynchronizerTest`,
`LoginAndSyncTest`, `FeedColorsTest` — all moved, and so did the JVM test
`LoginOverTheWireTest`, which now logs in over TLS because a login over plain
HTTP is a thing this app cannot do.

### The login screen reads the address once, and refuses what it cannot use

`canonicalServerUrl(typedUrl)` in
`app/src/main/java/app/lenews/account/credentials/AccountCredentialsScreenModel.kt`,
a pure function beside `accountToLogInWith`. It answers a `ServerUrl`: either
`Usable(url)` — the exact string the login request is built with and the exact
string written to `Account.url` — or one of `Missing`, `NotHttps`,
`CarriesAUserName`, `Unreadable`. `validateFields()` returns that address or
null, and `login()` uses what it returns, so **nothing downstream reads the
typed text a second time**. That is the point: the first version checked one
reading of the text and built the request from another, which the review round
below shows was exploitable.

What it does, in order: whitespace dropped; a scheme read from the front in
lower case, and anything but `https` refused (`http`, and `ftp` as much); an
address with no scheme read as `https://`, so `rss.lan` is accepted and reached
over TLS; what does not parse refused rather than guessed at; a user name or
password in front of the host refused rather than dropped, with a message of its
own (`url_must_have_no_user_name`, new, English, `tools:ignore="MissingTranslation"`
for the same reason as the strings around it); the query and the fragment
dropped, because Retrofit resolves every call against this address as a base and
keeps neither; and a path that does not end in `/` given one — `Account.url` is
concatenated with `api/greader.php/` in `Credentials.toCredentials`, so the
trailing slash is load-bearing twice over.

`TextFieldError.CleartextUrl` still shows `url_must_be_https` for a non-https
scheme, so the screen says the same thing it did.

`app/src/test/java/app/lenews/account/credentials/ServerUrlTest.kt` (which
replaces `ServerUrlSchemeTest.kt`) asserts the canonical address rather than a
yes/no: the crafted input, `HTTP://`, `HTTPS://RSS.LAN`, `  https://rss.lan  `,
`rss.lan`, `rss.lan:8443`, `https://rss.lan:8443`, a path with and without a
trailing slash, a user name and a user name with a password (written with a
named constant, since spelling one out reads as an email address and G1 says
so), `http://localhost`, `ftp://` and `file://`, the query and fragment cases,
the empty string, blanks, `not an address`, `///` and `rss.lan:notaport`.

`Utils.normalizeUrl` is **deleted**. It was the second reading of a typed
address and its only caller was this screen.

### The instrumented test, and the half it cannot reach

`app/src/androidTest/java/app/lenews/NetworkSecurityPolicyTest.kt` reads the
policy back from `NetworkSecurityPolicy.getInstance()`: no cleartext at all, none
to `rss.lan`, none to `example.org`, and — since the review round — none to
`localhost`, `127.0.0.1` or `10.0.2.2` either.

**The trust-anchor half has no clean public API.** `NetworkSecurityPolicy`
exposes cleartext only. The platform's per-host trust decision is reachable
through `android.net.http.X509TrustManagerExtensions`, but only by presenting a
certificate chain signed by a user-installed authority, and that means either a
real handshake against `rss.lan` — a network the gate must not need — or a
checked-in test authority. Neither is worth it: the emulator checks below observe
exactly that decision, in both directions, on the real server.

### On the emulator

`bench-pixel6-aosp` on `emulator-5554`, `ANDROID_SERIAL` pinned on every adb and
Gradle command, so nothing could reach the phone that is often plugged in.

The debug build with this config was installed over the existing `ledev` store
(`app.lenews.debug`, already logged in). `local.properties` was not copied into
the worktree and no credential was read.

1. **Positive — the sync completes.** Manual sync from the timeline:
   `WM-WorkerWrapper: Worker result SUCCESS`, new articles in the list. The
   authenticated client trusts the Caddy root for `rss.lan` through the
   `<domain-config>` and nothing else changed.
2. **Negative — without the root, it fails with a certificate error.**
   `adb -s emulator-5554 root`, then
   `mv /data/misc/user/0/cacerts-added/33c83ea1.0 /data/local/tmp/`. A
   **force-stop of `app.lenews.debug` was enough** — no reboot: the trust store is
   read per process. The next sync failed in 1.5 s with
   `javax.net.ssl.SSLHandshakeException: java.security.cert.CertPathValidatorException:
   Trust anchor for certification path not found` in logcat, and the app showed
   *"Network failure: java.security.cert.CertPathValidatorException: Trust anchor
   for certification path not found."*
   (`/tmp/lenews-run2/ticket-19-no-ca-sync-fails.png`). The file was then moved
   back, `chown system:system`, `chmod 644`, the app force-stopped again, and the
   sync answered `SUCCESS` once more. **The emulator was not left without the
   root.**
3. **`http://rss.lan` is refused and nothing goes out.** Account tab →
   Credentials, without logging out; the URL edited to `http://rss.lan` and
   *Validate* pressed. The field turns red with *"The address must start with
   https://. LeNews never sends your password or your token in the clear."* and
   there is **no login error and nothing in logcat** — no OkHttp, no
   `CLEARTEXT communication to rss.lan not permitted`, no exception
   (`/tmp/lenews-run2/ticket-19-http-refused.png`). The control that makes that
   silence mean something: the same screen with `https://rss.lan.invalid`
   **does** build a request and answers *"Unreachable URL"*
   (`/tmp/lenews-run2/ticket-19-control-request-goes-out.png`). Two different
   outcomes, so the http one never reached the network.
   The store was not wiped — no `pm clear` was needed — and the account came back
   from the screen unchanged at `https://rss.lan/`
   (`/tmp/lenews-run2/ticket-19-account-unchanged.png`): a login that never runs
   writes nothing.

### Left out, consciously

- **The root certificate on the user's phone stays the user's.** Whether the
  Caddy root is installed there cannot be checked from this machine, the phone is
  out of bounds, and this ticket does not change that. What is now settled is the
  emulator half: with the root present the sync works, without it the app refuses
  the server. `CLAUDE.md` still records the phone as open.
- **`rss.lan` is written into the app.** The user-CA exception names one host, so
  another self-hosted FreshRSS behind a private authority would not be reachable
  without editing this file. That follows from the ticket's own policy — one
  domain, no bundled root — and from LeNews being a client for one account on one
  server; making the trusted host follow the account URL would mean building the
  network security configuration at run time, which the platform does not offer.
- **Feed articles served over `http://` no longer load their images.** Cleartext
  is refused for the WebView too, and that is the policy working rather than a
  regression; nothing was added to soften it.

### Review round (2026-09-06)

An adversarial Codex review of the branch found three things and refused to pass
it. All three were accepted and are fixed here.

**1. The loopback cleartext exception was reachable outside the tests.** It was
argued above as harmless because "the app never connects to the loopback
interface outside those tests", and that was wrong: `ArticleHtml` keeps `http://`
image URLs as they are, and Coil, `FeedColors` and feed discovery all make
requests without going anywhere near the login screen's check — so an article
image or a redirect to `http://127.0.0.1:<port>` would have been fetched in the
clear, at a local service. An account saved with an `http` loopback URL by an
older build would also have synced over it, since the screen check only runs when
someone logs in. The exception is gone, from the shipped file and from anywhere
else; the stub servers serve TLS instead, as described above, and
`NetworkSecurityPolicyTest` now asserts that cleartext to `localhost`,
`127.0.0.1` and `10.0.2.2` is refused.

**2. The check and the request builder read the address differently.**
`serverUrlIsCleartext("http:127.0.0.1:8888/#http://")` answered false — the
fragment holds `http://` while the front of the text holds `http:` without the
slashes — and `Utils.normalizeUrl` kept the text as it was, so Retrofit resolved
`http://127.0.0.1:8888/accounts/ClientLogin` and the exception above let it out.
A password would have gone with it. There is now one function,
`canonicalServerUrl`, whose answer is what the request is built with and what is
stored, and the tests follow that answer through to the request the server
received (`LoginOverTheWireTest.theAddressTheLoginUsesIsTheOneTheServerSees`).

**3. HTTP/2 connection reuse could cross the two trust policies.** This app
allows user-installed authorities for `rss.lan` and preinstalled ones everywhere
else. OkHttp shares one HTTP/2 connection between two hostnames at one address
when the certificate it holds covers both, re-checking the hostname and the
certificate pins and nothing else — so a certificate the user's own authority
issued for `rss.lan` and for a second name would have carried that trust to the
second name, on the plain client. Both clients are now restricted to
`Protocol.HTTP_1_1`, with the reason in a comment in `HttpClients`; the cost is
one connection per host, and this app makes a handful of sequential calls to one
server per sync, so there is nothing here HTTP/2 multiplexing was helping.
`api/src/test/java/app/lenews/api/ConnectionReuseTest.kt` holds it: one
certificate covering `rss.lan` and `images.example`, both resolved to
`127.0.0.1`, and the second request has to open its own connection. Its control
asks the same of a client that still allows HTTP/2 and gets **one** connection —
so the reuse the review described is real, observed here, and closed.

Also in this round: `Utils.normalizeUrl` deleted with its last caller;
`okhttp-tls` added to the version catalog as a test dependency of `api` and `app`
only (its own dependencies are okio, the Kotlin standard library and okhttp — G4
stays green); and `CLAUDE.md`'s count of files carrying the fork copyright header
corrected from forty-five to forty-nine — it was already stale by two before
this round, and two files were added here.

**Not covered.** The emulator checks recorded above were made against the
`ledev` account before this round; the change since then is which protocol
version the clients offer and how a typed address is read, neither of which
touches the trust anchors those checks observed, and the instrumented suite —
including the login and sync against a TLS stub — is green on
`bench-pixel6-aosp`. The three screenshots still show the screen as it was, and
the message for an `http://` address is unchanged.

## From the global review (2026-09-06, second run)

One finding of the second global review lands on the login screen this ticket
rewrote.

**Editing the server or the user kept the previous account's store.**
`AccountCredentialsScreenModel.login()` wrote the new account row and nothing
else, and `accountToLogInWith` carried the cursor over. So pointing the app at
another FreshRSS server, or at another user of the same one, left every article,
every queued read and star, every feed, every folder and the cursor in place —
all of it belonging to the account that had just been replaced. The next sync
would then upload the previous account's pending ids to the new server, where
they name other articles or nothing at all, and its cursor would tell that sync
the new account's older content had already been fetched, so it never would be.

**Two halves, and the login screen has the first.**

`theStoreBelongsToAnotherAccount(storedUrl, storedLogin, url, login)` is a pure
function beside `accountToLogInWith`: an account is a server and a user of it,
so those two decide and the password is not even an argument. The stored URL is
read from the account row and the stored login from the encrypted preferences,
where each is authoritative, rather than from the account object the screen was
opened with — and both addresses are the canonical form `canonicalServerUrl`
makes, so `rss.lan` retyped without its scheme is the same server rather than a
new one. `StoreOwnershipTest` covers the six cases, the first login included.

`Database.writeTheAccountAfterLogin(account, theStoreBelongsToAnotherAccount)`
in `db/src/main/java/app/lenews/db/StoreReset.kt` is the write. When the store
belongs to another account it empties the articles, the pending changes (through
the cascade, and named anyway so the sequence says what it does), the feeds, the
folders and the ledger of ids the horizon dropped, and writes the account row
with `cursor = 0` — **all in one transaction**, so there is no moment at which a
new account row sits over half of somebody else's store. A zero cursor makes the
new account's first sync the initial sync of `docs/article-store.md` §7. When it
does not, the call is the account write it always was, and a password-only change
keeps everything. `StoreResetTest` covers both directions, that one account row
is left, and that the account object handed in is not written back to.

The other half — a sync that was already running when the account was replaced —
is ticket 14's: the sync re-reads the account row inside its transaction and
gives up rather than committing into the new account's store.

**Left out on purpose.** The transaction itself is Room's `withTransaction` and
is asserted by reading rather than by an injected failure: nothing in the
sequence can be made to fail from outside the function, so a test that pretended
to prove it would be proving Room instead.
