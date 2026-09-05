# What the FreshRSS Google Reader API actually guarantees

Research for ticket 09 of the *appropriate-and-fix* map. Every claim below was
read in FreshRSS's own source or documentation. All links point at the `edge`
branch pinned to commit `e5f99067814471cd6b1e0990f01a280a569a5300`
(2026-09-03), so line numbers stay valid. Readrops/LeNews talks to
`p/api/greader.php`; the SQL behind it lives in `app/Models/EntryDAO.php`, the
feed-refresh loop that creates and updates articles in
`app/Controllers/feedController.php`.

## Summary

An article's identity on FreshRSS is the `_entry.id` column: a 64-bit integer
built from a microsecond timestamp when the article is first discovered,
renumbered once when the batch is committed, and never changed afterwards —
feed refreshes update the row in place by `(id_feed, guid)`, so the id survives
content updates and is unique by primary key. `stream/contents` returns it as
`tag:google.com,2005:reader/item/<16 hex digits>`, `stream/items/ids` as the
plain decimal, and every write endpoint accepts either form. The `ot`
parameter is *not* compared to the publication date: it is `id >= ot·10⁶`
(discovery time encoded in the id) **OR** `lastModified >= ot`, both inclusive,
and `lastModified` is only bumped when a refresh finds the article's content
hash changed — so an article already fetched is returned again by a later `ot`
call exactly when it was edited upstream, and a replayed `ot` always returns
the boundary article again. `n` has no server-side cap (default 20, straight
into SQL `LIMIT`), and `c`/`continuation` is keyset pagination on the id.
`stream/items/ids?s=…/reading-list` includes read articles, has no cap, and
paginates the same way; it excludes feeds whose visibility is "hidden". The
purge policy (default: keep 3 months, at most 200, at least 50 per feed, never
favourites or labelled, based on `lastSeen` = last time the article was still
in the upstream feed) is not exposed by the API in any way. The server stores
a `lastUserModified` timestamp when an article is marked read/unread or
starred/unstarred, but no API output includes it — the read timestamp is not
observable from a client. The write token is a deterministic hash that never
expires, and `edit-tag` is not atomic: ids are applied in SQL statements of at
most 998 ids each, without a wrapping transaction, and the response is always
`OK` even when a statement failed.

## What this means for LeNews

- **Identity rule.** Key articles by the decimal FreshRSS id (a 64-bit
  integer). Normalise the long form by stripping
  `tag:google.com,2005:reader/item/` and parsing the 16 hex digits — that is
  exactly what the server does on input. The id is a primary key, stable across
  content updates, so a uniqueness constraint on it is safe and an upsert is
  the correct write. The only way the same upstream article gets a second id
  is if FreshRSS itself created a second row (purged then rediscovered, feed
  re-added, or the feed's "unicity criteria" setting changed) — from the
  client's point of view that *is* a new article.
- **Replay-safe sync.** Because `ot` is inclusive (`>=`) and also matches on
  `lastModified`, a `stream/contents?ot=<cursor>` call can legitimately return
  articles the client already has: the boundary article on every call, and any
  article edited upstream since. The sync must therefore upsert by id, never
  insert. Advancing the cursor only after all inserts succeed remains right;
  with upserts a crash between "server answered" and "cursor advanced" costs a
  re-download, not duplicates. Use `crawlTimeMsec`/`timestampUsec` (or the id
  itself) rather than `published` for the cursor, since `ot` is compared to
  discovery time, not publication date. Use `c` when `n` items came back.
- **Mirror rule.** `stream/items/ids?s=user/-/state/com.google/reading-list&n=…`
  with no `xt` is the complete set of ids FreshRSS still holds in non-hidden
  feeds (read and unread), paginated by `continuation`. Page until no
  `continuation` is returned; then anything local not in that set has been
  purged (or its feed hidden/deleted) and can be dropped. The purge policy is
  not readable through the API, so the mirror can only be observed, not
  predicted.
- **History timestamp.** Confirmed: the API never exposes when an article
  became read. The server has `lastUserModified`, but no output field carries
  it and it is overwritten by star/unstar too. The local, sync-time-stamped
  history is the only option.
- **Write path.** Cache the write token for the session; it only changes if
  the API password or the server salt changes, and the server even accepts an
  empty token. Send `edit-tag` in batches of at most 998 ids so each batch is a
  single atomic SQL statement, and treat `OK` as "accepted", not "applied":
  the next `stream/items/ids?xt=read` pass is the confirmation. Keep request
  bodies well under 1 MiB — the server truncates the body there and silently
  drops the ids past the cut.

## 1. Article identity

**The id is a 64-bit integer primary key; two articles never share it.**
`_entry.id BIGINT NOT NULL`, `PRIMARY KEY (id)`, plus `UNIQUE KEY (id_feed, guid)`.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/SQL/install.sql.mysql.php#L43-L62

**The id is assigned when the article is first discovered, from a microsecond
timestamp.** In the refresh loop a new entry gets `$id = uTimeString();`
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/Controllers/feedController.php#L692-L695
and `uTimeString()` is Unix seconds concatenated with zero-padded microseconds.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/lib/lib_rss.php#L358-L363

**New articles are staged in `_entrytmp` and renumbered once at commit.**
`addEntry(..., useTmpTable: true)` inserts into `_entrytmp`
(https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/Controllers/feedController.php#L733
and https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/Models/EntryDAO.php#L192-L200);
`commitNewEntries()` moves them to `_entry` with `id = MAX(id) - COUNT(*) + rank`
ordered by `date, id` — so within one commit batch the final ids are consecutive
integers just below the largest staged timestamp, ordered by publication date.
MySQL: https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/Models/EntryDAO.php#L265-L289 ·
SQLite: https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/Models/EntryDAOSQLite.php#L78-L105 ·
PostgreSQL: https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/Models/EntryDAOPGSQL.php#L86-L111.
Consequence: the id is *approximately* the discovery time in microseconds (the
API derives `crawlTimeMsec` and `timestampUsec` from it, see below), and it is
final once committed.

**`stream/contents` returns the long form; `stream/items/ids` returns the
decimal.** `FreshRSS_Entry::toGReader()` emits
`'id' => 'tag:google.com,2005:reader/item/' . self::dec2hex($this->id())`,
with `crawlTimeMsec` and `timestampUsec` computed from the same id and
`published` from the article's own date.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/Models/Entry.php#L1224-L1235
`dec2hex()` zero-pads to 16 hex digits.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/Models/Entry.php#L1200-L1209
`streamContentsItemsIds()` emits `'id' => '' . $entryId // 64-bit decimal`.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/p/api/greader.php#L834-L837

**Every input accepts both forms.** `edit-tag` and `stream/items/contents`
run each id through `hex2dec(basename($e_id))` unless it is all digits with no
leading zero.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/p/api/greader.php#L908-L913 ·
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/p/api/greader.php#L860-L864 ·
`hex2dec()`: https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/p/api/greader.php#L36-L51

**The id survives a feed refresh that updates the article.** Existing
articles are recognised by `guid` within the feed
(`listHashForFeedGuids($feed->id(), $newGuids)`),
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/Controllers/feedController.php#L630-L632
and, when the content hash differs, updated with
`UPDATE _entry SET title=…, content…, date=…, lastSeen=…, lastModified=COALESCE(…) … WHERE id_feed=:id_feed AND guid=:guid` —
the `id` column is not in the SET list.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/Controllers/feedController.php#L644-L690 ·
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/Models/EntryDAO.php#L297-L325
When the hash is unchanged only `lastSeen` is touched.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/Controllers/feedController.php#L738-L743

**What "same article" means to FreshRSS.** The guid is the feed item's id,
or, per feed setting `unicityCriteria`, the link or a SHA-1 of chosen fields.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/Models/Feed.php#L699-L735
A duplicate `(id_feed, guid)` insert is ignored (`sqlIgnoreConflict`; constraint
violations are expected and filtered from the log).
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/Models/EntryDAO.php#L193-L194 ·
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/Models/EntryDAO.php#L250-L258
Inference from the schema, not a documented promise: the same upstream item
appearing in two different subscribed feeds is two rows with two ids; an
article purged and later rediscovered gets a new id.

**Client note.** The Readrops `GReaderItemsIdsAdapter` rebuilds the long form
from the decimal with `toString(16).padStart(value.length, '0')` — padding to
the decimal string's length (16 digits for any id between 2001 and 2286)
rather than to 16 explicitly. It matches the server's 16-digit padding in
practice, but the safer identity key is the decimal integer.
`api/src/main/java/com/readrops/api/services/greader/adapters/GReaderItemsIdsAdapter.kt`, lines 31-35.

## 2. The `ot` parameter

**Parsing.** `ot` and `nt` are read as integers (0 when absent), `n` defaults
to 20 and is an unbounded integer, `r` defaults to `d`, `c` must be all digits
or is treated as `0`.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/p/api/greader.php#L1172-L1191

**`ot` is compared to the discovery time encoded in the id OR to
`lastModified`, never to the publication date, and both comparisons are
inclusive.** `streamContentsFilters()` adds two `FreshRSS_Search` objects when
`ot != 0`: one with `setMinDate($start_time)`, one with
`setMinModifiedDate($start_time)`, commented `// OR`.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/p/api/greader.php#L669-L684
Plain searches inside a `FreshRSS_BooleanSearch` are combined by OR.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/Models/EntryDAO.php#L957-L984
`getMinDate()` becomes `AND e.id >= ?` with value `"{ot}000000"`, i.e.
`id >= ot·10⁶`; `getMinModifiedDate()` becomes `AND e.lastModified >= ?`.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/Models/EntryDAO.php#L1004-L1007 ·
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/Models/EntryDAO.php#L1020-L1023
(The publication-date filter `date >= ?` exists as `getMinPubdate()` but the
API never sets it.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/Models/EntryDAO.php#L1012-L1015)
`nt` is the mirror image, combined with AND: `id <= nt·10⁶ AND COALESCE(lastModified,0) <= nt`.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/p/api/greader.php#L678-L684 ·
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/Models/EntryDAO.php#L1008-L1011 ·
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/Models/EntryDAO.php#L1024-L1027

**Note on `lastSeen`.** The ticket asked whether `ot` compares to crawl time
(`lastSeen`). It does not use the `lastSeen` column at all; the "crawl time" it
uses is the one frozen into the id at discovery (see §1), which is also what
the API reports as `crawlTimeMsec`. `lastSeen` is only used by the purge (§3).

**When `lastModified` moves.** It is set to the refresh time only on the
"already exists but has been updated" branch, i.e. when the stored hash
differs from the freshly computed one.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/Controllers/feedController.php#L644-L649
The hash covers link, title, authors, original content, tags and enclosures
— explicitly not the date.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/Models/Entry.php#L513-L520
Re-seeing an unchanged article updates `lastSeen` only.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/Controllers/feedController.php#L738-L743 ·
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/Models/EntryDAO.php#L1941-L1961
Marking read or starred writes `lastUserModified`, not `lastModified`.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/Models/EntryDAO.php#L517-L523

**Therefore:** an article FreshRSS already returned *can* be returned again by
a later call with a later `ot` — exactly when a feed refresh found its content
changed (`lastModified >= ot`). It is also returned again whenever the cursor
equals its own discovery second (`>=`). It is *not* returned again merely
because it was re-seen in the feed, read, or starred. Since the date is
excluded from the hash, an upstream `<updated>` bump with no other change does
not cause a re-send.

**Caps on `n`.** There are none in FreshRSS: `n` goes straight from the query
string to the `limit` argument and into SQL `LIMIT n` (omitted when `n <= 0`).
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/p/api/greader.php#L717-L721 ·
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/Models/EntryDAO.php#L1646-L1648
Practical limits: `stream/contents` materialises the full result with
`iterator_to_array` before streaming it out (memory),
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/p/api/greader.php#L585-L597
and each item's `summary.content` is cut at 500 000 bytes.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/Models/Entry.php#L1211-L1216
The maintainer's synchronisation recommendation (linked from the official API
page) shows `n=1000` for `stream/contents` and `n=10000` for `stream/items/ids`.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/docs/en/developers/06_GoogleReader_API.md ("Synchronisation strategy") ·
https://github.com/FreshRSS/FreshRSS/issues/2566#issuecomment-541317776

**How `c`/`continuation` works.** Keyset pagination on the id. When `c != 0`
the server asks for `n+1` rows with `continuation_id = c`, then discards the
first row (which was the last row of the previous page); a `continuation`
value equal to the last row's decimal id is emitted only when the page is full
(`nbItems >= count`).
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/p/api/greader.php#L713-L728 ·
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/p/api/greader.php#L750-L763
In SQL, with the default id sort, `c` becomes `id <= c` for descending order
(`r=d`/`r=n`) or `id >= c` for ascending (`r=o`).
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/Models/EntryDAO.php#L1447-L1462 ·
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/p/api/greader.php#L719
Because new articles always get larger ids, a page walk is not disturbed by
articles arriving during the walk (descending order: they land before the
first page and are picked up by the next `ot` sync).

## 3. What the server still has

**`stream/items/ids` with `s=user/-/state/com.google/reading-list`.**
The stream maps to type `A`,
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/p/api/greader.php#L776-L784
which is "All except PRIORITY_HIDDEN": `f.priority >= PRIORITY_CATEGORY (0)`,
so feeds whose visibility is "hidden" (`PRIORITY_HIDDEN = -10`) are excluded.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/Models/EntryDAO.php#L1556-L1559 ·
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/Models/Feed.php#L38-L42
(`subscription/list` skips the same hidden feeds, so the two views agree.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/p/api/greader.php#L345-L349)

*Includes read articles:* the read/unread/starred state is only constrained
when `it` (filter) or `xt` (exclude) is given; with neither, `STATE_ALL`.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/p/api/greader.php#L651-L667

*Maximum `n`:* none, same parsing and same `LIMIT` as `stream/contents`
(§2). `ot`/`nt` also apply to this endpoint if sent.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/p/api/greader.php#L1240-L1243 ·
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/p/api/greader.php#L810-L821

*Pagination:* identical continuation mechanism; response is
`{"itemRefs":[{"id":"<decimal>"},…],"continuation":"<decimal>"}` with
`continuation` present only when the page is full.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/p/api/greader.php#L812-L852
Each `itemRef` carries only `id` (no `directStreamIds`, no `timestampUsec`).
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/p/api/greader.php#L833-L838

**FreshRSS's purge policy.** Defaults (user configuration `archiving`):
`keep_period = 'P3M'`, `keep_max = 200`, `keep_min = 50`,
`keep_favourites = true`, `keep_labels = true`, `keep_unreads = false`.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/config-user.default.php#L12-L19
Resolution order: feed attribute, else category attribute, else user default.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/Models/Feed.php#L1268-L1289 ·
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/docs/en/users/05_Configuration.md ("Archiving")

The SQL, per feed (`EntryDAO::cleanOldEntries`): delete rows of the feed that
are not favourites (if `keep_favourites`), not unread (if `keep_unreads`), not
labelled (if `keep_labels`), whose `lastSeen` is older than the `keep_min`-th
most recent `lastSeen` in the feed, **and** older than the feed's maximum
`lastSeen` (so articles seen at the last refresh are always kept), **and**
either older than `now - keep_period` or beyond the `keep_max`-th most recent.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/Models/EntryDAO.php#L797-L870

Age is measured on `lastSeen`, which is set to the refresh time for every
article still present in the fetched feed (also when the feed body is
unchanged), so an article's purge clock only starts when it drops out of the
upstream feed.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/Controllers/feedController.php#L738-L743 ·
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/Models/EntryDAO.php#L1941-L2007
The user docs describe this as "based on the date it was discovered by
FreshRSS, not its published date. An article still present in the upstream
feed is always kept".
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/docs/en/users/05_Configuration.md ("Archiving")

When it runs: on a refresh with probability 1/31 per feed
(`rand(0, 30) === 1`), or on "Purge now" / `cli/purge.php`.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/Controllers/feedController.php#L746-L750 ·
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/cli/README.md ("purge.php")

Related but distinct policies that change *read state*, not existence, all
off by default: `mark_when.gone` (mark unread articles read when they vanish
from the upstream feed) and `mark_when.max_n_unread`.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/config-user.default.php#L69-L78 ·
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/Models/Feed.php#L1241-L1265

**Is the purge policy exposed through the API?** No. `greader.php` has no
endpoint touching `archiving`, and `subscription/list` returns only id, title,
categories, url, htmlUrl, iconUrl and `frss:priority` per feed.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/p/api/greader.php#L338-L385
The only observable is absence from `stream/items/ids`.

## 4. Read timestamps

**The server records one.** Marking read/unread sets
`lastUserModified = time()` (batch and single-id paths, and mark-all-as-read).
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/Models/EntryDAO.php#L517-L523 ·
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/Models/EntryDAO.php#L546-L556 ·
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/Models/EntryDAO.php#L600-L602
The column exists since v1.28.0.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/SQL/install.sql.mysql.php#L53
But it is also overwritten by star/unstar, so even server-side it is "last
user change", not "when read".
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/Models/EntryDAO.php#L416-L438

**No API output carries it.** The item representation used by
`stream/contents` and `stream/items/contents` (`toGReader`) contains `id`,
`crawlTimeMsec`, `timestampUsec`, `published`, `title`, `canonical`,
`alternate`, `categories`, `origin`, `summary`/`content`, `enclosure`,
`author` and label categories — no `lastUserModified`, `lastModified` or
`lastSeen`; even `updated` is commented out.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/Models/Entry.php#L1224-L1323
Both endpoints go through `entriesToArray()` → `toGReader('compat', …)`.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/p/api/greader.php#L599-L613 ·
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/p/api/greader.php#L868-L871
`stream/items/ids` emits ids only (§3). `tag/list` emits ids and unread
counts.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/p/api/greader.php#L279-L313
`unread-count` emits `newestItemTimestampUsec`, which is the feed's newest
item time, not a read time.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/p/api/greader.php#L518-L578

**Nor can it be queried.** A `lastUserModified >= ?` filter exists in the
search layer, but `greader.php` builds searches only from `ot`/`nt` and parses
no free-text `q` parameter, so it is unreachable through the API.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/Models/EntryDAO.php#L1028-L1035 ·
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/p/api/greader.php#L669-L688

The map's assumption is confirmed: the read timestamp is not observable from
a client, only inferable as "between the previous sync and this one".

## 5. Write token and edit-tag

**Token.** `GET reader/api/0/token` returns
`str_pad(sha1(salt . user . apiPasswordHash), 57, 'Z')` — deterministic, with
the in-code note `TODO: Implement real token that expires`.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/p/api/greader.php#L234-L245
`checkToken()` accepts that value, and, for any non-internal user, also an
empty token or the literal `x` (FeedMe/Reeder compatibility); anything else is
`401 Unauthorized`.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/p/api/greader.php#L247-L262
The token only changes when the user's API password or the instance salt
changes.

**Request parsing.** `T` comes from `$_POST`; `a`, `r` and `i` are read by
`multiplePosts()` from the raw request body, so repeated `i=` fields work
regardless of PHP's array handling.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/p/api/greader.php#L1304-L1313 ·
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/p/api/greader.php#L79-L92
That raw body is read with a hard cap of 1 048 576 bytes; anything past 1 MiB
is silently dropped.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/p/api/greader.php#L1140
(At about 50 bytes per long-form `i=tag:google.com,2005:reader/item/…&`, that
is roughly 20 000 ids; the practical batch size should be far smaller, see
atomicity.) Each id may be long-form hex or decimal (§1).

**Processing order.** For each `a` value, then for each `r` value: `read` →
`markRead(ids, true|false)`, `starred` → `markFavorite(ids, true|false)`,
`user/-/label/…` → one `tagEntry()` call per id (creating the label if needed);
`broadcast`, `like`, `tracking-kept-unread` are ignored.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/p/api/greader.php#L908-L988

**Atomicity.** `editTag()` opens no transaction, and it ends with
`exit('OK')` unconditionally — a failed `markRead` (which returns `false` and
logs) still yields `200 OK`.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/p/api/greader.php#L988
`markRead()` splits by size: fewer than 6 ids → one `UPDATE` per id; 6 to 998
ids → a single `UPDATE … WHERE is_read<>? AND id IN (…)`; more than 998 →
chunks of 998, each its own statement, no wrapping transaction.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/Models/EntryDAO.php#L499-L545
The chunk size is `FreshRSS_DatabaseDAO::MAX_VARIABLE_NUMBER = 998`
("Based on SQLite SQLITE_MAX_VARIABLE_NUMBER").
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/Models/DatabaseDAO.php#L14-L17
`markFavorite()` has the same shape.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/app/Models/EntryDAO.php#L416-L454
So: a batch of at most 998 ids for one state change is one atomic SQL
statement; larger batches, or a request carrying both `a=read` and
`a=starred`, can be partially applied. The updates are idempotent
(`WHERE is_read <> ?`), and ids the server no longer has simply match no row.

**Deployment limits.** The official Docker image sets PHP
`post_max_size = 32M`, above the 1 MiB read cap, so in that image the 1 MiB
cap is the effective body limit.
https://github.com/FreshRSS/FreshRSS/blob/e5f99067814471cd6b1e0990f01a280a569a5300/Docker/entrypoint.sh#L6-L9

**Not established from primary sources.** Whether a given self-hosted
FreshRSS (non-Docker PHP, reverse proxy) imposes a smaller request-body limit
than 1 MiB is deployment-specific and not documented by FreshRSS; no FreshRSS
documentation states a batch limit for `edit-tag`. There is likewise no
documented maximum for `n`; the absence of a cap is a reading of the code, not
a stated guarantee, and a future version could add one.
