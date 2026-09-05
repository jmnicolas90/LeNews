#!/bin/bash
# Copyright (C) 2026 Jean-Michel Nicolas
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <http://www.gnu.org/licenses/>.

# Codex adversarial review helper — makes launching repeatable and
# workspace-safe. The companion runtime scopes every job to the directory
# it is started from, so each subcommand takes the worktree directory as a
# required first argument and cd's there itself; the caller never needs an
# inline `cd`.
#
# Usage:
#   scripts/codex-review.sh launch <worktree-dir> <base-sha> <briefing-file>
#       Start a review of the worktree's branch diff against <base-sha>.
#       Runs in the foreground for the whole review (6–10 min typical):
#       run it with the shell tool's background mode.
#
#   scripts/codex-review.sh find <worktree-dir> <base-sha>
#       Print the id of the newest job in this workspace whose summary
#       mentions <base-sha> (run ~10 s after launch).
#
#   scripts/codex-review.sh wait <worktree-dir> <job-id> [timeout-ms]
#       Block until the job finishes or the timeout (default 480000 ms)
#       elapses. Prints one JSON line: id, status, phase, waitTimedOut,
#       summary. waitTimedOut=true means still running — call again.
#       Ceiling per the skill: 30 min from launch, then cancel.
#
#   scripts/codex-review.sh result <worktree-dir> <job-id>
#       Print verdict, summary, and findings as JSON.
#
#   scripts/codex-review.sh cancel <worktree-dir> <job-id>
#       Cancel a job (stale, orphaned, or past the ceiling).
set -euo pipefail

die() { echo "codex-review: $*" >&2; exit 1; }

[ $# -ge 2 ] || die "usage: codex-review.sh <launch|find|wait|result|cancel> <worktree-dir> ..."
cmd=$1
worktree=$2
shift 2

[ -d "$worktree" ] || die "worktree directory not found: $worktree"
cd "$worktree"

companion=$(ls -d "$HOME"/.claude/plugins/cache/openai-codex/codex/*/scripts/codex-companion.mjs 2>/dev/null | sort -V | tail -1)
[ -n "$companion" ] || die "codex-companion.mjs not found under ~/.claude/plugins/cache/openai-codex"

case "$cmd" in
  launch)
    [ $# -eq 2 ] || die "launch needs: <base-sha> <briefing-file>"
    base=$1; brief=$2
    git rev-parse --verify --quiet "$base^{commit}" >/dev/null || die "base sha not found in this checkout: $base"
    [ -s "$brief" ] || die "briefing file missing or empty: $brief"
    # base and briefing stay separate arguments — collapsed into one
    # string the companion re-splits them and the briefing arrives mangled
    exec node "$companion" adversarial-review --base "$base" "$(cat "$brief")"
    ;;
  find)
    [ $# -eq 1 ] || die "find needs: <base-sha>"
    base=$1
    # note: on completion the companion replaces a job's summary with the
    # review verdict, so the base sha only stays visible in running (and
    # cancelled) jobs — run this shortly after launch, and check the
    # status printed on stderr
    env -u CODEX_COMPANION_SESSION_ID node "$companion" status --all --json | python3 -c "
import json, sys
base = sys.argv[1]
d = json.load(sys.stdin)
jobs = []
def walk(o):
    if isinstance(o, list):
        for x in o: walk(x)
    elif isinstance(o, dict):
        if o.get('kind') == 'adversarial-review' and base in (o.get('summary') or ''):
            jobs.append(o)
        else:
            for v in o.values(): walk(v)
walk(d)
if not jobs:
    sys.exit('no adversarial-review job mentions ' + base + ' in this workspace')
rank = {'running': 2, 'completed': 1}
jobs.sort(key=lambda j: (rank.get(j.get('status'), 0), j.get('createdAt') or ''))
best = jobs[-1]
print('picked job with status: ' + str(best.get('status')), file=sys.stderr)
print(best['id'])
" "$base"
    ;;
  wait)
    [ $# -ge 1 ] || die "wait needs: <job-id> [timeout-ms]"
    job=$1; timeout=${2:-480000}
    node "$companion" status "$job" --wait --timeout-ms "$timeout" --poll-interval-ms 15000 --json | python3 -c "
import json, sys
d = json.load(sys.stdin)
j = d.get('job') or d
print(json.dumps({k: j.get(k) for k in ('id', 'status', 'phase', 'waitTimedOut', 'summary')}))
"
    ;;
  result)
    [ $# -eq 1 ] || die "result needs: <job-id>"
    job=$1
    node "$companion" result "$job" --json | python3 -c "
import json, sys
d = json.load(sys.stdin)
r = (d.get('storedJob') or {}).get('result', {}).get('result')
if not r:
    sys.exit('no parsed result on job — inspect the full JSON with the companion directly')
print(json.dumps({k: r.get(k) for k in ('verdict', 'summary', 'findings')}, indent=1))
"
    ;;
  cancel)
    [ $# -eq 1 ] || die "cancel needs: <job-id>"
    exec env -u CODEX_COMPANION_SESSION_ID node "$companion" cancel "$1"
    ;;
  *)
    die "unknown subcommand: $cmd"
    ;;
esac
