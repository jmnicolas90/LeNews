# 02 — Raise minSdk from 21 to 31

Type: task
Status: resolved
Blocked by: 01

## Question

`build.gradle.kts` sets `minSdk = 21` for all three modules. The hard constraint says 31, matching Ding. Raising it first, before any other code change, means every later ticket writes against one API floor and the pre-31 branches disappear before anyone has to read them.

Raise it, then delete what the raise makes dead: `Build.VERSION.SDK_INT` checks against anything below 31, `@RequiresApi` below 31, compat shims, notification-channel and permission fallbacks for older releases, the "for Android 7.1 and earlier" priority line in `SyncWorker`. Lint's `ObsoleteSdkInt` check finds most of them. Do not touch anything else.

The gate does not exist yet (ticket 03), so verification is by hand: `./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug`.

**Done when** `minSdk = 31` in the root build file, no `SDK_INT` comparison against a value below 31 remains, lint reports no `ObsoleteSdkInt`, and the debug APK builds.

## Answer (2026-09-05)

`minSdk` is 31 in the root `build.gradle.kts`, for all three modules. Lint
reported 5 `ObsoleteSdkInt` findings right after the raise (4 in `app`, 1 in
`db`, 0 in `api`) and reports 0 now.

Done in two commits: the raise itself, then a review round that removed two
more compat shims the raise had made dead (core library desugaring, and the
legacy storage permission in `api`'s debug manifest).

Removed, file by file:

- `build.gradle.kts` — `minSdk = 21` becomes `minSdk = 31`.
- `app/src/main/java/com/readrops/app/ReadropsApp.kt` — the
  `SDK_INT >= O` guard around notification channel creation, and the
  `android.os.Build` import it was the only user of. Channels exist on every
  supported release now.
- `app/src/main/java/com/readrops/app/account/selection/AccountSelectionScreen.kt`
  — the `SDK_INT >= O` branch in `adaptiveIconPainterResource`, which had a
  whole duplicate `painterResource` path for pre-Oreo. The remaining code
  tries the drawable as an adaptive icon and falls back to
  `painterResource` when it is not one; that fallback is about the drawable's
  shape, not about the API level, so it stays.
- `app/src/main/java/com/readrops/app/more/preferences/PreferencesScreen.kt`
  — the `SDK_INT >= O` guard hiding the "disable battery optimization"
  preference. The preference is now always shown.
- `app/src/main/AndroidManifest.xml` — `tools:targetApi="n"` (a promise about
  API 24) and the `xmlns:tools` declaration that had no other user left.
- `app/src/main/java/com/readrops/app/sync/SyncWorker.kt` — the
  `.setPriority(NotificationCompat.PRIORITY_DEFAULT) // for Android 7.1 and
  earlier` line. From API 26 the channel's importance decides, and the call is
  ignored.
- `db/src/main/java/com/readrops/db/util/DateUtils.kt` — the
  `@SuppressLint("NewApi")` on `parse` and its now-unused import. `java.time`
  is native from API 26, so lint has nothing left to complain about; the run
  confirms no `NewApi` finding appeared in its place.
- `db/src/main/res/mipmap-anydpi-v26/` renamed to `mipmap-anydpi/`. The `v26`
  qualifier could never lose at minSdk 31.

Removed in the review round:

- Core library desugaring, everywhere: `isCoreLibraryDesugaringEnabled` and
  the commented-out `add("coreLibraryDesugaring", ...)` block in the root
  `build.gradle.kts`, the `coreLibraryDesugaring(libs.jdk.desugar)` line in
  `app`, `api` and `db`, and the now-unused `jdk-desugar` alias in
  `gradle/libs.versions.toml`. Desugaring backported `java.time`,
  `java.util.stream` and `java.nio.file` to releases that did not have them;
  all three are native from API 26, so at a floor of 31 the whole mechanism
  was a shim for devices the app no longer runs on. Lint raises no `NewApi`
  in its place, and the debug APK no longer carries any `j$/time` class.
  Two comments in `db/.../util/DateUtils.kt` that blamed "java.time android
  desugaring" for strict day-of-week parsing were reworded; the parsing
  itself is unchanged, `java.time` is strict about that on Android either
  way.
- `api/src/debug/AndroidManifest.xml` — the `WRITE_EXTERNAL_STORAGE`
  permission and `android:requestLegacyExternalStorage="true"`. Scoped
  storage is enforced from API 29 and the opt-out stopped working at 30, so
  from a floor of 31 the platform ignores both. Nothing relied on them:
  `api` has no `androidTest` source set at all, only JVM tests under
  `api/src/test`, which write nowhere. Neither string appears in `api`'s
  merged debug manifest nor in the app's. The `INTERNET` permission and
  `usesCleartextTraffic` in that file stay; MockWebServer needs them, and
  the cleartext policy is ticket 19's.

Kept, and why:

- `notifications/NotificationsScreen.kt` and `timelime/TimelineTab.kt` check
  `SDK_INT >= TIRAMISU` (33), above the new floor. They gate the runtime
  `POST_NOTIFICATIONS` permission request, which really does only exist from
  33, so they are live checks.
- `util/extensions/ContextExtensions.kt` still asks connectivity through the
  deprecated `activeNetworkInfo`, under a comment saying the non-deprecated
  APIs need API 23. The comment is stale, but swapping in
  `NetworkCapabilities` is a behaviour change, not a deletion, and this ticket
  deletes only what the raise makes dead. Left for a later ticket.
- `compileSdk` and `targetSdk` stay at 35. Raising `targetSdk` is its own
  future ticket.
- `.github/workflows/android.yml` names emulator `api-level: 34`, already
  above the new floor, so nothing to change; ticket 03 rewrites it anyway.

Verified by hand, from the worktree: `:app:lintDebug :api:lintDebug
:db:lintDebug :app:testDebugUnitTest :api:testDebugUnitTest
:db:testDebugUnitTest :app:assembleDebug` passes, 146 unit tests green
(app 4, api 135, db 7), the debug APK builds, and the three lint reports
contain no `ObsoleteSdkInt` and no `NewApi`. Lint's exit code proves nothing
here — `abortOnError` is false — so the reports were read. What they do
report is unrelated to the floor: 224 `MissingTranslation` and 30
`UnusedResources` in `app`, 29 `GradleDependency` in `api`, vector-drawable
warnings in `db`. One incidental note:
`NextcloudNewsDataSourceTest.classicSyncTest` failed once under load and
passed on every rerun, on this branch and on the untouched base alike; it is a
timing-sensitive MockWebServer test that upstream already annotated as
mysterious, and that code is deleted by ticket 04.
