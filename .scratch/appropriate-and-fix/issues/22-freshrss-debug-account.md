# 22 — A FreshRSS debug account on rss.lan for the agent to test against

Type: task
Status: resolved
Blocked by: —

## Question

The user's personal FreshRSS account and their phone's Readrops database are off limits, full stop. What the agent may use instead is a debug account on the user's own server, `https://rss.lan` (192.168.101.2, reachable from this machine on the VLAN and from the phone over VPN; the Google Reader endpoint `/api/greader.php` answers). This ticket sets it up so tickets 14, 15 and 16 can verify against a real FreshRSS.

**Human part (checklist for the user):**
1. In FreshRSS on `rss.lan`, create a second user for LeNews development, and set its API password (Profile → API management). Do not reuse the personal account's credentials.
2. Subscribe it to a set of feeds that together produce a high daily volume, so the store grows like the real one does; a few hundred articles a day is the target, the exact list is the user's choice. A handful of busy feeds (news wires, Hacker News firehose, a few Reddit RSS feeds) does it.
3. Confirm the Caddy local-authority **root** certificate is installed as a user CA on the phone (Settings → Security → Encryption & credentials), which is what lets any app that opts into user-CA trust reach `rss.lan`.
4. Put the credentials in `local.properties` (gitignored) using the keys the build already reads for the debug build's login autofill: `debug.freshrss.url=https\://rss.lan`, `debug.freshrss.login=<user>`, `debug.freshrss.password=<api password>`. Never paste them anywhere else.

**Agent part:** verify from this machine that the account logs in over the Google Reader API (`ClientLogin`, then `user-info`), record the user name and the subscription count, and check that `local.properties` is indeed ignored (`git check-ignore local.properties`). Do not print the password anywhere, including this ticket.

**Done when** `local.properties` holds the three keys, a curl login against `rss.lan` with them returns an `Auth` token, and the debug build autofills the login screen with them. The answer records the account name and the number of feeds, nothing more.

## Answer (2026-09-06)

The debug account exists and works. It is **`ledev`** on `https://rss.lan`, with
**19 subscriptions** in 6 categories (Beauté, News, Sans catégorie, Science,
Tech, Voitures). That is the record the ticket asks for: no password, no token,
no address, here or anywhere else in the tree.

**Verified from this machine**, in this order:

- `POST /api/greader.php/accounts/ClientLogin` with the two credentials read out
  of `local.properties` returns an `Auth` token. TLS verified without `-k` and
  without any curl CA flag, so the Caddy local-authority root is already in this
  machine's trust store — only the phone's copy (checklist item 3) is outside
  what can be checked from here.
- `GET /reader/api/0/user-info` answers `userName=ledev`, `subscription/list`
  answers 19 feeds, `unread-count` answers 512 on the reading list. The token is
  sent as `Authorization: GoogleLogin auth=…`, which is what the app's own
  `api` module sends.
- `git check-ignore -v local.properties` answers `.gitignore:22`, and
  `git status --porcelain` is empty with the file written, so the credentials
  are invisible to git rather than merely uncommitted. The other file that ends
  up holding them, `app/build/generated/res/resValues/debug/values/gradleResValues.xml`,
  is ignored too (`app/.gitignore:1`), which is what keeps G1 — which scans
  untracked files — green.
- `./gradlew :app:generateDebugResValues :app:generateReleaseResValues` then
  reading both generated files: the **debug** variant carries the URL, the login
  and the password as three non-empty string resources, and the **release**
  variant carries `https://` and two empty ones. `app/build.gradle.kts:64`
  sources them for `name == "debug"` only, and
  `AccountCredentialsScreenModel.initAccountCredentialsState` reads exactly
  those three names. **The app itself was not launched**: what is proved is that
  the values reach the debug variant's resources and that the login screen's
  state is built from them, not that a screen was seen filled in. No credential
  can reach a release build.

**The volume target (checklist item 2) is not met yet, and could not be
measured.** The account holds 513 articles, and every one of them was inserted
within the same 20-minute window on the morning the feeds were subscribed — so
that number is FreshRSS's first backfill, not a day's flow, and the "few hundred
a day" the ticket asks for stays unproven until a second sample. One call
settles it, a day later:
`stream/items/ids?s=user/-/state/com.google/reading-list&ot=<epoch − 86400>`,
counting `itemRefs`. Worth doing before ticket 14 leans on this account, because
a store that never grows tests none of what tickets 14 to 16 change.

`local.properties` deliberately carries **no `sdk.dir`**. Adding one would make
this file, rather than `ANDROID_HOME`, the answer for both
`scripts/android-sdk-path.sh` and AGP; they would still agree, but the machine's
SDK path would then be duplicated in a file nobody reviews.

**Three `CLAUDE.md` passages were corrected in the same commit**, and they were
wrong in three different ways, not one: the SDK-lookup note said this machine
has **no** `local.properties`; the access-boundary note said ticket 22 was still
open; and the Environment note said the file holds an `sdk.dir`, which was wrong
in the *other* direction — it described a file that did not exist and gave it a
key the file now deliberately does not have.

**Not covered here.** Whether the Caddy root is installed as a user CA on the
*phone* — checklist item 3 — cannot be checked from this machine and the phone
is out of bounds; it is the user's to confirm before a debug build on the phone
reaches `rss.lan`. Nor was `rss.lan` reached from the `bench-pixel6-aosp`
emulator: that AVD has neither the root certificate nor a tested route, and it
does not need one, because G7 uses MockWebServer. Tickets 14 to 16 verifying
against this account is what will settle both.
