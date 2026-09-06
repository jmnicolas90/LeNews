# 24 — Decide whether LeNews needs a baseline profile of its own

Type: task
Status: open
Blocked by: 23

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
this ticket waits on ticket 23 to make one installable.

**Steps:**

1. Once ticket 23 lands a signable release configuration, install a
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
