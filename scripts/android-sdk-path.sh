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

# Prints the path of the Android SDK on stdout, or fails saying how to set one.
#
# Its own file because two callers need the same answer — scripts/check-preflight.sh
# to look for the platform package and the AVD, scripts/check.sh to find adb and
# the emulator for G7 — and because Gradle finds the SDK by these same rules. Two
# copies of this would one day disagree about which SDK the gate is testing.
#
# Same precedence AGP 8.10 uses (its SdkLocator): sdk.dir in local.properties
# first — gitignored, and this machine's own answer — then ANDROID_HOME, then
# the deprecated ANDROID_SDK_ROOT. local.properties wins on purpose: if it names
# an SDK, that is the SDK Gradle will build against, so it must be the SDK the
# gate checks for the platform package and the AVD. The two answering
# differently is exactly the failure this file exists to prevent.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

sdk=''
if [ -f "$ROOT/local.properties" ]; then
  # tr strips the trailing CR a CRLF local.properties would leave behind.
  sdk="$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" | tail -1 | tr -d '\r')"
fi
if [ -z "$sdk" ]; then
  sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
fi
if [ -z "$sdk" ]; then
  echo "✗ no Android SDK: put sdk.dir=... in local.properties, or set ANDROID_HOME" >&2
  exit 1
fi
if [ ! -d "$sdk" ]; then
  echo "✗ the Android SDK path does not exist: $sdk" >&2
  exit 1
fi

printf '%s\n' "$sdk"
