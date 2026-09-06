# LeNews — agent context

A FreshRSS client for Android (Kotlin, Jetpack Compose, Room, WorkManager,
OkHttp/Retrofit, Koin), in three Gradle modules: `api` speaks the Google Reader
API that FreshRSS exposes, `db` holds the Room database and its queries, `app`
is the UI and the sync worker. One account, one service, and a lot of
articles — a few hundred a day is the shape it is built for, so anything that
gets slower as articles accumulate, stores an article twice, or loses the way
back to an article that was swiped away is the most serious class of bug in
this repo. "One account" is the **intended scope, not what the tree does
today**: upstream's multi-account screens are all still here — `AccountTab` has
an add-account button and an "Other accounts" switcher, and
`AccountCredentialsScreenModel.login()` inserts a second row — because the
account is threaded through the schema. Ticket 13 collapses it, after ticket 12
decides the article store. Do not write code that assumes one account until
then, and do not write a document that says there is one.

**Hard fork** of `readrops/Readrops` at commit `9ebbe038` (2025-07-20, its
`develop` branch: v2.1.1 plus an unfinished tag feature), GPL-3.0. Upstream is
never merged again, and there is nothing to merge: ticket 10 found upstream
dormant since that very commit — no release after v2.1.1, `develop` still
pointing at the fork point. So there are no cherry-picks to keep up with. What
upstream still offers is its **open issue tracker**, which is a list of bugs to
check LeNews against, not a source of patches; the ones that applied are folded
into tickets 12, 15, 16, 17 and 18. Keep the GPL attribution in `LICENSE`; it
is a legal obligation, not a leftover.

**The rename is done** (ticket 05). `applicationId` and the app module's
namespace are both `app.lenews`, the libraries are `app.lenews.db` and
`app.lenews.api`, the source package is `app.lenews.*`, the launcher icon is
this fork's own and the app on screen is "LeNews", v0.1.0. Nothing under
`app/src/` says Readrops any more, and no *inherited* file carries a copyright
header — upstream shipped none, so the attribution the GPL asks for lives in
`LICENSE`, in `README.md` and in the header rule `CONTRIBUTING.md` states
(ticket 08). What still says Readrops is deliberate and stays: the
*Readrops history* half of `CHANGELOG.md`, which is upstream's release history
kept unedited below LeNews's own; `code-review-02-09-2026.md` at the root, which
reviewed upstream's code; `docs/research/`, which reports on upstream; the fork
paragraph of `README.md`; and the historical record under `.scratch/`.

**The public documents are written** (ticket 08). `README.md` describes LeNews
and only LeNews, including what is *not* done: the three pain points are still
there. `CONTRIBUTING.md` holds the rule for copyright headers —
`Copyright (C) 2026 Jean-Michel Nicolas`, name only, on files this fork creates,
in the comment syntax of their language, *added* under upstream's header and
never substituted for it, and **not** on Markdown documentation, which says in
its own prose who wrote it. In this tree that means five `scripts/*.sh`,
`.github/workflows/ci.yml`, the two fork-drawn launcher drawables,
`app/src/test/java/app/lenews/util/accounterror/GReaderErrorTest.kt` and
`db/src/androidTest/java/app/lenews/db/benchmark/TimelineSlownessBenchmarkTest.kt`
carry the header, `LICENSE` is byte-identical to upstream's, and nothing else
does.
`CONTRIBUTING.md` lists the six non-Markdown fork-created files that
deliberately carry no header and why — JSON has no comments, the lint baseline is regenerated, and
`util/components/LoadingScreen.kt` is upstream's own code moved into a file of
its own when `RefreshScreen.kt` was deleted, so a fork line on it would claim
someone else's work. **Adding a fork-created source file means adding the
header in the same commit.**

## Hard constraints — do not break these

- **GrapheneOS / AOSP compatible, always.** No Google Play Services, GMS or
  Firebase dependency may ever enter the graph, transitively included. It is
  enforced, not documented: gate G4 below runs `checkNoGoogleDependencies`,
  registered per module in the root `build.gradle.kts`, on **all three
  modules** — `api` and `db` carry their own graphs and a library is exactly
  where a transitive Play Services dependency would arrive unnoticed. It walks
  the full runtime classpath of every variant, collected from the variant API
  rather than from a hard-coded list of build types, and fails on the groups
  `com.google.android.gms` and `com.google.firebase` or on any module whose
  name contains `play-services`. An unresolvable edge is reported too, and a
  module where the variant API hands back nothing to inspect is a failure
  rather than a pass. The match is on coordinates, never on the string
  "google", because three `com.google.*` dependencies are deliberately allowed
  and are in the graph today: `com.google.android.material` (AndroidX Material
  Components), `com.google.accompanist` (Compose helpers) and
  `com.google.devtools.ksp` (the annotation processor). None of them has
  anything to do with Play Services. The guard is not only a gate stage: the
  root `build.gradle.kts` makes `check` **and every task whose name starts with
  `assemble`, `package`, `install` or `bundle`** depend on it, so a bare
  `./gradlew assembleDebug` goes red, and so do `packageDebug`, `installDebug`
  and `bundleRelease`, none of which depends on an `assemble` task — that hole
  was open until the global review round of 2026-09-06. No APK or app bundle can
  come out of this tree with such a dependency in it, and none can be installed
  on a device. That is what lets `README.md` and `CHANGELOG.md` state it as a
  property of the build rather than of one script. Note the consequence: because the guard walks
  *every* variant, `assembleDebug` also resolves the release runtime classpath,
  so a release-only offender fails a debug build.
- **`minSdk 31`, `targetSdk 35`, `compileSdk 35`**, set once in the root
  `build.gradle.kts` for all three modules. The floor is 31 since ticket 02,
  which also deleted every compat shim the raise made dead (notification
  channel guards, the adaptive-icon painter branch, the battery-optimization
  guard, core library desugaring, the legacy storage permission). Raising to
  **`targetSdk 36` has not been decided** — it is on the map's *Not yet
  specified* list, waiting for a ticket of its own now that the gate exists to
  catch what it breaks.
- **No personal email address anywhere** — not in the tree, not in commit
  metadata, not in published artifacts. Commits use the GitHub no-reply address
  configured repo-locally in `.git/config` (ticket 01); the global git config
  is untouched. It is enforced (ticket 03): gate G1 runs
  `scripts/check-no-personal-email.sh`, which makes five checks and runs all
  five rather than stopping at the first hit — every file in the working tree,
  **tracked and untracked alike** (git's own ignore rules still apply), because
  an untracked file is one `git add` from a commit; every tracked file **as
  staged in the index**, because `git add -p` can stage a hunk the file on disk
  no longer has; the author and committer identity the **next** commit would
  carry, name as well as address; every commit **this fork authored**, meaning
  author and committer, the whole message including trailers, and the commit's
  own tree, because a clone receives every historical blob and an address
  committed then redacted is still published; and the **names of those files**,
  tracked, untracked and historical, since a path is published as loudly as a
  line. It reports the commit, file and line and never prints the address, and
  any diagnostic git itself prints while scanning fails the check rather than
  passing for a clean tree — redacted first, since a diagnostic names files.
  Allowed: `LICENSE` (the GPL text carries the Free Software Foundation's
  address) and no-reply addresses as the script defines them (local part exactly
  `noreply`, or the domain `users.noreply.github.com` — not the word appearing
  inside an otherwise deliverable address, and the whole token is matched, so an
  address that merely *ends* in such a local part is reported).
  **Kotlin's qualified `this` is no longer exempt**: the exemption also accepted
  an address written as a string literal in a `.kt` file, so it was dropped in
  the global review round of 2026-09-06 and the two call sites that had the
  shape of an address were written differently (a renamed lambda parameter in
  `ItemScreenModel`, a renamed companion property in `ShareIntentTextRenderer`).
  Writing a qualified `this` followed by a dot and a member in a `.kt` file
  turns G1 red; use another receiver name or a local `val`. Upstream's own commits are excluded by **commit range**,
  never by naming an address here: the fork point `9ebbe038` is the boundary,
  and one boundary is enough because upstream's `master` is an ancestor of it.
  Two narrow relaxations apply to historical **trees only** — the addresses the
  fork point tree already publishes (read out of `9ebbe038` at run time, never
  written into the script) and one exact blob of
  `docs/research/upstream-since-fork.md`. Neither touches the working tree, the
  index, an identity or a message. Full history is required: a shallow clone or
  a clone missing the fork point is a failure, not a pass, which is why
  `.github/workflows/ci.yml` checks out with `fetch-depth: 0`.
- **Never commit a red gate.**
- **The access boundary.** The user's personal FreshRSS account and the
  Readrops database on their phone are **never** accessed, read, copied or
  synced against, by any ticket, for any reason. Testing against a real server
  uses a separate debug account on the user's own FreshRSS at `https://rss.lan`
  (LAN and VPN only), whose credentials live in the gitignored
  `local.properties` — ticket 22 sets that up and is still open. The
  instrumented gate stage needs no network at all: it uses MockWebServer on the
  emulator. A real phone is often attached to this machine over adb and is out
  of bounds; G7 pins the serial so nothing can reach it.

## The gate

One command, from the repo root, and it must be green before every commit:

```
scripts/check.sh
```

Eight stages, fail-fast, in this order. The individual invocations:

| Stage | Command |
|---|---|
| G0 preflight | not Gradle — `scripts/check-preflight.sh` |
| G1 email guard | not Gradle — `scripts/check-no-personal-email.sh` |
| G2 lint | `./gradlew -q :app:lintDebug :app:lintRelease :api:lintDebug :api:lintRelease :db:lintDebug :db:lintRelease` |
| G3 unit tests | `./gradlew -q :app:testDebugUnitTest :api:testDebugUnitTest :db:testDebugUnitTest` |
| G4 Google guard | `./gradlew -q :app:checkNoGoogleDependencies :api:checkNoGoogleDependencies :db:checkNoGoogleDependencies` |
| G5 debug APK | `./gradlew -q :app:assembleDebug` |
| G6 release APK | `./gradlew -q :app:assembleRelease` |
| G7 instrumented tests | `ANDROID_SERIAL=emulator-5554 ./gradlew -q :db:connectedDebugAndroidTest :app:connectedDebugAndroidTest` — what G7 runs *after* its own boot wait and AVD-name check; see below before running it by hand |

Every Gradle stage names all three modules explicitly rather than trusting an
unqualified task name to reach them all: it costs a line and it means a red
stage says which module failed.

G4 stays a stage of its own even though G5 and G6 now pull the same guard in
through their `assemble` dependency: a stage that names the offence is worth the
seconds, and it fails before two APK builds instead of during one.

`.github/workflows/ci.yml` runs the same stages in the same order on every
branch push and pull request. (It runs G1 before G0, because on a runner the
SDK is installed by a setup step rather than found; nothing else differs, and
its emulator is API 35 where the local AVD is API 36.) **If the two ever drift,
one of them is lying about whether the tree is good — fix the drift, don't pick
a winner.**

Notes that save time:

- **G0** wants `platforms;android-35` *exactly*. `compileSdk` is 35, and
  `android-36` and `android-36.1` sit in the same directory and do **not**
  substitute, so the raw Gradle error reads "platform missing" while
  directories that look like platforms are right there. G0 also checks that the
  `bench-pixel6-aosp` AVD exists — a Pixel 6 on a pure AOSP system image, API
  36 x86_64, no Play Services — and skips that half when `SKIP_INSTRUMENTED` is
  set. Both failures print the exact `sdkmanager` / `avdmanager` line to fix
  them.
- **G7** uses whatever is already on `emulator-5554` and leaves it running, or
  boots `bench-pixel6-aosp` headless itself and shuts down **only** what it
  started — and it stops it by signalling the process it launched (SIGTERM,
  bounded wait, SIGKILL), never by sending `emu kill` to the port, which is
  addressed to whoever holds the port rather than to whoever this stage
  started. If the emulator it launched has died by then, it kills nothing and
  fails the stage, because it can no longer say what the tests ran against. Either way it asks the emulator console which AVD it is before
  installing anything, and refuses (without killing) any other. `-gpu host` is
  not a preference: with the software renderer this AVD dies silently at
  "performing a full startup" on this machine and the only symptom is a boot
  that never completes. `-feature -GnssGrpcV1` switches off a service that
  otherwise just logs errors. It waits for `sys.boot_completed`, not merely for
  adb to see the device, and watches the emulator process meanwhile so a boot
  that failed says why instead of timing out. `ANDROID_SERIAL` rather than a
  `--device` flag, because that is the one thing every tool in the chain
  honours — without it a `connectedAndroidTest` on this machine would install
  a debug build on the phone that is usually plugged in.
- **The G7 line in the table is not a command to paste as it stands.** It is
  what G7 runs *after* two steps of its own: waiting for `sys.boot_completed`
  on `emulator-5554`, and asking that emulator's console which AVD is behind
  the serial. Run by hand without them, `ANDROID_SERIAL=emulator-5554` will
  install a debug build and run tests that wipe databases on whatever happens
  to sit on that serial. So a hand run first checks that

  ```
  adb -s emulator-5554 emu avd name
  ```

  answers `bench-pixel6-aosp` — or, simpler, just runs `scripts/check.sh`,
  which makes that check itself and refuses any other device without killing
  it. Either way the test APKs stay installed on the device afterwards:
  `gradle.properties` (line 15) sets
  `android.injected.androidTest.leaveApksInstalledAfterRun=true`, so AGP skips
  the uninstall it would otherwise do after the run. Finding `app.lenews` and
  its test package still on the emulator after a green gate is expected, not a
  leftover from a failure.
- **On this machine the emulator cannot be started from inside an agent's Bash
  sandbox.** Either run the whole gate outside the sandbox, or start the
  emulator outside it first and let G7 find it on the serial:

  ```
  "$ANDROID_HOME/emulator/emulator" -avd bench-pixel6-aosp -port 5554 \
      -no-window -no-audio -no-snapshot -gpu host -feature -GnssGrpcV1
  ```

- **`SKIP_INSTRUMENTED=1 scripts/check.sh`** leaves G7 out and says so. That is
  for quick iterations only; the default run includes it and CI always runs it.
- **The first run in a fresh worktree is slow** (several minutes before G7 even
  starts); later runs are much faster. A full run including G7 takes a few
  minutes more.
- **Lint fails on errors only**; warnings print and stop nothing. `abortOnError`
  is set once, in the root `build.gradle.kts`, for all three modules.
  `app/lint-baseline.xml` holds what was already red on the day the gate was
  built: **411 entries — 347 errors and 64 warnings**, and every error is
  translation debt (199 `MissingTranslation`, 135 `ExtraTranslation`, 47
  `UnusedResources`, 10 `MissingDefaultResource`, 3 `ImpliedQuantity`), waiting
  on the locales product call nobody has made. `api` and `db` have no errors
  and no baseline, so their warnings still print. **A baseline is a list of
  findings to clear, not a rule switched off**: a new error of any of those
  kinds still fails the gate. Known noise: `lintVitalRelease`, which
  `assembleRelease` runs, prints that the baseline entries were not found "using
  a different target/variant" — AGP has one baseline per module, not per
  variant, and it finds no errors of its own.
- **Not every script under `scripts/` is a gate.** `scripts/codex-review.sh`
  launches an adversarial Codex review of a worktree's branch diff and reports
  its findings; it is a helper for the review step of the ticket loop, nothing
  in `check.sh` calls it, and its verdict is advice, not a pass mark.
  `scripts/android-sdk-path.sh` just answers "where is the SDK" for the other
  two, in the order AGP 8.10 itself uses (its `SdkLocator`): `sdk.dir` in
  `local.properties` first, then `ANDROID_HOME`, then the deprecated
  `ANDROID_SDK_ROOT`. **The script and AGP have to agree**: otherwise the gate
  would look for `platforms;android-35` and the AVD in one SDK while Gradle
  built against another, and G0 would pass or fail about a directory nothing
  uses. That is the whole reason this lookup lives in one file. On this machine
  there is no `local.properties`, so both fall through to `ANDROID_HOME`
  (`/home/skynet/dev/android/sdk`); write a `local.properties` with a `sdk.dir`
  and it wins, for the script exactly as for Gradle.

## Working conventions

- **Plain language, no invented jargon.** Name things by what they do. No
  codenames for concepts, components, plans or workflows. This applies to code,
  comments, docs, tickets, commit messages and agent reports alike.
- **A ticket must fit one fresh agent at low context**: one narrow change, a
  handful of files, one sitting. If it looks bigger, split it before starting.
- **One long-lived branch, `main`.** Upstream's git-flow `develop`/`master`
  split is retired. Code changes happen in short-lived worktrees on ticket
  branches under `.claude/worktrees/` (gitignored), one ticket each, merged
  back into `main` with `--no-ff` once the gate is green — never committed
  directly on `main`. Delete the worktree and its branch after the merge.
  Releases are tags.
- **Never commit red gates**, and don't leave finished green work uncommitted
  at the end of a turn.
- **The commit trailer names the model that actually wrote the code**, at its
  own vendor's no-reply address — `Co-authored-by: Fable 5.1
  <noreply@anthropic.com>` for this one. A non-Anthropic model uses its own name
  and its own vendor's address, e.g.
  `Co-authored-by: Codex GPT-5 <noreply@openai.com>`. It never signs as an
  Anthropic model. An honest trailer is the only thing that keeps `git log`
  usable as a record of who wrote what.
- **Bookkeeping is part of the ticket, in the same commit**: set
  `Status: resolved` on the ticket file, write an `## Answer (date)` section
  saying what was actually done, and append one line to *Decisions so far* in
  `.scratch/appropriate-and-fix/map.md` in the format of the lines already
  there.

## Domain vocabulary

`CONTEXT.md` at the root is the glossary — article, feed, folder, unread, read,
becoming read, history, horizon, starred, sync, mirror, duplicate — with the
words to avoid for each. Read it before touching articles or their state; it is
short and this file does not repeat it.

`docs/research/freshrss-greader-api.md` is what the server actually guarantees,
every claim a permalink into FreshRSS source. The facts a session trips over:

- **An article's identity is a 64-bit integer**, FreshRSS's `_entry.id`, unique
  by primary key and stable across content updates. It comes back **hex** from
  `stream/contents` (as `tag:google.com,2005:reader/item/<16 hex digits>`) and
  **decimal** from `stream/items/ids`; both forms are accepted on write.
  **What the code stores today is the long form.** `GReaderItemsAdapter` keeps
  the `id` string `stream/contents` sent, and `GReaderItemsIdsAdapter` converts
  the decimal that `stream/items/ids` sent back into that same
  `tag:google.com,2005:reader/item/<hex>` shape, so both paths put that string
  into `Item.remote_id` — and `ItemState`, which is where read and starred
  state actually lives (see below), joins on `remote_id` verbatim, as a string.
  `docs/research/freshrss-greader-api.md` (ticket 09) recommends the **decimal
  integer** as the safer key: it is what the server parses every input down to,
  and it is a number rather than a 48-character string repeated in two tables.
  Switching is not a one-line change — it touches the schema, both adapters and
  every stored row at once — so it belongs to ticket 12's article-store model,
  and until that lands the long form is what the tree uses. The rule in the
  meantime: **mixing the two forms breaks the read-state join.** An
  `Item.remote_id` in one form and an `ItemState.remote_id` in the other never
  match, and the article silently shows up with no read and no starred state.
  Fixtures, tests and any partial change must therefore keep one form
  throughout.
- **Re-delivery is normal, not a failure.** `ot` is inclusive and is compared
  to discovery time or last-modified time, not to the publication date
  (`id >= ot·10⁶` OR `lastModified >= ot`), so the boundary article comes back
  on every sync and any article edited upstream comes back once more. The sync
  must therefore **upsert by id, never insert**, and repeating a sync must
  change nothing.
- **No read timestamp exists server-side.** No API output carries when an
  article became read, so History is local only and a read observed at sync is
  stamped with the sync time. That is a settled consequence, not a limitation
  to work around.
- **Read state does not live in `Item.read` today.** The only remaining account
  type, `FRESHRSS`, has `useSeparateState = true` in
  `db/.../entities/account/AccountConfig.kt`, so read and starred state is read
  through the `ItemState` table (joined on `remote_id`) and local changes are
  queued in `ItemStateChange` for the next upload. Every query builder in `db`
  branches on that boolean. Do not assume `Item.read` means anything.

**Pending, so do not write code as if it were decided**: how the article store
is modelled at all, including whether tags survive the schema reset (ticket 12,
a grilling ticket); collapsing the account layer and the separate-state join
for a single account (ticket 13); the mirror-and-horizon retention rule
(ticket 15, below); what the history list looks like (ticket 16); and which of
the 14 inherited locales LeNews keeps, which is the one product call that
clears most of the lint baseline.

**The retention rule** is agreed in principle and not implemented (ticket 15).
It is two rules with one exception that covers both:

- **Mirror** — the phone holds what FreshRSS holds, no more: an article the
  server no longer returns is dropped locally, unless it is starred or still
  within the horizon.
- **Horizon** — thirty days, measured from when the article **became read**,
  not from when it was published or fetched. Past it a read article is no
  longer kept, whatever FreshRSS still has.
- **Starred articles survive both** — kept regardless of the horizon and
  regardless of what FreshRSS returns.

The one-line summary "the phone holds what FreshRSS holds, nothing read older
than 30 days" leaves the starred exception out and says nothing about what the
thirty days are counted from, which is why it is spelled out here. `CONTEXT.md`
defines Mirror, Horizon and Starred; ticket 15 implements them.

## Tickets and bookkeeping

Tickets live as markdown under `.scratch/appropriate-and-fix/` (committed, not
gitignored). `.scratch/appropriate-and-fix/map.md` is the map: the destination,
the hard constraints, what was settled while charting, what is *Not yet
specified*, what is *Out of scope*, and a *Decisions so far* log with one line
per resolved ticket. Read the map before starting anything; read the `Answer`
section of a ticket the map links rather than re-deriving it.

One file per ticket under `issues/`, with `Type:` / `Status:` / `Blocked by:`
near the top. Routing is per ticket, from the `Type:` line — do not re-enter
the map's skill for the whole map:

- `Type: task` → `/implement`. The decision was already made while charting.
- `Type: grilling` (12) → `/wayfinder`. Genuine fog; the answer does not exist
  yet. A grilling ticket resolves a decision; implementing it is a separate
  ticket.
- `Type: research` (09, 10, both resolved) → a `/research` background agent,
  findings committed as `docs/research/<slug>.md`.

Then the bookkeeping above, by hand, in the same commit.

## Environment

Android SDK at `/home/skynet/dev/android/sdk` (`$ANDROID_HOME`), needing the
`platforms;android-35` package and the `bench-pixel6-aosp` AVD. Gradle JDK is
Temurin 21 at `/home/skynet/dev/jdk/jdk-21.0.12+8` (`$JAVA_HOME`), matching the
`java-version` in CI. The host is Fedora with a French locale, so Gradle, git
and the emulator may answer in French.

`local.properties` is gitignored and holds this machine's answers: `sdk.dir`,
and the three keys the debug build reads to autofill the login screen —
`debug.freshrss.url`, `debug.freshrss.login`, `debug.freshrss.password`
(`app/build.gradle.kts` turns them into string resources for the debug build
type only; a release build gets empty values). Never paste those credentials
anywhere else.

The 02-09-2026 code review of the upstream code is at the repo root
(`code-review-02-09-2026.md`); every one of its findings is tracked as a ticket,
fixed or consciously ruled out of scope.
