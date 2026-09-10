# 24 — Decide whether LeNews needs a baseline profile of its own

Type: task
Status: resolved
Blocked by: —

## Question

The release APK carries a baseline profile; the debug APK carries none. Checked
on 2026-09-06 with `unzip -l`: two `assets/dexopt/baseline.*` entries in
`app-release-unsigned.apk`, zero in `app-debug.apk`. What is in the release one
is **not LeNews's** — it is the profile Compose and the other AndroidX libraries
embed in their AARs, which AGP merges. No `androidx.baselineprofile` plugin is
applied anywhere, there is no generator module, and nothing in the tree profiles
this app's own startup or its timeline scroll.

The question this ticket answers is whether that is a gap worth closing, and it
must be answered **with a measurement, not a guess** — the same standard
ticket 11 set for the database side.

What prompted it: on the user's Galaxy A06 the debug build JIT-compiles as it
goes, visibly. The log line

```
Compiler allocated 4198KB to compile void app.lenews.timelime.components.TimelineItemPartsKt.TimelineItemHeader-Lb_0hxI(...)
```

is one composable costing 4 MB of JIT output at first use. That is the debug
build having no profile at all, so it says nothing yet about the release build —
which is exactly why the measurement has to happen on a release build, and why
this ticket waits on [ticket 26](26-signing-config-and-keystore.md) to make
one installable. ([Ticket 23](23-release-signing.md) decided how; 26 builds it.)

**Steps:**

1. Once ticket 26 lands a signable release configuration, install a
   release build on `bench-pixel6-aosp` and, if the user allows it again, on the
   Samsung. Time cold start to first timeline frame and a scroll of the timeline,
   several runs, on a store of a realistic size — the phone held **1,572
   articles across 19 feeds** on the day this was written, which is small; seed
   or sync up to something closer to the year-sized fixture ticket 11 uses.
2. Add the `androidx.baselineprofile` plugin and a generator module, generate a
   profile from those same journeys, and measure again the same way.
3. **Compare, and let the numbers decide.** If the app-specific profile does not
   beat the library-embedded one by a margin worth a new Gradle module and a
   generator that needs an emulator, **close this ticket as not needed** and say
   so with the figures. A module that has to run on a device to produce an
   artifact is a real cost to the gate; it has to earn its place.

Two constraints it must not break: the generator module goes through
`checkNoGoogleDependencies` like every other module (G4 names all three modules
explicitly today — a fourth means a fourth line), and nothing about it may
require Play Services or a Google-image emulator, which `bench-pixel6-aosp`
deliberately is not.

**Why ticket 25 comes first, and it is not a preference.** A baseline profile is
a list of fully-qualified JVM descriptors — the merged one in the tree today
reads `Lcoil3/compose/AsyncImageKt;`, `SPLcoil3/compose/AsyncImageKt;->AsyncImage-…`
and so on. A profile generated for LeNews would therefore carry
`Lapp/lenews/timelime/...` lines by the hundred. Rename the package afterwards
and every one of those rules stops matching — **silently**. Nothing fails, no
warning is printed, the profile is simply partly dead and the app is partly
unoptimised, which is the worst possible outcome for a ticket whose entire
deliverable is a before-and-after comparison. So the rename lands first and this
ticket generates against the final package name, once. **Ticket 25 landed on
2026-09-06**, so the final name is `app.lenews.timeline` and a profile generated
now will read `Lapp/lenews/timeline/...`; this ticket is left blocked by 23 alone.

**Done when** this ticket's `Answer` holds before-and-after numbers from a
release build on real hardware and a decision either way. Either the profile is
in the tree and the gate is green with it, or the ticket says with figures why
LeNews does not need one.

## Answer (2026-09-10)

**LeNews keeps a baseline profile of its own.** The numbers earned it on the
hardware that matters: on the user's Galaxy A06 the app-specific profile takes
**47 ms off every cold start** — 687.4 ms to 640.6 ms, medians of ten cold
launches — and **halves the worst frame overrun of a timeline scroll**, 6.69 ms
past the deadline at P99 down to 3.49 ms. The profile is
`app/src/main/generated/baselineProfiles/`, 28,973 rules with a 24,987-rule
startup subset, committed as text; the module that wrote it is
`:baselineprofile`; and **the gate never runs it**.

### What was measured, and how the two APKs differ

The question is not "does a baseline profile help" — the release APK already
carried one, 5,381 bytes of rules that Compose, Paging, Room and the rest embed
in their own AARs and AGP merges. The question is whether **LeNews's own** code
is worth adding to it. Those rules live in one merged
`assets/dexopt/baseline.prof`, so telling the two apart means two APKs, not two
compilation modes:

| | `assets/dexopt/baseline.prof` |
|---|---:|
| before — the libraries' rules alone | 5,381 bytes |
| after — plus LeNews's own | 9,274 bytes |

Both are `benchmarkRelease`: the release build type with `minifyEnabled`,
`shrinkResources` and the release signing key, plus `profileable android:shell`
so a macrobenchmark can read its traces. Both were measured with
`CompilationMode.Partial(BaselineProfileMode.Require)`, which is what a reader
who installed the APK gets, and each measurement is ten iterations.

`CompilationMode.None()` — nothing compiled ahead of time — was measured on both
APKs as a control. It should be almost unaffected by a profile, and it is: the
small improvement it does show is `dexLayoutOptimization`, which uses the startup
profile to lay the dex files out and so pays off before ART compiles anything.

### Cold start — time to initial display, median of 10 cold launches

| APK, compilation mode | Galaxy A06 | bench-pixel6-aosp |
| --- | ---: | ---: |
| before — nothing compiled ahead of time | 818.4 ms | 149.0 ms |
| before — the profile the APK carries | **687.4 ms** | **129.5 ms** |
| after — nothing compiled ahead of time | 785.0 ms | 141.8 ms |
| after — the profile the APK carries | **640.6 ms** | **118.4 ms** |
| **what LeNews's own rules buy** | **−46.8 ms (−6.8%)** | **−11.1 ms (−8.6%)** |

Min and max, because a median hides the tail: on the phone, before is
668.2 / 687.4 / 764.3 and after is 622.0 / 640.6 / 746.7; the worst launch of the
whole run was 1302.8 ms, and it was an uncompiled one.

Read the first two rows together and the shape is clear. Any profile at all is
worth 131 ms on that phone; LeNews's own rules are worth a further 47 ms, which
is **about a third again** of what the libraries' rules already bought.

### Timeline scroll — four flings, each on a freshly started process

Frame CPU time and frame overrun, in milliseconds, at the profile the APK
carries. Overrun is the number that means jank: how far past its deadline a
frame finished, so anything above zero is a frame the reader could see drop.

| | before P50 | after P50 | before P90 | after P90 | before P95 | after P95 | before P99 | after P99 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| A06, frame CPU | 8.95 | 8.93 | 13.95 | **13.37** | 15.86 | **14.91** | 20.52 | **18.93** |
| A06, overrun | 0.20 | **−1.97** | 1.01 | **0.71** | 1.67 | **1.15** | 6.69 | **3.49** |
| emulator, frame CPU | 1.42 | 1.33 | 2.43 | 2.25 | 2.78 | 2.76 | 3.46 | 3.38 |

The phone's median frame moves from 0.20 ms **past** its deadline to 1.97 ms
**inside** it, and the P99 overrun halves. The emulator's scroll barely moves,
which is not a disagreement: at 1.4 ms a frame it has so much headroom that
there is nothing left to win, and that is exactly why the ticket insisted on
real hardware. For the same reason the uncompiled control is worth quoting — on
the A06 it scrolls at P99 40.9 ms CPU and 42.9 ms overrun, so a scroll with no
profile at all is visibly broken and both profiles fix most of it.

### The stores the two devices measured against

| | articles | feeds | folders |
| --- | ---: | ---: | ---: |
| bench-pixel6-aosp | 100,000 seeded | 100 | 10 |
| Galaxy A06 | 8,337 synced from `ledev` | 19 | 6 |

The ticket asked for something closer to the year-sized fixture than the 1,572
articles the phone held when it was written, and the emulator half got it:
`scripts/seed-store.sh` builds a hundred thousand articles and pushes them under
an installed build in **under two seconds**. The phone half could not — a
release build is not debuggable and the A06 is not rooted, so there is no way to
write into its store, and what it holds is whatever `ledev` has: 8,337 articles
across 19 feeds, up from 1,572 because the account has been collecting since.
That is a real limit of the phone measurement and it is recorded rather than
papered over. It matters less than it looks: what a baseline profile changes is
class loading and first-run compilation, not query time, and
`TimelineTimeBudgetTest` already holds the timeline's first page to 0.4 ms at a
hundred thousand articles.

### What it costs, which is the other half of the question

The ticket set the bar at "a margin worth a new Gradle module and a generator
that needs an emulator". The generator does need one — and it is **not in the
gate**, which is what makes the bar clearable:

- `baselineProfile { automaticGenerationDuringBuild = false }` in
  `app/build.gradle.kts`. `:app:assembleRelease` reads two committed text files
  and asks for no device. G6 is what it was, and a GitHub runner never needs an
  emulator to build a release APK.
- The profile is regenerated by hand, `./gradlew :app:generateBaselineProfile`,
  against a seeded emulator, when the code it describes has moved enough to be
  worth it. Its files are committed in the same commit as that code.
- `:baselineprofile` appears in the gate **once**, as a fourth
  `checkNoGoogleDependencies` in G4 — the ticket's own constraint. It has no
  lint task, no unit tests and no APK a reader installs. The guard had to learn
  `com.android.test` modules to do it: it collects variants through
  `TestAndroidComponentsExtension` now as well as the application and library
  ones, and since a module it can inspect nothing of is a failure rather than a
  pass, a fourth module it did not understand would have turned G4 red.
- `androidx.benchmark` went in at **1.5.0, not 1.3.4**, and that is not
  housekeeping: on API 36 `pm dump-profiles` answers with two lines of progress
  before the path, and 1.3.4 fails to parse it — *"Expected `pm dump-profiles`
  stdout to be either black or …"*. Generation is impossible on
  `bench-pixel6-aosp` below 1.5.0.
- `settings.gradle.kts` gained a `pluginManagement` block. `androidx.baselineprofile`
  is published to Google's Maven repository and a `plugins { alias(…) }` block
  looks only at the Gradle plugin portal; the other Android plugins never needed
  it because they arrive on the root buildscript classpath.

### Three traps, recorded so nobody pays for them twice

1. **`startupMode = StartupMode.COLD` kills the process *after* the setup
   block.** That is right for a benchmark that launches the app in the measured
   block and wrong for a scroll benchmark that launches it in the setup block:
   every iteration measured the launcher, with the app never started. The scroll
   benchmark calls `killProcess()` in its own setup block instead, which gets a
   fresh process *and* a started activity.
2. **`FrameTimingMetric`'s percentiles are not in `metrics`.** `frameCount` is;
   `frameDurationCpuMs` and `frameOverrunMs` are under `sampledMetrics` in
   `…-benchmarkData.json`, and a reader that walks only `metrics` reports a
   frame count and no timings at all.
3. **The seeded fixture must set `Feed.open_in_ask` to 0.** The database seeder
   the timing tests use leaves it at 1, which is faithful to a fresh sync — and
   makes the first tap on an article put up the "Open Feed in" dialog instead of
   opening it. The generator recorded the dialog and then failed on the article
   that never appeared. `scripts/seed-store.sh` differs from
   `ArticleStoreSeeder` in that one column, deliberately, with a comment saying
   why.

### On the phone, and what was left behind

Done at the user's request in this session, `-s R8YY10CRHSV` pinned on every adb
call because the emulator was up at the same time. The A06 was **left exactly as
found**: `app.lenews` and `app.lenews.baselineprofile` are uninstalled and only
`app.lenews.debug`, which was there before, remains. The `ledev` account is as
found too — nothing was marked read (a mark-all-read dialog was opened by
accident, looking for the sync button, and cancelled), nothing was starred, and
its unread count went *up* over the session, 8,267 to 8,337, because the feeds
kept delivering.

One thing the session found that is not this ticket's to fix: **the release
build's login screen rejects the account password with HTTP 401 and gives no
hint that FreshRSS wants the API password instead** — the hint text under the
field says so, but the error does not. Worth a ticket if anyone else ever has to
sign in from scratch.

**Two limits on all of the above.** Neither device had its clocks locked
(`cpuLocked=False`), so these are medians of a noisy machine rather than
laboratory numbers — which is why every figure here is a median of ten and why
the uncompiled control is quoted beside it. And the emulator numbers were taken
with `androidx.benchmark.suppressErrors=EMULATOR`, because androidx refuses
emulators as untrustworthy and is right to; the emulator column is context for
the phone column, never a substitute for it.
