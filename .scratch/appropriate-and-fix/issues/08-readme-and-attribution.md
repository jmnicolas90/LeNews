# 08 — Rewrite the README and get the fork's attribution right

Type: task
Status: open
Blocked by: 06

## Question

After ticket 06 the README is a hollowed-out Readrops page. Write LeNews's: what it is (a FreshRSS client for Android, one account, built for a high article volume), what it does today, the fork relationship with exactly one link to `readrops/Readrops` labelled as the original project and one sentence on why it was forked, Android 12+ / `minSdk 31`, Google-free on GrapheneOS and AOSP stated as an enforced property, how to build (`./gradlew assembleDebug`) and how to check (`scripts/check.sh`), GitHub releases as the only distribution, GitHub issues as the only contact channel.

Attribution: the copyright line for fork-authored files is `Copyright (C) 2026 Jean-Michel Nicolas` — git author name, never an email address — *added* alongside upstream's on files the fork creates (the gate scripts, the CI workflow, `CONTEXT.md` if it takes a header), never substituted on inherited files, whose headers and years stay exactly as they are. State that rule in a short `CONTRIBUTING.md` (upstream has none; the file also says that external contributions are not expected). Keep `LICENSE` byte-identical.

Upstream's `CHANGELOG.md` records Readrops releases; keep it as history under a heading that says so, and start LeNews's own changelog above it.

Screenshots: none. Do not ship upstream's images under the new name and do not invent any; leave a marked gap.

**Done when** the gate is green, the README describes LeNews and only LeNews, and `grep -rn "Copyright"` shows upstream's lines untouched and the fork's line only on fork-authored files.
