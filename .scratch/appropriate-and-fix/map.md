# Appropriate the fork as LeNews and fix what hurts

Label: wayfinder:map
Charted: 2026-09-05

## Destination

A repo that is unmistakably **LeNews** — its own name, package, identity and docs, carrying no trace of upstream ownership beyond the GPL attribution it is legally obliged to keep — that is a **FreshRSS-only, single-account** Android client, builds and passes a **green, executable quality gate** both on this machine and in CI (instrumented database tests included), in which **every finding of the 02-09-2026 code review** has been either fixed or consciously ruled out of scope, and in which the **three pain points are gone**: the app no longer slows down as articles accumulate, an article is never stored twice, and there is a chronological **history** of everything that became read so a dismissed article can be found again.

## Notes

**Domain.** Three-module Android app (`api`, `db`, `app`), Kotlin, Jetpack Compose, Room, WorkManager, OkHttp/Retrofit, Koin. GPL-3.0 hard fork of `readrops/Readrops` at `9ebbe038` (2025-07-20, `develop`, v2.1.1 plus in-progress tag support). Upstream has been dormant since the fork commit itself (ticket 10): `develop` still points at `9ebbe038`, no release after v2.1.1. It is never merged again; there is nothing to cherry-pick, and its open issue tracker is a list of bugs to check LeNews against, not a source of patches. Source review: `code-review-02-09-2026.md` at the repo root. Glossary: `CONTEXT.md`.

**Tracker.** Local markdown, this directory. Map here, one file per ticket under `issues/`, `Type:` / `Status:` / `Blocked by:` lines near the top. Same layout as `../Ding/.scratch/appropriate-and-fix/`.

**This map carries execution.** As in Ding, tickets deliver working, gate-green changes, not just decisions. The user's brief was to *do* the appropriation and *fix* the pain points. `grilling` tickets are the exception: they resolve a decision, and their implementation is a separate ticket. `research` tickets are resolved by a background research agent and only produce a findings file.

**Which skill works which ticket.** Routing is per ticket, from its `Type:` line — do not re-enter `/wayfinder` for the whole map.

- **`Type: grilling`** (12) → **`/wayfinder`**. Genuine fog; the answer does not exist yet.
- **`Type: research`** (09, 10) → **`/research`** background agent, findings committed on a `research/<slug>` branch as `docs/research/<slug>.md`.
- **`Type: task`** (everything else) → **`/implement`**. The decision was already made during charting. `/implement` drives `/tdd` where there is a seam, then `/code-review`s the diff before committing. Ticket 01 is two `git config` lines; just do it.

Whichever route, do the bookkeeping by hand afterwards: set `Status: resolved` on the ticket and append one line to *Decisions so far* below.

**Access boundary (stated by the user 2026-09-05).** The user's personal FreshRSS account and the Readrops database on their phone are **never** to be accessed, read, copied or synced against, by any ticket, for any reason. Testing against a real server uses a separate debug account on the user's own FreshRSS at `https://rss.lan` (192.168.101.2; LAN and VPN only; Caddy local CA with 12-hour leaf certificates, root trusted by this machine; ticket 22 sets the account up and stores its credentials in the gitignored `local.properties`). The instrumented gate stage uses MockWebServer and never needs the network.

**Skills every session should consult.** `/grilling` and `/domain-modeling` for any `grilling` ticket; `/tdd` for anything touching the database schema, sync, or retention; `/diagnosing-bugs` for ticket 11.

**Hard constraints** (carried over from Ding unchanged, decided 2026-09-05).
- **GrapheneOS / AOSP compatible, always.** No Google Play Services, GMS or Firebase dependency may ever enter the graph. The graph is free of them today (`com.google.android.material`, `com.google.accompanist` and the KSP plugin are not GMS); this is a property to keep, enforced by a gate stage.
- **`minSdk 31`**, `targetSdk 35` today, raised when the SDK is.
- **No personal email address anywhere** — not in the tree, not in commit metadata, not in published artifacts. Commits use the GitHub no-reply address configured repo-locally (ticket 01).
- **Never commit a red gate.**

**Working conventions** (Ding's, unchanged).
- Plain language, no invented jargon. Name things by what they do.
- A ticket must fit one fresh agent at low context. If it looks bigger, split it first.
- **One long-lived branch, `main`.** Upstream's git-flow `develop`/`master` split is retired (ticket 03 does the switch). Code changes happen in short-lived worktrees on ticket branches under `.claude/worktrees/` (gitignored), one ticket each, merged back into `main` with `--no-ff` once the gate is green. Releases are tags.
- Commit trailer names the model that actually wrote the code, at its own vendor's no-reply address: `Co-authored-by: Fable 5.1 <noreply@anthropic.com>` for this one. A non-Anthropic model uses its own name and vendor address.

### Settled while charting

Decided in conversation on 2026-09-05. Not tickets.

- **Scope** — Ding's shape plus the three pain points: repo is ours, gate is green, every review finding fixed or ruled out, slowness, duplicates and history solved. Feature work beyond that is a later map.
- **Name** — **LeNews**. `applicationId` and `namespace` become `app.lenews`, source package `app.lenews.*`, house style of `app.ding` / `app.loquace`. Renaming the applicationId makes the phone treat it as a new app: fresh install, re-login, full re-sync. That is accepted, and it means **no migration from the Readrops schema is ever needed** — the schema can be reset.
- **Hard fork** — upstream is never merged again. Charting assumed upstream was still active with ~14 months of `develop` to survey; ticket 10 found it went dormant at the fork commit, so the hard fork costs nothing and every fix is LeNews's own.
- **FreshRSS only** — local RSS parsing, Nextcloud News and the Fever API are deleted (ticket 04). The Google Reader API code path stays, since that is how FreshRSS is spoken to.
- **Single account** — one FreshRSS account, so the account layer and the per-account separate read-state table can collapse. The account screen becomes a login screen. Exact model is ticket 12's to decide.
- **History** — *every* transition to read enters history: opening an article, swiping it, mark-all-read, and a read observed at sync (read on the FreshRSS web UI) stamped with the sync time. One chronological list, newest first. The timestamp is local only; the Google Reader API cannot store it. The user accepts that mark-all-read at 300 articles a day fills the list — history is simply "everything that became read, in order".
- **Retention** — the local store **mirrors FreshRSS**: an article FreshRSS no longer returns is dropped locally. **History horizon: 30 days** — nothing read is kept locally past 30 days, whatever the server still has. The user does not search for something seen months ago.
- **Slowness diagnosis** — the installed app is a store build and the user's database is off limits regardless, so nothing real is pulled. Diagnosis runs on an emulator against a database seeded with a year of articles (ticket 11). The code already names the suspects: `Item` has a single index (`feed_id`); FreshRSS accounts read state through `LEFT JOIN ItemState ON Item.remote_id = ItemState.remote_id` with no index on `Item.remote_id`; items are never deleted; every sync deletes and reinserts all `ItemState` rows for the account.
- **Duplicates** — the sync inserts every returned item with no existence check and no uniqueness constraint; the sync cursor (`account.lastModified`) is advanced only after all inserts, so any failure in between replays the same items next time. Ticket 09 then showed re-delivery is *normal*: FreshRSS's `ot` filter is inclusive and also matches articles whose content changed since, so the boundary article comes back on every sync and any edited article comes back once more. One account, so the review's multi-account join theory does not apply here.
- **Gate** — Ding's `scripts/check.sh` shape plus a **final instrumented stage** on the existing `bench-pixel6-aosp` emulator (API 36, pure AOSP), mirrored in CI on GitHub's emulator runner. All three pain points are database-level and Room migration/DAO/query tests only run on a device.
- **Distribution** — public GitHub releases, as Ding. Not the Play Store, not F-Droid.
- **Tracker** — local markdown under `.scratch/`, committed.

## Decisions so far

<!-- one line per resolved ticket: gist, then the link for the detail the ticket holds -->

- [Commit with a no-reply address in this repo](issues/01-git-identity-no-reply-address.md) — `user.email` set repo-locally to the GitHub no-reply address, `user.name` kept; global config untouched, no history rewritten. First fork-authored commit is the one that lands the tracker.
- [Research: what the FreshRSS Google Reader API actually guarantees](issues/09-research-freshrss-greader-api.md) — findings on branch `research/freshrss-greader-api`, `docs/research/freshrss-greader-api.md`, every claim a permalink into FreshRSS source. **The article id is a 64-bit integer**, unique by primary key, stable across content updates (hex in `stream/contents`, decimal in `stream/items/ids`, both accepted on write). **`ot` is inclusive and matches `id >= ot·10⁶` or `lastModified >= ot`**, so the boundary article and any article edited upstream are re-delivered as a matter of course: the client must be idempotent, re-delivery is not a failure. `stream/items/ids` for the reading list includes read articles, has no cap and pages with `continuation`, which makes the mirror rule implementable; FreshRSS's purge policy is not exposed. **No API output carries a read timestamp**; history is local only, confirmed. `edit-tag` is non-atomic in ≤998-id statements and answers `OK` regardless; bodies truncate at 1 MiB silently.
- [Research: what upstream changed since the fork point](issues/10-research-upstream-since-fork.md) — findings on branch `research/upstream-since-fork`, `docs/research/upstream-since-fork.md`. **Upstream is dormant since the fork commit**: nothing to cherry-pick, no release after v2.1.1, and `develop` as left cannot open a v2.1.1 database (Room bumped to 6 with no 5→6 migration). Open upstream issues folded into tickets: mark-all-read broken with FreshRSS (#341 → 16), old items never deleted (#359 → 15), `FileUriExposedException` on WebView links (five reports → 17), blocklisted default User-Agent and multipart `ClientLogin` (#360, #334 → 18), initial-sync item cap with FreshRSS `continuation` available (#282 → 12). Only PR #363 (swipe-to-page toggle) is worth cherry-picking, after ticket 13.
- [minSdk raised from 21 to 31](issues/02-raise-minsdk-to-31.md) — `minSdk = 31` for all three modules, and what the raise made dead is gone: the API 26 guards around notification channels, the adaptive-icon painter and the battery-optimization preference, `tools:targetApi` in the manifest, the SyncWorker notification priority line "for Android 7.1 and earlier", a dead `NewApi` suppression in `DateUtils`, and `db`'s `mipmap-anydpi-v26` folder folded into `mipmap-anydpi`. A review round then removed two more shims the floor made dead: core library desugaring in all four build files plus its catalog alias (`java.time`, `java.util.stream` and `java.nio.file` are native from API 26), and the `WRITE_EXTERNAL_STORAGE` / `requestLegacyExternalStorage` pair in `api`'s debug manifest, which the platform ignores under scoped storage. Lint went from 5 `ObsoleteSdkInt` findings to 0 and raised no `NewApi` in their place. The two `TIRAMISU` checks stay: 33 is above the floor and they gate the notification permission.
- [An executable quality gate, locally and in CI](issues/03-executable-quality-gate.md) — `scripts/check.sh`, eight fail-fast stages: G0 preflight, G1 email guard, G2 lint, G3 unit tests, G4 Google guard, G5 debug APK, G6 release APK, G7 instrumented tests on `bench-pixel6-aosp`, skippable with `SKIP_INSTRUMENTED=1`. `.github/workflows/ci.yml` runs the same eight in the same order and replaces `android.yml`; every action pinned to a commit SHA, no codecov. **Lint is blocking now**: 1 error fixed (a Flow operator in composition), 227 errors and 47 warnings baselined in app, all of them translation debt waiting on a product call; api and db had no errors. **G1** edited seven fixture addresses rather than widen the allowlist, found that the three "addresses" in Kotlin source are qualified-`this` labels and taught the guard to tell them apart, and redacted upstream's contact address from ticket 10's findings. **G4 proven red** by a planted `play-services-base`, which it named in all three variants along with its two transitive dependencies. **G7** found four genuinely broken instrumented tests: `POST_NOTIFICATIONS` is granted by the test rules now, not by a CI line, and `TestApplication` creates the notification channel the system was silently dropping notifications for. The `classicSyncTest` flake was a racy `++` on a MockWebServer dispatcher thread, not timing.

## Not yet specified

- **What the history list looks like.** A mode of the timeline sorted by read time, or its own tab? Does it show the read-time or the publication date, and does it distinguish opened from swiped from synced? Sharpens once ticket 12 has decided how the read timestamp is stored; may graduate into a `prototype` ticket.
- **Translations.** Upstream ships 14 languages via Weblate. Deleting three services orphans many strings and the Weblate link goes with the scrub (ticket 06). Keep all, or English + French only? A product call nobody has made.
- **Tags.** The fork point sits mid-feature: FreshRSS tags are fetched, stored and displayed in the timeline (last five upstream commits), and ticket 10 found upstream never finished it (no migration was even registered). Whether LeNews keeps tags, and whether the per-item tag query (review finding) is fixed or the feature dropped, is a product call; ticket 12 should take it when it decides the entities, since `Tag`/`TagJoin` are part of the schema reset.
- **New-article notifications.** Readrops notifies on new items after background sync. Untouched by the destination, but the sync analyzer is per-account code that ticket 13's single-account collapse will brush against. Keep as is, unless it gets in the way.
- **Release signing and publishing.** A keystore has to exist, live somewhere safe, and be reachable from a release process without leaking into the repo. Sharpens once the rename lands and there is a first release worth cutting.
- **Regenerating screenshots and store metadata** under the new name. `fastlane/metadata` is upstream's and is deleted by ticket 06; what replaces it, if anything, is undecided.
- **`targetSdk 36`.** `compileSdk`/`targetSdk` are 35. Ding raised to 36 as its own ticket; same here, once the gate exists to catch what it breaks.

## Out of scope

- **Staying mergeable with upstream** — hard fork. No design tax is paid to keep `git merge upstream/develop` viable.
- **Local RSS, Nextcloud News, Fever** — deleted, not maintained. Review findings that live only in that code fall out with it: `LocalRSSDataSource` response leaks, `FeverFaviconFetcher` leaks, `FeverDataSource.login` leak, the Fever-only share-to-add-feed crash, the OPML-import empty-folder crash (OPML import creates a local account).
- **Multi-account** — single FreshRSS account. Review findings whose failure mode needs two accounts fall out: the account-unscoped `ItemState` joins across six query paths, and the cross-account `updateReadAndStarState`. Ticket 13 removes the joins rather than scoping them.
- **A migration from the Readrops database** — the applicationId changes, nothing upgrades in place, the schema is reset. Nobody has to merge duplicate rows.
- **Play Store and F-Droid publishing** — distribution is GitHub releases.
- **Feature work beyond the three pain points** — anything the user wants next in a FreshRSS client is the *next* map.
