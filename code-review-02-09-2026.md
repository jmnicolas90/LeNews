# Code Review — Readrops — 02-09-2026

## Summary

- Commit: `9ebbe038`, branch: `develop`
- Coverage: 385 total OCR-reviewable files / 385 reviewed files / 0 skipped files / 100% coverage. Full tracked-tree accounting: 481 files = 385 reviewed + 95 excluded by OCR + 1 binary omitted by OCR.
- Findings: Critical 0 · High 4 · Medium 11 · Low 3
- Overall assessment: Readrops has a coherent three-module Android architecture, with API adapters, repositories, and Room persistence separated clearly enough to trace data end to end. The largest correctness risk is that remote article identity is not consistently scoped to an account, which can produce exactly the reported double or triple articles when two or three accounts share a remote ID. A second, independent client-side path can persist duplicate FreshRSS/GReader articles because synchronization inserts are not idempotent and the database has no matching uniqueness constraint. The most serious security risks are the process-wide mutable authentication interceptor and JavaScript execution for unsanitized article HTML. Resource ownership and lifecycle boundaries need tightening in HTTP, WorkManager, paging, and screen-disposal paths. The unit test suite passes, but the database tests mainly inspect generated SQL strings or use one account, so they miss multi-account identity collisions and replayed synchronization batches. Configuration and resource files are generally consistent; all 39 reviewable JSON files and 104 reviewable XML files parsed successfully.

## Critical & High

### A mutable process-wide interceptor can leak or drop credentials

- **File:** `api/src/main/java/com/readrops/api/utils/AuthInterceptor.kt:8-19`
- **Category:** security  **Severity:** high
- **Rule:** Kotlin (`*.kt`), OCR group 4
- **Problem:** `credentials` is mutable state on a singleton interceptor attached to the shared `OkHttpClient`. Account synchronization, tab changes, feed discovery, and local-feed requests overwrite or clear the same value concurrently, and the interceptor adds the current authorization header to every request without checking its host. `ReadropsApp.newImageLoader()` calls `client.newBuilder()`, which retains this interceptor despite the comment that it avoids mixing authentication; image or user-supplied feed requests can therefore receive credentials for an unrelated account, while another request can clear credentials during a sync.
- **Fix:** Build service-specific clients with immutable credentials and a strict expected-host check. Use a genuinely separate client with no authentication interceptor for local feeds, favicons, article images, and other arbitrary URLs; remove all mutation of a singleton credential field and add concurrent cross-host tests.

### Unsanitized article HTML executes JavaScript in a file-origin WebView

- **File:** `app/src/main/java/com/readrops/app/item/view/ItemWebView.kt:28-99`
- **Category:** security  **Severity:** high
- **Rule:** Kotlin (`*.kt`), OCR group 4
- **Problem:** Article content comes from feeds or remote servers, is parsed with Jsoup, and is loaded with a `file:///android_asset/` base URL while JavaScript is enabled. Parsing is not sanitization: the code only clears attributes from `div` and `span`, leaving `script` elements, event handlers on other tags, dangerous URLs, and active embedded content intact. A malicious or compromised feed can execute stored script in the article view, perform network requests, replace visible content, or create phishing UI.
- **Fix:** Disable JavaScript for article rendering, sanitize with an explicit Jsoup `Safelist`, reject active/embedded elements and unsafe schemes, disable file/content access where possible, and allow navigation only to validated `http`/`https` URLs. Add a test corpus containing scripts, event attributes, iframes, and `javascript:` URLs.

### FreshRSS/GReader synchronization is not idempotent

- **File:** `app/src/main/java/com/readrops/app/repositories/GReaderRepository.kt:150-185`
- **Category:** bug  **Severity:** high
- **Rule:** Kotlin (`*.kt`), OCR group 4
- **Problem:** Every returned GReader item is inserted without checking for an existing remote ID. The `Item` entity has no unique index for remote identity, and the synchronization updates `account.lastModified` only after item, tag, and state writes. A repeated API boundary item, duplicated response member, process failure after insertion, or WorkManager retry before the cursor update therefore creates another physical row. FreshRSS uses this GReader path, so this is a client-side explanation for persisted duplicates even if FreshRSS returns a correct stable item ID.
- **Fix:** Define a database uniqueness invariant such as `(feed_id, remote_id)` or, after making account identity explicit on `Item`, `(account_id, remote_id)`. Insert with conflict-ignore/upsert inside one Room transaction together with cursor/state advancement, deduplicate each response batch before insertion, and add a migration that removes existing duplicates without losing read/star state. Test the same batch twice and inject a failure immediately before the cursor update.

### Item-state joins ignore the account and multiply timeline rows

- **File:** `db/src/main/java/com/readrops/db/queries/ItemsQueryBuilder.kt:48-49`
- **Category:** bug  **Severity:** high
- **Rule:** Kotlin (`*.kt`), OCR group 4
- **Problem:** `ItemState` is uniquely identified by `(remote_id, account_id)`, but the timeline joins it using `remote_id` alone. If the same remote ID exists in two or three FreshRSS/GReader/Fever accounts, one item joins two or three state rows, producing exactly double or triple timeline entries and potentially displaying another account's read/star state. The same missing account predicate appears in `ItemSelectionQueryBuilder`, `FeedUnreadCountQueryBuilder`, `FoldersAndFeedsQueryBuilder`, `ItemStateChangeDao`, and `ItemDao.selectUnreadNewItemsCountByItemState`.
- **Fix:** Scope every state join to both dimensions, for example `LEFT JOIN ItemState ON Item.remote_id = ItemState.remote_id AND ItemState.account_id = Feed.account_id`, using explicit table-qualified account columns in count/state-change queries. Add a Room integration test with identical remote IDs in three accounts that asserts one timeline row, correct state, and correct unread counts for each account.

## Medium

### CI executes mutable third-party actions with implicit permissions

- **File:** `.github/workflows/android.yml:11-49`
- **Category:** security  **Severity:** medium
- **Rule:** GitHub workflow YAML, OCR group 3
- **Problem:** The workflow uses tag references such as `actions/checkout@v2`, `ReactiveCircus/android-emulator-runner@v2.33.0`, and `codecov/codecov-action@v4` instead of immutable commit SHAs, and it does not declare least-privilege `permissions`. A moved or compromised tag changes executable CI code without a repository change. The job also has no timeout or concurrency cancellation, so stuck emulator jobs and superseded branch builds can consume runners indefinitely.
- **Fix:** Pin every action to a reviewed full commit SHA, declare `permissions: contents: read` (and only additional permissions that are demonstrably needed), set `timeout-minutes`, and add a branch/PR concurrency group with `cancel-in-progress: true`.

### HTTP responses leak on normal and exceptional paths

- **File:** `api/src/main/java/com/readrops/api/localfeed/LocalRSSDataSource.kt:36-87`
- **Category:** performance  **Severity:** medium
- **Rule:** Kotlin (`*.kt`), OCR group 4
- **Problem:** `queryRSSResource()` closes only the successful response body after parsing; 304 responses, error responses, and parser exceptions leave the response open. `isUrlRSSResource()` never closes the response and has early returns for missing/invalid content types. Repeated discovery or synchronization can exhaust OkHttp connections/file descriptors and reduce connection reuse.
- **Fix:** Wrap the complete response in `queryUrl(...).use { response -> ... }` and keep all parsing and early-return decisions inside that scope. Apply the same ownership pattern to `FeedColors`, `FeverFaviconFetcher`, and `FeverDataSource.login`, which show the same leak pattern.

### OPML files containing only empty folders crash import

- **File:** `app/src/main/java/com/readrops/app/account/selection/AccountSelectionScreenModel.kt:89-100`
- **Category:** bug  **Severity:** medium
- **Rule:** Kotlin (`*.kt`), OCR group 4
- **Problem:** The code checks only whether the folder-to-feed map is empty, then calls `foldersAndFeeds.values.first().first()`. `OPMLAdapter` deliberately creates entries with an empty feed list for empty outlines, so a valid OPML document containing only empty folders passes the check and throws `NoSuchElementException`. `AccountScreenModel` repeats the same access.
- **Fix:** Compute `val firstFeed = foldersAndFeeds.values.asSequence().flatten().firstOrNull()` and show the empty-file state when it is null. Share this validation between both import entry points and add fixtures for an empty body, one empty folder, and nested empty folders.

### Share-to-add-feed crashes when no account supports feed creation

- **File:** `app/src/main/java/com/readrops/app/feeds/newfeed/NewFeedScreenModel.kt:45-55`
- **Category:** bug  **Severity:** medium
- **Rule:** Kotlin (`*.kt`), OCR group 4
- **Problem:** Accounts are filtered by `canCreateFeed` and then `accounts.first()` is called. The normal feed-tab button is hidden for Fever, but `MainActivity` can still open this screen from an `ACTION_SEND` intent. A Fever-only installation (or any empty eligible-account set) therefore crashes when an article/feed URL is shared to Readrops.
- **Fix:** Use `firstOrNull()`, expose a clear unavailable state, and make the share-intent route decline or explain the action when no eligible account exists. Add a Fever-only navigation test.

### Image download writes directly to a blocked scoped-storage path

- **File:** `app/src/main/java/com/readrops/app/item/ItemScreenModel.kt:284-305`
- **Category:** bug  **Severity:** medium
- **Rule:** Kotlin (`*.kt`), OCR group 4
- **Problem:** With target SDK 35, the app writes a `File` directly under the public Downloads directory and declares no legacy storage permission. Scoped storage prevents this direct path on modern Android, so image download can fail with an I/O/security exception; the coroutine does not catch that exception and can also produce invalid or colliding filenames from URLs.
- **Fix:** Insert through `MediaStore.Downloads` with `DISPLAY_NAME`, `MIME_TYPE`, and `RELATIVE_PATH`, write through the returned content URI, sanitize/generate the filename, and surface failures to UI. Cover Android 10+ behavior in an instrumented test.

### Disposal can crash before initialization and launches untracked work

- **File:** `app/src/main/java/com/readrops/app/item/ItemScreenModel.kt:366-389`
- **Category:** bug  **Severity:** medium
- **Rule:** Kotlin (`*.kt`), OCR group 4
- **Problem:** `onDispose()` unconditionally uses a `lateinit repository` that is assigned asynchronously from a database flow. Quickly leaving the screen before that flow emits can throw `UninitializedPropertyAccessException`, even when there are no state changes. The work is launched in `GlobalScope`, so it has no owner, error handling, retry policy, or guarantee of completing before process death.
- **Fix:** Return immediately when there are no changes, avoid `lateinit` by deriving the repository before exposing the screen, and hand state writes to a durable application-owned repository/queue or WorkManager task with explicit error handling. Do not use `GlobalScope` as persistence infrastructure.

### Paging performs one tag query per visible article

- **File:** `app/src/main/java/com/readrops/app/timelime/TimelineScreenModel.kt:197-204`
- **Category:** performance  **Severity:** medium
- **Rule:** Kotlin (`*.kt`), OCR group 4
- **Problem:** Every item emitted by Paging triggers `selectAllByItem(item.id)`, turning each page into one item query plus N tag queries on the I/O dispatcher. `ItemScreenModel` repeats the pattern. Large pages and rapid scrolling create avoidable Room scheduling and disk work on a hot path.
- **Fix:** Fetch tags in a Room relation/join, or batch-load tags for all item IDs in each page and map them in memory. Add a query-count or benchmark test for a tagged 50-item page.

### WorkManager output uses one global, non-persistent map

- **File:** `app/src/main/java/com/readrops/app/util/extensions/Extensions.kt:13-25`
- **Category:** bug  **Severity:** medium
- **Rule:** Kotlin (`*.kt`), OCR group 4
- **Problem:** The delegated extension property creates one static `MutableMap` shared by every `Data` receiver, rather than storage belonging to an individual `Data` instance. Concurrent syncs can overwrite each other's values, and `clearSerializables()` for one result clears all results. Nothing is serialized into WorkManager's persisted `Data`, so errors disappear after process death or worker/result recreation.
- **Fix:** Encode a bounded, supported representation into `Data` (for example structured error codes/messages as JSON or primitive arrays), or persist detailed errors in Room under a work ID. Remove the shared map and test concurrent work plus process recreation.

### Initial PagingSource failures are presented as empty content

- **File:** `app/src/main/java/com/readrops/app/util/extensions/LazyPagingItemsExtensions.kt:10-12`
- **Category:** bug  **Severity:** medium
- **Rule:** Kotlin (`*.kt`), OCR group 4
- **Problem:** `isError()` checks only `loadState.append`; the refresh-error check is commented out. Both the timeline and item screens rely on this helper, so a database failure during the initial refresh bypasses their error placeholder and falls into the empty/content branch. The item screen can then construct a pager with zero pages.
- **Fix:** Treat `refresh` as an error whenever it is `LoadState.Error`, and handle append errors separately as an inline retry/footer so existing items remain visible. Add UI tests for initial-load and append failures.

### Cleartext and user-added CAs are trusted for every host

- **File:** `app/src/main/res/xml/network_security_config.xml:3-12`
- **Category:** security  **Severity:** medium
- **Rule:** Default, OCR group 1
- **Problem:** The base configuration globally permits cleartext HTTP and trusts user-installed certificate authorities in all builds. Because remote accounts use Basic or token authentication, an HTTP account URL sends credentials without transport protection; global user-CA trust also broadens interception exposure for unrelated feeds, images, and service endpoints.
- **Fix:** Require HTTPS for credentialed accounts, scope any unavoidable cleartext/user-CA exception to explicit domains or debug builds, and show a deliberate security warning before accepting insecure self-hosted endpoints. Keep arbitrary feed/image clients isolated from authenticated clients.

### Remote-ID state updates cross account boundaries

- **File:** `db/src/main/java/com/readrops/db/dao/ItemDao.kt:42-43`
- **Category:** bug  **Severity:** medium
- **Rule:** Kotlin (`*.kt`), OCR group 4
- **Problem:** `updateReadAndStarState()` updates every `Item` with the supplied remote ID, with no feed/account predicate. `NextcloudNewsRepository` invokes it when an existing item is returned, so a colliding ID in another account is silently marked read/starred too. The preceding existence check is account-scoped, which makes the unscoped update especially easy to overlook.
- **Fix:** Add `accountId` to the DAO method and constrain through `feed_id IN (SELECT id FROM Feed WHERE account_id = :accountId)`, or update the known local item ID. Add a two-account collision test.

## Low

| File:Line | Category | Issue | Fix |
|---|---|---|---|
| `app/src/main/java/com/readrops/app/sync/SyncWorker.kt:103-111` | maintainability | `printStackTrace()` writes separately and interpolates `Unit`, while `Exception(e.cause)` discards the original exception's message and stack context. | Use `Log.e(TAG, "Synchronization failed", e)` and persist a stable error code plus a safe message instead of wrapping only the cause. |
| `build.gradle.kts:75-75` | test | Lint failures are globally non-blocking, so `./gradlew build` and CI can succeed with new correctness or security lint errors. | Migrate to `lint { abortOnError = true }`, establish a baseline for accepted legacy findings, and fail CI on new ones. |
| `db/src/main/java/com/readrops/db/entities/ItemState.kt:10-46` | performance | Room reports both `account_id` foreign keys as unindexed; the `(remote_id, account_id)` index cannot efficiently serve account-only cascade/update scans because `account_id` is not its leading column. | Add an `account_id` index to `ItemStateChange` and `ItemState` (retain the composite unique index for identity) and verify the migration. |

## Recurring patterns

- Remote IDs are treated as globally unique in at least six SQL paths even though the data model makes account part of identity. This affects timeline selection, item selection, folder/feed counts, new-item counts, pending state changes, and direct state updates.
- OkHttp `Response` ownership is manual and incomplete in `LocalRSSDataSource`, `FeedColors`, `FeverFaviconFetcher`, and `FeverDataSource.login`. A consistent `.use` boundary is needed at every synchronous `execute()` call.
- Risky synchronization behavior is tested mostly with one account and one successful pass. GReader/FreshRSS, Fever, and Nextcloud paths need replay, partial-failure, same-remote-ID/multi-account, and transaction-boundary tests.

## Recommended next steps

1. Fix all account-unscoped `ItemState` joins and add a three-account/same-ID Room regression test; this is the most direct explanation for articles appearing exactly two or three times.
2. Add an item uniqueness migration and make GReader/FreshRSS synchronization transactional and replay-safe. Before deleting rows, merge existing duplicate read/star/tag state and retain the canonical local ID deliberately.
3. Split authenticated and unauthenticated HTTP clients, make credentials immutable and host-bound, then disable JavaScript and sanitize article HTML.
4. Correct cross-account state updates and add end-to-end multi-account tests for timeline, unread counts, item detail, and sync state upload.
5. Close every HTTP response with `.use`, replace the global WorkManager side channel, and move disposal writes to durable structured work.
6. Address scoped-storage download, OPML/Fever-only empty-list crashes, refresh error handling, and paging N+1 queries.
7. Pin CI actions, restrict workflow permissions, re-enable lint gating, and run the instrumented database suite in addition to the passing unit suite.

## Coverage

- Reviewed by OCR rule group: Default group 1 — 123 files; GitHub funding YAML group 2 — 1 file; GitHub workflow YAML group 3 — 1 file; Kotlin group 4 — 218 files; JSON group 5 — 39 files; YAML group 6 — 1 file; properties group 7 — 2 files.
- Skipped files: none among the 385 OCR-reviewable files.
- Verification: `./gradlew --no-daemon testDebugUnitTest` completed successfully (64 tasks). JSON validation (`jq`) passed for all 39 reviewable JSON files; XML validation (`xmllint`) passed for all 104 reviewable XML files. Instrumented tests were reviewed but not executed in this local audit.
- OCR preview exclusions are not counted as skipped. Textual exclusions were read for context where relevant; binaries were inventory-checked only.

### Excluded by OCR: default path rules (38)

- `api/src/test/java/com/readrops/api/MockServerExtensions.kt`
- `api/src/test/java/com/readrops/api/TestUtils.kt`
- `api/src/test/java/com/readrops/api/localfeed/LocalRSSDataSourceTest.kt`
- `api/src/test/java/com/readrops/api/localfeed/LocalRSSHelperTest.kt`
- `api/src/test/java/com/readrops/api/localfeed/XmlAdapterTest.kt`
- `api/src/test/java/com/readrops/api/localfeed/atom/ATOMAdapterTest.kt`
- `api/src/test/java/com/readrops/api/localfeed/json/JSONFeedAdapterTest.kt`
- `api/src/test/java/com/readrops/api/localfeed/rss1/RSS1AdapterTest.kt`
- `api/src/test/java/com/readrops/api/localfeed/rss2/RSS2AdapterTest.kt`
- `api/src/test/java/com/readrops/api/opml/OPMLParserTest.kt`
- `api/src/test/java/com/readrops/api/services/CredentialsTest.kt`
- `api/src/test/java/com/readrops/api/services/fever/FeverDataSourceTest.kt`
- `api/src/test/java/com/readrops/api/services/fever/adapters/FeverAPIAdapterTest.kt`
- `api/src/test/java/com/readrops/api/services/fever/adapters/FeverFaviconsAdapterTest.kt`
- `api/src/test/java/com/readrops/api/services/fever/adapters/FeverFeedsAdapterTest.kt`
- `api/src/test/java/com/readrops/api/services/fever/adapters/FeverFoldersAdapterTest.kt`
- `api/src/test/java/com/readrops/api/services/fever/adapters/FeverItemsAdapterTest.kt`
- `api/src/test/java/com/readrops/api/services/fever/adapters/FeverItemsIdsAdapterTest.kt`
- `api/src/test/java/com/readrops/api/services/greader/GReaderDataSourceTest.kt`
- `api/src/test/java/com/readrops/api/services/greader/adapters/GReaderFeedsAdapterTest.kt`
- `api/src/test/java/com/readrops/api/services/greader/adapters/GReaderFoldersTagsAdapterTest.kt`
- `api/src/test/java/com/readrops/api/services/greader/adapters/GReaderItemsAdapterTest.kt`
- `api/src/test/java/com/readrops/api/services/greader/adapters/GReaderItemsIdsAdapterTest.kt`
- `api/src/test/java/com/readrops/api/services/greader/adapters/GReaderUserInfoAdapterTest.kt`
- `api/src/test/java/com/readrops/api/services/nextcloudnews/NextcloudNewsDataSourceTest.kt`
- `api/src/test/java/com/readrops/api/services/nextcloudnews/adapters/NextcloudNewsFeedsAdapterTest.kt`
- `api/src/test/java/com/readrops/api/services/nextcloudnews/adapters/NextcloudNewsFoldersAdapterTest.kt`
- `api/src/test/java/com/readrops/api/services/nextcloudnews/adapters/NextcloudNewsItemsAdapterTest.kt`
- `api/src/test/java/com/readrops/api/services/nextcloudnews/adapters/NextcloudNewsUserAdapterTest.kt`
- `api/src/test/java/com/readrops/api/utils/ApiUtilsTest.kt`
- `api/src/test/java/com/readrops/api/utils/AuthInterceptorTest.kt`
- `api/src/test/java/com/readrops/api/utils/ErrorInterceptorTest.kt`
- `api/src/test/java/com/readrops/api/utils/HtmlParserTest.kt`
- `api/src/test/java/com/readrops/api/utils/JsonReaderExtensionsTest.kt`
- `api/src/test/java/com/readrops/api/utils/KonsumerExtensionsTest.kt`
- `app/src/test/java/com/readrops/app/TemplateTest.kt`
- `app/src/test/java/com/readrops/app/UtilsTest.kt`
- `db/src/test/java/com/readrops/db/DateUtilsTest.kt`

Reason: OCR's configured default-path selection excluded these test paths from the reviewable diff. They were used as context, and the complete JVM test suite passed.

### Excluded by OCR: unsupported extensions (34)

- `.github/ISSUE_TEMPLATE/bug_report.md`
- `.github/ISSUE_TEMPLATE/feature_request.md`
- `CHANGELOG.md`
- `README.md`
- `api/proguard-rules.pro`
- `api/src/test/resources/opml/lite_subscriptions.opml`
- `api/src/test/resources/opml/subscriptions.opml`
- `api/src/test/resources/opml/wrong_version.opml`
- `app/proguard-rules.pro`
- `db/consumer-rules.pro`
- `db/proguard-rules.pro`
- `fastlane/metadata/android/en-US/changelogs/10.txt`
- `fastlane/metadata/android/en-US/changelogs/11.txt`
- `fastlane/metadata/android/en-US/changelogs/12.txt`
- `fastlane/metadata/android/en-US/changelogs/13.txt`
- `fastlane/metadata/android/en-US/changelogs/14.txt`
- `fastlane/metadata/android/en-US/changelogs/15.txt`
- `fastlane/metadata/android/en-US/changelogs/16.txt`
- `fastlane/metadata/android/en-US/changelogs/17.txt`
- `fastlane/metadata/android/en-US/changelogs/18.txt`
- `fastlane/metadata/android/en-US/changelogs/19.txt`
- `fastlane/metadata/android/en-US/changelogs/20.txt`
- `fastlane/metadata/android/en-US/changelogs/21.txt`
- `fastlane/metadata/android/en-US/changelogs/22.txt`
- `fastlane/metadata/android/en-US/changelogs/3.txt`
- `fastlane/metadata/android/en-US/changelogs/4.txt`
- `fastlane/metadata/android/en-US/changelogs/5.txt`
- `fastlane/metadata/android/en-US/changelogs/6.txt`
- `fastlane/metadata/android/en-US/changelogs/7.txt`
- `fastlane/metadata/android/en-US/changelogs/8.txt`
- `fastlane/metadata/android/en-US/changelogs/9.txt`
- `fastlane/metadata/android/en-US/full_description.txt`
- `fastlane/metadata/android/en-US/short_description.txt`
- `gradlew.bat`

Reason: OCR preview classified these Markdown, ProGuard, OPML, text, and Windows batch files as unsupported extensions. They were read as full files and used for documentation, parser-fixture, packaging, and historical context.

### Excluded by OCR: binary files (23)

- `app/src/androidTest/resources/favicon.ico`
- `app/src/main/assets/fonts/Inter-Bold.woff2`
- `app/src/main/assets/fonts/Inter-BoldItalic.woff2`
- `app/src/main/assets/fonts/Inter-Italic.woff2`
- `app/src/main/assets/fonts/Inter-Regular.woff2`
- `db/src/main/res/mipmap-hdpi/ic_launcher.png`
- `db/src/main/res/mipmap-mdpi/ic_launcher.png`
- `db/src/main/res/mipmap-xhdpi/ic_launcher.png`
- `db/src/main/res/mipmap-xxhdpi/ic_launcher.png`
- `db/src/main/res/mipmap-xxxhdpi/ic_launcher.png`
- `fastlane/metadata/android/en-US/images/featureGraphic.jpg`
- `fastlane/metadata/android/en-US/images/icon.png`
- `fastlane/metadata/android/en-US/images/phoneScreenshots/Screenshot_1.jpg`
- `fastlane/metadata/android/en-US/images/phoneScreenshots/Screenshot_2.jpg`
- `fastlane/metadata/android/en-US/images/phoneScreenshots/Screenshot_3.jpg`
- `fastlane/metadata/android/en-US/images/phoneScreenshots/Screenshot_4.jpg`
- `fastlane/metadata/android/en-US/images/phoneScreenshots/Screenshot_5.jpg`
- `fastlane/metadata/android/en-US/images/phoneScreenshots/Screenshot_6.jpg`
- `fastlane/metadata/android/en-US/images/promoGraphic.jpg`
- `images/fdroid-badge.png`
- `images/google-play-badge.png`
- `images/paypal-badge.png`
- `images/readrops_logo.png`

Reason: raster images, icon data, and fonts have no applicable source-code OCR checklist; they were accounted for as binary assets rather than line-reviewed.

### Omitted by OCR preview (1)

- `gradle/wrapper/gradle-wrapper.jar` — tracked binary Gradle bootstrap artifact; it was absent from both preview lists and was therefore added explicitly to full-tree accounting. The successful wrapper-driven test run provides an execution check, but the JAR was not decompiled for this source audit.
