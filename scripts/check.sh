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

# Pre-commit gate: the one command that has to be green before committing.
#
# G0 preflight -> G1 email guard -> G2 lint -> G3 unit tests -> G4 Google guard
# -> G5 debug APK -> G6 release APK -> G7 instrumented tests. Fail-fast: the
# first red gate stops the run.
#
# The stages mirror .github/workflows/ci.yml stage for stage, on purpose (the
# runners are noisier — no -q — but they run the same tasks in the same order).
# If the two ever drift, one of them is lying about whether the tree is good.
#
# Every Gradle stage names the modules it covers explicitly rather than relying
# on an unqualified task name reaching them all. It costs a line and it means a
# red stage says which module failed. G4 is the only stage naming four:
# baselineprofile has no lint task, no unit tests and no APK a reader installs.
#
# Set SKIP_INSTRUMENTED to anything to leave G7 out. That is for quick
# iterations only: the default run includes it, and CI always runs it.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE="$ROOT/gradlew"

# The emulator G7 runs on. Pinned by name and by serial because this machine
# usually has a real phone attached over adb as well, and an unpinned
# connectedAndroidTest would install a debug build on whichever device adb
# happens to list first. The user's phone is out of bounds; nothing here may
# reach it.
device_serial='emulator-5554'
device_port='5554'
emulator_avd='bench-pixel6-aosp'
emulator_boot_timeout_seconds=300
emulator_shutdown_timeout_seconds=60
emulator_kill_timeout_seconds=10

# Filled in by instrumented_tests, and read by the helpers below — including the
# ones the trap calls, which run after the function has returned.
adb=''
emulator_pid=''
emulator_owned=0

# Run one gate. Everything after the label is the command to run, passed as
# separate words rather than a string, so nothing goes through a second round
# of word-splitting.
#
# The subshell is a single `cd ... && "$@"` chain on purpose: `set -e` does NOT
# apply inside a compound command on the left of `||`, so a subshell of several
# bare commands exits with the *last* one's status and would report a gate green
# with its first step red. Here there is only ever one command, so there is no
# status to lose. G7 is several steps, which is why it is a function that
# reports its own status rather than a list of commands here.
gate() {
  local id="$1" label="$2"
  shift 2
  echo "── $id $label ──"
  if ! (cd "$ROOT" && "$@"); then
    echo "✗ GATE $id FAILED" >&2
    exit 1
  fi
  echo "✓ $id $label"
}

# Is anything at all sitting on that serial? "Anything", in whatever state adb
# lists it: an emulator that is still starting is listed `offline`, and asking
# instead whether it answers `device` would read a booting emulator as "nothing
# is running", try to start a second one on a port already taken, and then treat
# the emulator it did not start as its own to kill. Whatever is there is someone
# else's; the only right move is to wait for it.
device_on_serial() {
  "$adb" devices 2>/dev/null \
    | awk -v serial="$device_serial" '$1 == serial { found = 1 } END { exit found ? 0 : 1 }'
}

# The AVD name behind the serial, asked of the emulator's own console. Prints
# nothing when nothing answers. The console replies with the name and then OK,
# hence the first line only.
emulator_avd_name() {
  "$adb" -s "$device_serial" emu avd name 2>/dev/null | head -n 1 | tr -d '\r\n' || true
}

# The last of the emulator's own output, for the two cases where the emulator
# failed and its log is the only thing that says why.
report_emulator_log() {
  if [ -s "$ROOT/build/emulator.log" ]; then
    echo "  the last of the emulator's own output (build/emulator.log):" >&2
    tail -n 40 "$ROOT/build/emulator.log" | sed 's/^/    /' >&2
  else
    echo "  build/emulator.log is empty" >&2
  fi
}

# Shut down the emulator this stage started, and only that one. Does nothing at
# all when the stage did not start one, which is what makes it safe to call from
# the trap as well as from the normal path — it runs once, whoever calls first.
#
# What it stops is the process this stage launched, by pid. It used to be the
# console command `emu kill`, and a console command is addressed to a port: if
# the launched emulator had died and another instance had taken port 5554
# meanwhile, the gate shut down an emulator that was none of its business, waited
# for a pid that was already gone, and called the stage green. A port is not an
# identity. Neither is the AVD name, which the other instance answers just the
# same when it runs the same AVD.
#
# So ownership is established before anything is signalled — the launched pid is
# still alive — and then that pid is signalled: SIGTERM, which the emulator
# handles by shutting itself down, a bounded wait, then SIGKILL and a shorter
# wait. Nothing is ever sent to the port.
#
# Returns non-zero whenever it could not do its job, so the stage fails rather
# than reporting a green gate over a machine it has left in a state it did not
# intend: the launched emulator was already gone, so this run cannot say what is
# on the serial any more, or the process would not die.
stop_owned_emulator() {
  local waited=0 avd_name
  if [ "$emulator_owned" -ne 1 ]; then
    return 0
  fi
  emulator_owned=0

  if ! kill -0 "$emulator_pid" 2>/dev/null; then
    wait "$emulator_pid" 2>/dev/null || true
    echo "✗ the emulator this stage started (pid $emulator_pid) is already gone" >&2
    echo "  Nothing was shut down. Whatever is on port $device_port now was not started" >&2
    echo "  here, and the tests may have run against it rather than against this stage's" >&2
    echo "  emulator. Check what is on $device_serial before trusting this run." >&2
    report_emulator_log
    return 1
  fi

  # For the report only. The pid is what says the process is ours; this says
  # whether the serial still belongs to it.
  avd_name="$(emulator_avd_name)"
  if [ "$avd_name" != "$emulator_avd" ]; then
    echo "· $device_serial answers ${avd_name:-nothing} rather than $emulator_avd," \
         "so the serial is no longer this stage's emulator; stopping only pid $emulator_pid" >&2
  fi

  echo "· shutting down $device_serial (this stage started it, pid $emulator_pid)"
  kill -TERM "$emulator_pid" 2>/dev/null || true
  while [ "$waited" -lt "$emulator_shutdown_timeout_seconds" ]; do
    if ! kill -0 "$emulator_pid" 2>/dev/null; then
      wait "$emulator_pid" 2>/dev/null || true
      return 0
    fi
    sleep 1
    waited=$((waited + 1))
  done

  echo "· pid $emulator_pid ignored SIGTERM for ${emulator_shutdown_timeout_seconds}s;" \
       "sending SIGKILL" >&2
  kill -KILL "$emulator_pid" 2>/dev/null || true
  waited=0
  while [ "$waited" -lt "$emulator_kill_timeout_seconds" ]; do
    if ! kill -0 "$emulator_pid" 2>/dev/null; then
      wait "$emulator_pid" 2>/dev/null || true
      return 0
    fi
    sleep 1
    waited=$((waited + 1))
  done
  echo "✗ the emulator this stage started (pid $emulator_pid) is still alive after SIGKILL" >&2
  echo "  kill it by hand before running the gate again; it is holding port $device_port" >&2
  return 1
}

# G7's body. Uses whatever is already on the serial, or boots the AVD headless
# itself; either way it checks that the device is the bench AVD before it
# installs anything on it, waits for the system to finish booting, runs the
# instrumented tests of the two modules that have any, and shuts the emulator
# down again only if this stage is what started it.
#
# The one rule the whole function is built around: never touch a device this
# stage did not start. This machine usually has the user's phone plugged in, and
# an emulator started by hand for a debugging session is just as much someone
# else's.
instrumented_tests() {
  local sdk emulator_binary waited=0 status=0 boot='' avd_name

  sdk="$("$ROOT/scripts/android-sdk-path.sh")" || return 1
  adb="$sdk/platform-tools/adb"
  emulator_binary="$sdk/emulator/emulator"

  if [ ! -x "$adb" ]; then
    echo "✗ no adb at $adb" >&2
    echo "  fix: \"$sdk/cmdline-tools/latest/bin/sdkmanager\" platform-tools" >&2
    return 1
  fi

  if device_on_serial; then
    echo "· something is already on $device_serial; using it and leaving it running afterwards"
  else
    if [ ! -x "$emulator_binary" ]; then
      echo "✗ no emulator at $emulator_binary" >&2
      echo "  fix: \"$sdk/cmdline-tools/latest/bin/sdkmanager\" emulator" >&2
      return 1
    fi
    mkdir -p "$ROOT/build"
    echo "· booting $emulator_avd headless on $device_serial (about two minutes cold)"
    # -gpu host is not a preference. With the software renderer this AVD dies
    # silently at "performing a full startup" on this machine, and the only
    # symptom is a boot that never completes. -no-snapshot so every run starts
    # from the same state; -feature -GnssGrpcV1 because the GNSS service the
    # emulator starts by default is not wanted and logs errors without it being
    # switched off.
    "$emulator_binary" -avd "$emulator_avd" -port "$device_port" \
      -no-window -no-audio -no-snapshot -gpu host -feature -GnssGrpcV1 \
      >"$ROOT/build/emulator.log" 2>&1 &
    emulator_pid=$!
    emulator_owned=1
    # From here on there is a process this run is responsible for. The traps
    # exist for the interrupted run: Ctrl-C during a five-minute test run would
    # otherwise leave a headless emulator behind with nobody to notice. They
    # kill only what this stage started, because that is all stop_owned_emulator
    # ever kills.
    trap 'stop_owned_emulator || true' EXIT
    trap 'stop_owned_emulator || true; exit 130' INT
    trap 'stop_owned_emulator || true; exit 143' TERM
  fi

  # Wait for the system to finish booting, not merely for adb to see the device:
  # adb answers "device" while Android is still starting, and a test run started
  # then fails on an install that cannot reach the package manager yet.
  #
  # An emulator that cannot start — a taken port, a broken AVD, no KVM — exits
  # within seconds and says why in its log, so the wait watches the process as
  # well as the property. Without that, the stage would sit here for five
  # minutes and then report a boot timeout, which is not what happened.
  while [ "$waited" -lt "$emulator_boot_timeout_seconds" ]; do
    if [ "$emulator_owned" -eq 1 ] && ! kill -0 "$emulator_pid" 2>/dev/null; then
      echo "✗ the emulator this stage started exited before $device_serial finished booting" >&2
      report_emulator_log
      emulator_owned=0
      wait "$emulator_pid" 2>/dev/null || true
      return 1
    fi
    boot="$("$adb" -s "$device_serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n' || true)"
    if [ "$boot" = '1' ]; then
      break
    fi
    sleep 2
    waited=$((waited + 2))
  done
  if [ "$boot" != '1' ]; then
    echo "✗ $device_serial did not finish booting within ${emulator_boot_timeout_seconds}s" >&2
    if [ "$emulator_owned" -eq 1 ]; then
      report_emulator_log
    fi
    stop_owned_emulator || true
    return 1
  fi

  # Which AVD is that, actually. The serial says where the device is and nothing
  # about what it is, and this stage is about to install a debug build on it and
  # run tests that wipe databases. Any other AVD is refused rather than used —
  # and refused without being killed, since something else is running it.
  avd_name="$(emulator_avd_name)"
  if [ "$avd_name" != "$emulator_avd" ]; then
    echo "✗ the device on $device_serial is not $emulator_avd" >&2
    echo "  it answers: ${avd_name:-nothing (its console did not reply)}" >&2
    echo "  fix: stop it, or run the gate when it is not there. Nothing was installed on it." >&2
    stop_owned_emulator || true
    return 1
  fi

  # ANDROID_SERIAL, not a --device flag, because that is the one thing every
  # tool in the chain honours: the Gradle plugin, adb, and anything the tests
  # shell out to. Without it, a connectedAndroidTest on this machine would pick
  # the real phone that is usually plugged in.
  #
  # db first: it is the module whose tests this map exists to keep honest, and
  # it is the faster of the two, so a schema mistake is reported in a minute
  # instead of five.
  ANDROID_SERIAL="$device_serial" "$GRADLE" -q \
    :db:connectedDebugAndroidTest :app:connectedDebugAndroidTest || status=$?

  if ! stop_owned_emulator; then
    status=1
  fi

  return "$status"
}

# G0 and G1 are the two gates that are not Gradle tasks, and they run first
# because between them they cost under a second and both answer questions the
# rest of the run cannot recover from. G0: is the SDK platform and the emulator
# image the run needs actually installed. G1: is an address about to be
# published, which is the one failure that cannot be taken back after a push.
gate G0 preflight     "$ROOT/scripts/check-preflight.sh"

# G1 covers the tracked files, the staged index, the identity the next commit
# would carry, and every commit this fork authored — metadata, message and tree
# — so it needs full history and fails on a shallow clone. The check lives in
# its own script, which the CI workflow runs too, so the pattern, the allowlist
# and the commit range have one home instead of two that drift.
gate G1 "email guard" "$ROOT/scripts/check-no-personal-email.sh"

# Both variants: the argument for a separate release APK stage — that
# release-only breakage is invisible to a debug gate — applies to lint too.
# Errors only; warnings print and stop nothing. What was already red on the day
# the gate was built is in app/lint-baseline.xml, which is a list of findings to
# clear, not a rule switched off: a new error of any of those kinds still fails.
gate G2 lint          "$GRADLE" -q \
  :app:lintDebug :app:lintRelease \
  :api:lintDebug :api:lintRelease \
  :db:lintDebug :db:lintRelease

gate G3 "unit tests"  "$GRADLE" -q \
  :app:testDebugUnitTest :api:testDebugUnitTest :db:testDebugUnitTest

# The GrapheneOS constraint, enforced rather than documented. All four modules,
# because api and db carry their own dependency graphs and a library module is
# exactly where a transitive Play Services dependency would arrive unnoticed —
# and because baselineprofile installs an APK of its own on a device, which is
# the one other way something could reach one.
gate G4 "Google guard" "$GRADLE" -q \
  :app:checkNoGoogleDependencies \
  :api:checkNoGoogleDependencies \
  :db:checkNoGoogleDependencies \
  :baselineprofile:checkNoGoogleDependencies

gate G5 "debug APK"   "$GRADLE" -q :app:assembleDebug

# G6 is its own gate because release is the only build type with minifyEnabled,
# so R8's shrinking, optimization and obfuscation passes run nowhere else and
# breakage they cause is invisible to every gate above this one. It needs no
# keystore of its own: signing is presence-based on the four lenews.release.*
# properties in ~/.gradle/gradle.properties (ticket 23), so this stage produces
# a signed APK on the machine that holds the key and app-release-unsigned.apk
# on a runner, which is what lets it run unchanged in both places. The stage
# asks whether the release build works; the signature is a property of the
# artifact, not a verdict about the tree, and checking it belongs to the
# release procedure (apksigner verify --print-certs against the fingerprint
# published in README.md).
gate G6 "release APK" "$GRADLE" -q :app:assembleRelease

# G7 is the stage that tests the database, and the database is where this map's
# three pain points live. Room migrations, DAO queries and the query builders
# only run on a device, so nothing above this line can see a schema mistake.
if [ -n "${SKIP_INSTRUMENTED:-}" ]; then
  echo "── G7 instrumented tests ──"
  echo "· SKIP_INSTRUMENTED is set: skipped. The default run includes G7 and CI always runs it."
else
  gate G7 "instrumented tests" instrumented_tests
fi

echo "─────────────────────────────"
echo "All gates green."
