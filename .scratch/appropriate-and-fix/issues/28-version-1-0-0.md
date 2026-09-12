# 28 — Move `versionName` from `0.1.0` to `1.0.0`

Type: task
Status: resolved
Blocked by: —

## Question

Nothing to decide. [Ticket 27](27-inherited-upstream-tags.md) settled it: **the
app is usable, so the first release is `1.0.0`**, which reverses the `0.1.0`
[ticket 05](05-rename-to-lenews.md) chose back when the fork was a rename and
three unfixed pain points. This ticket is the edit.

`versionCode` **stays at `1`** — it only has to increase, never to match the
name, and no device anywhere holds a higher one. Do not touch it.

This is not the release. No tag is created here and no APK is published;
`v1.0.0` gets its own ticket once [ticket 24](24-baseline-profile.md) is
answered.

**The four live files that name the version**, and nothing else:

```
app/build.gradle.kts:46                    versionName = "0.1.0"
CHANGELOG.md:6                             ## 0.1.0 — unreleased
CHANGELOG.md:21                            "...new launcher icon, version 0.1.0."
CLAUDE.md:28                               "...the app on screen is "LeNews", v0.1.0."
.github/ISSUE_TEMPLATE/bug_report.md:24    " - LeNews version: [e.g. 0.1.0]"
```

(Four files, five lines — `CHANGELOG.md` carries two.)

**Leave alone**: every other `0.1.0` in the tree is in a resolved ticket under
`issues/` or in the map's *Decisions so far*. Those record what was true when
they were written, and rewriting them would make the log lie. `grep -rn "0\.1\.0"`
will show them; the test of whether a hit should change is whether it *states the
app's current version* or *reports a past decision*.

Two things that follow on their own and need no edit: the **About screen**, which
reads `versionName` from `BuildConfig`, and the **`User-Agent`**, built in the app
module from the same value — it becomes `LeNews/1.0.0`. Worth a glance at the
About screen on the emulator to confirm it reads `v1.0.0 (1)`.

`CHANGELOG.md`'s heading stays **`## 1.0.0 — unreleased`** — the word "unreleased"
is still true and comes off in the release ticket, not this one. Its opening line
"Nothing has been released yet" stays true too.

**Done when** the four files say `1.0.0`, `versionCode` is still `1`, the About
screen shows `v1.0.0 (1)`, and the gate is green G0-G7.

## Answer (2026-09-12)

Done as written: the five lines in the four files now say `1.0.0`, and
`versionCode` is still `1`.

```
app/build.gradle.kts:47                    versionName = "1.0.0"
CHANGELOG.md:6                             ## 1.0.0 — unreleased
CHANGELOG.md:21                            "...new launcher icon, version 1.0.0."
CLAUDE.md:30                               "...the app on screen is "LeNews", v1.0.0."
.github/ISSUE_TEMPLATE/bug_report.md:24    " - LeNews version: [e.g. 1.0.0]"
```

Every other `0.1.0` in the tree is in a resolved ticket or in the map's
*Decisions so far* and was left alone, as the ticket asked.

The About screen and the `User-Agent` followed on their own. Rather than a
screenshot, the check was the package manager's word on the debug build G7 had
just installed on `bench-pixel6-aosp`:

```
adb -s emulator-5554 shell dumpsys package app.lenews.debug | grep -E 'versionCode|versionName'
    versionCode=1 minSdk=31 targetSdk=35
    versionName=1.0.0
```

which is the same `BuildConfig` the About screen reads and the `User-Agent`
string is built from.

`CHANGELOG.md` keeps *unreleased*; that word comes off in
[ticket 29](29-release-v1-0-0.md), which this ticket opens. It exists now,
rather than "after 24" as ticket 27 foresaw, because the user decided on
2026-09-12 to release and to use the app day to day on their own phone
instead of spending more time on the Samsung, which is a slow test device.
Gate green G0-G7.
