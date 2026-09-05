# 02 — Raise minSdk from 21 to 31

Type: task
Status: open
Blocked by: 01

## Question

`build.gradle.kts` sets `minSdk = 21` for all three modules. The hard constraint says 31, matching Ding. Raising it first, before any other code change, means every later ticket writes against one API floor and the pre-31 branches disappear before anyone has to read them.

Raise it, then delete what the raise makes dead: `Build.VERSION.SDK_INT` checks against anything below 31, `@RequiresApi` below 31, compat shims, notification-channel and permission fallbacks for older releases, the "for Android 7.1 and earlier" priority line in `SyncWorker`. Lint's `ObsoleteSdkInt` check finds most of them. Do not touch anything else.

The gate does not exist yet (ticket 03), so verification is by hand: `./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug`.

**Done when** `minSdk = 31` in the root build file, no `SDK_INT` comparison against a value below 31 remains, lint reports no `ObsoleteSdkInt`, and the debug APK builds.
