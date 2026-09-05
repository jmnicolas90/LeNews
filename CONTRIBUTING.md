# Contributing

LeNews is a personal fork with one maintainer, and external contributions are
not expected. Issues are welcome — a bug report or a concrete suggestion on
[the tracker](https://github.com/jmnicolas90/LeNews/issues) is genuinely useful.
Pull requests may be closed without review; please open an issue first rather
than spending an evening on a patch nobody asked for.

If you do work on the code, `CLAUDE.md` at the repository root is the working
document: what the app is, the constraints, the gate, the conventions and the
domain vocabulary. This file only states the few things a contributor has to
agree to before writing a line, and does not repeat it.

## Copyright headers

LeNews is a fork, so a file's header records who wrote the file, not who owns
the project.

- **A file this fork creates carries the fork's copyright line**, followed by
  the GPL notice as in the rest of the project:

      Copyright (C) 2026 Jean-Michel Nicolas

  Use the comment syntax the file's language provides — `#` for shell scripts
  and YAML, `<!-- -->` for XML, `/* */` for Kotlin and Gradle — and keep the
  wording the same everywhere.
- **The name only, never an email address**, in a header as anywhere else.
- **Headers inherited from Readrops are never removed, edited or replaced**,
  including their copyright years. They are the attribution the GPL requires.
  A fork copyright line is *added* underneath upstream's, never substituted for
  it, and only where the fork's changes to the file are substantial. As it
  happens Readrops shipped no per-file copyright headers at all — the inherited
  Kotlin, Gradle and XML files carry none — so there is nothing in this tree to
  preserve except `LICENSE`, which stays byte-identical. Do not go adding
  headers to inherited files: a header on a file this fork did not write would
  claim it.
- **Markdown documentation takes no header.** `README.md`, `CLAUDE.md`,
  `CONTEXT.md`, `CHANGELOG.md`, this file, and everything under `docs/` and
  `.scratch/` say in their own prose who wrote them and where their text came
  from. A document that reproduces someone else's writing — `CHANGELOG.md` does
  — must never carry a fork copyright claim over it.

## The gate is the bar

`scripts/check.sh` from the repository root, green, before every commit. It runs
the same stages CI runs, in the same order. Never commit with it red, and do not
weaken a stage to get past it; if a stage is wrong, fix the stage and say so.

## Branches and commits

- One long-lived branch, `main`. Work happens in a short-lived worktree on a
  branch of its own under `.claude/worktrees/`, one task each, merged back into
  `main` with `--no-ff` once the gate is green. Never commit directly on `main`.
  Releases are tags.
- **No personal email address anywhere** — not in a file, not in a file name,
  not in commit metadata. Commits use a GitHub no-reply address, configured
  repository-locally. Stage G1 of the gate fails on a real one, in the working
  tree, in the index, in the identity the next commit would carry, and in this
  fork's history.
- **When a model wrote the code, the commit says so**, with a trailer naming
  that model at its own vendor's no-reply address, for instance:

      Co-authored-by: Opus 5 <noreply@anthropic.com>

  A model never signs as one it is not. The trailer is what keeps `git log`
  usable as a record of who wrote what.
