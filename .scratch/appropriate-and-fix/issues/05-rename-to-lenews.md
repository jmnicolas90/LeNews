# 05 — Rename the app to LeNews

Type: task
Status: resolved
Blocked by: 04

## Question

Mechanical, wide, and cheap exactly once — after ticket 04 there is less to rename, and the gate catches breakage. `applicationId` and `namespace` become `app.lenews`; the three modules' namespaces become `app.lenews.app`, `app.lenews.db`, `app.lenews.api` (or `app.lenews` for the app module and sub-namespaces for the libraries; pick one and say why); the source package `com.readrops.*` becomes `app.lenews.*` across the tree with `git mv`, tests included; `app_name` and every user-visible string that says Readrops becomes LeNews; the launcher icon and `db`'s stray mipmaps are replaced by a placeholder icon that is not upstream's logo.

Build identity: upstream has three build types, `debug` (`.debug` suffix), `release`, and `beta` (`.beta` suffix, debug-signed). Ding decided one build identity for every build type. Do the same here unless there is a reason not to: delete `beta`, keep the `.debug` suffix only if the user wants both installed side by side — recommend keeping it, since the store Readrops and a LeNews debug build coexisting on the phone during this map is useful.

Version: start LeNews at `versionCode 1`, `versionName 0.1.0`. Nothing upgrades from the Readrops package, so the old numbers carry no obligation.

Also rename the GitHub repository (`jmnicolas90/Readrops` → `jmnicolas90/LeNews`), the `origin` remote URL, and this working directory (`/home/skynet/dev/LeNews`). The directory rename changes the memory path for agent sessions; note it in the resolution.

Keep the GPL copyright headers and every upstream author name exactly as they are. Renaming the app does not change who wrote the code.

**Done when** the gate is green, `grep -ri readrops` returns only GPL headers, `CHANGELOG.md`, the code-review file and deliberate fork-origin prose, and `aapt dump badging` on the debug APK shows `package: name='app.lenews.debug'` and `application-label:'LeNews'`.

## Answer (2026-09-06)

The app is `app.lenews` now, top to bottom: applicationId, namespaces, source
package, class names, the launcher icon, the app name on screen and the version.
227 files changed, 68 of them moves git followed. The gate is green G0 to G7.

### The namespace scheme, and why

| module | namespace | source package |
| --- | --- | --- |
| `app` | `app.lenews` | `app.lenews.*` |
| `db` | `app.lenews.db` | `app.lenews.db.*` |
| `api` | `app.lenews.api` | `app.lenews.api.*` |

`applicationId` is `app.lenews`, so for the app module namespace and
applicationId are the same string, which is the ordinary Android arrangement and
one less thing to keep in step.

The ticket offered `app.lenews.app` / `app.lenews.db` / `app.lenews.api` as the
alternative. It was turned down for one reason: the app module's namespace is
where `R` and `BuildConfig` are generated, and `app.lenews.app.BuildConfig`
stutters where `app.lenews.BuildConfig` does not. The two libraries do need
namespaces of their own — AGP will not let two modules share one — and
`app.lenews.db` / `app.lenews.api` say what is in them. Nesting a library's
namespace under the app's is allowed and common; nothing in AGP or in Kotlin
minds. Checked before committing to it: no subpackage of the app module is
called `db` or `api`, so `app.lenews.db` names the library and nothing else.

The house style the map settled (`app.ding`, `app.loquace`) is followed.

### What was renamed

- **Source tree.** Eight `git mv`s, one per source set that had any:
  `api/src/{main,test}`, `app/src/{main,test,androidTest}`,
  `db/src/{main,test,androidTest}`. `com/readrops/api` → `app/lenews/api`,
  `com/readrops/db` → `app/lenews/db`, `com/readrops/app` → `app/lenews`.
  History follows: `git log --follow` still reaches the fork point.
- **Package declarations and imports**, every `.kt` file, plus the Koin module
  references (`apiModule`, `dbModule`, `appModule` are imported by their new
  fully-qualified names in `LeNewsApp` and in `TestApplication`).
- **Build files.** `namespace` in all three modules, `applicationId`,
  `testInstrumentationRunner` (`app.lenews.LeNewsTestRunner`).
- **`AndroidManifest.xml`.** `android:name=".LeNewsApp"`, the two `@style/Theme.LeNews`
  references. The relative component names (`.sync.SyncBroadcastReceiver`,
  `.MainActivity`, `.util.CrashActivity`) resolve against the new namespace and
  needed no edit; verified in the merged manifest through `aapt dump badging`,
  which reports `app.lenews.MainActivity`.
- **Class names that carried the old brand.** `ReadropsApp` → `LeNewsApp`,
  `ReadropsTheme` → `LeNewsTheme`, `ReadropsTestRule` → `LeNewsTestRule`,
  `ReadropsTestRunner` → `LeNewsTestRunner`; their three files renamed with
  `git mv` too. `TestApplication` keeps its name — it says what it is and never
  said Readrops.
- **Resources.** `Theme.Readrops` and `Theme.Readrops.SplashScreen` →
  `Theme.LeNews` / `Theme.LeNews.SplashScreen`; the string `readrops_crashed` →
  `app_crashed`, in `values/` and in the eight locales that translate it.
- **Room schema export directory.** `db/schemas/com.readrops.db.Database` →
  `db/schemas/app.lenews.db.Database`, with `git mv`. **The six exported JSON
  files do not embed the package** — checked, `grep -c readrops` is 0 in all six
  — so only the directory name, which Room derives from the database class,
  needed changing. `MigrationsTest` reads them from `androidTest` assets and
  passes in G7.
- **The database file name.** `Room.databaseBuilder(..., "readrops-db")` is
  `"lenews-db"`. It is a file inside the app's private data directory and the
  applicationId change makes every install fresh, so nothing has to be migrated
  or moved.
- **The lint baseline.** Regenerated rather than hand-edited, and diffed against
  the old one entry by entry: **429 entries before, 429 after, one substitution**
  — the `MissingTranslation` entry for `readrops_crashed` is now the one for
  `app_crashed`. Nothing new was baselined, nothing real was hidden.
- **ProGuard/R8, `network_security_config`, `xml/`, `about_libraries`.** Checked,
  nothing to do. Ticket 04 already took out the four `-keep` rules that named
  `com.readrops.*` packages; the remaining rules are `-dontwarn`s for third-party
  libraries. `network_security_config.xml` and `file_paths.xml` never named the
  app. There is no `aboutlibraries` config file — the plugin reads the dependency
  graph — and `AboutLibrariesScreen` names no package.
- **`scripts/check.sh`, `.github/workflows/ci.yml`, `scripts/check-no-personal-email.sh`.**
  Checked one by one: **none of the three ever named the package.** G7 pins the
  device with `ANDROID_SERIAL` and lets Gradle install and uninstall, so there is
  no `adb install`, no `pm grant` and no `uninstall` line naming a package
  anywhere in the gate — the `POST_NOTIFICATIONS` grant moved into the test rule
  in ticket 03. The only edit in the three was the CI comment that named
  `ReadropsTestRule`, now `LeNewsTestRule`.

### The launcher icon

Upstream's logo is gone from the tree. It lived entirely in `db` — five
`mipmap-*dpi/ic_launcher.png` bitmaps, `mipmap-anydpi/ic_launcher.xml`, and the
adaptive icon's `ic_launcher_background` / `ic_launcher_foreground` vectors, the
foreground being upstream's stacked-lines mark. All eight files are deleted, and
`db` is left with the two resources a library has business holding:
`ic_freshrss.xml` and its `strings.xml`.

The replacement is new, in `app`, where a launcher belongs:

- `app/src/main/res/drawable/ic_launcher_background.xml` — one path filling
  108×108, flat `#FF14213D` (deep navy).
- `app/src/main/res/drawable/ic_launcher_foreground.xml` — the letters **LN** in
  white, four straight-edged paths (the L in one, the N as left stem, diagonal,
  right stem), inside the adaptive icon's safe zone: the glyph box is
  x 30–78, y 38–70 of 108, whose farthest corner is 29 from the centre against
  the 33 the safe circle allows.
- `app/src/main/res/mipmap-anydpi/ic_launcher.xml` — the adaptive icon, with a
  `monochrome` layer pointing at the same foreground so themed icons work.

No PNG fallback, and none needed: the floor is `minSdk 31` and adaptive icons
arrived at 26. **No bitmap of any kind is left in the app's or the library's
resources.** `images/readrops_logo.png` and the three store badges are still in
`images/`, referenced only from `README.md`; they are ticket 06's to delete and
nothing in the app reads them.

`app/src/main/res/values/colors.xml` follows the icon. `primary` and `splash`
are used by nothing but the splash theme — `primary` is the circle drawn behind
`ic_launcher_foreground`, `splash` the window behind it — so leaving them on
upstream's blue would have framed the new mark in the old palette. They are
`#FF14213D` (the launcher background) and `#FFE8ECF4` now.

### The `beta` build type

Deleted from all three modules, along with `app/src/beta/res/values/strings.xml`.
It was a debug-signed release with a `.beta` applicationIdSuffix, for handing out
pre-releases; LeNews publishes GitHub releases and has no second audience. The
only thing that branched on it was `configureEach`'s
`name == "debug" || name == "beta"`, which decides whether the FreshRSS debug
credentials are sourced from `local.properties`; it reads `name == "debug"` now.
The Google guard's comment in the root build file said "this project already has
a third build type"; it says upstream had one, and the guard still collects
whatever the variant API hands it rather than a hard-coded list.

`debug` keeps `applicationIdSuffix = ".debug"`, as the ticket recommends: the
store Readrops (`com.readrops.app`) and a LeNews debug build (`app.lenews.debug`)
sit on the phone at the same time.

`app/src/debug/res/values/strings.xml` went too. It held one string, `app_name`,
overriding the label to `ReadropsDebug`. With `beta` gone, `debug` was the only
variant renaming the app, and the label the ticket asks for on the debug APK is
`LeNews` exactly. Nothing is lost: the two apps on the phone are called
*Readrops* and *LeNews*, which is already the distinction the suffix existed to
make visible.

### Version

`versionCode 1`, `versionName "0.1.0"`, down from upstream's 22 / 2.1.1. Nothing
upgrades from the Readrops package, so the old numbers carried no obligation.
The account screen shows `v0.1.0`.

### Copyright

Untouched, deliberately. No GPL header was edited, no upstream author name was
changed, `LICENSE` is byte-identical. Renaming the app does not change who wrote
the code. (There are in fact no per-file GPL headers in the inherited Kotlin
sources; the fork-authored gate scripts carry the fork's own header, and ticket
08 owns the attribution rules.)

### Every remaining `grep -ri readrops` hit, grouped

33 files. None is a leftover of the rename; every one is either deliberate prose,
a record of the past, or ticket 06's to delete.

**1 — Upstream URLs in shipped resources and code. Ticket 06's.** These are the
contact, funding and store surfaces the next ticket deletes or retargets; this
ticket deliberately did not half-scrub them.
- `app/src/main/res/values/strings.xml` ×5 — `app_url`, `changelog_url`,
  `app_changelog_url`, `app_issues_url` (all `github.com/readrops/Readrops`) and
  `paypal_url`.
- `app/src/main/java/app/lenews/util/CrashActivity.kt:151` — the "report this
  crash" link, `github.com/readrops/Readrops/issues/new`.
- `.github/FUNDING.yml`, `README.md` ×8, `fastlane/metadata/…` ×3.

**2 — Orphaned translations, waiting on the translations product call.** Nine
hits in five locale files (`values-es`, `values-fr`, `values-pt-rBR`,
`values-zh-rCN`, `values-zh-rTW`): the `greader_warning` and `fever_warning`
strings, whose English originals ticket 04 deleted, each ending "report it to the
Readrops GitHub repository". They are dead resources — already `ExtraTranslation`
in the lint baseline — and the one edit that removes them is the same edit as
deciding which locales LeNews keeps. Left alone, as ticket 04 left them and as
this ticket's "leave other strings alone" says.
The eight locales that *did* translate the crash message were changed: the app
name inside them is LeNews now, the surrounding grammar untouched
(`values-de`, `-es`, `-fr`, `-nl`, `-pt-rBR`, `-ta`, `-zh-rCN`, `-zh-rTW`).
`app_name` itself is `translatable="false"` and appears in no locale file, so
there was nothing to change there — the ticket's "if they translate it" resolves
to no.

**3 — The lint baseline's echo of groups 1 and 2.** `app/lint-baseline.xml` ×12:
eleven `errorLine1` snippets quoting the orphaned translations, one quoting
`app_changelog_url`. They disappear the moment those two groups do.

**4 — Deliberate fork-origin prose.** Sentences that are *about* the fork and
would be wrong without the word.
- `scripts/check-no-personal-email.sh:54` — "Upstream Readrops' own commits carry
  the upstream author's address", explaining the historical-scan exemption.
- `app/build.gradle.kts` ×2 — the two comments this ticket wrote, saying that
  nothing upgrades from the Readrops package and that the `.debug` suffix is what
  lets Readrops and LeNews coexist.
- `.claude/agents/implementer.md`, `.claude/agents/implementer-high.md` ×2 each —
  the agent briefs, which describe the fork's origin and the access boundary.

**5 — The record of the past.** Not to be rewritten.
- `CHANGELOG.md` ×1 (upstream's release history; ticket 08 puts a heading on it).
- `code-review-02-09-2026.md` ×58 (the review that started this map, quoting
  file paths as they were).
- `docs/research/freshrss-greader-api.md` ×3, `docs/research/upstream-since-fork.md` ×33.
- The tracker, `.scratch/appropriate-and-fix/` ×33 across 11 files, including
  this ticket.

### The badging output

`$ANDROID_HOME/build-tools/35.0.1/aapt dump badging app/build/outputs/apk/debug/app-debug.apk`:

```
package: name='app.lenews.debug' versionCode='1' versionName='0.1.0' platformBuildVersionName='15' platformBuildVersionCode='35' compileSdkVersion='35' compileSdkVersionCodename='15'
sdkVersion:'31'
targetSdkVersion:'35'
application-label:'LeNews'
application-icon-160:'res/mipmap-anydpi-v21/ic_launcher.xml'
launchable-activity: name='app.lenews.MainActivity'  label='' icon=''
```

All 86 localised labels read `LeNews` too.

### On the emulator

`bench-pixel6-aosp`, `emulator-5554`. The three stale packages from the old
identity — `com.readrops.app.debug`, `com.readrops.app.debug.test` and
`com.readrops.db.test` — were uninstalled before the gate ran, so G7's install
was the first `app.lenews.*` on the device.

The debug APK was then installed by hand and launched.
`build/lenews-account-screen.png` shows the account screen: the new LN mark
(white letters on the navy rounded square), the title **LeNews**, one card
"Choose an account" with the single FreshRSS row, and **v0.1.0** at the bottom
clear of the navigation bar. Uninstalled afterwards, along with the two test
packages G7 leaves; `pm list packages` matches neither `readrops` nor `lenews`
now.

### The gate

Green end to end on the already-running emulator: G0 preflight, G1 email guard,
G2 lint ("no new issues", 362 errors and 67 warnings filtered by the baseline, on
both variants), G3 unit tests, G4 Google guard, G5 debug APK, G6 release APK
(R8 included), G7 instrumented tests. G7 found the emulator already up, used it
and left it running, as designed.

### What was pending here, and is done now (2026-09-06)

All three items this ticket left to the user or the orchestrator have happened.
They were checked from the renamed working directory, not assumed.

- **The GitHub repository rename and the `origin` URL.**
  `gh repo view jmnicolas90/LeNews` answers `nameWithOwner jmnicolas90/LeNews`
  with `defaultBranchRef main`, and `git remote -v` reads
  `https://github.com/jmnicolas90/LeNews.git` on fetch and push. So the 403 that
  ticket 03 recorded is gone: the token reaches this repository now.
- **`main` is pushed and CI has run.** `git ls-remote --heads origin` returns
  one head, `refs/heads/main` at `ef10d6da`, which is this checkout's `main` —
  the remote `develop` is gone as well, so nothing is left of upstream's
  git-flow split on either side. One CI run exists, `34053906941`, `push` on
  `main`, its single `Gate` job **success** in 14 min 57 s. That is the first
  time `.github/workflows/ci.yml` has ever run; ticket 03's "CI has not run"
  no longer holds.
- **The three in-app GitHub URLs resolve.** `app_url`, `changelog_url` and
  `app_issues_url` — and the crash screen's report button, which uses the third
  — all answer **200** now. Ticket 06 recorded them as 404 pending this rename;
  that note is updated in its own file.
- **The working directory rename**, `/home/skynet/dev/Readrops` →
  `/home/skynet/dev/LeNews`, done by the user. What it moved, and what had to be
  done about it:
  - **The agent memory path moved and the memory did not follow it.**
    `~/.claude/projects/-home-skynet-dev-LeNews/` was created empty by the first
    session in the renamed directory, while the five memory files and their
    `MEMORY.md` index stayed behind under `-home-skynet-dev-Readrops/`. They were
    copied across; the old directory was left in place, since it also holds the
    session transcripts of the two unattended runs. This is exactly the failure
    this ticket predicted, so: **after a directory rename, copy
    `memory/` across before anything else**, or the next session starts with no
    index and re-derives what is already written down.
  - **No worktree had to be re-pointed.** `.claude/worktrees/` was empty at the
    time of the move and `git worktree list` names only the main checkout, so the
    absolute paths in a worktree's `.git` file were never a problem here.
  - **Nothing in the tree holds the old path.** `.gradle/` and `.kotlin/` carry
    no `dev/Readrops` string; only regenerated AGP intermediates under
    `app/build/` do, and they are gitignored and rewritten on the next build.
    `local.properties` carries no `sdk.dir` by design, so the SDK lookup stays on
    `$ANDROID_HOME` and never pointed inside the renamed directory. The SDK, the
    JDK and the AVDs all live outside it.
  - **One consequence worth knowing, not a defect.** `settings.gradle.kts` sets
    no `rootProject.name`, so Gradle takes the root project's name from the
    directory: it was `Readrops` and is `LeNews` now. Nothing observable depends
    on it — the modules are `:api`, `:db`, `:app` and no artifact carries the
    root name — but it does mean a checkout in a differently named directory
    renames the root project again.

**The gate is green G0 to G7 from the renamed directory**, on `ef10d6da` with a
clean working tree: `scripts/check.sh` end to end, G7 on `bench-pixel6-aosp`
started outside the agent sandbox and found on `emulator-5554`. Lint is as
documented — one warning, the baseline filtering the rest, and the ten baseline
entries that no longer match anything still reported as fixed findings.

### Still pending, elsewhere

- The **CI half of the play-services red proof**, ticket 03's: push a throwaway
  branch carrying a `play-services-base` dependency and watch G4 go red on the
  runner. It is unblocked now that the token reaches the repository, but it
  publishes a branch, so it is the user's call to make; it belongs to ticket 03
  and is recorded there.
