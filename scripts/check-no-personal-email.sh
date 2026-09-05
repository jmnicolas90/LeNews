#!/usr/bin/env bash
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

# Fails when an email address appears in a tracked file, in the staged index, in
# the identity the next commit would carry, or anywhere in the commits this fork
# authored.
#
# The map's constraint is "no personal email address anywhere" — not in the
# tree, not in commit metadata, not in published artifacts. This is the enforced
# half of it, the way checkNoGoogleDependencies enforces the no-Google rule
# rather than leaving it to good intentions. The repo is public, so an address
# that reaches a push is published to harvesters the moment it lands, and taking
# it back means rewriting history.
#
# Five checks, and all five run: the script reports everything it finds rather
# than stopping at the first hit, so one run tells you the whole job.
#
#   1. Every tracked file in the working tree.
#   2. Every tracked file as it is staged in the index. The index is not the
#      working tree: `git add -p` can stage a hunk holding an address while the
#      file on disk is being cleaned up around it, and the commit takes what is
#      staged. Without this check that commit lands after a green gate.
#   3. The author and committer identity the *next* commit would carry — the
#      name as well as the address — which is the one check that fires before
#      anything has been written.
#   4. Every commit this fork authored — author and committer name and address,
#      the whole message including its trailers, and the commit's own tree. The
#      tree matters because a clone receives every historical blob: an address
#      that was committed and redacted two commits later is still published,
#      and only this check sees it.
#   5. The names of tracked files, in the index and in the tree of every fork
#      commit. A path is published exactly as loudly as a line of a file — it
#      is in `git ls-files`, in every clone's checkout and in the web view of
#      the repository — and nothing else here looks at paths as text.
#
# Check 4 needs real history, so a shallow clone, or a clone missing the fork
# point, is a failure and not a pass — the check must not look green exactly
# where it can see the least. That is why .github/workflows/ci.yml sets
# fetch-depth: 0 on its checkout.
#
# Upstream Readrops' own commits carry the upstream author's address. They are
# excluded from check 4 by commit range — by reachability, never by naming an
# address here, which would put in this file the very thing the file exists to
# keep out of the repo. One boundary is enough: this is a hard fork of a single
# branch, and upstream's master is an ancestor of the fork point, so everything
# reachable from the fork point is upstream's and everything else is ours.
#
# Two relaxations, both narrow, both only on the scan of historical trees, which
# is the one place the fork cannot put anything right without rewriting history.
#
#   a. By address value: the set of addresses the fork point tree already
#      publishes. The fork's early commits carry upstream's files unchanged, so
#      those addresses sit in the fork's own trees as well as in upstream's.
#      Reporting them forever would buy nothing and would bury real findings
#      under noise. This one is deliberately not bound to particular commits:
#      an address that upstream published in its own tree is not something this
#      fork can unpublish — upstream's commits stay in this repo's ancestry for
#      good, and every clone receives them — so wherever such an address turns
#      up in a fork commit's tree it is already public through a commit no
#      rewrite of ours could reach. The set is read out of the fork point at run
#      time and is never written into this file, so this file still names no
#      address.
#   b. By blob content: the exact bytes of docs/research/upstream-since-fork.md
#      as ticket 10 wrote it. Those findings quoted, from a public upstream
#      issue thread, the address that upstream publishes as its contact. Ticket
#      03 removed the quote from the working tree, but the five commits that
#      carry that blob are already in this history and only a rewrite would
#      unpublish them. The exemption is on the content and not on the path,
#      because a path exemption would hold for every commit that ever has that
#      file — an address written into it after this gate was built and taken out
#      again before the gate ran would have passed. A blob id is the bytes that
#      are already published and nothing else. It applies in historical trees
#      alone: the working tree and the index are still checked, so the quote
#      cannot come back.
#
# Neither relaxation touches the working tree, the index, an identity or a
# commit message. A fork commit that copies an inherited address into a new file
# is caught before it can land, by checks 1 and 2.
#
# Both scripts/check.sh (stage G1) and .github/workflows/ci.yml run this one
# file, so the pattern, the allowlist and the commit range live in a single
# place and the two gates cannot drift apart on them.
#
# Allowed everywhere, and why:
#   - LICENSE — the GPL text itself, which carries the Free Software
#     Foundation's own address. Attribution the licence requires, not a leak.
#   - a no-reply address, as is_no_reply_address defines it and nothing wider:
#     the forge and vendor no-reply addresses that commits and co-author
#     trailers are signed with. The test is on the matched address itself and
#     not on the line it sits on, so a real address cannot hide beside a
#     no-reply one.
#   - Kotlin's qualified-this syntax, as is_kotlin_qualified_this defines it: a
#     label such as `this@ItemScreenModel` followed by a member is not an
#     address, and the address pattern cannot tell the two apart. Three of those
#     sit in this tree today and more will be written; without this test the
#     gate would be red on ordinary Kotlin.
# The GPL copyright headers are deliberately NOT allowlisted: they carry names,
# not addresses. If one ever gains an address, the header is the thing to fix.
#
# It reports the commit, the file and the line number and never prints the
# address itself, so a failing gate does not republish what it just caught. A
# path can itself hold an address, so every location this script prints goes
# through redact() first.
# That is also why tracing is turned off below and never turned back on, and why
# no comment in this file spells out an example address: this script is itself a
# tracked file, and check 1 reads it like any other.

# An inherited `bash -x` would print every matched address to stderr, which is
# exactly the republishing this script exists to prevent. Off before anything
# else runs, and nothing here turns it back on.
set +x
set -euo pipefail

cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Case-insensitive on both sides: the character classes accept either case, and
# every grep below is given -i as well. Either alone would do; together, neither
# a future edit to the pattern nor a dropped flag at one call site can quietly
# let an all-capitals address through.
#
# The local part accepts every character RFC 5322 allows in an unquoted address
# — the alphanumerics, the dot, and ! # $ % & ' * + / = ? ^ _ ` { | } ~ - —
# rather than a polite subset of them. A narrow class does not merely miss such
# an address, it mis-reads it: the match starts *after* the character the class
# does not know, and the fragment that is left can look like something the
# allowlist below waves through. The no-reply test is a test of the whole local
# part, so the whole local part has to be what was matched.
#
# It has to start at an alphanumeric all the same, because the text around an
# address is often punctuation the class now contains — a backquote in
# markdown, an angle bracket in a trailer — and dragging that in would break the
# same test in the other direction. No real mailbox begins with punctuation; one
# that did would still be reported, with its first character missing from a
# value this script never prints anyway.
address_pattern="[A-Za-z0-9][A-Za-z0-9.!#\$%&'*+/=?^_\`{|}~-]*@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"

# Paths where an address is attribution the licence requires.
attribution_paths=(
  ':!LICENSE'
)

# Blobs exempt from the historical-tree scan only; see relaxation (b) above.
# Content, not paths, so the exemption covers the bytes that are already
# published and stops there.
declare -A historical_only_exempt_blobs=(
  # docs/research/upstream-since-fork.md as ticket 10 wrote it. Five commits
  # carry it: the research commit 48a3360e, its merge 1402ea19, and the three
  # commits of ticket 02 that sit between that merge and the commit which took
  # the quote out. Any other content of that file, in any commit, is scanned.
  ['b4317da144155078333c4b98543cf38d2f4744b0']=1
)

# The fork point on upstream's develop. Everything reachable from it is
# upstream's; everything else in this history is the fork's own and has to
# answer for itself.
fork_point='9ebbe038'

failures=0

# Report one problem. Every argument is printed on its own line, so a caller can
# pass the headline, the hits and the advice as separate strings. Callers pass
# locations, never the text that matched.
fail() {
  printf '%s\n' "$@" >&2
  failures=$((failures + 1))
}

# Blank out any address inside a location before it is printed. A location is a
# path, and a path can hold an address itself, so reporting one verbatim would
# republish exactly what the report is careful never to print.
#
# Component by component, because the local part may hold a slash and a greedy
# match would then swallow the directories on either side of the name, leaving a
# report that says an address was found somewhere. A path separator is part of
# no address, so splitting on it costs nothing and the report still says where.
redact() {
  local text="$1" out='' part separator=''
  local -a parts=()
  IFS='/' read -r -a parts <<< "$text"
  for part in "${parts[@]}"; do
    out="$out$separator$(redact_field "$part")"
    separator='/'
  done
  printf '%s' "$out"
}

# One component of a location. The colon is the s/// delimiter: the pattern
# holds no colon, while it does hold a comma (in the {2,} repetition) and most
# of the punctuation sed would otherwise accept. I is GNU sed's case-insensitive
# flag, matching the -i every grep here is given.
#
# A failed redaction prints a placeholder rather than nothing. An empty string
# would read to the caller as "no hit at all", which is the one answer this
# script must never give by accident.
redact_field() {
  local redacted
  if redacted="$(printf '%s' "$1" | sed -E "s:$address_pattern:<address withheld>:gI")"; then
    printf '%s' "$redacted"
  else
    printf '%s' '<withheld: redaction failed>'
  fi
}

# The one test for "this is a no-reply address", used by every check, so there
# is a single answer to the question rather than one answer per call site.
#
# Strict on purpose. Asking whether the address merely *contains* "noreply" is
# something a real mailbox can trivially arrange: put the word in the local part
# beside a real name, or register a domain with the word in it, and a
# deliverable address walks through the gate. An address qualifies here only
# when its local part is exactly "noreply", or its domain is exactly GitHub's
# per-user no-reply domain. Anything else is a hit. Both halves are compared
# lowercased, because neither a local part nor a domain is case-sensitive in any
# address this repo signs with.
is_no_reply_address() {
  local address="$1" local_part domain
  local_part="${address%@*}"
  domain="${address#*@}"
  [ "${local_part,,}" = 'noreply' ] || [ "${domain,,}" = 'users.noreply.github.com' ]
}

# The one test for "this match is Kotlin, not an address". A qualified this — a
# `this@Companion` label followed by a member — has the shape of an address and
# is none: the local part is the keyword `this`, which no mailbox is named, and
# a qualified this only ever appears in Kotlin source.
#
# Three conditions, all required. The file is a .kt, so no other kind of file
# passes by starting a line with the keyword. The local part is exactly `this`,
# lowercase, because that is the keyword and nothing else is. And what stands
# where the domain would stand has the shape a qualified this actually has: a
# label naming a class or an object, which by Kotlin convention starts with a
# capital, then one or more members. That last condition is the one that matters
# for a guard: without it anything at all after the keyword and the at sign was
# exempt in a .kt file, a real domain included, and a string literal in the
# middle of the code is precisely where an address gets written. What is left
# exempt is a label like `Companion` or `ItemScreenModel` followed by a member,
# which is not a mailbox anyone publishes by accident; anything with an ordinary
# lowercase domain after it is now reported, in a .kt file like anywhere else.
#
# $1 is the matched address, $2 the "file:line" it was found at. The path is
# everything before the last colon of that location, and a path may hold colons
# of its own, so strip only the final field.
is_kotlin_qualified_this() {
  local address="$1" location="$2" path label
  local kotlin_label='^[A-Z][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+$'
  path="${location%:*}"
  [ "${path%.kt}" != "$path" ] || return 1
  [ "${address%%@*}" = 'this' ] || return 1
  label="${address#*@}"
  [[ "$label" =~ $kotlin_label ]]
}

# Is this historical hit inside one of the blobs relaxation (b) exempts? $1 is
# the commit being scanned, $2 the "<commit>:<path>:<line>" git grep reported.
# The revision prefix is what this scan passed in and the line number is the
# last field, so strip both; whatever colons remain belong to the path.
is_exempt_historical_blob() {
  local commit="$1" location="$2" path blob
  path="${location#"$commit":}"
  path="${path%:*}"
  blob="$(git rev-parse --quiet --verify "$commit:$path" 2>/dev/null)" || return 1
  [ -n "${historical_only_exempt_blobs[$blob]+set}" ]
}

# The address values the fork point tree holds, lowercased. Filled by
# read_fork_point_addresses and read by the historical-tree scan alone.
declare -A fork_point_addresses=()

# Print "file:line" for every address that is none of: a no-reply address,
# Kotlin's qualified this, or — where asked — one the fork point already
# publishes; prefixed with the revision when the scan is of a commit. $1 says
# where to look: "worktree", "index", or a commit id. $2 is "none" or
# "historical" and picks the exemptions. Returns 2 when git grep itself failed.
#
# The three callers differ only in those two arguments, so the pattern and the
# flags are spelled once and cannot drift between the tree, the index and
# history. --cached has to go before the pattern; a revision has to go after it,
# or git grep reads the option as a revision and fails.
#
# git grep searches tracked files only, which is the same set as `git ls-files`.
# -a treats every blob as text: the -I it replaces skips whatever git calls
# binary, and a file holding one NUL byte and an address would sail through.
# With -o each match is its own output line ending in the matched address, which
# is what lets the tests below look at the address rather than at the whole line
# — a real address cannot hide beside an allowed one.
addresses_in_tree() {
  local where="$1" exemption="$2"
  local raw status line location address
  local -a grep_args=(-naoEi "$address_pattern")
  local -a paths=("${attribution_paths[@]}")
  case "$where" in
    worktree) ;;
    index) grep_args=(--cached "${grep_args[@]}") ;;
    *) grep_args+=("$where") ;;
  esac
  set +e
  raw="$(git grep "${grep_args[@]}" -- "${paths[@]}")"
  status=$?
  set -e
  # git grep exits 1 for "no matches", which is the good case here. Anything
  # above that is a real failure and must not pass for a clean tree.
  if [ "$status" -gt 1 ]; then
    return 2
  fi
  if [ -z "$raw" ]; then
    return 0
  fi
  # The matched address is the last colon-separated field and can hold no colon
  # itself, so dropping that field leaves the location however many colons the
  # path contains.
  while IFS= read -r line; do
    if [ -z "$line" ]; then
      continue
    fi
    address="${line##*:}"
    location="${line%:*}"
    if is_no_reply_address "$address"; then
      continue
    fi
    if is_kotlin_qualified_this "$address" "$location"; then
      continue
    fi
    if [ "$exemption" = 'historical' ] \
      && [ -n "${fork_point_addresses[${address,,}]+set}" ]; then
      continue
    fi
    if [ "$exemption" = 'historical' ] \
      && is_exempt_historical_blob "$where" "$location"; then
      continue
    fi
    printf '%s\n' "$(redact "$location")"
  done <<< "$raw"
}

# Read the exempt set out of the fork point tree. Same pattern and same flags as
# the scans, so every address the historical scan can match is one this can
# match too; without that, an inherited file would produce a hit no exemption
# could ever cover. No path exclusions: an address upstream published is
# published whichever of its files holds it. Returns 1 when git grep failed or
# when the set came out empty, because an empty set would silently turn the
# exemption off rather than mean there is nothing to exempt.
read_fork_point_addresses() {
  local raw status line address
  set +e
  raw="$(git grep -naoEi "$address_pattern" "$fork_point")"
  status=$?
  set -e
  if [ "$status" -gt 1 ]; then
    return 1
  fi
  if [ -n "$raw" ]; then
    while IFS= read -r line; do
      if [ -z "$line" ]; then
        continue
      fi
      address="${line##*:}"
      fork_point_addresses["${address,,}"]=1
    done <<< "$raw"
  fi
  if [ "${#fork_point_addresses[@]}" -eq 0 ]; then
    return 1
  fi
}

# Print every tracked path that holds an address, redacted, for check 5. $1 is
# "index" or a commit id.
#
# `git ls-files` is the index, which is also the set of paths a checkout of this
# working tree has on disk; `git ls-tree -r --name-only` is the same question
# asked of a commit. No exemptions at all here: LICENSE earns its exemption
# through what is inside it, and no path in this repository has ever needed one.
# A name that matches is either a mistake or something deliberate, and both want
# reporting.
#
# One grep over the whole listing rather than one grep per path — this runs for
# every fork commit, and a thousand paths a commit would be a thousand processes
# a commit. -n numbers the lines of the listing, which is how a hit gets back to
# the name it came from. Returns 2 when git itself failed.
names_with_addresses() {
  local where="$1" listing status match address position
  local -a names=()
  set +e
  case "$where" in
    index) listing="$(git ls-files)" ;;
    *) listing="$(git ls-tree -r --name-only "$where")" ;;
  esac
  status=$?
  set -e
  if [ "$status" -ne 0 ]; then
    return 2
  fi
  if [ -z "$listing" ]; then
    return 0
  fi
  mapfile -t names <<< "$listing"
  while IFS= read -r match; do
    if [ -z "$match" ]; then
      continue
    fi
    position="${match%%:*}"
    address="${match#*:}"
    if is_no_reply_address "$address"; then
      continue
    fi
    printf '%s\n' "$(redact "${names[position - 1]}")"
  done < <(printf '%s\n' "$listing" | { grep -naoEi "$address_pattern" || true; })
}

# Does this name — a commit's author or committer name, or the name half of the
# identity the next commit would carry — hold an address? A name is a name; git
# will happily write an address into that field, and it is published with the
# commit exactly like the address field beside it.
name_holds_address() {
  printf '%s\n' "$1" | grep -qaEi "$address_pattern"
}

# 1. The working tree. No historical exemption: an address upstream published is
# still an address this fork would be shipping.
check_working_tree() {
  local hits status=0
  hits="$(addresses_in_tree worktree none)" || status=$?
  if [ "$status" -ne 0 ]; then
    fail "✗ git grep failed while searching the working tree"
    return
  fi
  if [ -n "$hits" ]; then
    fail "✗ email address in tracked files (file and line only, address withheld):" \
         "$(printf '%s\n' "$hits" | sed 's/^/    /')" \
         "  If it is attribution the licence requires, it belongs in LICENSE." \
         "  Otherwise remove it." \
         "  Line numbers are the working tree's; check 2 reports the index separately."
  fi
}

# 2. The index, which is what a commit actually takes. Same scan, same
# allowlist, same absence of a historical exemption — only the content differs,
# and it differs exactly in the case this catches: a hunk staged out of a file
# that has since been cleaned up on disk.
check_index() {
  local hits status=0
  hits="$(addresses_in_tree index none)" || status=$?
  if [ "$status" -ne 0 ]; then
    fail "✗ git grep failed while searching the index"
    return
  fi
  if [ -n "$hits" ]; then
    fail "✗ email address staged in the index (file and line only, address withheld):" \
         "$(printf '%s\n' "$hits" | sed 's/^/    /')" \
         "  The line numbers are the staged content's, not the working tree's." \
         "  fix: unstage it (git restore --staged <file>) and remove it."
  fi
}

# 5a. The names of the tracked files, as the index holds them — which is both
# what the next commit would write and what a checkout of this tree puts on
# disk. The historical half of check 5 is inside check_fork_commits.
check_tracked_names() {
  local hits status=0
  hits="$(names_with_addresses index)" || status=$?
  if [ "$status" -ne 0 ]; then
    fail "✗ git failed while listing the tracked file names"
    return
  fi
  if [ -n "$hits" ]; then
    fail "✗ email address in the name of a tracked file (address blanked out):" \
         "$(printf '%s\n' "$hits" | sed 's/^/    /')" \
         "  fix: git mv it to a name that holds no address."
  fi
}

# 3. The identity the next commit would carry. git var applies the same
# precedence a commit does — the environment, then repo config, then global — so
# this is the address that would actually be written, and asking git beats
# reimplementing that order here.
#
# user.useConfigOnly stops git falling back to a guess made from the login name
# and the host, so a failure here means "nobody configured an address", not "the
# address is fine". That is a failure too. This check has one job, to know what
# the next commit would be signed with, and it either knows or it does not;
# treating "cannot tell" as a pass makes the gate green exactly where it is
# blindest.
#
# The one place that reasoning does not hold is a hosted CI runner, which
# configures no identity and never commits; that case is handled at the call
# site, once, and out loud.
check_next_commit_identity() {
  local role="$1" git_variable="$2" ident address name status=0
  ident="$(git -c user.useConfigOnly=true var "$git_variable" 2>/dev/null)" || status=$?
  if [ "$status" -ne 0 ]; then
    fail "✗ git cannot say what $role address the next commit would carry" \
         "  With user.useConfigOnly that means no address is configured here." \
         "  fix: git config user.email with your forge's no-reply address"
    return
  fi
  # An identity is "Name <address> timestamp zone", and a name may itself hold
  # an angle bracket, so take what lies between the last < and the next >. An
  # identity without both brackets is one this check cannot read, which is the
  # same "cannot tell" as above and gets the same answer.
  case "$ident" in
    *'<'*'>'*) ;;
    *) fail "✗ the $role identity the next commit would carry is not in a readable form" \
            "  expected: Name <address> timestamp zone"
       return ;;
  esac
  address="${ident##*<}"
  address="${address%%>*}"
  if ! is_no_reply_address "$address"; then
    fail "✗ the next commit's $role address is not a no-reply address (address withheld)" \
         "  fix: git config user.email with your forge's no-reply address"
  fi
  # The name half, which is published with every commit just as the address
  # half is. user.name set to an address is an easy thing to do by accident on a
  # machine where the two were once the same string.
  name="${ident%<*}"
  if name_holds_address "$name"; then
    fail "✗ the next commit's $role name holds an email address (name withheld)" \
         "  fix: git config user.name with a name, not an address"
  fi
}

# 4. Every commit the fork authored.
check_fork_commits() {
  local commits commit metadata author committer message
  local author_name committer_name
  local message_lines match address hits status

  if [ "$(git rev-parse --is-shallow-repository)" != "false" ]; then
    fail "✗ shallow clone: the fork's own commits cannot be checked" \
         "  fix: clone with full history (actions/checkout needs fetch-depth: 0)"
    return
  fi

  if ! git rev-parse --verify --quiet "$fork_point^{commit}" >/dev/null; then
    fail "✗ commit $fork_point is missing, so upstream's own commits cannot be excluded" \
         "  fix: fetch this repository's full history"
    return
  fi

  commits="$(git rev-list HEAD "^$fork_point")"
  if [ -z "$commits" ]; then
    return
  fi

  if ! read_fork_point_addresses; then
    fail "✗ could not read the addresses the fork point already publishes" \
         "  fix: fetch this repository's full history"
    return
  fi

  while IFS= read -r commit; do
    # Names first, then addresses, then the message: git forbids a newline in
    # either half of an identity, so the four fields are exactly four lines and
    # the message is everything after them.
    metadata="$(git show --no-patch --format='%an%n%cn%n%ae%n%ce%n%B' "$commit")"
    author_name="$(printf '%s\n' "$metadata" | sed -n 1p)"
    committer_name="$(printf '%s\n' "$metadata" | sed -n 2p)"
    author="$(printf '%s\n' "$metadata" | sed -n 3p)"
    committer="$(printf '%s\n' "$metadata" | sed -n 4p)"
    message="$(printf '%s\n' "$metadata" | sed -n '5,$p')"

    if ! is_no_reply_address "$author"; then
      fail "✗ commit $commit: author address is not a no-reply address (address withheld)"
    fi
    if ! is_no_reply_address "$committer"; then
      fail "✗ commit $commit: committer address is not a no-reply address (address withheld)"
    fi
    if name_holds_address "$author_name"; then
      fail "✗ commit $commit: author name holds an email address (name withheld)"
    fi
    if name_holds_address "$committer_name"; then
      fail "✗ commit $commit: committer name holds an email address (name withheld)"
    fi

    # The whole message, subject and body and trailers alike, so a
    # Co-authored-by line with a personal address is caught like any other. No
    # exemption here: we write our own commit messages. grep exits 1 when the
    # message holds no address at all, which is the good case and must not trip
    # pipefail.
    message_lines=''
    while IFS= read -r match; do
      if [ -z "$match" ]; then
        continue
      fi
      address="${match##*:}"
      if is_no_reply_address "$address"; then
        continue
      fi
      message_lines="$message_lines${match%%:*} "
    done < <(printf '%s\n' "$message" | { grep -naoEi "$address_pattern" || true; })
    if [ -n "$message_lines" ]; then
      fail "✗ commit $commit: email address in the commit message, at message line(s) ${message_lines% }"
    fi

    status=0
    hits="$(addresses_in_tree "$commit" historical)" || status=$?
    if [ "$status" -ne 0 ]; then
      fail "✗ git grep failed while searching the tree of commit $commit"
    elif [ -n "$hits" ]; then
      fail "✗ email address in a file at commit $commit (commit, file and line only, address withheld):" \
           "$(printf '%s\n' "$hits" | sed 's/^/    /')"
    fi

    # 5b. The names in that commit's tree. A path that once held an address is
    # published by the commit that carried it, the same way a line of a file is.
    status=0
    hits="$(names_with_addresses "$commit")" || status=$?
    if [ "$status" -ne 0 ]; then
      fail "✗ git failed while listing the file names of commit $commit"
    elif [ -n "$hits" ]; then
      fail "✗ email address in a file name at commit $commit (address blanked out):" \
           "$(printf '%s\n' "$hits" | sed 's/^/    /')"
    fi
  done <<< "$commits"
}

check_working_tree
check_index
check_tracked_names
# A hosted runner has no configured identity and never commits, so there is no
# "next commit" for check 3 to be about. Skipping it there is the one exception
# to treating an unreadable identity as a failure; it is announced rather than
# silent, and what was actually pushed is still covered by check 4.
if [ "${GITHUB_ACTIONS:-}" = 'true' ]; then
  echo "· GITHUB_ACTIONS=true: skipping the next-commit identity check (nothing commits on a hosted runner; check 4 covers what was pushed)"
else
  check_next_commit_identity author GIT_AUTHOR_IDENT
  check_next_commit_identity committer GIT_COMMITTER_IDENT
fi
check_fork_commits

if [ "$failures" -ne 0 ]; then
  exit 1
fi
