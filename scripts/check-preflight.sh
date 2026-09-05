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

# The preflight the local gate runs first: are the two things the rest of the
# run needs actually installed?
#
#   1. The Android SDK, holding the platform package the project compiles
#      against. Without it every Gradle stage fails, and it fails with an error
#      about a missing platform rather than about a missing SDK.
#   2. The emulator image the instrumented stage boots. Without it G7 would run
#      for two minutes and then fail on a name it could have checked in a
#      millisecond.
#
# Its own script rather than lines inside scripts/check.sh, because anything
# that wants to run the instrumented tests by hand needs the same two answers,
# and a preflight copied into two files is a preflight that will one day check
# two different things.
#
# When SKIP_INSTRUMENTED is set, the AVD half is skipped: a run that is not
# going to boot the emulator has no business demanding it exists.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# The exact platform package, not a near neighbour. compileSdk 35 needs
# platforms/android-35; android-36 and android-36.1 sit in the same directory
# and do NOT substitute for it, so the raw Gradle error reads as "platform
# missing" while directories that look like platforms are right there. Name the
# real fix instead.
sdk="$("$ROOT/scripts/android-sdk-path.sh")"
if [ ! -d "$sdk/platforms/android-35" ]; then
  echo "✗ missing \$ANDROID_HOME/platforms/android-35 (android-36 will NOT do)" >&2
  echo "  fix: \"$sdk/cmdline-tools/latest/bin/sdkmanager\" 'platforms;android-35'" >&2
  exit 1
fi

if [ -n "${SKIP_INSTRUMENTED:-}" ]; then
  exit 0
fi

# The AVD the instrumented stage runs on: a pure AOSP image, no Play Services,
# because that is the kind of device this app has to work on. The emulator keeps
# its AVDs under $ANDROID_AVD_HOME, else $ANDROID_SDK_HOME/.android/avd, else
# ~/.android/avd; the first of those that exists is the one it will read.
avd_name='bench-pixel6-aosp'
avd_home="${ANDROID_AVD_HOME:-${ANDROID_SDK_HOME:+$ANDROID_SDK_HOME/.android/avd}}"
avd_home="${avd_home:-$HOME/.android/avd}"
if [ ! -f "$avd_home/$avd_name.ini" ]; then
  echo "✗ missing the $avd_name AVD (looked for $avd_home/$avd_name.ini)" >&2
  echo "  It is a Pixel 6 on a pure AOSP system image, API 36 x86_64:" >&2
  echo "  fix: \"$sdk/cmdline-tools/latest/bin/sdkmanager\" 'system-images;android-36;default;x86_64'" >&2
  echo "       \"$sdk/cmdline-tools/latest/bin/avdmanager\" create avd -n $avd_name \\" >&2
  echo "           -k 'system-images;android-36;default;x86_64' -d pixel_6" >&2
  echo "  or run the gate with SKIP_INSTRUMENTED=1 to leave G7 out." >&2
  exit 1
fi
