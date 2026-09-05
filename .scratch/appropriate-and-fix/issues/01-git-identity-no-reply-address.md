# 01 — Commit with a no-reply address in this repo

Type: task
Status: resolved
Blocked by: —

## Question

The global git config carries a personal email address, and this repo has no local override, so every commit made here so far and every commit an agent makes next leaks it into history. The hard constraint says no personal address anywhere, commit metadata included.

Set `user.name` and `user.email` repo-locally to the same GitHub no-reply identity Ding uses (`git -C ../Ding config user.email` shows it). Two `git config` lines, nothing to test, no diff to review. Do not rewrite existing history: the fork's history is upstream's and the fork-point commit is not ours.

**Done when** `git config --show-origin user.email` in this repo points at `.git/config` and shows a `users.noreply.github.com` address.

## Answer (2026-09-05)

Set repo-locally, in `.git/config`, before any commit was authored here:

- `user.email` = `68194446+jmnicolas90@users.noreply.github.com`, the same GitHub no-reply address Ding uses.
- `user.name` = `Jean-Michel NICOLAS`, kept: a name is not harvestable the way an address is.

`git config --show-origin user.email` reports `file:.git/config`, and `git var GIT_AUTHOR_IDENT` / `GIT_COMMITTER_IDENT` both carry the no-reply address. The global config still holds the personal address and is untouched; every other repo on this machine is on its own. No history was rewritten: everything before this commit is upstream's.

Nothing was reviewed: two config lines, no diff.
