# LeNews

A FreshRSS client for Android. One account, one service, and a lot of
articles — a few hundred a day is the shape it is built for.

Screenshots: none yet.

## What it does today

LeNews is honestly described as the FreshRSS half of Readrops with everything
else taken out. It talks to a FreshRSS server through the Google Reader API
that FreshRSS exposes, and it does what that path already did: log in, sync
feeds, folders and articles in the background, manage the subscriptions, show a
timeline, read an article in the app or in a browser, mark it read or starred,
share it, find again anything it has marked read, and notify you when a
background sync brings new ones.

What is gone is everything that was not FreshRSS: local RSS parsing, Nextcloud
News, the Fever API, OPML import and export.

One account is the scope LeNews is designed for, and it is what the code does:
the first screen is a FreshRSS login screen, and there is no way to add, switch
or delete an account because there is only ever one.

LeNews speaks HTTPS and nothing else. The login screen refuses a server address
that starts with `http://` before it sends anything, and the app's network
security configuration refuses cleartext at the socket, in every build. A
certificate authority the phone's owner installed themselves is trusted for one
host, `rss.lan` — the self-hosted FreshRSS this fork is built against, whose name
no public authority will ever sign — and for no other; everything else, article
images included, is checked against the preinstalled authorities alone. That
root is not bundled with the app: it belongs to whoever runs the server, and a
copy pinned here would break the day they re-key it. The consequence to know
about: another self-hosted server behind a private authority needs
`app/src/main/res/xml/network_security_config.xml` edited, and a feed that serves
its images over `http://` will not show them.

Where the three problems this fork exists to fix stand today:

- **Storing an article twice is no longer possible.** The article's identity is
  the number FreshRSS gives it, and that number is the primary key, so an
  article FreshRSS sends again — which it does on every sync — updates the row
  it already has. A sync is safe to repeat: it writes everything in one
  database transaction, so one that fails part-way changes nothing at all and
  the next one starts from the same place.
- **The timeline no longer slows down as articles accumulate, and the store no
  longer grows without bound.** Reading a page of any timeline, and the
  drawer's unread counts, take about the same time on a hundred thousand
  articles as on ten thousand. Every sync now also drops what the phone no
  longer needs: an article FreshRSS itself no longer returns, and any article
  read more than thirty days ago. Starred articles are kept whatever those two
  rules say. That is what bounds the one query no index could help — the count
  the list paging asks for on every reload — which takes 3.7 ms on the store a
  month of reading leaves, against 46.6 ms on a hoarded year of it.
- **An article you swiped away can be found again.** Every route by which an
  article becomes read records the moment it happened: opening it, swiping it,
  reading past it, marking the list, a feed, a folder, the starred articles or
  the last day read, and a read done on the FreshRSS web interface and learned
  at the next sync. *History*, in the drawer next to Articles, New articles and
  Favorites, is all of them in one list, newest first, showing the feed and the
  hour it became read; tapping one opens the article as the timeline does. It
  reaches back as far as the thirty days above, because past those the article
  itself is gone.

All three are addressed and every one of them is held to its behaviour by the
test suite. What has not happened is time: the sync, the retention rule and the
history have been checked against a real FreshRSS server in single sittings,
never watched across the week — or the thirty days — they are really about.

## Where it comes from

LeNews is a hard fork of the
[Readrops](https://github.com/readrops/Readrops) project (the original one),
taken at commit `9ebbe038`. It was forked because what was wanted was a client
for one FreshRSS account rather than a multi-service, multi-account one, because
the three problems above needed fixing in the database and the sync rather than
around them, and because doing that means restructuring code that upstream has
every reason to keep as it is. Upstream is not merged back in.

## Requirements

Android 12 or later (`minSdk 31`).

LeNews is Google-free: no Play Services, no Firebase, nothing from Google Mobile
Services in the dependency graph. It runs on GrapheneOS and on plain AOSP. This
is checked rather than promised: a Gradle task walks the full runtime classpath
of every variant of all three modules and fails if such a dependency appears,
including one pulled in indirectly. It is wired to `check` and to every task
whose name starts with `assemble`, `package`, `install` or `bundle`, so
`./gradlew assembleDebug`, `assembleRelease`, `packageDebug`, `installDebug`,
`bundleRelease`, `./gradlew check`, the quality gate below (its stage G4) and CI
all go red on it: no APK or app bundle can be built, and none installed on a
device, with such a dependency in it. Only a task that produces no artifact —
`./gradlew clean`, a plain `compileDebugKotlin` — gets past it.

## Building

You need the Android SDK with the `platforms;android-35` package, and JDK 21.

```
./gradlew assembleDebug
```

To run everything the project requires to be green — a preflight check, the
no-email guard, lint, unit tests, the Google-dependency check, the debug and
release APKs, and the instrumented database and sync tests on an emulator:

```
scripts/check.sh
```

Nothing is committed with that red. `SKIP_INSTRUMENTED=1 scripts/check.sh`
leaves the emulator stage out for a quick iteration, and says so.

To autofill the login form on a debug build, put your own FreshRSS details in
the project's `local.properties`, which is not tracked:

```properties
debug.freshrss.url=https\://<your_instance>
debug.freshrss.login=<login>
debug.freshrss.password=<password>
```

A release build ignores them.

## Getting it

Releases are published on
[GitHub](https://github.com/jmnicolas90/LeNews/releases). LeNews is not on
F-Droid and not on the Play Store, and there is no plan to put it there.

## Feedback

[GitHub issues on this repository](https://github.com/jmnicolas90/LeNews/issues)
are the only contact channel. There is no mailing list, no chat and no email
address. Please search the
[existing issues](https://github.com/jmnicolas90/LeNews/issues?q=is%3Aissue)
first, then open a bug report or a feature request from the templates. If you
want to work on the code, see [CONTRIBUTING.md](CONTRIBUTING.md).

## Licence

GPL-3.0, as the original was. There is no warranty. The full text is in
[LICENSE](LICENSE).

Copyright (C) the Readrops authors for the code inherited from the original
project, and Copyright (C) 2026 Jean-Michel Nicolas for this fork's own files
and changes.
