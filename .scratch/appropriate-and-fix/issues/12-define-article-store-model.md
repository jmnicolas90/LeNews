# 12 — Define the article store: identity, state, sync, mirror, history

Type: grilling
Status: resolved
Blocked by: 04, 09, 11

## Question

**The heart of this map.** All three pain points are the same defect from three angles: the local store does not know what an article *is*. Rows have no identity beyond an autoincrement id, so the same article can be inserted twice; read state lives in a second table joined without an index, so the store slows with size; nothing records when an article became read, so nothing can list it. Fixing them one at a time would paper over it. The model comes first, and tickets 13 through 16 implement it.

Use `/grilling` and `/domain-modeling`. Read `docs/research/freshrss-greader-api.md` (ticket 09), `docs/research/upstream-since-fork.md` (ticket 10) and the measurements in ticket 11 first. The output is a **written model** in `docs/article-store.md`, the glossary in `CONTEXT.md` kept in step, and an ADR if the trade-off deserves one — not code.

What has to be decided:

**Identity.** One FreshRSS account, so the FreshRSS item id is the article's identity. Is `(remote_id)` unique on its own, or `(feed_id, remote_id)`? What happens when the server returns an id the store already has — ignore, or update content and keep state? Ticket 09 says what the id guarantees.

**One read state or two.** Today `Item.read` exists but FreshRSS accounts ignore it and read `ItemState` instead, with a full delete-and-reinsert per sync. With one account, does `ItemState` collapse into columns on the article, with the sync applying the server's unread and starred id lists as updates? What is the local-change queue (`ItemStateChange`) then, and how does a change made offline survive a sync that reports the old state?

**Replay-safe sync.** The cursor (`lastModified`) is advanced after all inserts; a failure in between replays. What is the transaction boundary — items, state, tags and cursor in one Room transaction? Is a duplicated response member possible and how is it handled? What does a sync that fails halfway leave behind, and what does the next one do? Ticket 09's `ot` semantics decide whether re-delivery is normal or exceptional.

**Mirror and horizon.** The decision is made: the store mirrors FreshRSS, read articles older than 30 days are dropped, starred are kept. What does "FreshRSS no longer returns it" mean operationally given what `stream/items/ids` actually offers (ticket 09)? When does the drop run — in the sync transaction, or as its own step? Is the horizon a constant or a setting?

**Becoming read.** Every transition to read is dated, by any route, including a read learned at sync (stamped with sync time). Where does the timestamp live — a column on the article, or a history table? Does an article that is marked unread again and then read again get a new date? What does history show for an article that is dropped by the horizon — it is simply gone, since the horizon is 30 days and history is bounded by it; confirm.

**Initial sync.** Today the first sync caps at 2,500 items and 1,000 starred (`MAX_ITEMS`, `MAX_STARRED_ITEMS`); upstream issue #282 / PR #306 wanted the limits raised, and ticket 09 found FreshRSS paginates with `c=`/`continuation` with no server cap. Decide whether the initial sync pages through everything the server has, or fetches a bounded window and lets the mirror rule define the rest.

**Indexes.** Which indexes the timeline, the drawer counts, the history list and the sync need, from ticket 11's plans.

**Schema reset.** Nothing upgrades from Readrops, so the Room database can restart at version 1 with no migrations. Decide whether to keep the `Account` table at all (one row) or move account fields to a preferences store.

Constraint from charting: **do not design features beyond the three pain points.** The model must serve the timeline, the drawer, the item screen, the history list and sync, nothing else.

**Done when** `docs/article-store.md` exists with the entities, the identity rule, the sync sequence with its transaction boundary, the mirror-and-horizon rule, the becoming-read rule and the index list, such that tickets 13 to 16 can be implemented against it and a test can be written from it directly.

## Answer (2026-09-06)

Grilled in two rounds with the user, every question put with a recommendation
and every recommendation taken. The model is written in **`docs/article-store.md`**
(entities, identity, the sync sequence with its transaction boundary, the
mirror-and-horizon predicate, becoming read, indexes and statistics, the time
budget, the initial sync, the screens served, and the tests the rules imply),
the one hard-to-reverse choice in **`docs/adr/0001-freshrss-id-is-the-article-key.md`**,
and `CONTEXT.md` gained *Pending change* and *Cursor* and now says an article's
identity is FreshRSS's number for it. No code was written.

What was decided, one line each:

- **Identity**: the FreshRSS 64-bit id is the article's primary key, stored as
  a decimal integer; no autoincrement id, no `remote_id` string. Unique on its
  own, not `(feed_id, id)`. A re-delivered id updates the content columns and
  keeps every state column; last occurrence in a response wins.
- **One read state**: `read`, `starred` and `read_at` are columns on the
  article. `ItemState` and `ItemStateChange` are gone, and so is
  `useSeparateState`.
- **Pending changes**: a `PendingChange(article_id, read?, starred?)` table,
  one row per article, uploaded first in batches of at most 998, cleared only
  where the value still matches what was uploaded; the server's lists never
  touch a column that has a pending value.
- **Sync**: every network call before the transaction, then one Room
  transaction in order: feeds and folders, article upserts, read state, starred
  state, retention delete, cursor, `PRAGMA optimize`. A failure anywhere rolls
  back everything and deletes nothing. State comes from three paged
  `stream/items/ids` walks (all, unread, starred); `stream/contents` is content
  only. Starred ids the store lacks have their content fetched; the
  starred-exclusion workaround goes.
- **Cursor**: a column on the one-row `Account` table, written in the
  transaction; not derived from the data, not in DataStore.
- **Mirror and horizon**: "no longer returns it" = absent from the full
  reading-list id set of this sync. One delete in the transaction:
  `starred = 0 AND ((read = 1 AND read_at < now − 30 days) OR (read = 0 AND id
  not on the server))`. The horizon is a named constant, not a setting. A
  starred article whose feed is unsubscribed goes with the feed, and that is
  the one case starred does not survive.
- **Becoming read**: `read_at` on the article, stamped on every 0→1 transition
  by any route, sync time for a read learned at sync; unread clears it, reading
  again stamps anew; history is `read_at IS NOT NULL ORDER BY read_at DESC`,
  no history table; an article the horizon dropped is gone from it.
- **Initial sync**: all unread and all starred articles, paged to the end, no
  cap, no read articles; history starts empty at install.
- **Indexes**: primary key, `(pub_date)`, `(feed_id, pub_date)`,
  `(read, pub_date)`, `(starred, pub_date)`, `(read_at)`, plus the unique
  `remote_id` on Feed and Folder; `PRAGMA optimize` after every sync and at
  creation, because ticket 11 proved an index without statistics fixes nothing.
- **Budget**: on the 100k seeded store, first page of every timeline, the
  drawer counts and the history page under 10 ms; on a 20k store, Paging's
  `COUNT(*)` under 20 ms.
- **Schema reset**: Room version 1, no migrations; `Account` stays as one row
  (URL, name, tokens, notifications flag, cursor) and every other `account_id`
  column goes; credentials stay outside the database.
- **Tags dropped** from the schema: unfinished upstream, outside the pain
  points, the one per-row query the timeline still paid.

The three inherited defects recorded below are each answered by a rule above:
the caps by §7 and the paged pulls, the identity by §2, the starred workaround
by step 2 of §3. Tickets 13 to 16 implement them.

## From the global review (2026-09-06)

Two adversarial reviews — one on the repo's own standards, one on what the
tickets asked for — read everything committed since the fork point. The upstream
defects below are still in the tree at HEAD and belong to this ticket rather
than to the round that found them, so they are recorded here and nothing was
changed for them.

- **The fetch stops at the caps and throws the continuation away.**
  `api/src/main/java/app/lenews/api/services/greader/GReaderDataSource.kt:50-56`
  (initial) and `:73-78` (incremental) ask for `MAX_ITEMS = 2500` contents and
  `MAX_STARRED_ITEMS = 1000` (`:161-162`) and read no `continuation` from any
  response. `GReaderRepository.insertItemsIds` then replaces every `ItemState`
  row of the account from those capped lists, so an unread article the cap left
  out comes back as read, and the cursor (`account.lastModified`,
  `GReaderRepository.kt:64` and `:78-79`) advances past content that was never
  fetched. Ticket 09 found `stream/items/ids` has no server cap and pages with
  `continuation`. The *Initial sync* question of this ticket is exactly this
  one; ticket 14 implements whatever it decides.
- **Articles are inserted with no identity.**
  `app/src/main/java/app/lenews/repositories/GReaderRepository.kt:176-180`
  inserts every returned article with `insert`, and no unique constraint on
  `remote_id` exists to stop it. With `ot` inclusive (ticket 09), the boundary
  article and every article edited upstream come back and are stored again.
  The identity rule and the constraint are this ticket's; the upsert is 14's.
- **The starred-exclusion workaround runs on incremental syncs too.**
  `GReaderRepository.kt:165-171` drops any article that arrives with
  `isStarred` set from the main items call, on every sync and not only on the
  initial one, because the API exclusion filter was found unreliable. An
  article starred elsewhere between two syncs therefore has its body discarded
  and appears in neither the timeline nor the stars. What the store does with
  an article that arrives starred is a model question; ticket 15 owns the
  retention half of it.
