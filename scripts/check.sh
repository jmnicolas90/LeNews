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
# Every Gradle stage names all three modules explicitly rather than relying on
# an unqualified task name reaching them all. It costs a line and it means a red
# stage says which module failed.
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

# G7's body. Boots the AVD headless if nothing is already serving on its port,
# waits for the system to finish booting, runs the instrumented tests of the two
# modules that have any, and shuts the emulator down again only if this stage is
# what started it. Returns non-zero on any of those failing, and shuts down
# before returning, so a failed test run does not leave an emulator behind.
instrumented_tests() {
  local sdk adb emulator_binary started_here=0 waited=0 status=0 boot=''

  sdk="$("$ROOT/scripts/android-sdk-path.sh")" || return 1
  adb="$sdk/platform-tools/adb"
  emulator_binary="$sdk/emulator/emulator"

  if [ ! -x "$adb" ]; then
    echo "✗ no adb at $adb" >&2
    echo "  fix: \"$sdk/cmdline-tools/latest/bin/sdkmanager\" platform-tools" >&2
    return 1
  fi

  # "Is one already running" is asked of the serial, not of the AVD name: that
  # is the same question the Gradle task will ask through ANDROID_SERIAL, so if
  # the answer here is yes the tests will find it too.
  if [ "$("$adb" -s "$device_serial" get-state 2>/dev/null || true)" = 'device' ]; then
    echo "· $device_serial is already running; using it and leaving it running afterwards"
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
    started_here=1
  fi

  # Wait for the system to finish booting, not merely for adb to see the device:
  # adb answers "device" while Android is still starting, and a test run started
  # then fails on an install that cannot reach the package manager yet.
  while [ "$waited" -lt "$emulator_boot_timeout_seconds" ]; do
    boot="$("$adb" -s "$device_serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n' || true)"
    if [ "$boot" = '1' ]; then
      break
    fi
    sleep 2
    waited=$((waited + 2))
  done
  if [ "$boot" != '1' ]; then
    echo "✗ $device_serial did not finish booting within ${emulator_boot_timeout_seconds}s" >&2
    if [ "$started_here" -eq 1 ]; then
      echo "  the emulator's own output is in build/emulator.log" >&2
      "$adb" -s "$device_serial" emu kill >/dev/null 2>&1 || true
    fi
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

  if [ "$started_here" -eq 1 ]; then
    echo "· shutting down $device_serial (this stage started it)"
    "$adb" -s "$device_serial" emu kill >/dev/null 2>&1 || true
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

# The GrapheneOS constraint, enforced rather than documented. All three modules,
# because api and db carry their own dependency graphs and a library module is
# exactly where a transitive Play Services dependency would arrive unnoticed.
gate G4 "Google guard" "$GRADLE" -q \
  :app:checkNoGoogleDependencies \
  :api:checkNoGoogleDependencies \
  :db:checkNoGoogleDependencies

gate G5 "debug APK"   "$GRADLE" -q :app:assembleDebug

# G6 is its own gate because release is the only build type with minifyEnabled,
# so R8's shrinking, optimization and obfuscation passes run nowhere else and
# breakage they cause is invisible to every gate above this one. It needs no
# keystore: no signingConfig is set on release, so this produces
# app-release-unsigned.apk, which is what lets the stage run unchanged here and
# on a runner with no secrets. Signing is a release-process problem, not a gate
# problem.
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
