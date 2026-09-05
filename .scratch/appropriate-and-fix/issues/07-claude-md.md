# 07 — Write CLAUDE.md

Type: task
Status: resolved
Blocked by: 05

## Question

Every agent session in this repo needs one page that says what the app is, what must never break, how to prove the tree is good, and where the tickets live. Adapt `../Ding/CLAUDE.md` — same section order, trimmed and retargeted: fork origin (`readrops/Readrops` at `9ebbe038`, 2025-07-20, GPL-3.0, hard fork, upstream still active so cherry-picks are by hand); the hard constraints from the map, each with how it is enforced; the gate as a one-liner and as a per-stage table, G7 included, with the notes that save time (which SDK package G0 wants, which AVD G7 wants, that the first run is slow); working conventions (plain language, one trunk `main`, worktrees under `.claude/worktrees/`, `--no-ff`, never commit red, the co-author trailer rule with `Fable 5.1 <noreply@anthropic.com>` as the current example); domain vocabulary pointing at `CONTEXT.md` rather than duplicating it, plus the two or three facts about articles and sync that every session trips over; tickets and bookkeeping (this directory, routing by `Type:`); environment (`$ANDROID_HOME`, JDK, French locale).

Write it after tickets 03 and 05 have landed so it describes the tree as it is, not as intended. Where something is still pending (ticket 13's single-account collapse, the retention rule), say so rather than describing the future.

**Done when** `CLAUDE.md` exists at the repo root, every command it names runs as written, and a fresh agent given only it and a ticket can find the gate, the tracker and the glossary without asking.

## Answer (2026-09-06)

`CLAUDE.md` is at the repo root, in Ding's section order, retargeted and
trimmed: what the app is and the fork origin; the hard constraints; the gate;
working conventions; domain vocabulary; tickets and bookkeeping; environment.
The gate is green G0 to G7.

### What it covers

- **What the app is.** One paragraph: a FreshRSS client for Android, one
  account, built for a few hundred articles a day, in three modules (`api`
  speaks the Google Reader API, `db` is Room, `app` is the UI and the sync
  worker), Kotlin / Compose / Room / WorkManager / OkHttp-Retrofit / Koin, with
  the three pain points named as the most serious class of bug here.
- **Fork origin.** `readrops/Readrops` at `9ebbe038` (2025-07-20, `develop`,
  v2.1.1 plus the unfinished tag feature), GPL-3.0, hard fork. The ticket text
  said "upstream still active so cherry-picks are by hand"; **ticket 10 found
  the opposite** and the file says what is true: upstream has been dormant
  since the fork commit, there is nothing to cherry-pick, and its open issue
  tracker is a list of bugs to check LeNews against, folded into tickets 12,
  15, 16, 17 and 18.
- **What still says Readrops, deliberately.** `CHANGELOG.md`,
  `code-review-02-09-2026.md`, `docs/research/`, and the tracker under
  `.scratch/`. **Correction to the ticket's premise:** there are no GPL
  copyright headers in this repo's Kotlin sources to keep — upstream shipped
  none (`grep -rl Copyright --include=*.kt app db api` is empty), and the only
  headers in the tree are the fork's own, on `scripts/` and `.github/`. So the
  attribution the GPL asks for lives in `LICENSE` and in what ticket 08 writes
  into `README.md`, and the file says that rather than describing headers that
  do not exist.
- **Hard constraints, each with how it is enforced today.** GrapheneOS/AOSP via
  G4 (`checkNoGoogleDependencies`: all three modules, full runtime classpath of
  every variant collected from the variant API, banned on the groups
  `com.google.android.gms` / `com.google.firebase` and on any module containing
  `play-services`, matched on coordinates so the three `com.google.*`
  dependencies in the graph today — Material Components, Accompanist, KSP — are
  deliberately allowed). `minSdk 31` / `targetSdk 35` / `compileSdk 35` with
  ticket 02 named and `targetSdk 36` marked as not yet specified. No personal
  email via G1, with all five of its checks, the allowlist and why each entry
  is there, the fork point `9ebbe038` as the commit-range boundary, the two
  historical-tree relaxations, and `fetch-depth: 0` in CI. Never commit a red
  gate. The access boundary: the personal account and the phone database are
  never touched, testing uses the `https://rss.lan` debug account of ticket 22
  (still open), the instrumented stage uses MockWebServer, and the attached
  phone is out of bounds.
- **The gate.** `scripts/check.sh` as the one-liner, then a G0–G7 table with
  the exact command each stage runs, read out of the script. Notes: G0 wants
  `platforms;android-35` exactly and the `bench-pixel6-aosp` AVD; G7 boots that
  AVD headless with `-gpu host -feature -GnssGrpcV1` and why the software
  renderer is not an option, pins `ANDROID_SERIAL=emulator-5554`, checks the
  AVD name before installing, kills only what it started; the emulator cannot
  be started from inside an agent's Bash sandbox on this machine, with the
  exact command to start it outside first; `SKIP_INSTRUMENTED=1`; the first run
  is slow; lint errors only, with the baseline's current counts (411 entries,
  347 errors, 64 warnings, and the five kinds); the `lintVitalRelease` noise;
  CI running the same stages and the drift rule; and that
  `scripts/codex-review.sh` and `scripts/android-sdk-path.sh` are not gates.
- **Working conventions.** Plain language, one ticket per fresh agent, one
  trunk `main`, worktrees under `.claude/worktrees/` merged `--no-ff`, never
  commit red, the co-author trailer rule with `Fable 5.1` as the current
  example, and the per-ticket bookkeeping.
- **Domain vocabulary.** Points at `CONTEXT.md` instead of repeating it, then
  the four facts sessions trip over: the 64-bit article id, hex in
  `stream/contents` and decimal in `stream/items/ids`; `ot` is inclusive and
  matches `lastModified` too, so re-delivery is normal and the sync must upsert;
  no server-side read timestamp, so History is local; and `useSeparateState =
  true` for `FRESHRSS`, so read state is in `ItemState` / `ItemStateChange` and
  `Item.read` means nothing. Then an explicit **pending** list — tickets 12, 13,
  15, 16 and the locales call — rather than describing the future.
- **Tickets and bookkeeping**, **Environment.** `.scratch/appropriate-and-fix/`,
  routing by `Type:`, the after-ticket bookkeeping; `$ANDROID_HOME`,
  `$JAVA_HOME` (Temurin 21), the French locale, `local.properties`' `sdk.dir`
  and the three `debug.freshrss.*` keys `app/build.gradle.kts` reads, and the
  code review file at the root.

No GPL header: `CLAUDE.md` is documentation.

### Verified

Every command the file names was run from this worktree, and each did what the
file says:

- `scripts/check.sh` — green G0 to G7. G7 found the orchestrator's emulator
  already on `emulator-5554`, used it and left it running, exactly as the file
  describes.
- `SKIP_INSTRUMENTED=1 scripts/check.sh` — green, G7 skipped with its message,
  and it is where the baseline counts in the file come from: lint reported
  "no new issues (and 347 errors and 64 warnings filtered by baseline)".
- `scripts/check-preflight.sh`, `scripts/check-no-personal-email.sh` — both
  exit 0 on their own.
- `scripts/android-sdk-path.sh` — prints `/home/skynet/dev/android/sdk`.
- `scripts/codex-review.sh` — prints its usage line.
- The six Gradle stage commands in the table are the ones `check.sh` runs, so
  the gate run covers them.

The one command not run as written is the emulator line: the orchestrator had
already booted `bench-pixel6-aosp` on `emulator-5554`, and running it again
would start a second emulator, which the standing rules forbid. It is verbatim
the invocation `scripts/check.sh` uses, and G7's own boot path is exercised by
any gate run that starts from no emulator.
