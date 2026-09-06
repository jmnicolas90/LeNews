# 27 — What to do about the 22 inherited Readrops tags on `origin`

Type: grilling
Status: resolved
Blocked by: —

## Question

Surfaced by [ticket 23](23-release-signing.md) while deciding what "publishing"
means here, and deliberately left out of it: the signing decision does not
depend on this one.

`git tag -l` lists **22 tags, `v1.0` through `v2.1.1`**, and `git ls-remote
--tags origin` shows they are all on `github.com/jmnicolas90/LeNews`. They are
upstream's release history, inherited with the fork. LeNews itself is
`versionCode 1`, `versionName 0.1.0`.

The map says **releases are tags** and distribution is GitHub releases. So:

- A LeNews `v0.1.0` tag sorts **below every inherited tag** in any version
  ordering. Someone browsing the repository sees `v2.1.1` as the newest-looking
  thing in it, pointing at code this fork has rewritten most of.
- Those tags point at commits that *are* genuinely in this history — the fork is
  a real ancestor chain back through Readrops — so deleting them deletes a true
  record, not a false one. That cuts both ways.
- `CHANGELOG.md` deliberately keeps the *Readrops history* section below
  LeNews's own, so there is already a precedent for keeping upstream's record
  visible **as upstream's**, clearly separated. Tags have no such separation
  mechanism beyond their names.

The options, none of them obviously right:

- **Delete them from `origin`, keep them locally.** The public repository shows
  only LeNews's releases; the history is still reachable by commit and still
  recorded in `CHANGELOG.md`. Deleting a pushed tag is a published-history
  change, though nobody is watching this repository yet.
- **Keep them, and start LeNews above them** at `v3.0.0` or similar. No deletion,
  no confusion about which is newest — but the version number then lies about
  what LeNews is, and `versionName 0.1.0` in the build would have to move too.
- **Keep them, and prefix LeNews's own** (`lenews-v0.1.0`). Nothing is deleted
  and nothing sorts wrongly, at the cost of an ugly tag name for ever.
- **Rename the inherited ones** (`readrops-v2.1.1`), which is a delete and a
  create on `origin` either way, but keeps the record and its attribution.

**Done when** the decision is written in this ticket's `Answer` — what happens to
the 22 tags, what LeNews's first tag is called, and whether `versionName` moves —
specific enough that doing it needs no further judgement. **Doing it is a
separate ticket** if it turns out to be more than a handful of git commands.

## Answer (2026-09-06)

Decided in conversation, nine questions. **The 22 tags are deleted, from `origin`
and from this clone; LeNews's first tag will be `v1.0.0`; and `versionName` moves
from `0.1.0` to `1.0.0`** — up rather than down, which is the one outcome the
question as written did not anticipate. The deletion is done, in this ticket. The
version bump is [ticket 28](28-version-1-0-0.md); cutting the release itself is
neither, and gets a ticket when [ticket 24](24-baseline-profile.md) is answered.

### Four facts that decided it, none of them in the question

The question weighed "deleting them deletes a true record" against the confusion,
and treated that as a genuine trade. It is not one, and the reason is four things
nobody had looked up:

- **There were no GitHub Releases at all.** `gh release list` on
  `jmnicolas90/LeNews` returned nothing. The 22 were bare refs — no release page,
  no notes, no attached APK. Deleting one deleted a ref and nothing else. The
  option "delete them from `origin`" was therefore far cheaper than it sounded;
  it was not deleting anything a reader could have read.
- **Upstream still publishes every one of them.** `readrops/Readrops` is public
  and unarchived, and serves **22 tags and 21 releases** — with the notes and
  artifacts this repository never had. LeNews's copies duplicated a record that
  is safe somewhere else and maintained by the people who wrote it.
- **GitHub states the provenance without any tag.** The repository is
  `fork=true`, parent `readrops/Readrops`, and the page prints "forked from
  readrops/Readrops" above everything else. The attribution the tags were
  imagined to carry was never carried by them.
- **Nobody is downstream.** 0 forks, 0 watchers, 0 stars. No clone anywhere holds
  these refs, so deleting a pushed ref broke nothing for anyone.

Against that, the confusion is real but small: a release here is a **stable home
for an APK on the user's own devices**, not a public download — that was settled
first, because it sets how much the shopfront argument is worth. It is worth
little. But so was the cost of deleting, and between two small numbers the one
that leaves the repository saying only true things wins.

**Deleted locally too, not just on `origin`.** The question proposed keeping the
local copies "so the history is still reachable". That reasoning does not hold:
the commits are reachable regardless — every one of the 22 is still an ancestor
of `main`, checked after the deletion — so the tags added no reachability. What a
local copy did add was a hazard: one `git push --tags`, which is a thing people
type, and all 22 come back on `origin` silently. `git tag -l` is empty until
`v1.0.0`.

### What was deleted

`git push --delete origin` for all 22, then `git tag -d` for the same 22.
Twenty-one were lightweight; `v1.0.1` was the one annotated tag. For the record,
tag to commit — every one of these commits is still in this history:

```
v1.0        1e72cf29    v1.1.4      bb62fd79    v2.0.1      c88c1755
v1.0.1      3afa5b2b    v1.2.0      a01e7408    v2.0.2      383aea07
v1.0.2      be04139d    v1.2.1      1ef9757a    v2.0.3      1b54518c
v1.0.2.1    cc77edf0    v1.3.0      fb069901    v2.1.0      8edecc0a
v1.0.2.2    8867eefe    v1.3.1      7feb54f4    v2.1.1      dcd7a9f3
v1.1.0      3d98aa57    v2.0        377ab70a
v1.1.1      0202df95    v2.0-beta01 6e2051b4
v1.1.2      3a0667a1    v2.0-beta02 cddca8f8
v1.1.3      66616b57
```

Verified after: 0 local tags, 0 tags on `origin`, 22 still on
`readrops/Readrops`, and `1e72cf29` and `dcd7a9f3` both still ancestors of
`main`.

**Nothing in the repository records the removal**, and that is deliberate.
`CHANGELOG.md` already keeps upstream's 242 lines unedited under *Readrops
history (before the fork)*, `README.md` already links the original project and
names the fork point, and GitHub prints the fork banner. A sentence explaining
the absence of tags no reader knew were there is archaeology.

### The first release is `1.0.0`, not `0.1.0`

This is the user's call and it reverses what [ticket 05](05-rename-to-lenews.md)
chose: **the app is usable now, so the first release is `1.0.0`**. `0.1.0` was
picked when the fork was a rename and three known pain points; they are fixed, and
a version number that says "not really working yet" would now be the false
statement.

It also makes the sorting worry moot twice over — with the inherited tags gone
there is nothing to sort against, and `1.0.0` would not have collided with them
anyway.

Three consequences, all settled:

- **The tag is `v1.0.0`**, with the `v`. With upstream's tags gone there is no
  consistency argument either way, so it is the convention a stranger's tooling
  expects.
- **`versionCode` stays at `1`.** It only has to increase, never to match the
  name, and nothing has ever been installed from a release, so no device holds a
  higher one. Moving it for symmetry would burn a number and buy nothing.
- **`versionName` moves now, the tag waits.** The tree should stop claiming 0.x
  immediately; the actual `v1.0.0` tag is not imminent. Three things are open and
  want doing first — the **locales call** (14 inherited languages, many partly
  orphaned, 346 lint-baseline errors of translation debt), **ticket 24**, which
  has not yet measured a release build on hardware, and the **days-long real-use
  check**, so nothing has confirmed the thirty-day horizon actually fires. None
  blocks a tag; all three block calling it finished.

### What happens next

- **This ticket** deleted the tags. No file changed, so no gate was needed for
  that half; the bookkeeping below rides the gate like any other commit.
- **[Ticket 28](28-version-1-0-0.md)** moves `versionName` to `1.0.0` across the
  four live files that name it — `app/build.gradle.kts`, two lines of
  `CHANGELOG.md`, `CLAUDE.md`, and the example in
  `.github/ISSUE_TEMPLATE/bug_report.md`. The About screen and the
  `User-Agent` read `versionName` at build time and follow on their own; the
  User-Agent becomes `LeNews/1.0.0`. Mentions of `0.1.0` in resolved tickets and
  in the map's *Decisions so far* are historical record and stay as they are.
- **Cutting `v1.0.0`** gets its own ticket after 24, deliberately not opened now
  so that it does not sit there implying a date.
