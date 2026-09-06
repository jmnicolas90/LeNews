# 26 — Sign the release build: the keystore, the Gradle plumbing, the fingerprint

Type: task
Status: open
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
