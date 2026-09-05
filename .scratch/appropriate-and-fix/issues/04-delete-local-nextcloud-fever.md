# 04 — Delete local RSS, Nextcloud News and Fever

Type: task
Status: resolved
Blocked by: 03

## Question

LeNews speaks FreshRSS only, through the Google Reader API code path. Everything that exists to serve the other three account types is deleted, not disabled: `api/.../localfeed/` (RSS1/RSS2/ATOM/JSONFeed parsers, `LocalRSSDataSource`, `LocalRSSHelper`), `api/.../services/nextcloudnews/`, `api/.../services/fever/`, the matching repositories (`LocalRSSRepository`, `NextcloudNewsRepository`, `FeverRepository`), their entries in `AccountType` and `AccountConfig`, their Koin bindings, their tests and test fixtures, their icons and strings, and the OPML import/export path, which only ever created local accounts (`OPMLAdapter`, `AccountSelectionScreenModel` import, `AccountScreenModel` import).

Keep the `Account` entity, the account screens and the multi-account plumbing for now — collapsing to a single account is ticket 13, after the model is decided in ticket 12. Keep `useSeparateState` as it is; every remaining account type has it `true`, which is what lets ticket 13 remove the other branch. Keep `GREADER` as an account type alongside `FRESHRSS` only if it costs nothing; otherwise fold it in — FreshRSS is a Google Reader API server.

Things that go with the deleted code, and should be checked rather than assumed: the feed-URL discovery flow in the new-feed screen (FreshRSS creates feeds server-side from a URL, so the local parser is not needed for it), `FeedColors` (fetches a favicon to pick a colour; keep, it is used for FreshRSS feeds too, but it is also a response-leak finding for ticket 20), the "add feed" share intent (stays; `canCreateFeed` is now always true so the review's `first()` crash cannot happen), and the CI script's `pm grant` lines.

Deleting three of four services orphans strings in 14 translations. Leave the translations alone in this ticket; whether they stay is on the map's *Not yet specified*.

**Done when** the gate is green, `grep -ri "nextcloud\|fever\|localfeed\|opml" --include=*.kt` returns nothing outside `CHANGELOG.md`, and a debug APK offers exactly one account type on the account screen.

## Answer (2026-09-05)

The three services are gone, not switched off. 127 files deleted, 3 added,
49 changed: 2 517 insertions against 9 266 deletions. The gate is green end to
end, G0 to G7, and a debug APK offers one account type.

### What was deleted, by package

**`api`**
- `localfeed/` — `LocalRSSDataSource`, `LocalRSSHelper`, `XmlAdapter`, `RSSMedia`
  and the eight RSS1 / RSS2 / ATOM / JSONFeed adapters, with their five test
  classes and 27 fixtures.
- `opml/` — `OPMLParser`, `OPMLAdapter`, one test class, three fixtures.
- `services/nextcloudnews/` — eight files, five test classes, five fixtures.
- `services/fever/` — ten files, seven test classes, ten fixtures.
- `utils/extensions/KonsumerExtensions.kt` and its test: nothing else read XML.
- `utils/exceptions/` — `UnknownFormatException` (thrown by the local parser),
  `LoginFailedException` (thrown by the Fever repository) and `ConflictException`,
  which nothing had thrown for some time.
- `Credentials.toCredentials` no longer chooses: every account is a Google Reader
  account and the FreshRSS endpoint is a constant beside it.
- `ApiUtils` lost the OPML mime types, the ETag / If-None-Match / Last-Modified /
  If-Modified-Since header names (the local parser's conditional GET), `isMimeImage`,
  `md5hash` (Fever's authentication hash), `parseContentType` and `cleanText`. It
  gained `isFeedContentType`, the four feed content types that `LocalRSSHelper`
  used to hold, because feed discovery still needs them (below).

**`app`**
- `repositories/` — `LocalRSSRepository`, `NextcloudNewsRepository`, `FeverRepository`.
  `FeedExistException` went with the first, and its branch in `AccountError`.
- `util/FeverFaviconFetcher.kt`, `util/accounterror/NextcloudNewsError.kt`.
- `account/dialog/` — `OPMLChoiceDialog`, `OPMLImportProgressDialog` and
  `AccountWarningDialog`, which only ever warned about Google Reader and Fever.
- `ErrorListDialog` and `ErrorDialog`: both showed errors that only the local sync
  and the OPML import produced.
- `RefreshScreen.kt` is now `LoadingScreen.kt`, holding the one composable that
  survived. `RefreshScreen` and `RefreshIndicator` showed "feed 3 of 20" progress,
  which only the local account ever reported.
- `androidTest` — `LocalRSSRepositoryTest`, the Fever fixtures, and the two RSS
  fixtures the local sync tests fed to MockWebServer.

**`db`** — the Fever, Nextcloud News, Feedly and Google Reader icons, and
`values-fr/strings.xml`, which held one string: the local account's name.

**Resources and build files** — `app`'s `ic_import_export` drawable (the OPML
menu icon); 19 English strings and plurals; the `konsumexml` and
`kotlin-xml-builder` dependencies; four ProGuard keep rules and the four
`-dontwarn` lines that only the XML parser needed.

### The account types

`AccountType` is one entry: `FRESHRSS`. `AccountConfig` is one config, the old
`GREADER` one with `showCustomFolderDeleteMessage = true`. `useSeparateState`
stays `true` and the `false` branch of the eight state writers in `BaseRepository`
stays with it, for ticket 13 to remove; what went is the third branch, the local
account's, which wrote the item row without recording a state change.

**`GREADER` is folded into `FRESHRSS`.** Keeping it did not cost nothing: it is
an account type with an icon and a name of its own, so the account screen would
have offered two, and the ticket's own "done when" asks for exactly one. Nothing
was lost by folding — the two configs differed by one boolean, the credentials
screen filled the same four fields for both, and `AccountError.from` mapped both
to `GReaderError`. The one behaviour that was `GREADER`-only, the "provide the
full URL" helper text under the URL field, went with it: FreshRSS was already on
the other branch, and asks for the root URL.

**`FEEDLY` went too**, unmentioned by the ticket. It was upstream's dead entry —
marked "to be ignored", filtered out of the one dialog that listed the enum, and
carrying `AccountConfig.LOCAL`, which no longer exists. It could not have stayed.

`ACCOUNT_APIS`, the list that told the "API" account types from the rest, has no
distinction left to make and is gone.

### What was kept, and why

- **The `Account` entity, the account screens, the multi-account plumbing.**
  Untouched except where a branch tested for a deleted type. The account tab still
  lists other accounts and switches between them; ticket 13 collapses that.
- **The rename-account dialog**, which the account tab used to show only for local
  accounts, is shown for every account now rather than deleted. It is account
  plumbing, not local-account code, and it was one line either way.
  The credentials entry, hidden for local accounts, is likewise unconditional.
- **`FeedColors`** — checked, kept, unchanged. It picks a colour from a feed's
  favicon and the FreshRSS path calls it on every sync.
- **The add-feed share intent** — checked, kept. `canCreateFeed` is `true` for the
  only account type there is, so the `accounts.first()` the review flagged can no
  longer be called on an empty list.
- **The `pm grant` equivalent in the test rule** — checked, kept.
  `POST_NOTIFICATIONS` is still granted by `ReadropsTestRule`, because
  `SyncWorkerTest` still inspects a notification and the system drops a
  notification the app may not post. The comment that explained it named the
  local sync's feed counter, which is gone; it names what is left.
- **The underscore-first folder sort** in `GetFoldersWithFeeds`, whose comment
  said "Nextcloud News case". The behaviour is generic — folders whose name starts
  with an underscore sort first — the new-feed screen sorts the same way with no
  comment, and removing it from one place only would have made the two disagree.
  The comment now says what the code does.

### Feed discovery in the new-feed screen

Checked, and it did need work. The screen used to ask `LocalRSSDataSource` "is
this URL a feed?", which fetched the URL, read the content type and, when that
was inconclusive, sniffed the XML root element with `konsumexml`. All of that is
deleted, and the naive replacement would have broken the common case: hand a
plain feed URL to `HtmlParser.getFeedLink` and it throws, because the response is
not an HTML page.

The question is now asked the other way round, which needs no parser and one
request rather than two: try to read the URL as a web page and collect the feed
links it announces. If it is not a web page — no `text/html`, or no `<head>` —
the URL is handed to FreshRSS as typed, and the server says whether it is a feed.
A page with one link subscribes to it, a page with several offers the choice, a
page with none reports "no feed found", all as before. The four feed content
types moved from `LocalRSSHelper` to `ApiUtils.isFeedContentType`, which is what
filters the `<link>` elements.

### The sync, once there is nothing local to sync

`Repository.synchronize(selectedFeeds, onUpdate)` — "global synchronization for
the local account" — is gone, and with it `GReaderRepository`'s override that
threw `NotImplementedError`. `SyncResult.favicons`, which the comment said was
"only for Fever", is gone. `insertOPMLFoldersAndFeeds` is gone.

`Synchronizer.synchronizeAccounts` takes an account id rather than a
`SyncInputData` of account, feed and folder — the feed and folder narrowed a
local sync to one feed or one folder, which the Google Reader API cannot do — and
returns the sync results alone, no `ErrorResult`: per-feed errors could only come
from the local sync, which fetched each feed itself. That removed
`SyncWorker.LOCAL_SYNC_ERRORS_KEY`, `FEED_ID_KEY`, `FOLDER_ID_KEY` and the three
progress keys, and in the timeline `localSyncErrors`, `currentFeed`, `feedCount`,
`feedMax`, `isAccountLocal`, `displayRefreshScreen` and `DialogState.ErrorList`.

### The database migration that broke

`MigrationFrom4To5` turned the account type from an integer into text by reading
`AccountType.entries[ordinal]`. With one entry left, position 0 would have named
FreshRSS accounts wrongly and every other position would have thrown. It now
names the one type it can: position 3 was FreshRSS, those rows get `FRESHRSS`,
and the rest are deleted along with their folders, feeds, items and item states.
That is what a FreshRSS-only client can honestly do with a database holding
accounts of services it does not speak to. `MigrationsTest.migrate4To5` asserts
the new name, and a second test asserts that an account of another service and
its feed are gone.

This migration cannot actually run in LeNews — the applicationId change makes
every install fresh, and the map has already settled that no migration from the
Readrops schema is ever needed — but a migration that is wrong is worse than one
that is unreachable, and deleting the chain belongs to ticket 12's schema reset.

### Dependencies

Out: **`konsumexml`** (`com.gitlab.mvysny.konsume-xml`) and
**`kotlin-xml-builder`** (`org.redundent`), from `api/build.gradle.kts` and from
`gradle/libs.versions.toml`. Proven by grep: after the deletions no source file
imports `com.gitlab.mvysny.konsumexml` or `org.redundent`, the only XML the app
still reads being none — the Google Reader API is JSON throughout.

Kept, with the proof: **`moshi`** and **`retrofit`** are the Google Reader
adapters and service; **`okhttp`** is every request; **`jsoup`** is
`HtmlParser`, which feed discovery still uses, and is a direct `app` dependency
as well. `kotlinx-serialization` is not in the graph and never was.

Four ProGuard keep rules went with the code: `com.readrops.api.localfeed.**`,
`com.readrops.api.opml.model.**`, `com.readrops.api.services.nextcloudnews.json.**`
and `com.readrops.api.services.greader.json.**` — the last one named a package
that does not exist in this tree. So did the `org.xmlpull`, `org.simpleframework.xml`
and `javax.xml.stream` rules, which existed for `konsumexml`'s StAX API. G6 builds
the release APK, R8 included, with none of them.

`app/build.gradle.kts` generated fifteen `debug.<account>.<field>` string
resources from a list of five services; it generates three, for `freshrss`.

### Strings and the lint baseline

Nineteen entries left `app/src/main/res/values/strings.xml`: the OPML five, the
two service warnings, the two account-screen section headers plus `local`,
`api`, `provide_full_url`, `empty_file`, `details`, `synchronization_errors`,
`understand`, `warning`, `invalid_feed`, `invalid_folder`, `login_failed`, and
the `error_occurred` and `error_occurred_feed` plurals. **The 14 translations were
left alone**, as the ticket asks; which locales LeNews keeps is the product call
on the map's *Not yet specified*.

That has a price, and the baseline is where it lands. Regenerated:

| | entries | errors | warnings |
| --- | --- | --- | --- |
| before | 275 | 228 | 47 |
| after | 431 | 363 | 67 |

The 135 new errors are all one thing: 146 `ExtraTranslation` and 10
`MissingDefaultResource` (up from none) and 50 `UnusedResources` (up from 30) are
the 14 locales still holding strings that no longer exist in English, against 204
`MissingTranslation`, down from 224 because 20 of the deleted strings were the
subject of those complaints. One edit clears all of it — deleting the orphaned
strings from the locale files — and it is the same edit as making the translations
call, so it waits for it. The comment above `baseline =` in `app/build.gradle.kts`
says so. Lint is still blocking and new errors of these kinds still fail G2.

### Tests

No test was left passing because it tests nothing.

| | before | after |
| --- | --- | --- |
| `api` unit | 135 | 43 |
| `app` unit | 4 | 4 |
| `db` unit | 7 | 7 |
| `db` instrumented | 20 | 21 |
| `app` instrumented | 32 | 25 |

The 92 unit tests that went were the parsers', the OPML parser's, Fever's,
Nextcloud News's and `KonsumerExtensionsTest`. `ApiUtilsTest` lost the three tests
of methods that went and gained one for `isFeedContentType`. `CredentialsTest`
lost its Nextcloud News half.

`db` gained the second migration test described above. In `app`,
`LocalRSSRepositoryTest` is gone; `SynchronizerTest` was five tests, three of them
local and one Fever favicons, and is now one, the FreshRSS synchronization, with
the same assertions the old remote test made; `SyncWorkerTest` was six tests over
a local account and lost `localAccountErrorTest`, whose subject — the per-feed
error result — no longer exists, while the other five run against a MockWebServer
answering the Google Reader calls. Two fixtures were added for it,
`greader/items_1_item.json` and `greader/items_empty.json`, so that one new
article arrives and the notification carries the actions the test triggers.
`SyncAnalyzerTest` and `GetFoldersWithFeedsTest` and the two `db` DAO tests only
needed their account types changed.

### On the emulator

`:app:installDebug` on `bench-pixel6-aosp`, launched cold on a device with no
account. The account screen shows the app icon, the name, and a single card
titled "Choose an account" holding one row: the FreshRSS logo and the word
FreshRSS. No "Local" section, no "External" section, no "API" section, no OPML
import entry. Tapping it opens the credentials screen — FreshRSS logo and name,
account name prefilled "FreshRSS", account url "https://" under "Please provide
the service root URL", login, password under "This is your FreshRSS API password
(Configuration > Profile)", and Validate. Uninstalled afterwards.

The first screenshot caught one regression the deletions caused: the version
number at the bottom sat under the navigation bar, because removing the account
screen's snackbar took the `Scaffold` with it and the system-bar padding came
from the `Scaffold`. An empty `Scaffold` is back, with a comment saying that is
what it is for, and the second screenshot shows the version clear of the bar.

### Left alone on purpose

`README.md` and `.github/ISSUE_TEMPLATE/bug_report.md` still advertise Nextcloud
News, Fever and local feeds. They are upstream's, in upstream's name, and ticket
06 replaces them whole; touching them here would only be a half-scrub. The grep
this ticket owes is over `.kt`, and that one is clean — one rename was needed for
it, a local variable `localFeedIds` in `FeedDao` that matched "localfeed" as a
substring; it is `storedFeedIds` now, which is also what it means.

### Review round (2026-09-06)

Two findings from the Codex review, both about code the deletions left standing
with the wrong account type in mind.

**The notification actions wrote a column nothing reads.** `SyncBroadcastReceiver`
answered the notification's mark-read and star buttons with
`itemDao().updateReadState` / `updateStarState`, which set `Item.read` and
`Item.starred`. Every remaining account type has `useSeparateState = true`: the
timeline reads `ItemState`, and the sync uploads what is in `ItemStateChange`. So
the buttons changed nothing the user could see and sent nothing to FreshRSS. The
receiver now looks the account up and goes through `BaseRepository.setItemReadState`
/ `setItemStarState`, the same two methods the timeline and the article screen
use, which write both tables. The account id travels in the action intents, next
to the article id that was already there; `SyncAnalyzer` only ever attaches
actions to a single-account notification, which always carries one. The work also
takes `goAsync()` now — it is three queries and a transaction rather than one
update, and `onReceive` returning used to be the process's licence to die
mid-write.

The rewritten `SyncWorkerTest` had moved to a FreshRSS account but kept asserting
`Item.read` and `Item.starred`, so it passed on the broken behaviour. It is
honest now: the mock server's unread-ids call returns the one article the fixture
delivers (`greader/items_unread_ids.json`, decimal `1625234531559678` for the
hexadecimal `0005c62466ee28fe` in `items_1_item.json`), so the article has an
`ItemState` row to change; after the two actions the test reads `ItemState` and
the pending `ItemStateChange` rows; and a second synchronization has to put the
article's id on an `edit-tag` request for `…/state/com.google/read` and another
for `…/starred`. The three ids calls are now told apart by their `xt` parameter
rather than all answered with the starred ids. Reverting the receiver alone makes
the test fail, which is what it is for.

**A rejected URL was reported as a duplicate.** The new-feed screen hands
anything that is not an HTML page to FreshRSS as typed, which is right — the
server is the one that knows what a feed is. But `GReaderError.newFeedMessage`
mapped every HTTP 400 to "Feed already exists". FreshRSS's `subscription/edit`
handler (`p/api/greader.php`, `subscriptionEdit`) reaches the same `badRequest()`
from both branches of `ac=subscribe`: the URL is already subscribed, or
`addFeed` threw because there was no feed to read there. `badRequest()` sends
`400` with the body `Bad Request!` in both cases and logs the difference
server-side only, so nothing in the response tells a client which happened. The
ambiguous message is therefore the only honest one:
`freshrss_feed_not_added`, "FreshRSS could not add this feed: it is not a feed it
can read, or it is already subscribed". `feed_already_exists` is gone, from the
English strings and from the seven locales that had translated it, so no dead
resource is left behind; its one entry left the lint baseline with it, which is
now 362 errors rather than 363. `updateFeedMessage` used to delegate to
`newFeedMessage`; it delegates to `deleteFeedMessage` instead, because with
`ac=edit` and `ac=unsubscribe` a 400 does have one meaning — the server does not
know this feed. `GReaderErrorTest` in `app/src/test` pins the three mappings.

Left out: the new string is marked `tools:ignore="MissingTranslation"`. Adding it
to the 14 locales would mean inventing translations, and which locales LeNews
keeps is still the open product call the lint baseline's comment describes.
`feed_doesnt_exist` still reads "The feed %1$s doesn't exist on the server" and
is still fetched without an argument, so it shows the placeholder; that wart
predates this round and lives with the translations call.
