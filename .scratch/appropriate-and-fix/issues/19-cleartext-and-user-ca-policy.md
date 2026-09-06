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

Nothing was added to either HTTP client: no `TrustManager`, no
`hostnameVerifier`, no `ConnectionSpec.CLEARTEXT`. `grep -rn
"sslSocketFactory\|hostnameVerifier\|TrustManager\|CLEARTEXT" --include=*.kt`
finds nothing in `api` or `app` outside this ticket's test. The plain client of
ticket 18 is unchanged and so stays on the base configuration: a feed image from
another host is checked against preinstalled authorities alone.

### The one cleartext exception, and why it is not a debug override

The instrumented tests run a stub FreshRSS server (MockWebServer) on the
device's **loopback** interface, and the app under test obeys this same file.
With cleartext refused everywhere, the instrumented tests that talk to it
fail with `UnknownServiceException: CLEARTEXT communication to localhost not
permitted` — observed on the four tests of `SyncWorkerTest`, and six androidTest
files use that stub. So the file carries a second `<domain-config>`
permitting cleartext to `localhost` and `127.0.0.1`, and nothing else.

That is a deviation from the ticket's letter and worth stating plainly. Two
things make it small:

- Bytes sent to the loopback interface never leave the device, so there is no
  network on which they could be read, and the app makes no such connection
  outside the tests — the login screen refuses an `http://` address whatever the
  host.
- `NetworkSecurityPolicy.isCleartextTrafficPermitted()` with no argument is
  still **false**: the platform answers true only when cleartext is permitted for
  *every* destination (`ApplicationConfig.isCleartextTrafficPermitted`), so the
  exception cannot hide behind it.

It is in the one shipped file rather than in a debug-only copy on purpose. Two
copies drift, and the copy nobody runs the instrumented tests against would be
the one making the promise; with one file, the test that reads the policy back
speaks about the app that ships. The exception is asserted in that test —
`localhost` and `127.0.0.1` permitted, `notlocalhost`, `localhost.example.org`
and `127.0.0.2` refused — so widening it means changing a test.

### The login screen refuses http:// before any request

`serverUrlIsCleartext(typedUrl)` in
`app/src/main/java/app/lenews/account/credentials/AccountCredentialsScreenModel.kt`,
a pure function beside `accountToLogInWith`. `validateFields()` calls it and sets
a new `TextFieldError.CleartextUrl`, so `login()` never runs and no request is
built. The message is a new English string, `url_must_be_https`, carrying
`tools:ignore="MissingTranslation"` for the same reason as the two strings above
it: which locales LeNews keeps is still an open product call.

The decision is made on the **parsed `HttpUrl`**, not on the text: whitespace is
trimmed, the scheme is compared in lower case, and an address with **no scheme
is read as `https://`** — which is what `Utils.normalizeUrl` does with it
afterwards, so `rss.lan` is accepted and reached over TLS. Text that is no
address at all is not cleartext; the empty-field check and the login's own error
report those.

`app/src/test/java/app/lenews/account/credentials/ServerUrlSchemeTest.kt`, written
before the function and failing against its absence, covers `http://`, `HTTP://`,
`HtTp://RSS.LAN/`, `  https://rss.lan  `, `  http://rss.lan  `, `rss.lan`,
`rss.lan/api/greader.php`, `https://rss.lan:8443`, `http://rss.lan:8443`,
a host behind a user name in both schemes (written with a named constant in the
test, since spelling it out reads as an email address and G1 says so), an IP
with and without a scheme,
`https://rss.lan/?next=http://example.org` (the query string is not a scheme),
the empty string, blanks, `not an address` and `ftp://rss.lan`.

### The instrumented test, and the half it cannot reach

`app/src/androidTest/java/app/lenews/NetworkSecurityPolicyTest.kt` reads the
policy back from `NetworkSecurityPolicy.getInstance()`: no cleartext at all, none
to `rss.lan`, none to `example.org`, and the loopback exception exactly as wide
as it is meant to be.

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
- **`Utils.normalizeUrl` was left as it is.** It decides whether an address has a
  scheme with `contains` where the new function uses a leading-scheme match; the
  two agree on every address a person would type, and rewriting it belongs with
  whoever revisits that helper.
