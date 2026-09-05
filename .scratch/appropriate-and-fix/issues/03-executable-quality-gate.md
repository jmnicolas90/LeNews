# 03 — Build the executable quality gate, locally and in CI, and switch to a single trunk

Type: task
Status: open
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
