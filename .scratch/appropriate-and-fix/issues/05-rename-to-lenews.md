# 05 — Rename the app to LeNews

Type: task
Status: open
Blocked by: 04

## Question

Mechanical, wide, and cheap exactly once — after ticket 04 there is less to rename, and the gate catches breakage. `applicationId` and `namespace` become `app.lenews`; the three modules' namespaces become `app.lenews.app`, `app.lenews.db`, `app.lenews.api` (or `app.lenews` for the app module and sub-namespaces for the libraries; pick one and say why); the source package `com.readrops.*` becomes `app.lenews.*` across the tree with `git mv`, tests included; `app_name` and every user-visible string that says Readrops becomes LeNews; the launcher icon and `db`'s stray mipmaps are replaced by a placeholder icon that is not upstream's logo.

Build identity: upstream has three build types, `debug` (`.debug` suffix), `release`, and `beta` (`.beta` suffix, debug-signed). Ding decided one build identity for every build type. Do the same here unless there is a reason not to: delete `beta`, keep the `.debug` suffix only if the user wants both installed side by side — recommend keeping it, since the store Readrops and a LeNews debug build coexisting on the phone during this map is useful.

Version: start LeNews at `versionCode 1`, `versionName 0.1.0`. Nothing upgrades from the Readrops package, so the old numbers carry no obligation.

Also rename the GitHub repository (`jmnicolas90/Readrops` → `jmnicolas90/LeNews`), the `origin` remote URL, and this working directory (`/home/skynet/dev/LeNews`). The directory rename changes the memory path for agent sessions; note it in the resolution.

Keep the GPL copyright headers and every upstream author name exactly as they are. Renaming the app does not change who wrote the code.

**Done when** the gate is green, `grep -ri readrops` returns only GPL headers, `CHANGELOG.md`, the code-review file and deliberate fork-origin prose, and `aapt dump badging` on the debug APK shows `package: name='app.lenews.debug'` and `application-label:'LeNews'`.
