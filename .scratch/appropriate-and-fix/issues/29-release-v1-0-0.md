# 29 — Cut `v1.0.0`: the tag, the signed APK, the GitHub release, and the phone it goes on

Type: task
Status: open
Blocked by: — ([28](28-version-1-0-0.md) is merged)

## Question

Nothing to decide about *whether*: on 2026-09-12 the user decided that the app
is to be released and used day to day on their own phone, because the Samsung
is a slow test device and daily use is the only check the sync, the horizon and
the history have never had (the README's "what has not happened is time"). What
[ticket 23](23-release-signing.md) and [ticket 26](26-signing-config-and-keystore.md)
left open — which commit is tagged, how `versionCode` moves, the artifact's
name, where the notes come from — is settled below, and this ticket does it.

## The tree changes, one commit on a ticket branch

1. **`CHANGELOG.md`**: the heading becomes `## 1.0.0 — 2026-09-12` and the
   opening line "Nothing has been released yet. This is what the fork has
   changed so far." becomes a sentence saying this is the first release. The
   list under it is the release notes and is not rewritten.
2. **`CLAUDE.md`, the access boundary**: it speaks of "the user's phone" as one
   device, and there are two. The **Samsung Galaxy A06 (Android 16)** is a slow
   *test* phone, touched only when the user asks in that turn, as today. The
   **Pixel 6 on GrapheneOS** is the user's daily phone: it holds their personal
   FreshRSS account and their Readrops database, it is **never attached to this
   machine for any ticket and never a target of adb**, and the app reaches it
   from the GitHub release page, installed by the user. State the
   "debug in prod" rule that follows: a bug found there is reported by the user
   as symptoms, plus logcat they pull themselves if they choose; agents reproduce
   on the emulator against `ledev`; nothing ever reads that phone's store or
   syncs against that account. Note that the GrapheneOS Pixel is also the first
   device to exercise the GrapheneOS constraint for real. Adjust the
   "A real phone is often attached" sentences so they name the Samsung.
3. **`README.md`**: nothing, unless a sentence turns out to be false once the
   release exists. *Getting it* already points at the releases page.

## The release procedure, decided here

- **Which commit.** The `--no-ff` merge of this ticket into `main`, once the
  gate is green on it, carries an **annotated** tag `v1.0.0` whose message is
  the changelog heading. Every later release is the same: a ticket, a merge, a
  tag on the merge.
- **`versionCode`.** `1` for `1.0.0`. It goes up by one on every release after
  this, whatever the version name does, in the release ticket of that version.
- **The build.** `./gradlew :app:assembleRelease` from the **main checkout** on
  this machine — the only one with the key — at the tagged commit with a clean
  `git status`, so `git describe --exact-match` answers `v1.0.0`. The artifact
  is renamed **`LeNews-1.0.0.apk`**.
- **The check before anything is attached**, all three:
  `apksigner verify --print-certs` prints the SHA-256 the README publishes;
  `aapt2 dump badging` (or `apkanalyzer`) reads `versionName='1.0.0'`
  `versionCode='1'`; and the APK the tag builds is the APK that goes up.
- **The release.** `gh release create v1.0.0 LeNews-1.0.0.apk --title
  "LeNews 1.0.0" --notes-file <the 1.0.0 section of CHANGELOG.md>`; notes are
  the changelog section verbatim, nowhere else. `main` and the tag pushed
  first, so CI has run on the commit the release names.

## On the Pixel — the user's step, written down here so it is one file to follow

The app trusts a user-installed authority for `rss.lan` and no other host, and
the Caddy root is not on the Pixel. GrapheneOS supports user CAs in the normal
place: *Settings → Security & privacy → More security settings → Encryption &
credentials → Install a certificate → CA certificate*, given the root
(`/etc/pki/ca-trust/source/anchors/caddy-root.crt` on this machine) copied to the
phone by any means that is not adb from an agent session. Then the APK from the
release page, installed by the user, and a login with their own credentials —
which is the initial sync of the model's §7, from zero. Nothing about this step
touches the tree, and the ticket does not wait for it: the answer records it as
the user's, done or pending.

## Done when

`v1.0.0` is on `origin` and points at the merge on `main`; the release page
carries `LeNews-1.0.0.apk`; its fingerprint matched the README before it went
up and its badging says `1.0.0` / `1`; CI is green on the pushed `main`;
`CHANGELOG.md` and `CLAUDE.md` say what is now true; and the gate was green
G0-G7 on the commit the tag names.
