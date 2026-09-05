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
