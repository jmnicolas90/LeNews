# 08 — Rewrite the README and get the fork's attribution right

Type: task
Status: resolved
Blocked by: 06

## Question

After ticket 06 the README is a hollowed-out Readrops page. Write LeNews's: what it is (a FreshRSS client for Android, one account, built for a high article volume), what it does today, the fork relationship with exactly one link to `readrops/Readrops` labelled as the original project and one sentence on why it was forked, Android 12+ / `minSdk 31`, Google-free on GrapheneOS and AOSP stated as an enforced property, how to build (`./gradlew assembleDebug`) and how to check (`scripts/check.sh`), GitHub releases as the only distribution, GitHub issues as the only contact channel.

Attribution: the copyright line for fork-authored files is `Copyright (C) 2026 Jean-Michel Nicolas` — git author name, never an email address — *added* alongside upstream's on files the fork creates (the gate scripts, the CI workflow, `CONTEXT.md` if it takes a header), never substituted on inherited files, whose headers and years stay exactly as they are. State that rule in a short `CONTRIBUTING.md` (upstream has none; the file also says that external contributions are not expected). Keep `LICENSE` byte-identical.

Upstream's `CHANGELOG.md` records Readrops releases; keep it as history under a heading that says so, and start LeNews's own changelog above it.

Screenshots: none. Do not ship upstream's images under the new name and do not invent any; leave a marked gap.

**Done when** the gate is green, the README describes LeNews and only LeNews, and `grep -rn "Copyright"` shows upstream's lines untouched and the fork's line only on fork-authored files.

## Answer (2026-09-06)

Four documents written or rewritten, two copyright headers added, `LICENSE`
untouched. Gate green G0 to G7.

### `README.md`

Rewritten from the ticket 06 placeholder. In order: what LeNews is (a FreshRSS
client for Android, one account, a few hundred articles a day); **what it does
today**, said honestly — the FreshRSS half of Readrops with everything else
taken out, listing what the inherited path already does and what is gone (local
RSS, Nextcloud News, Fever, OPML, multi-account), then naming the three pain
points as **not done**: it still slows down as articles accumulate, an article
can still be stored twice, there is still no history. Then where it comes from,
with **exactly one link** to `https://github.com/readrops/Readrops` labelled as
the original project, the fork point `9ebbe038` and one sentence on why (one
FreshRSS account rather than many services and accounts, the three problems
needing fixes in the database and the sync, the freedom to restructure code
upstream has every reason to keep). Then Android 12 or later / `minSdk 31`;
Google-free as an **enforced** property, naming gate stage G4 and what it walks;
building with `./gradlew assembleDebug` (SDK `platforms;android-35`, JDK 21) and
checking with `scripts/check.sh`, with `SKIP_INSTRUMENTED=1` mentioned; the
three `local.properties` debug-login keys; GitHub releases as the only
distribution ("not on F-Droid and not on the Play Store, and there is no plan
to"); GitHub issues as the only contact channel, with "no mailing list, no chat
and no email address" said out loud; and the licence with both copyright
holders. **Screenshots: none yet**, a line of its own under the opening
paragraph, so the gap is marked rather than hidden. No badges, no email address,
no image at all.

Two claims were checked against the code before being written rather than copied
from upstream's feature list: there is **no search** in the app (`grep -rl
Search app/src/main/java/app/lenews/` is empty), so the sentence says what is
there — sync, subscription management, timeline, read in app or browser, read
and starred, share, new-article notifications.

### `CONTRIBUTING.md`

New file, short, five sections. External contributions are not expected: issues
welcome, pull requests may be closed without review, open an issue first. Then
the copyright header rule (below), the gate as the bar (`scripts/check.sh`
green before every commit, never commit red, fix a wrong stage rather than
weaken it), the branch and worktree convention, the no-email rule with what G1
checks, and the co-author trailer naming the model that actually wrote the code.
Everything else points at `CLAUDE.md` rather than repeating it.

### The header decision

**Markdown documentation takes no header** — `CONTEXT.md` and `CLAUDE.md`
included, and `README.md`, `CHANGELOG.md`, `CONTRIBUTING.md`, `docs/` and
`.scratch/` with them. Two reasons, both stated in `CONTRIBUTING.md`: a document
says in its own prose who wrote it and where its text came from, and
`CHANGELOG.md` in particular **reproduces upstream's writing**, so a fork
copyright line at the top of it would claim text the fork did not write.

The rule as written: the line is `Copyright (C) 2026 Jean-Michel Nicolas`, name
only and never an address, followed by the GPL notice in the comment syntax of
the file's language, on files **this fork creates**; it is *added underneath*
an inherited header, never substituted for one; and no header is added to an
inherited file, because a header on a file the fork did not write would claim
it. Ticket 07's finding is restated in the file as fact: Readrops shipped no
per-file copyright headers at all, so there is nothing in this tree to preserve
except `LICENSE`.

Two headers were **added**, to make the rule true of the tree rather than
aspirational: `app/src/main/res/drawable/ic_launcher_background.xml` and
`ic_launcher_foreground.xml`, the fork's own launcher artwork from ticket 05,
which is exactly the kind of file a copyright line is for. Their existing
explanatory comments were kept below the notice. Nothing else changed.

### The `grep -rn "Copyright"` result

Outside `.scratch/` (tracker prose) the whole tree is:

| File | Line |
| --- | --- |
| `LICENSE` (4 lines) | the FSF's own, byte-identical to upstream's |
| `scripts/check.sh` | fork header |
| `scripts/check-preflight.sh` | fork header |
| `scripts/check-no-personal-email.sh` | fork header |
| `scripts/android-sdk-path.sh` | fork header |
| `scripts/codex-review.sh` | fork header |
| `.github/workflows/ci.yml` | fork header |
| `app/src/main/res/drawable/ic_launcher_background.xml` | fork header (new) |
| `app/src/main/res/drawable/ic_launcher_foreground.xml` | fork header (new) |
| `README.md`, `CONTRIBUTING.md`, `CLAUDE.md` | prose stating the rule, not headers |

Every one of the eight header files is fork-authored (`git diff
--diff-filter=A 9ebbe038 HEAD` lists them all as added). **Upstream's lines are
untouched because there are none**: no inherited Kotlin, Gradle or XML file
carries a copyright header, and the only upstream copyright lines in the repo
are the FSF's inside `LICENSE`.

### `LICENSE`

`md5sum` before and after: `1ebbd3e34237af26da5dc08a4e440464` both times, and
`git diff -- LICENSE` is empty.

### `CHANGELOG.md`

Upstream's 242 lines are kept **unedited**, moved under a heading that says what
they are: `# Readrops history (before the fork)`, with one paragraph naming the
fork point `9ebbe038` (v2.1.1, 20 July 2025) and saying the releases listed
below are Readrops releases, not LeNews ones. Above it, `# Changelog` and a
`## 0.1.0 — unreleased` section that opens with "Nothing has been released yet"
and lists in user-facing terms what this map has done: FreshRSS only, the rename
with the fresh-install consequence, Android 12 or later, no store/donation/crash
reporting surfaces with GitHub issues as the only channel, Google-free enforced
rather than promised, and the one command that has to be green. Nothing
speculative — the three pain points are not mentioned as if they were fixed.

### `CLAUDE.md`

The one sentence that said the attribution "lives in `LICENSE` and in what
ticket 08 writes into `README.md`" now points at the written files, and a short
paragraph was added saying the public documents exist, what `CONTRIBUTING.md`'s
header rule is, and which eight files carry the header. The list of what still
says Readrops deliberately was corrected: `CHANGELOG.md` is now half LeNews's,
and `README.md`'s fork paragraph joins the list.

### Review round (2026-09-06)

Three findings from the adversarial review, all accepted and all fixed.

**1. The README claimed multi-account was gone. It is not.** `AccountTab` has an
add-account button that opens the account-type dialog, lists the others under
"Other accounts" and switches between them, and
`AccountCredentialsScreenModel.login()` in `NEW_CREDENTIALS` mode really does
`accountDao().insert(newAccount)`. What ticket 04 removed was the other three
*services*, not the multi-account plumbing, which it deliberately left for
ticket 13. `README.md` now takes `multi-account` out of the "what is gone" list
and adds a paragraph saying it plainly: one account is the scope LeNews is
designed for, the inherited screen still adds, lists and switches accounts, and
collapsing it into a login screen is a later change that waits on the article
store decision because the account is threaded through the schema.
`CHANGELOG.md` says the same in one sentence on the FreshRSS-only bullet.
`CLAUDE.md` says it where it matters most, in the opening paragraph, so that a
session reading "one account, one service" is told in the same breath that this
is the intended scope and not what the tree does, with ticket 13 named and an
instruction not to write code — or a document — that assumes otherwise.

**2. "Every build fails" on a Google dependency was false, so the build was
changed to make it true.** The guard was attached to `check` only, which the
gate's G4 and CI run; `./gradlew assembleDebug` did not run it, so an APK with
Play Services in it could be built by hand. Of the two options offered — word
the guarantee precisely, or widen the guard — **the guard was widened**, because
it is one line in the root `build.gradle.kts`:

    tasks.matching { it.name == "check" || it.name.startsWith("assemble") }
        .configureEach { dependsOn(guard) }

Proved both ways with `implementation("com.google.android.gms:play-services-base:18.5.0")`
planted in `app/build.gradle.kts`. With the wiring, `./gradlew :app:assembleDebug`
fails at `:app:checkNoGoogleDependencies` and names six coordinates — the planted
one plus `play-services-basement` and `play-services-tasks` pulled in behind it,
in both the debug and the release runtime classpath. With the wiring reverted and
the plant still in place, the same command is `BUILD SUCCESSFUL`. Plant then
removed; `git diff` on `app/build.gradle.kts` is empty.

So the three documents now say the same true thing: a Play Services or Firebase
dependency fails `./gradlew check`, fails `assembleDebug` and `assembleRelease`,
and fails the gate and CI — no APK can come out of this tree with one in it.
`README.md` also says what does *not* trigger it (a task that builds nothing,
such as `clean` or a bare `compileDebugKotlin`), because a guarantee with no
stated edge is the kind that gets overstated again. `CLAUDE.md` records the
consequence to expect: the guard walks every variant, so `assembleDebug` also
resolves the release classpath and a release-only offender fails a debug build.
G4 stays a stage of its own — it names the offence and fails before two APK
builds rather than during one.

**3. A fork-authored file lacked the header, and the rule had never been
audited.** `app/src/test/java/app/lenews/util/accounterror/GReaderErrorTest.kt`,
written by ticket 04's own review round (commit `7126d1be`), had no GPL notice.
It has one now, the same text as `scripts/check.sh`'s in a Kotlin block comment.

Then the audit the first pass skipped. `git log --diff-filter=A --name-only
--format= 9ebbe038..HEAD` lists every file this fork *created*, following a file
through the ticket 05 rename. Leaving Markdown aside (exempt, and that covers
the 22 tickets, the map, `CLAUDE.md`, `CONTEXT.md`, `CONTRIBUTING.md`,
`code-review-02-09-2026.md`, both `docs/research/` reports and the two
`.claude/agents/` files), fifteen files remain:

| File | Header | Why |
| --- | --- | --- |
| `scripts/check.sh` | yes | shell |
| `scripts/check-preflight.sh` | yes | shell |
| `scripts/check-no-personal-email.sh` | yes | shell |
| `scripts/android-sdk-path.sh` | yes | shell |
| `scripts/codex-review.sh` | yes | shell |
| `.github/workflows/ci.yml` | yes | YAML |
| `app/src/main/res/drawable/ic_launcher_background.xml` | yes | XML |
| `app/src/main/res/drawable/ic_launcher_foreground.xml` | yes | XML |
| `app/src/test/java/app/lenews/util/accounterror/GReaderErrorTest.kt` | **added this round** | Kotlin |
| `app/src/androidTest/resources/greader/items_1_item.json` | no | JSON has no comment syntax |
| `app/src/androidTest/resources/greader/items_empty.json` | no | same |
| `app/src/androidTest/resources/greader/items_no_ids.json` | no | same |
| `app/src/androidTest/resources/greader/items_unread_ids.json` | no | same |
| `app/lint-baseline.xml` | no | lint regenerates the file and would drop the comment |
| `app/src/main/java/app/lenews/util/components/LoadingScreen.kt` | no | **the code is upstream's** |

That last one is the finding inside the finding. Git records `LoadingScreen.kt`
as added by ticket 04, but its body is `fun LoadingScreen` lifted **unchanged**
out of upstream's `util/components/RefreshScreen.kt` — verified against
`9ebbe038:app/src/main/java/com/readrops/app/util/components/RefreshScreen.kt`
lines 56–72 — when that file was deleted with the local-RSS screens. A fork
copyright line on it would claim someone else's work, which is precisely what
the rule forbids. "Created by git" and "written by the fork" are not the same
thing, and the rule follows the second.

Room's schema JSON under `db/schemas/` is generated JSON like the fixtures, but
it never reaches the rule at all: those files are inherited and only renamed.

`CONTRIBUTING.md` now carries this list as a section of the header rule, so the
rule is true of the tree rather than aspirational, with two consequences stated:
a new fork-written source file gets its header in the same commit, and a new
file that cannot take one gets added to the exception list with its reason.
`CLAUDE.md`'s summary was corrected from eight header files to nine and from "no
Kotlin file carries a header" to "no *inherited* file carries one", and it points
at the exception list rather than restating it. No inherited file was touched.
