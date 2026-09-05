# 03 — Build the executable quality gate, locally and in CI, and switch to a single trunk

Type: task
Status: resolved
Blocked by: 02

## Question

This is the safety net for an AI-developed app: what converts "the agent says it's fixed" into "the gate says it's fixed". Upstream has a CI workflow but it is not a gate: `actions/checkout@v2`, unpinned third-party actions, no `permissions`, no timeout, `./gradlew clean build` then an emulator run, and lint is globally non-blocking (`abortOnError = false`, review low finding). Copy Ding's shape, then add the one stage Ding did not have.

**Local gate — `scripts/check.sh`.** `set -euo pipefail`, fail-fast, one command. Follow Ding's hard-won detail: every command in a subshell needs an explicit `|| exit 1`. Stages, in order:

- G0 preflight — `$ANDROID_HOME/platforms/android-35` exists (the exact package, not `-35.x`); the `bench-pixel6-aosp` AVD exists.
- G1 email guard — `scripts/check-no-personal-email.sh`, ported from Ding; allow only `LICENSE`, no-reply addresses, and upstream attribution files.
- G2 lint — `lintDebug` and `lintRelease` on all three modules, **`abortOnError = true`**, fail on errors only; establish a baseline for the warnings.
- G3 unit tests — `testDebugUnitTest` on all three modules.
- G4 Google guard — `checkNoGoogleDependencies`, ported from Ding: walk every variant's runtime classpath, fail on groups `com.google.android.gms`, `com.google.firebase` or a module name containing `play-services`. Match on coordinates, never on the string "google": `com.google.android.material`, `com.google.accompanist` and `com.google.devtools.ksp` are legitimately present.
- G5 debug APK, G6 release APK — release is separate because R8 optimisation and obfuscation only run there.
- **G7 instrumented tests** — boot `bench-pixel6-aosp` headless if not running, `connectedDebugAndroidTest` on `db` and `app`, shut it down if this stage started it. This is the stage that tests every database change this map makes. Make it skippable with an env flag for quick iterations, but the default run includes it and CI always runs it.

**CI — `.github/workflows/ci.yml`.** Replace `android.yml`. Push and pull-request triggers; every action pinned to a full commit SHA; `permissions: contents: read`; `timeout-minutes`; a branch concurrency group with `cancel-in-progress`. Temurin 21, `gradle/actions/setup-gradle`. Same stages in the same order; G7 on `ReactiveCircus/android-emulator-runner` with an AOSP (`default`, not `google_apis`) system image. Drop codecov and its token; nobody reads it. The two must never drift: if CI and `check.sh` disagree about whether the tree is good, fix the drift.

**Single trunk.** Upstream's `develop`/`master` split is retired: create `main` from the current `develop`, make it the default branch on GitHub, delete the local `develop`, and leave the upstream feature branches alone (they are history). Every later ticket works in a worktree under `.claude/worktrees/` (add it to `.gitignore`) on a ticket branch merged into `main` with `--no-ff`.

Lint currently passes with `abortOnError = false`; find out what turning it on fails, fix or baseline, and record the count.

**Done when** `scripts/check.sh` exits 0 locally through G7, CI is green on `main`, a deliberately added `play-services-base` dependency turns both red at G4, and `git branch` shows `main` and no `develop`.


## Answer (2026-09-05)

`scripts/check.sh` is the one command. Eight stages, fail-fast, green end to end
on this machine. `.github/workflows/ci.yml` runs the same eight in the same
order and replaces `android.yml`, which is deleted.

### The stages

| Stage | What it proves | Command |
| --- | --- | --- |
| G0 | the SDK platform and the bench AVD are installed | `scripts/check-preflight.sh` |
| G1 | no email address in the tree, the index, the next commit's identity or any fork commit | `scripts/check-no-personal-email.sh` |
| G2 | lint finds no error, on both variants of all three modules | `./gradlew -q :app:lintDebug :app:lintRelease :api:lintDebug :api:lintRelease :db:lintDebug :db:lintRelease` |
| G3 | the unit tests pass | `./gradlew -q :app:testDebugUnitTest :api:testDebugUnitTest :db:testDebugUnitTest` |
| G4 | no Play Services or Firebase on any variant runtime classpath | `./gradlew -q :app:checkNoGoogleDependencies :api:checkNoGoogleDependencies :db:checkNoGoogleDependencies` |
| G5 | the debug APK builds | `./gradlew -q :app:assembleDebug` |
| G6 | the release APK builds, R8 passes included | `./gradlew -q :app:assembleRelease` |
| G7 | the instrumented tests pass on a device | `ANDROID_SERIAL=emulator-5554 ./gradlew -q :db:connectedDebugAndroidTest :app:connectedDebugAndroidTest` |

`SKIP_INSTRUMENTED=1 scripts/check.sh` leaves G7 out and prints that it did;
G0 then skips its AVD half too, since a run that will not boot the emulator has
no business demanding it exists. Verified: 3.6 s, all other stages green.

Three helper scripts rather than one, so no check has two homes:
`scripts/android-sdk-path.sh` answers "where is the SDK" for the preflight and
for G7's adb; `scripts/check-preflight.sh` is G0; `scripts/check-no-personal-email.sh`
is G1 and is the same file CI runs. `scripts/codex-review.sh` is ported from
Ding unchanged; it is a helper for the review step of the ticket loop, not a
gate, and nothing in `check.sh` calls it.

### G2 — what turning lint on found

`build.gradle.kts` had `lintOptions.isAbortOnError = false` for every module and
each module's own build file turned it off again. It is on now in one place, set
through the typed extension (AGP has deprecated `lintOptions`), and the three
per-module switches are gone.

Counts, on both variants, identical debug and release:

- **app**: 228 errors, 47 warnings. 224 `MissingTranslation`, 3
  `ImpliedQuantity`, 1 `FlowOperatorInvokedInComposition`.
- **api**: 0 errors, 37 warnings (29 `GradleDependency`, 6 `CheckResult`,
  2 `AndroidGradlePluginVersion`).
- **db**: 0 errors, 7 warnings (4 `VectorPath`, 3 `VectorRaster`).

**1 error fixed.** `FlowOperatorInvokedInComposition` in `MainActivity`: the
theme `Flow` was built with `map` inside composition, so `collectAsState` was
handed a new `Flow` on every recomposition and restarted the collection each
time. Wrapped in `remember`.

**227 errors and 47 warnings baselined**, `app/lint-baseline.xml`, 274 entries,
app only — api and db have no errors and need no baseline, so their warnings
still print. Everything baselined is translation debt: which of upstream's 14
Weblate locales LeNews keeps is a product call nobody has made (see
*Not yet specified* in the map), and both `MissingTranslation` and
`ImpliedQuantity` wait on it. No dependency was bumped to silence
`GradleDependency`, which stays a warning.

Remaining warnings, none of them failing the gate: 47 in app (in the baseline,
so the report reads "no new issues"), 37 in api, 7 in db.

One piece of noise stays: `lintVitalRelease`, which `assembleRelease` runs,
prints that the 274 baseline entries were not found "using a different
target/variant". It finds no errors of its own, so G6 is green; AGP has one
baseline per module, not one per variant.

### G1 — what the email guard needed

Ten fixture hits, three false positives, one real leak.

- **Fixtures, seven files, edited rather than allowlisted.** `help@`… in three
  RSS1 fixtures (now `Slashdot help desk`), `email@`… in the Nextcloud News
  `user.xml` (now `no address`), two `mailto:` links plus their visible text in
  `json_feed.json` (now the vendor's support URL), and a `mailto:` in the two
  Hacker News HTML fixtures (now the contact URL). No test asserts on any of
  those values; api's unit tests pass unchanged. Ticket 04 deletes all seven
  files with the services they belong to, so nothing here is load-bearing.
- **Three false positives in Kotlin source.** `ShareIntentTextRenderer.kt`,
  `FeverFaviconFetcher.kt` and `ItemScreenModel.kt` were listed as holding
  addresses. They do not: each is a qualified `this` — a label followed by a
  member — which has exactly the shape of an address and is ordinary Kotlin.
  Nothing was edited and nothing was allowlisted by path. The guard has one
  narrow test for it: the local part is exactly the keyword `this` **and** the
  file is a `.kt`. Both conditions, so a real address cannot pass by sitting in
  a Kotlin file and no other file type can pass at all. (The review round below
  adds a third condition — what follows the at sign must be a class-shaped label
  and a member — because those two alone still exempted a string literal.) There
  is no upstream author address in a GPL header anywhere in the tree.
- **One real address, in a fork-authored file.** `docs/research/upstream-since-fork.md`
  quoted, from a public upstream issue thread, the address upstream publishes as
  its contact. Removed from the working tree — the sentence says "the project's
  published contact address" and loses nothing. The two commits that carried it
  (the research commit and its merge) are already in this history and only a
  rewrite would unpublish them, so the historical-tree scan, and only that scan,
  exempts that one path. The working tree and the index are still checked, so
  the quote cannot come back. (The review round below narrows this to the exact
  blob, and counts five commits carrying it rather than two.)

The historical scan also exempts, by address value read at run time, the set the
fork point tree `9ebbe038` already publishes — the fixture addresses above, as
they were before this ticket edited them, which reach every clone through
upstream's own commits whatever this fork does. One boundary commit is enough
here, not Ding's two: upstream's `master` `dcd7a9f3` is an ancestor of the fork
point, so everything reachable from `9ebbe038` is upstream's. The fork's own
range is `HEAD ^9ebbe038`, eight commits today.

Allowlisted by path: `LICENSE` only, for the Free Software Foundation's address
in the GPL text. Proven both ways: a planted address in a new tracked file turns
G1 red naming the file and the line and never printing the address; removing it
turns it green.

### G3 — the known flake, diagnosed and fixed

`NextcloudNewsDataSourceTest.classicSyncTest` was not a MockWebServer timing
problem. `synchronize` launches the read-state and star-state calls
concurrently and MockWebServer answers each request on its own thread, so the
dispatcher's `setItemState++` was a non-atomic read-modify-write from four
threads and the test saw 3 where it expected 4. The `println` with the comment
"important, otherwise test fails and I don't know why" was hiding it: `System.out`
is synchronized, so printing serialised the dispatcher threads and published
the writes between them.

Fixed, not ignored: the counter is an `AtomicInteger` and the `println` is gone.
Reproduced first — with the plain counter and no `println`, 1 run in 15 failed
exactly as described. With the `AtomicInteger`, 10 runs in a row green.

### G4 — the red proof

`checkNoGoogleDependencies` is registered per module in the root
`build.gradle.kts`, one task each, collecting every variant's runtime
configuration from the variant API (so `beta` is guarded as well as debug and
release) and walking the whole resolved graph. It throws if it finds no
configuration to inspect, because a guard that checks nothing passes
everything. It matches on group `com.google.android.gms` or
`com.google.firebase`, or a module name containing `play-services` — never on
the string "google", so `com.google.android.material`, `com.google.accompanist`
and `com.google.devtools.ksp` stay. It is also wired into `check`.

Proof: `implementation("com.google.android.gms:play-services-base:18.5.0")` added
to app turned G4 red, and the message named the coordinate in every variant plus
the two dependencies it dragged in behind it:

```
    com.google.android.gms:play-services-base:18.5.0  (in :app betaRuntimeClasspath)
    com.google.android.gms:play-services-base:18.5.0  (in :app debugRuntimeClasspath)
    com.google.android.gms:play-services-base:18.5.0  (in :app releaseRuntimeClasspath)
    com.google.android.gms:play-services-basement:18.4.0  (in :app …)
    com.google.android.gms:play-services-tasks:18.2.0  (in :app …)
```

The dependency was then removed and G4 is green again.

### G6 — signing

None needed. No `signingConfig` is set on the release build type, so
`assembleRelease` produces `app-release-unsigned.apk`. The stage runs unchanged
here and on a runner with no secrets. Signing is a release-process problem, and
the map already lists it under *Not yet specified*.

### G7 — the instrumented stage

Boots `bench-pixel6-aosp` headless only if nothing is already serving on port
5554, waits for `sys.boot_completed`, runs `connectedDebugAndroidTest` on `db`
then `app` with `ANDROID_SERIAL=emulator-5554`, and kills the emulator only if
this stage started it.

**The AVD has to be started with `-gpu host` on this machine.** With the
software renderer it dies silently during "performing a full startup" and the
only symptom is a boot that never finishes, so the boot command in G7 is
`emulator -avd bench-pixel6-aosp -port 5554 -no-window -no-audio -no-snapshot
-gpu host -feature -GnssGrpcV1`. `ANDROID_SERIAL` is not optional either: this
machine usually has a real phone attached over adb, and an unpinned
`connectedAndroidTest` would install a debug build on whichever device adb lists
first. The user's phone is out of bounds.

Only the "already running" branch was exercised here — the emulator was up
throughout. The cold-boot branch is written and unexercised; the orchestrator
tests it. (The review round below rewrites this stage and exercises both
branches against a mock adb and a mock emulator.)

Four instrumented tests were red before this ticket and are green now. All four
came from one cause and neither of them is a flake:

- **The notification permission.** `Synchronizer` only counts a synced feed
  inside `if (notificationManager.areNotificationsEnabled())`, so with
  `POST_NOTIFICATIONS` ungranted the progress callback reported zero feeds and
  the three `SynchronizerTest.localAccount*` tests failed on `expected:<1> but
  was:<0>` — a failure that does not look like a permission problem at all.
  Upstream granted it with two `adb shell pm grant` lines in CI. It is granted
  by `ReadropsTestRule` now instead, so the tests pass wherever they are run,
  including by hand.
- **A missing notification channel.** `SyncWorkerTest.autoWorkerWithNotificationsTest`
  found no notification, because instrumented tests replace the Application with
  `TestApplication`, which never created the sync channel — and the system drops
  a notification posted on a channel that does not exist, with nothing but a log
  line. Channel creation moved out of `ReadropsApp` into one top-level function
  both applications call.

No `adb shell settings put global zen_mode 0` was needed.

### Timings

One whole run after `./gradlew clean`, on this machine, with the emulator
already up: **1 min 27 s**.

| Stage | Time |
| --- | --- |
| G0 preflight | under 1 s |
| G1 email guard | under 1 s |
| G2 lint | 25 s |
| G3 unit tests | 4 s |
| G4 Google guard | 1 s |
| G5 debug APK | 6 s |
| G6 release APK | 34 s |
| G7 instrumented tests | 17 s |

G7 is 20 tests in `db` and 32 in `app`, and add about two minutes to it when it
has to boot the emulator itself. `SKIP_INSTRUMENTED=1` on a warm tree: 3.6 s.

### What CI needs that this machine does not

- **The SDK packages.** `android-actions/setup-android` installs
  `platforms;android-35` and `build-tools;35.0.1`; the runner image ships an SDK
  but pins neither. G0 then runs with `SKIP_INSTRUMENTED=1`, so it checks the
  platform and not the bench AVD, which a runner does not have.
- **`fetch-depth: 0`.** G1 checks every fork commit and treats a shallow clone
  as a failure. G1 also skips its next-commit-identity check when
  `GITHUB_ACTIONS` is set, out loud: a runner has no identity and never commits.
- **The KVM udev rule**, or the emulator has no hardware acceleration.
- **A different emulator.** `ReactiveCircus/android-emulator-runner` with
  `api-level: 35`, `target: default` (AOSP, not `google_apis`), `arch: x86_64`,
  and the action's own default flags, which use the software renderer. `-gpu host`
  is a local necessity and a hosted runner has no GPU to hand it. That makes the
  device the one place the two gates differ: API 35 in CI, API 36 locally.
- **Nothing else.** No keystore, no secret, no codecov token — codecov and its
  token are gone with `android.yml`. Every action is pinned to a full commit
  SHA with its tag in a comment; `permissions: contents: read`;
  `timeout-minutes: 60`; a concurrency group per ref with `cancel-in-progress`;
  Temurin 21; `gradle/actions/setup-gradle`.

`codecov.yml` is left in place for ticket 06, and the `jacoco` wiring is
untouched — it did not get in the gate's way.

### Not done here

The single-trunk switch — create `main` from `develop`, make it the default
branch on GitHub, delete the local `develop` — is the orchestrator's, after this
branch merges. The ticket's two remaining "done when" conditions, **CI green on
`main`** and **the `play-services-base` red test in CI**, can only be checked
once something is pushed, and are recorded then.

### Review round (2026-09-05)

A Codex adversarial review of this ticket raised nine findings. Eight are fixed
in the second commit on this branch; the ninth is ruled out and recorded at the
end. The whole gate is green after the changes, on the emulator that was already
running, and `bash -n` and `shellcheck` are clean on all five scripts (one
pre-existing `SC2012` info in `codex-review.sh`, untouched).

1. **The exemption for the research file is bound to content, not to a path.**
   Exempting the path exempted it in every fork commit, so an address written
   into that file after this gate was built and taken out again before the gate
   ran would have passed. The exemption is now one blob id, and five commits
   carry that blob — the research commit `48a3360e`, its merge `1402ea19`, and
   the three commits of ticket 02 between that merge and the one that removed
   the quote. Proof: with the id replaced by one nothing has, exactly those five
   commits are reported and no others; with the real id, green. The exemption by
   address value read from the fork point stays unbounded on purpose, and the
   comment now says why: an address upstream published in its own tree is not
   something this fork can unpublish.
2. **G7 owns the emulator, or it leaves it alone.** Four faults, one fix. "Is
   one already running" now asks whether *anything* is listed on the serial in
   any state, because a booting emulator is listed `offline` and the old check
   read that as "nothing there", started a second one on a port already taken,
   set `started_here` anyway and killed, at the end, an emulator it had not
   started. The device is then identified — `adb emu avd name` must answer
   `bench-pixel6-aosp` or the stage fails without installing anything and
   without killing anything. A launched emulator's pid is kept, and if that
   process exits before the boot completes the stage fails at once and prints
   the tail of `build/emulator.log` instead of waiting five minutes for a boot
   timeout. Shutdown sends `emu kill` and then waits, bounded, for the pid to
   actually go; if it does not, the stage fails rather than reporting green over
   a machine it has left holding port 5554. A trap covers the interrupted run.
   Proofs, all against a mock `adb` and a mock emulator in a temporary directory
   — no second emulator was ever booted: the exact bug reproduced (an `offline`
   device on the serial, the launch failing on the busy port; the old code ran
   the tests and then killed the emulator it had not started, the new code uses
   it and kills nothing); a wrong AVD refused with nothing installed and nothing
   killed; a launched emulator that dies before boot reported with its log; a
   cold boot through tests to a clean shutdown; an emulator that ignores
   `emu kill` failing the stage; `SIGINT` during the boot wait exiting 130 after
   killing only the emulator the stage had started. The "already running" branch
   is exercised by the real gate run above.
3. **The Google guard no longer drops what it cannot resolve.** An
   `UnresolvedDependencyResult` edge was skipped in silence, so a banned
   dependency whose version does not resolve passed. Any unresolved edge now
   fails the task, naming the requested coordinate and the resolution failure.
   Proof: with a `play-services-base` at a version that does not exist added to
   the app, `./gradlew -q :app:checkNoGoogleDependencies` was green before and
   is red after, naming the coordinate in all three variants; green again once
   the dependency is removed.
4. **The Kotlin qualified-this exemption is narrowed.** A local part of `this`
   in a `.kt` file was enough, which exempted any address written inside a
   string literal — which is exactly where one would be written. What follows
   the at sign must now also read as a label naming a class or an object (a
   capital first letter) followed by one or more members. Narrowing beat
   rewriting the three call sites: the three are ordinary Kotlin, more will be
   written, and the exemption would have had to come back. Proof: a planted
   string literal in a `.kt` file whose domain is an ordinary lowercase one is
   green before and red after; the three real call sites stay exempt.
5. **Author and committer names are scanned.** `git` writes whatever it is
   given into the name field, and it is published with the commit exactly like
   the address beside it. The names of every fork commit and the name half of
   the identity the next commit would carry are now checked. Proof: with
   `GIT_AUTHOR_NAME` set to an address the script is green before and red after,
   saying so without printing the name; likewise `GIT_COMMITTER_NAME`; and a
   dangling commit object made with a bad author name (no ref moved) is reported
   by the fork-commit check.
6. **The whole mailbox is matched.** The local part accepts every character RFC
   5322 allows unquoted, so a real address that holds one of them — a bang
   before the word noreply, say — is no longer matched from after that character
   into something the no-reply exemption waves through. It still has to start at
   an alphanumeric, so a backquote or an angle bracket in the text around an
   address is not dragged in. Proof: such an address planted in a tracked file
   is green before and red after.
7. **File names are scanned, and every printed location is redacted.** A path is
   published as loudly as a line of a file. The names in the index and in the
   tree of every fork commit are checked, and any address inside a location this
   script prints is blanked out first — component by component, so the
   directories around the name survive and the report still says where. Proof: a
   tracked file whose name holds an address is green before and red after, and
   the report reads `docs/<address withheld>`; the file was removed and never
   committed.
8. **The notification grant is asked for only where it exists.**
   `POST_NOTIFICATIONS` arrived in API 33 and `GrantPermissionRule` fails during
   setup on a device that does not know it, which is every device at this app's
   API 31 floor. `ReadropsTestRule` now builds the chain with the grant only
   when `Build.VERSION.SDK_INT` is at least 33. Not provable here: the bench AVD
   is API 36 and no second emulator may be booted, so what is verified is that
   the instrumented tests still pass unchanged on API 36. An API 31 device would
   be needed to see the other branch run.

**Ruled out, not fixed.** The review also said the notification grant conceals
that `Synchronizer.refreshLocalAccount` only advances the feed counter when
notifications are enabled. True, and out of scope: that is the local-RSS sync
path, which ticket 04 deletes outright. The FreshRSS path is the one LeNews
keeps.

### Trunk switch (2026-09-05, orchestrator)

Done locally after the merge: `develop` renamed to `main` (same history), the ticket worktree and branch deleted, and the full gate run on `main` twice outside the agent sandbox: once with no emulator (G7 cold-booted `bench-pixel6-aosp`, ran 20 db + 32 app tests, shut it down; 28 s end to end with a warm Gradle) and once with the emulator started two seconds before the gate (G7 saw `emulator-5554 offline`, waited for boot, used it and left it running; 1 min 28 s).

Pending on GitHub access: `git push origin main` was refused with 403 and the default-branch change too. The `gh` fine-grained token only covers `jmnicolas90/Ding`; it needs `jmnicolas90/Readrops` added with Contents, Workflows and Administration write and Actions read. Until then: CI has not run on `main`, the CI half of the play-services red proof is not done, and GitHub's default branch is still `develop`. The remote `develop` is left as history either way; only the local one is gone.

