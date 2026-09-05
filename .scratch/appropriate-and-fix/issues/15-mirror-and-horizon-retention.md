# 15 — Drop what FreshRSS dropped, and everything read past the horizon

Type: task
Status: open
Blocked by: 14

## Question

Implement the mirror-and-horizon rule of `docs/article-store.md`: after a successful sync, delete articles FreshRSS no longer returns (as the model defines "returns", from ticket 09's facts about `stream/items/ids`), and delete read, unstarred articles that became read more than 30 days ago, whatever the server has. Starred articles are never deleted by either rule. Tags and history entries of a deleted article go with it (foreign keys with cascade, or the model's equivalent). The horizon is a constant or a setting as the model decided; if a setting, it lives with the other timeline preferences and defaults to 30.

Upstream issue #359 ("sync slows down because old items are never deleted") is another user stating pain point 1; ticket 09 established that `stream/items/ids` for the reading list includes read articles, has no cap and paginates with `continuation`, which is what makes the mirror rule implementable.

Run it where the model says (in the sync transaction, or its own step after it) and make sure a failed sync never deletes anything.

Test-first (`/tdd`): an article absent from the server's id list is gone after sync; a starred one absent from the list stays; an article read 31 days ago is gone, one read 29 days ago stays; a failed sync deletes nothing; the seeded 100k store shrinks to the expected size and the timeline query is measured again.

**Done when** the gate is green through G7, the tests pass, and the debug build's store on the emulator, synced against the debug account for a month or against a seeded server state, stabilises at the expected size (record the number).
