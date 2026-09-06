# 25 — Fix the misspelled `timelime` package

Type: task
Status: open
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
