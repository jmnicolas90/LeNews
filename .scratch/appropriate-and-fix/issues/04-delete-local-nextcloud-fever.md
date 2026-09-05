# 04 — Delete local RSS, Nextcloud News and Fever

Type: task
Status: open
Blocked by: 03

## Question

LeNews speaks FreshRSS only, through the Google Reader API code path. Everything that exists to serve the other three account types is deleted, not disabled: `api/.../localfeed/` (RSS1/RSS2/ATOM/JSONFeed parsers, `LocalRSSDataSource`, `LocalRSSHelper`), `api/.../services/nextcloudnews/`, `api/.../services/fever/`, the matching repositories (`LocalRSSRepository`, `NextcloudNewsRepository`, `FeverRepository`), their entries in `AccountType` and `AccountConfig`, their Koin bindings, their tests and test fixtures, their icons and strings, and the OPML import/export path, which only ever created local accounts (`OPMLAdapter`, `AccountSelectionScreenModel` import, `AccountScreenModel` import).

Keep the `Account` entity, the account screens and the multi-account plumbing for now — collapsing to a single account is ticket 13, after the model is decided in ticket 12. Keep `useSeparateState` as it is; every remaining account type has it `true`, which is what lets ticket 13 remove the other branch. Keep `GREADER` as an account type alongside `FRESHRSS` only if it costs nothing; otherwise fold it in — FreshRSS is a Google Reader API server.

Things that go with the deleted code, and should be checked rather than assumed: the feed-URL discovery flow in the new-feed screen (FreshRSS creates feeds server-side from a URL, so the local parser is not needed for it), `FeedColors` (fetches a favicon to pick a colour; keep, it is used for FreshRSS feeds too, but it is also a response-leak finding for ticket 20), the "add feed" share intent (stays; `canCreateFeed` is now always true so the review's `first()` crash cannot happen), and the CI script's `pm grant` lines.

Deleting three of four services orphans strings in 14 translations. Leave the translations alone in this ticket; whether they stay is on the map's *Not yet specified*.

**Done when** the gate is green, `grep -ri "nextcloud\|fever\|localfeed\|opml" --include=*.kt` returns nothing outside `CHANGELOG.md`, and a debug APK offers exactly one account type on the account screen.
