# 13 — Reset the schema: single account, one read state, real indexes

Type: task
Status: open
Blocked by: 12

## Question

Implement the entities of `docs/article-store.md`. Room restarts at version 1, all `MigrationFromXToY` objects and `db/schemas/*.json` up to 6 are deleted, the schema export starts fresh. The account layer collapses to one FreshRSS account as the model says (one-row table or preferences); `ItemState` disappears or becomes what the model says; `Item` gets the identity constraint and the indexes the model lists; `useSeparateState` and every `separateState` branch in the query builders, DAOs and repositories is deleted, along with the account-scoped joins the review flagged (they have nothing to scope any more).

UI follow-through, kept minimal: the account selection screen becomes a FreshRSS login screen; the account tab loses add/switch/delete; `TabScreenModel.accountEvent` and the notification code that keys on account id are simplified but notifications keep working.

Test-first (`/tdd`): DAO and query-builder tests under `db/src/androidTest` for the identity rule (inserting the same id twice leaves one row), the timeline query against a seeded 100k store (reuse ticket 11's seeder) with a time budget, and the drawer counts. Re-run ticket 11's measurements against the new schema and record them.

Do not implement the sync changes (ticket 14), retention (15) or the history list (16) here, even though this ticket creates the columns they need.

**Done when** the gate is green through G7, `grep -rn "separateState\|ItemState" --include=*.kt` returns nothing (or only what the model kept), and the timeline query on the 100k seeded store runs inside the budget the model set.

## From the global review (2026-09-06)

Two adversarial reviews — one on the repo's own standards, one on what the
tickets asked for — read everything committed since the fork point. The upstream
defects below are still in the tree at HEAD and belong to this ticket rather
than to the round that found them, so they are recorded here and nothing was
changed for them.

- **`ItemState` is joined on `remote_id` alone, never on the account.**
  `db/src/main/java/app/lenews/db/queries/ItemsQueryBuilder.kt:48-49`
  (`SEPARATE_STATE_JOIN`), and the same join in the count, the item-detail and
  the pending-change queries (`db/.../dao/ItemStateChangeDao.kt:16-21`,
  `db/.../queries/ItemSelectionQueryBuilder.kt:40`). Two accounts holding the same
  FreshRSS article would read each other's state. The map lists multi-account
  under *Out of scope*, so this is not a bug to scope: this ticket deletes the
  join instead of fixing it, and that is the note the map asked for.
- **The indexes ticket 11 measured are this ticket's to create**:
  `Item(remote_id)` (the unread timeline, 77.7 ms to 2.8 ms),
  `ItemState(account_id)` or `ANALYZE` for the drawer, and whatever the article
  store model settles on for the timeline order. Re-run ticket 11's
  measurements against the new schema, as this ticket already says.
