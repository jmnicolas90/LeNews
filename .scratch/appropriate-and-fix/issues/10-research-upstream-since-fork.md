# 10 — Research: what upstream changed since the fork point that LeNews should know about

Type: research
Status: resolved
Blocked by: —

## Question

The fork is at `9ebbe038` (2025-07-20, upstream `develop`). Upstream was active then, and about fourteen months have passed. Some of what it did since may already fix or touch what this map is about, and a hard fork cherry-picks by hand only if it knows what exists.

Survey `readrops/Readrops` on GitHub — commits on `develop` and `master` since 2025-07-20, releases, and issues/PRs — and report, with links:

1. **Duplicates.** Any issue, commit or release note about duplicated articles for FreshRSS/GReader accounts, and what upstream did about it (uniqueness constraint? existence check? cursor change?).
2. **Performance.** Anything about the timeline slowing down, `ItemState` joins, indexes on `Item`, item retention or deletion, or paging.
3. **Sync.** Changes to `GReaderRepository`, `GReaderDataSource`, the `ItemState` model, `insertItemsIds`, or the sync cursor.
4. **Tags.** The fork point is mid-feature (tags fetched and displayed). What did upstream finish, and did it keep the per-item tag query?
5. **Schema.** Room version and migrations added after version 6; anything that changed `Item` or `ItemState` columns.
6. **Security.** Any change to `AuthInterceptor`, `ItemWebView` JavaScript, or `network_security_config.xml`.
7. Anything else clearly relevant to a FreshRSS-only client: dependency upgrades that fix bugs LeNews would hit (Room, Paging, WorkManager, OkHttp), `targetSdk` bumps.

For each item say whether it looks worth cherry-picking, worth re-implementing in LeNews's simplified model, or irrelevant after the services are deleted. Do not cherry-pick anything; this ticket only reports.

Capture as `docs/research/upstream-since-fork.md`. Commit on a `research/upstream-since-fork` branch in a worktree of your own; do not touch `develop`.

**Done when** the file exists with links for every claim and a one-paragraph summary at the top, and ticket 12 can be grilled against it.

## Answer (2026-09-05)

Findings: `docs/research/upstream-since-fork.md` on branch `research/upstream-since-fork`. Merge into the trunk when convenient; until then `git show research/upstream-since-fork:docs/research/upstream-since-fork.md`.

**Upstream is dormant since the fork commit.** `pushed_at` is `2025-07-20T16:49:42Z`, the fork commit's own timestamp; `develop` still points at `9ebbe038`, `master` at `dcd7a9f3` (v2.1.1, 2025-07-07); no release after v2.1.1; every other branch predates 2024. There is nothing to cherry-pick: no commit, no merged PR, no schema change, no dependency bump, no security fix. The map's premise that upstream was active with ~14 months of `develop` since the fork was wrong; corrected in the map.

**Upstream `develop` is broken as left.** The tag commits bumped Room to version 6 and exported `6.json`, but no 5→6 migration was ever registered, so a v2.1.1 install upgraded to this code refuses to open its database. Moot for LeNews (schema reset, ticket 13), but it means the fork point is mid-feature and untested.

**Tracker items relevant to a FreshRSS-only client** (all still open, none fixed):
- #341 mark-all-read does not work with FreshRSS → ticket 16 must reproduce it before wiring the history route.
- #359 sync slows down because old items are never deleted → another user's statement of pain point 1; supports ticket 15.
- #354/#357/#358/#355/#353 `FileUriExposedException` when tapping links inside the article WebView → folded into ticket 17 (scheme-check before `startActivity`).
- #360 default `okhttp/4.12.0` User-Agent is 403'd by common blocklists → folded into ticket 18.
- #334 Google Reader `ClientLogin` sent as `multipart/form-data`, which Miniflux rejects → folded into ticket 18 (form-urlencoded).
- #347 "[Security] Important data breach", content never disclosed → unknowable; noted.
- PR #306 / issue #282 FreshRSS initial-sync item limits; FreshRSS supports `c=` continuation → input to ticket 12 (initial sync should page, not cap at 2500).
- PR #363 swipe-to-page toggle → the only thing worth cherry-picking, and only after ticket 13 settles the item screen.

Nobody upstream reported duplicated articles for FreshRSS; that diagnosis remains LeNews's own (ticket 09's `ot` semantics explain it). Dependency baselines upstream never took: Room 2.8.4, Paging 3.5.1, WorkManager 2.11.2, OkHttp 5.5.0.

Could not fetch: FreshRSS discussion #7759 (GitHub Discussions need GraphQL), and the private security report behind #347.
