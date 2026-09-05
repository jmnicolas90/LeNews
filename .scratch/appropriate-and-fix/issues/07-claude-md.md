# 07 — Write CLAUDE.md

Type: task
Status: open
Blocked by: 05

## Question

Every agent session in this repo needs one page that says what the app is, what must never break, how to prove the tree is good, and where the tickets live. Adapt `../Ding/CLAUDE.md` — same section order, trimmed and retargeted: fork origin (`readrops/Readrops` at `9ebbe038`, 2025-07-20, GPL-3.0, hard fork, upstream still active so cherry-picks are by hand); the hard constraints from the map, each with how it is enforced; the gate as a one-liner and as a per-stage table, G7 included, with the notes that save time (which SDK package G0 wants, which AVD G7 wants, that the first run is slow); working conventions (plain language, one trunk `main`, worktrees under `.claude/worktrees/`, `--no-ff`, never commit red, the co-author trailer rule with `Fable 5.1 <noreply@anthropic.com>` as the current example); domain vocabulary pointing at `CONTEXT.md` rather than duplicating it, plus the two or three facts about articles and sync that every session trips over; tickets and bookkeeping (this directory, routing by `Type:`); environment (`$ANDROID_HOME`, JDK, French locale).

Write it after tickets 03 and 05 have landed so it describes the tree as it is, not as intended. Where something is still pending (ticket 13's single-account collapse, the retention rule), say so rather than describing the future.

**Done when** `CLAUDE.md` exists at the repo root, every command it names runs as written, and a fresh agent given only it and a ticket can find the gate, the tracker and the glossary without asking.
