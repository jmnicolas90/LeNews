# 26 — Sign the release build: the keystore, the Gradle plumbing, the fingerprint

Type: task
Status: resolved
Blocked by: —

## Question

[Ticket 23](23-release-signing.md) decided all of it; this one builds it. Read
that `Answer` first and re-decide nothing in it.

The state today: `app/build.gradle.kts` declares no `signingConfig` for any
build type, so `:app:assembleRelease` yields `app-release-unsigned.apk`, which
cannot be installed. The debug build is the only one that can reach a phone,
which is why [ticket 24](24-baseline-profile.md) cannot measure anything.

**Steps:**

1. **A script the user runs, not lines to paste** (`/home/skynet/CLAUDE.md`:
   write commands in a file and let the user execute the file). It creates
   `~/.android/lenews-release.jks` with `keytool` — RSA 4096, 10000 days, alias
   `lenews`, `CN=LeNews, O=LeNews`, no email address in the DN — prompting for
   the password **interactively** — one password, since PKCS12 refuses a differing key
   password — and writes `~/.gradle/gradle.properties` with
   mode 600 holding `lenews.release.storeFile`, `storePassword`, `keyAlias` and
   `keyPassword`. It must refuse to overwrite an existing keystore. **The agent
   never learns the password**: it is not passed on a command line, not echoed,
   and not read back.

2. **Hand the user the backup checklist the moment the script finishes**, before
   anything else in this ticket, because a key that exists in one place is the
   failure ticket 23 is about. One KeePassXC entry: title `LeNews release
   signing key`, user name `lenews` (the alias), the password in the password
   field, and **`lenews-release.jks` as an attachment** on that same entry —
   Advanced tab, Attachments, Add. The notes field carries the keystore path and
   format (PKCS12, RSA 4096), the alias, that one password serves both the store
   and the key, the expiry `keytool` prints, the SHA-256 fingerprint, that the
   same passwords sit in `~/.gradle/gradle.properties` on this machine, and what
   losing it costs — no future build can upgrade an existing install, so every
   user uninstalls and loses their local history. Only the file and the password
   are irreducible; the alias is recoverable with `keytool -list` and the
   fingerprint is public.

3. **The Gradle plumbing**, presence-based. When all four properties are there,
   the release build type gets a `signingConfig` with **v2 and v3 enabled and v1
   off** (`enableV1Signing = false`, `enableV2Signing = true`,
   `enableV3Signing = true`). When any is missing — every GitHub runner — no
   `signingConfig` is declared and the build stays exactly as it is today. A
   missing keystore *file* when the properties are set is a configuration
   failure with a message naming the path, not a silent fall back to unsigned:
   that would hand the user an uninstallable APK they believe is signed.

4. **Correct the three places that assert a release build is unsigned**, or they
   become lies: the G6 step comment in `.github/workflows/ci.yml` (around line
   132), the G6 comment in `scripts/check.sh` (around line 337), and the
   `Answer` of [ticket 03](03-executable-quality-gate.md). CI's behaviour does
   not change — it has no properties, so it still builds unsigned — but its
   comment has to say *why* rather than "no signingConfig is set".

5. **Publish the fingerprint.** Once the key exists, read the SHA-256 out with
   `apksigner verify --print-certs` on the signed APK and put it in `README.md`
   beside the download instructions. It is public by construction and it is how
   a sideloader checks that the APK they downloaded carries the same key as the
   last one.

6. **Verify on hardware, not just in Gradle.** `apksigner verify --print-certs`
   on the release APK, then install it on `bench-pixel6-aosp` — an unsigned APK
   is refused by the installer, so a successful install is the actual proof. The
   `.debug` suffix is on the debug build only, so a release build and a debug
   build **cannot both sit on the emulator**; expect to uninstall one.

7. **`CLAUDE.md`** gains a short paragraph: where the key is, that the passwords
   live in `~/.gradle/gradle.properties` and never in the tree, that CI signs
   nothing and never gets the key, and that the fingerprint in `README.md` is
   the published one.

**Done when** `./gradlew :app:assembleRelease` on this machine produces an APK
that installs on the emulator, the same command on a runner still produces an
unsigned one, `scripts/check.sh` is green through G7, and the fingerprint is in
`README.md`.

**Not in scope**, per ticket 23: the release procedure itself — which commit is
tagged, how `versionCode` is bumped, the artifact's file name, where release
notes come from. Nothing is ready to release yet.

## Answer (2026-09-06)

Built as ticket 23 decided it; nothing in that decision was revisited.

### The key exists and it is backed up

`scripts/create-release-keystore.sh` is the script the user ran, once, in their
own terminal — the agent never saw the password and still has not. It refuses to
touch an existing keystore, creates `~/.android/lenews-release.jks` (PKCS12, RSA
4096, 10000 days, alias `lenews`, DN `CN=LeNews, O=LeNews`, no email address),
appends the four `lenews.release.*` properties to `~/.gradle/gradle.properties`
at mode 600, prints the certificate, and ends on the KeePassXC backup checklist.
The password reaches `keytool` on standard input rather than through
`-storepass`, so it never enters a process listing, and `printf` is a shell
builtin so it is not an argument of any process either. Values written into the
properties file are escaped for Java's properties format — a backslash doubled,
a space escaped — because a password starting with a space or containing a
backslash would otherwise be read back as a different string than was typed.

Two things the script had to learn from being run, both found by running it
against a throwaway `HOME` before it was handed over: `keytool` writes the
keystore but not the directory holding it, so the script creates it; and this
host answers in French, so the certificate is printed whole rather than filtered
for English labels that are not there.

The user confirmed the KeePassXC entry — the file attached to the entry that
holds its password, in a database backed up off this disk. Nothing in the repo
can check that, which is why it was handed over the moment the script finished
rather than at the end.

### The Gradle plumbing

`app/build.gradle.kts` reads the four properties through `providers.gradleProperty`
and builds `Map<String, String>?` — non-null only when all four are present and
none is blank. Non-null creates a `release` signing config; null leaves
`signingConfigs.findByName("release")` returning null, which is the same release
build type this module had before signing existed. `enableV1Signing = false`,
`enableV2Signing = true`, `enableV3Signing = true`.

A keystore file that is not there while the properties are set throws a
`GradleException` at configuration time naming the absolute path and both ways
out (restore from KeePassXC, or remove the properties). Verified by pointing
`storeFile` at `/nonexistent/`: the build fails with that message rather than
producing an unsigned APK.

### What was actually verified

- `:app:assembleRelease` with no properties → `app-release-unsigned.apk`, byte
  for byte the behaviour a runner gets. Checked before the key existed.
- `:app:assembleRelease` with the properties → `app-release.apk`.
- `apksigner verify --print-certs`: one signer, `CN=LeNews, O=LeNews`, RSA 4096,
  v1 **false**, v3 **true**.
- **`apksigner verify` reports v2 as `false` at the APK's own `minSdk 31`, and
  the v2 block is there all the same** — `apksigner verify --min-sdk-version 24`
  reports v2 `true`. v3 alone covers API 28 and up, so nothing below 31 is left
  for v2 to answer for. Do not read that first `false` as a missing block and
  "fix" the config.
- `adb -s emulator-5554 install -r` on `bench-pixel6-aosp`: `Success`, and
  `dumpsys package app.lenews` shows `PackageSignatures{… version:3 …}`. An
  unsigned APK is refused by the installer, so the install is the proof.
- **Step 6 of this ticket was wrong about one thing**: a release build and a
  debug build *do* sit on the emulator at the same time. The `.debug` suffix
  makes them `app.lenews` and `app.lenews.debug`, two different applications to
  the package manager, so nothing had to be uninstalled.

### The fingerprint

```
5badb557cb56fae27c19c798e9904bb234f91962f478b6c754edca8241831524
```

Published in `README.md` under *Getting it*, in the exact form
`apksigner verify --print-certs` prints, since that is the command the section
tells a sideloader to run. It is public by construction — it travels inside
every APK.

### The three lies corrected, and the documentation

`.github/workflows/ci.yml` and `scripts/check.sh` now say *why* a runner still
builds unsigned (it has none of the four properties, and the key never enters a
GitHub Actions secret) rather than "no signingConfig is set", and ticket 03's
`Answer` carries a dated correction rather than a quiet rewrite. `CLAUDE.md`
gains a *Release signing* paragraph in *Environment*, and the note that not
every script under `scripts/` is a gate now names this one.
`CONTRIBUTING.md`'s header table gains the new script and reads sixty-six files.

CI's behaviour does not change. G6 asks whether the release build works and gets
the same answer in both places; only the artifact differs, which is a property
of the artifact and not a verdict about the tree.

### The review round

Two reviewers, standards and spec, against the branch diff. Both confirmed the
fingerprint in `README.md` against the built APK, the two file modes, the four
property keys, the v1/v2/v3 report and the bookkeeping. What they found and what
was done:

- **`CLAUDE.md` still said "sixty-five non-Markdown files carry the header"**
  while `CONTRIBUTING.md` had been bumped to sixty-six in the same commit — the
  two documents that both state the count disagreeing, which is the drift
  `CLAUDE.md` itself names for `check.sh` against `ci.yml`. Fixed.
- **The properties escaping was incomplete and asymmetric.** Only the password
  was escaped, so a keystore path containing a space or a backslash would have
  been read back as a different path; and a leading tab, which
  `java.util.Properties` strips exactly as it strips a leading space, was not
  covered. There is now one `properties_escape` applied to all four values, and
  running the script against a throwaway `HOME` with the password ` a\b c `
  proved the round trip: `java.util.Properties` reads back
  `[32, 97, 92, 98, 32, 99, 32]`, byte for byte what was typed, and that string
  opens the keystore.
- **The password validation gained an ASCII check**, which came out of testing
  the tab case rather than out of a reviewer: PKCS12 encrypts with a PBE that
  takes printable ASCII only, so `keytool` refuses a tab or an accented letter
  *after* the password has been typed twice, with `Password is not ASCII` and
  nothing else to go on. The prompt now says so and asks again, which is also
  why the tab escaping is belt and braces rather than the live case.
- **`[ -e "$keystore" ]` is false for a dangling symlink**, which `keytool`
  would have written straight through. It is `[ -e ] || [ -L ]` now.
- **A keystore written but a properties file that could not be** would have
  exited mute under `set -e`, leaving a key with nothing pointing at it and a
  script that refuses to run again. It now says which half exists and what to do.
- **The `GradleException` fires for every task, not only the release build**,
  because it throws during configuration. That is literal compliance with step 3
  — "a configuration failure" — and it is kept, but the blast radius and the way
  out are now written down in both the code comment and `CLAUDE.md` rather than
  left to be discovered mid-gate.
- **`README.md` claimed "every release APK is signed with the same key"** when no
  LeNews release exists yet. Reworded to a promise about the key rather than a
  claim about artifacts that are not there.
- The stringly-typed `Map<String, String>` wrote each property name twice; the
  `lenews.release.` prefix appears once now.

Three findings were judged and kept as they are. The second refusal guard
(properties present, keystore absent) is beyond "must refuse to overwrite an
existing keystore" but is the same mistake seen from the other side. `chmod 600`
on a `~/.gradle/gradle.properties` that already existed is a file the script did
not create, but it is about to hold a password; it now says so rather than doing
it silently. And unblocking ticket 24 in the map's open list is more than the
one line *Decisions so far* asks for, but 24's entry read "blocked by 26" and
leaving it would have been false.

### Not done, deliberately

The release procedure — which commit is tagged, how `versionCode` moves, the
artifact's file name, where release notes come from — is out of scope per ticket
23, and nothing is ready to release. What ticket 24 needed is now there: a
genuine release build that installs on hardware.
