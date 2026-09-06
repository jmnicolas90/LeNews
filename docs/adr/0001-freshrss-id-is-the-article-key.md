---
status: accepted
date: 2026-09-06
---

# The FreshRSS id is the article's primary key, and state lives on the article

LeNews talks to one FreshRSS account, and FreshRSS gives every article a 64-bit
integer id that is unique server-wide and stable across content updates
(`docs/research/freshrss-greader-api.md`). We make that integer the article's
primary key, in decimal form, with no autoincrement id and no separate
`remote_id` string; and we put `read`, `starred` and `read_at` on the article
row itself, with a small `PendingChange` table for decisions the server has not
been told yet, instead of upstream's `ItemState` table joined on a string. The
schema restarts at version 1 with no migration, which the rename of the
`applicationId` made free.

Why: the three pain points were one defect. Rows had no identity, so a
re-delivered article (which FreshRSS does on every sync, because `ot` is
inclusive and also matches edited articles) was stored again; state lived in a
second table joined on an unindexed 48-character string, so every screen paid
the join and every sync rewrote the table; and nothing recorded when an
article became read. A key the server guarantees makes the upsert correct and
the duplicate impossible, one table makes the join disappear, and a column
holds the date.

Considered and rejected: an autoincrement key plus a unique `remote_id`
(two forms of one id, a string index, and every join still on a string);
keeping `ItemState` with an index (ticket 11 showed the index alone fixes
nothing and the delete-and-reinsert per sync stays); a history table instead of
`read_at` (one article has one becoming-read date, and the horizon bounds the
history, so a table adds a cascade and a join for nothing).

Consequence: `Item.id` is a `Long` everywhere it was an `Int`, and a FreshRSS
article that is purged and rediscovered is a new article on the phone too,
which is what the server says it is. Written by Jean-Michel Nicolas with
Fable 5.1, ticket 12 of the appropriate-and-fix map.
