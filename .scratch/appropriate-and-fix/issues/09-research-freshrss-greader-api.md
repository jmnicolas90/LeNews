# 09 — Research: what the FreshRSS Google Reader API actually guarantees

Type: research
Status: resolved
Blocked by: —

## Question

Three of this map's decisions hang on facts about the server that the code assumes rather than knows. Find them in FreshRSS's own source and documentation (`FreshRSS/FreshRSS` on GitHub, `p/api/greader.php` and the docs under `docs/en/developers/`), not in third-party write-ups. For each, cite the file and line or the doc section.

1. **Article identity.** What is the `id` FreshRSS returns for an item in `stream/contents` and `stream/items/ids` — the long-form `tag:google.com,2005:reader/item/<hex>` or the short decimal — is it stable across an article's lifetime, and can two articles ever share it? Does the id survive the article being updated by a feed refresh?
2. **The `ot` parameter.** `stream/contents/.../reading-list?ot=<seconds>` is what the sync uses with `account.lastModified`. Is `ot` compared against the article's publication date or against FreshRSS's crawl/insert time (`lastSeen`)? Is the comparison `>=` or `>`? Can an article FreshRSS already returned be returned again by a later call with a later `ot` (e.g. because a feed refresh bumped it)? What are the caps on `n`, and how does `c`/`continuation` work?
3. **What the server still has.** For the mirror rule, the client needs the set of ids FreshRSS currently holds. `stream/items/ids` with `s=user/-/state/com.google/reading-list` — what is its maximum `n`, does it paginate with `continuation`, and does it include read articles? What is FreshRSS's own purge policy (per-feed keep count, age), and is it exposed through the API?
4. **Read timestamps.** Does the API expose when an article was marked read, anywhere (item fields, `stream/items/contents`, tags)? The map assumes not; confirm it.
5. **Write token and edit-tag.** `edit-tag` with `a=`/`r=` for read/starred: batching limits, and whether a failed batch is atomic.

Capture as `docs/research/freshrss-greader-api.md`, one section per question, each claim with its source. Commit on a `research/freshrss-greader-api` branch in a worktree of your own; do not touch `develop`.

**Done when** the file answers all five with citations, and ticket 12 can be grilled against it.

## Answer (2026-09-05)

Findings: `docs/research/freshrss-greader-api.md` on branch `research/freshrss-greader-api` (404 lines, every claim a permalink into FreshRSS `edge` at `e5f99067`, 2026-09-03). Merge the branch into the trunk when convenient; until then read it with `git show research/freshrss-greader-api:docs/research/freshrss-greader-api.md`.

1. **Identity.** `_entry.id` is a 64-bit integer built from a microsecond discovery timestamp, renumbered once at batch commit, never changed after. A feed refresh updates the row in place by `(id_feed, guid)`, so the id survives content edits and is unique by primary key. `stream/contents` returns it as `tag:google.com,2005:reader/item/<16 hex digits>`, `stream/items/ids` as the plain decimal, and every write endpoint accepts either. The client's `GReaderItemsIdsAdapter` pads hex to the decimal string's length rather than 16; works in practice, but the decimal integer is the safer key.
2. **`ot`.** Not compared to the publication date and not to `lastSeen`. The filter is `id >= ot·10⁶` (discovery time encoded in the id) **or** `lastModified >= ot`, both inclusive. `lastModified` is bumped only when a refresh finds the content hash changed. So an article already fetched comes back on a later `ot` call exactly when it was edited upstream, and a replayed `ot` always returns the boundary article again. **Re-delivery is normal, not exceptional; the client must be idempotent.** `n` has no server cap (default 20, straight into SQL `LIMIT`); `c`/`continuation` is keyset pagination on the id.
3. **What the server still has.** `stream/items/ids?s=…/reading-list` includes read articles, has no cap, paginates by `continuation`, excludes feeds whose visibility is "hidden". Purge policy (default keep 3 months, at most 200, at least 50 per feed, never favourites or labelled, keyed on `lastSeen`) is not exposed by the API. So the mirror rule is implementable: page the full reading-list id set each sync and drop what is absent.
4. **Read timestamps.** The server stores `lastUserModified` on read/star changes but no API output includes it. Confirmed: the history timestamp is local only.
5. **Writes.** The write token is a deterministic hash that never expires. `edit-tag` is not atomic: ids are applied in statements of at most 998 ids without a wrapping transaction, and the response is `OK` even when a statement failed. Request bodies are truncated at 1 MiB with no error. Send batches of ≤998 and verify by re-reading state.

Not established: whether a self-hosted non-Docker deployment or reverse proxy caps the request body below 1 MiB; the absence of caps on `n` and batch size is a reading of the code, not a documented guarantee.
