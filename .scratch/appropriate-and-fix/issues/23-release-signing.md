# 23 — Where the release keystore lives, and how a release build gets signed

Type: grilling
Status: resolved
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

## Answer (2026-09-06)

Decided in conversation. Six questions, all six answered; the implementation is
[ticket 26](26-signing-config-and-keystore.md) and nothing below is left for it
to re-decide.

### The key lives outside the tree, at an absolute path

**`~/.android/lenews-release.jks`** — beside the `debug.keystore` that has been
there since 2025-07-26, which is where Android keys live on this machine. Not
the repo root, and the reason is stronger than "someone might `git add -f` past
`*.jks`": code changes happen in worktrees under `.claude/worktrees/`, so a path
relative to the root project is a **different file in every worktree**. A
release build there would either fail or need the key copied in — exactly the
friction `local.properties` already causes, and copying a release key around per
ticket branch is the opposite of what this ticket is for. An absolute path
outside the tree is one file, identical from every checkout, that no git
operation in this repo can reach.

**Backup: the user's KeePassXC database, which they confirmed they use and back
up regularly — the `.jks` attached to the entry that holds its password.** Full-
disk encryption protects the key against a stolen machine, not against a dead
disk, so the load-bearing part is that the `.kdbx` lives somewhere other than
this disk; a database backed up only to `/home/skynet` would die with the
keystore and protect nothing. One entry, so the password cannot drift from the
key it opens, and restoring is `attachment-export` plus reading the password
field. Only two things are irreducible — the file and the password; the alias is
recoverable with `keytool -list` and the fingerprint is published. **One password,
not two**: `storePassword` and `keyPassword` below hold the *same value*, because
`keytool` now generates PKCS12 and PKCS12 does not support a key password
differing from the store password. This is the user's to do and nothing in the
repo can check it. What it buys:
a lost release key means every future LeNews is a **different application** to
anyone who installed the old one — Android refuses to upgrade across a signature
change — so the recovery from losing it is telling every user to uninstall and
lose their local history.

### The passwords reach Gradle through `~/.gradle/gradle.properties`

That file does not exist yet; the implementation creates it. Four keys, all four
outside the tree, so **the repository holds no knowledge of the key at all, not
even its path**:

```
lenews.release.storeFile=/home/<user>/.android/lenews-release.jks
lenews.release.storePassword=…
lenews.release.keyAlias=lenews
lenews.release.keyPassword=…
```

Why not the three alternatives:

- **`local.properties`**, the existing pattern for the debug FreshRSS
  credentials, is *in the tree*. It gets copied into worktrees, so the release
  password would travel with every ticket branch — and `CLAUDE.md` already tells
  the user never to paste that file's contents anywhere.
- **Environment variables** in a shell profile are readable by every process the
  user runs and visible in `/proc/<pid>/environ`.
- **`secret-tool` / the GNOME keyring**, read at build time, needs an unlocked
  keyring; a headless or background gate run would fail as a mystery.

`gradle.properties` also avoids the one failure mode `-Pkey=value` has: a
command line lands in the process listing, a properties file never does.

**The agent never sees the release password.** Ticket 26 writes the Gradle
plumbing and hands the user a *script* to run — per `/home/skynet/CLAUDE.md`, a
file to execute, not lines to paste — which runs `keytool`, prompts for the
password interactively, and writes `~/.gradle/gradle.properties` with mode 600.
The password is chosen by the user, entered by the user, and never appears in an
agent transcript, a tool result or a commit.

### Signing is presence-based: signed here, unsigned on the runner

`app/build.gradle.kts` declares the release `signingConfig` **only when those
properties are present**. On this machine they are, so `:app:assembleRelease`
produces an installable APK. On a GitHub runner they are not, so it produces
`app-release-unsigned.apk` exactly as it does today.

**This is not the drift `CLAUDE.md` forbids.** That rule is about the two gates
disagreeing on *whether the tree is good*; both still ask "does the release build
work" and both still get the same answer. The signature is a property of the
artifact, not a verdict about the tree. Keeping it presence-based is what makes
the build measured on hardware the same build that would ship, rather than a
near-relative of it.

**The release key never goes into GitHub Actions secrets.** A secret on GitHub's
infrastructure is exfiltrable by anyone who can land a workflow change, and this
key has no rotation story that does not break every install. CI signs nothing,
ever.

The real risk that degradation opens is not asymmetry — it is somebody attaching
`app-release-unsigned.apk` to a GitHub release. That is a release-procedure
problem, not a gate problem, and the procedure closes it: **`apksigner verify
--print-certs` on the APK, and the fingerprint compared against the published
one, before anything is attached to a release.**

Three places in the tree currently assert that a release build is unsigned and
become lies the moment this lands — `.github/workflows/ci.yml` (the G6 step
comment), `scripts/check.sh` (the G6 comment), and the `Answer` of
[ticket 03](03-executable-quality-gate.md). All three are ticket 26's to correct
in the same commit.

### No debug-signed interim

A release-configuration build signed with the debug key would answer
[ticket 24](24-baseline-profile.md)'s performance question sooner, and it is
refused. Creating the real key is one `keytool` invocation and the plumbing is
small, so the interim buys days rather than weeks, at the cost of an artifact
that looks like a release and is not. There is therefore nothing to keep
distinguishable: **ticket 24 measures a genuine release build.**

### The certificate

Stated once here so ticket 26 does not re-decide it:

- **RSA 4096**, validity **10000 days**. Android wants a key that outlives the
  application; a certificate that expires is a signing key that can no longer
  sign an upgrade.
- Alias **`lenews`**.
- Distinguished name **`CN=LeNews, O=LeNews`** — **no email address in the DN**,
  which is published inside every APK. G1 would catch one in the tree, but the
  DN is not in the tree.
- **Signature schemes v2 and v3 only; v1 (jar signing) off.** `minSdk 31` means
  every target device does v2, so v1 is dead weight, and v3 (API 28+) is what
  makes key rotation possible at all should this key ever be compromised.

**The SHA-256 certificate fingerprint is published**, in `README.md` beside the
download instructions rather than in a file nobody opens. It is public by
construction — it is in every APK shipped — and for a sideloaded application it
is the only way somebody can check that the APK they just downloaded is signed
by the same key as the last one. Ticket 26 puts it there once the key exists.

### Surfaced, not resolved: upstream's 22 tags

`origin` carries **`v1.0` through `v2.1.1`, 22 inherited Readrops tags**, and
the map says releases are tags. LeNews is `versionCode 1` / `versionName 0.1.0`,
so a `v0.1.0` tag sorts *below* every one of them and a visitor to the repository
sees `v2.1.1` as the newest-looking thing there. That is a publishing decision
(delete them? prefix LeNews's own? start at v1?) with no bearing on where the key
lives, so it is [ticket 27](27-inherited-upstream-tags.md) and not this one.

### What this ticket deliberately does not settle

The **release procedure** beyond the verification step above: which commit gets
tagged, how `versionCode` is bumped, the artifact's file name, what the release
notes are drawn from. Nothing is ready to release — the destination does not
include cutting one — so that is a later ticket, opened when there is something
to publish.
