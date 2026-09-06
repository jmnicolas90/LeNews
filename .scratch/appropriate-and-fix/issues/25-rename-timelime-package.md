# 25 — Fix the misspelled `timelime` package

Type: task
Status: resolved
Blocked by: —

## Question

The timeline's package is spelled **`app.lenews.timelime`** — "timelime", not
"timeline". It is upstream's typo: `com.readrops.app.timelime` exists at the fork
point `9ebbe038`, and ticket 05's rename moved the directory faithfully without
noticing the misspelling inside it. It shows up in every stack trace and every
JIT log line the device prints, which is how it was found — reading logcat off
the user's Samsung on 2026-09-06.

Nothing is broken by it. It is a spelling mistake in an identifier that appears
in crash reports the user is asked to file on GitHub, and it will keep looking
like a mistake for as long as it is there.

**Do it before anything else, for a reason beyond the spelling.** Ticket 24 may
add a baseline profile, and a baseline profile is a list of fully-qualified JVM
descriptors — every LeNews class it names would be written `Lapp/lenews/timelime/…`.
Renaming the package after such a profile is committed makes each of those rules
stop matching **silently**: no build failure, no warning, just an app that is
quietly less optimised than its own profile claims. Ticket 24 is therefore marked
blocked by this one. The same argument holds for a release, more weakly — nothing
is released yet, so there is no crash-report history to split and no upgrade to
break, which makes now the cheapest this will ever be.

**The risk of the move itself is low, and that was checked** rather than assumed:
`app/proguard-rules.pro` names neither this package nor `app.lenews` at all, and
nothing under the directory persists a class name (no `@Parcelize`, no
`Serializable`, no `javaClass.name` key). The `applicationId` and the module
namespace are untouched by a rename below them, so no install breaks.

Rename the directory and the package declaration:
`app/src/main/java/app/lenews/timelime/` → `.../timeline/`, with `git mv` so the
history follows, and fix every `import app.lenews.timelime.*` with it. The
importers on the day this was written are `AppModule.kt`, `MainActivity.kt`,
`home/HomeTabs.kt`, `home/HomeScreen.kt`, `item/components/BackgroundTitle.kt`,
`item/components/SimpleTitle.kt` and `more/preferences/PreferencesScreen.kt`, plus
the files inside the package itself; check `androidTest` and `test` sources too
rather than trusting that list.

**Watch for two things** the compiler will not catch:

- **String references to the package**, if any — Koin module names, Voyager
  screen keys, anything reflective. `grep -rn timelime` across the whole tree,
  not just `*.kt`, and that includes `app/lint-baseline.xml`, whose entries
  carry file paths: a moved file makes its baseline entries stop matching, which
  turns findings back on. **Today that is a non-issue and was checked** —
  `grep -c timelime app/lint-baseline.xml` answers **0**, so none of the 410
  entries points into this directory. Check it again before moving anything
  rather than trusting this line, since the baseline changes when the locales
  call is made.
- **Nothing user-visible changes.** No string resource, no `applicationId`, no
  namespace — the app module's namespace is `app.lenews` and this is a package
  below it. If a diff touches any of those, it has gone too far.

**Done when** `grep -rn timelime` over the tree returns nothing outside
`.scratch/` and this ticket, the gate is green G0 to G7, and the ticket's
`Answer` records how many files moved and whether the lint baseline needed
touching.

## Answer (2026-09-06)

Done, and it was as small as the ticket said. **Eighteen files changed, thirty
lines**: the eleven files of the package moved with `git mv`
(`app/src/main/java/app/lenews/timelime/` → `.../timeline/`, so git records
renames and the history follows), and seven importers outside it updated —
`AppModule.kt`, `MainActivity.kt`, `home/HomeTabs.kt`, `home/HomeScreen.kt`,
`item/components/BackgroundTitle.kt`, `item/components/SimpleTitle.kt` and
`more/preferences/PreferencesScreen.kt`, exactly the list the ticket predicted.
`androidTest` and `test` sources were checked rather than assumed: neither names
the package, so neither moved. Every changed line is a `package` declaration or
an `import`; nothing else in the diff.

**The lint baseline needed no touching**, checked twice. Before the move,
`grep -c timelime app/lint-baseline.xml` answered **0**, as the ticket recorded —
none of the 410 entries points into this directory. After it, G2 filtered
**340 errors and 60 warnings** and reported **10 entries not found in the
project**, which are the ones tickets 13 and 16 already cleared: 340 + 60 + 10 is
the baseline's own 410, so every entry is still accounted for and none was turned
back on by a moved path. The file was left alone.

**Nothing user-visible changed.** No string resource, no `applicationId`, no
module namespace — `app.lenews` is untouched and this is a package below it. No
proguard rule names it (`app/proguard-rules.pro` still names no LeNews package
at all) and nothing under the directory persists a class name.

**One `timelime` remains outside `.scratch/`, so the "Done when" grep clause is
met as read rather than literally** — read as *LeNews paths only*, which is the
only reading that agrees with `CLAUDE.md`. The hit is deliberate:
`code-review-02-09-2026.md:96` names **`com.readrops.app.timelime`**, upstream's
own path in the review of upstream's code. That document is one of the files
`CLAUDE.md` keeps as Readrops; correcting the path there would misquote what was
reviewed. `grep -rn timelime` over the tree returns nothing else but that line,
this ticket, and the map and tickets 02, 21 and 24 that quote the old name in
their own history.

Ticket 24 is unblocked by half: its `Blocked by:` line is now `23` alone, and its
ordering paragraph records that a profile generated from now on reads
`Lapp/lenews/timeline/...`. That edit to a *second* ticket file is more than the
bookkeeping rule asks for, and it is deliberate: 24 names 25 as a blocker, the
blocker is gone, and a queue that still says otherwise misleads whoever picks 24
up next.

`scripts/check.sh` green G0 to G7 on the ticket branch, G7 against the running
`bench-pixel6-aosp` emulator.
