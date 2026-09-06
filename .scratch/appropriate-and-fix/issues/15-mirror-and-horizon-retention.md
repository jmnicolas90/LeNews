# 15 — Drop what FreshRSS dropped, and everything read past the horizon

Type: task
Status: open
Blocked by: 14

## Question

Implement the mirror-and-horizon rule of `docs/article-store.md`: after a successful sync, delete articles FreshRSS no longer returns (as the model defines "returns", from ticket 09's facts about `stream/items/ids`) unless they are starred or still within the horizon, and delete read, unstarred articles that became read more than 30 days ago, whatever the server has. Starred articles are never deleted by either rule. That is the same predicate as the *Retention* bullet of the map, the **Mirror**, **Horizon** and **Starred** entries of `CONTEXT.md` and the retention paragraph of `CLAUDE.md`; the four are meant to read alike. Tags and history entries of a deleted article go with it (foreign keys with cascade, or the model's equivalent). The horizon is a constant or a setting as the model decided; if a setting, it lives with the other timeline preferences and defaults to 30.

Upstream issue #359 ("sync slows down because old items are never deleted") is another user stating pain point 1; ticket 09 established that `stream/items/ids` for the reading list includes read articles, has no cap and paginates with `continuation`, which is what makes the mirror rule implementable.

Run it where the model says (in the sync transaction, or its own step after it) and make sure a failed sync never deletes anything.

Test-first (`/tdd`): an article absent from the server's id list is gone after sync; a starred one absent from the list stays; an article read 31 days ago is gone, one read 29 days ago stays; a failed sync deletes nothing; the seeded 100k store shrinks to the expected size and the timeline query is measured again.

**Done when** the gate is green through G7, the tests pass, and the debug build's store on the emulator, synced against the debug account for a month or against a seeded server state, stabilises at the expected size (record the number).

## From the global review (2026-09-06)

Two adversarial reviews — one on the repo's own standards, one on what the
tickets asked for — read everything committed since the fork point. The upstream
defects below are still in the tree at HEAD and belong to this ticket rather
than to the round that found them, so they are recorded here and nothing was
changed for them.

- **The starred-exclusion workaround loses articles this rule then has to keep.**
  `app/src/main/java/app/lenews/repositories/GReaderRepository.kt:165-171`
  discards any article that arrives with `isStarred` set from the main items
  call, on every sync and not only the initial one. An article starred on the
  FreshRSS web UI between two syncs is therefore never stored, which makes
  "starred articles survive both rules" impossible to honour for it. The model
  question is ticket 12's; the rule that depends on the answer is this one's.
- **Retention is what bounds the two costs ticket 11 could not index away.**
  One mark-all-read takes `ItemState` from 5,955 to 110,000 rows and makes the
  next sync's delete-and-reinsert cost 143 ms instead of 2.3 ms, and Paging's
  `SELECT COUNT(*) FROM (query)` stays at 81 ms with every index in place.
  Nothing but dropping rows fixes either, which is this ticket.
