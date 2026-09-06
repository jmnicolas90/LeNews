# 23 — Where the release keystore lives, and how a release build gets signed

Type: grilling
Status: open
Blocked by: —

## Question

`app/build.gradle.kts` declares no `signingConfig` for any build type, so
`:app:assembleRelease` produces `app-release-unsigned.apk` and **nothing signs
it**. G6 has always been green on that, because building an unsigned APK is
still building one. The consequence only shows on a device: an unsigned APK
cannot be installed, so the only build that can ever reach a phone today is the
debug one.

That surfaced on 2026-09-06, installing on the user's Samsung (Galaxy A06,
Android 16). The debug APK is 59.8 MB across 28 dex files with no R8 and no
baseline profile; the release APK is 7.7 MB with both. The user reported the app
"slow but usable", and **there is no way to tell how much of that is the app and
how much is the debug build**, because the comparison build cannot be installed.
Ticket 11 measured the database side of slowness on the emulator; this is the
other axis and it is currently unmeasurable on real hardware.

This is the map's *Not yet specified* entry **"Release signing and publishing"**
sharpening, exactly as that entry said it would once the rename landed. It has.
So the fog to clear:

- **Where does the keystore live?** Not in the repo — that is not negotiable,
  and `.gitignore` already drops `*.jks`. On this machine, next to
  `local.properties`? Somewhere outside the tree entirely? Backed up how? A lost
  release key means every future release is a new app to anyone who installed
  the old one.
- **How does Gradle reach the password** without it landing in the tree, in a
  process listing, or in a Gradle scan? `local.properties` is the existing
  answer for the debug FreshRSS credentials and is gitignored; is it the answer
  here too, or is that overloading a file that already holds something the user
  is told never to paste elsewhere?
- **Does CI sign at all?** `.github/workflows/ci.yml` builds a release APK on
  every push. If it must stay able to do that without a key, the config has to
  degrade to unsigned on the runner rather than fail — and then G6 means
  something different locally than it does in CI, which is the drift
  `CLAUDE.md` says to fix rather than pick a winner.
- **Is the debug keystore acceptable as an interim?** A release-configuration
  build signed with the debug key is installable and would answer the
  performance question this week, but it is not a release and must not be
  mistaken for one. If yes, say plainly how it is kept distinguishable from a
  real release.
- **What does "publishing" mean here?** The map says GitHub releases, not a
  store. That changes what the signing story has to satisfy: no Play App
  Signing, no upload key, just an APK someone downloads and sideloads — which
  makes key continuity entirely the user's problem.

**Done when** the decision is written in this ticket's `Answer`, specific enough
that an implementation ticket can be opened against it without re-deciding
anything: where the key lives, how the password travels, what CI does, and
whether an interim debug-signed release build is sanctioned. **Implementing it
is a separate ticket** — this one resolves the decision.
