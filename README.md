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
share it, and notify you when a background sync brings new ones.

What is gone is everything that was not FreshRSS: local RSS parsing, Nextcloud
News, the Fever API, OPML import and export, multi-account. The account screen
is on its way to being a login screen.

What is **not** done yet — the three problems this fork exists to fix:

- **It still slows down as articles accumulate.** The database has the indexes
  it inherited and nothing more, and articles are never deleted.
- **An article can still be stored twice.** The sync inserts what the server
  returns without checking whether it is already there, and FreshRSS re-sends
  the boundary article on every sync as a matter of course.
- **There is still no history.** An article swiped away is not findable again
  in a list of what became read, and when.

Those are the next pieces of work, not features you have today.

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
is checked rather than promised — stage G4 of the quality gate walks the full
runtime classpath of every variant of all three modules and fails the build if
such a dependency appears, including one pulled in indirectly.

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
