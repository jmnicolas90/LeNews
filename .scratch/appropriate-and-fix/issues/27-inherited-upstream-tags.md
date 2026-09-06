# 27 — What to do about the 22 inherited Readrops tags on `origin`

Type: grilling
Status: open
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
