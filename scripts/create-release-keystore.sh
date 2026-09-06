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

# Creates the LeNews release signing key and tells Gradle where it is.
#
# Run it once, by hand, on the machine that is to sign releases. It is not a
# gate stage and nothing in scripts/check.sh calls it.
#
#   1. It creates ~/.android/lenews-release.jks — PKCS12, RSA 4096, 10000 days,
#      alias `lenews`, DN `CN=LeNews, O=LeNews` (no email address: the DN is
#      published inside every APK). It refuses to touch an existing keystore,
#      because overwriting the release key is the one mistake with no recovery.
#   2. It writes the four `lenews.release.*` properties into
#      ~/.gradle/gradle.properties, mode 600. That file is outside the
#      repository, so the tree holds no knowledge of the key — not even its
#      path — and a worktree under .claude/worktrees/ reaches the same one file.
#
# The password is typed here and goes nowhere else: it is never an argument, so
# it never reaches a process listing, and it is never echoed, so it never
# reaches a terminal transcript. keytool gets it on its standard input. One
# password serves the store and the key, because keytool now writes PKCS12 and
# PKCS12 has no separate key password.
#
# Ticket 23 decided all of the above; ticket 26 built it.

set -euo pipefail

keystore="$HOME/.android/lenews-release.jks"
alias_name="lenews"
dname="CN=LeNews, O=LeNews"
gradle_properties="$HOME/.gradle/gradle.properties"

die() {
    echo "✗ $*" >&2
    exit 1
}

# java.util.Properties reads a backslash as an escape and drops leading
# whitespace from a value, so a password starting with a space would be read back
# shorter than it was typed — and the build would then fail with a bad password
# while the keystore holds the right one, which is undebuggable by an agent that
# is not allowed to know the password. Space and backslash are the two that can
# actually arrive, since the prompt above refuses anything but printable ASCII;
# tab and formfeed are escaped too so this stays correct if that ever loosens.
# All four are legal escapes anywhere in a value, so there is no need to reason
# about where in the string they fall.
properties_escape() {
    local value="${1//\\/\\\\}"
    value="${value// /\\ }"
    value="${value//$'\t'/\\t}"
    value="${value//$'\f'/\\f}"
    printf '%s' "$value"
}

# keytool comes from the JDK Gradle itself uses, so the key is made by the
# toolchain that will sign with it.
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/keytool" ]; then
    keytool="$JAVA_HOME/bin/keytool"
elif command -v keytool >/dev/null 2>&1; then
    keytool="$(command -v keytool)"
else
    die "No keytool found. Set JAVA_HOME to a JDK (this project uses Temurin 21)."
fi

# -e is false for a dangling symlink, which keytool would happily write through.
if [ -e "$keystore" ] || [ -L "$keystore" ]; then
    die "$keystore already exists. This script never overwrites a keystore: move the old one aside yourself if you really mean to replace it, knowing that a release signed with a new key cannot upgrade an install signed with the old one. If the four lenews.release.* properties are missing from $gradle_properties, add them by hand rather than making a second key."
fi

if [ -e "$gradle_properties" ] && grep -q '^[[:space:]]*lenews\.release\.' "$gradle_properties"; then
    die "$gradle_properties already carries lenews.release.* properties, but $keystore does not exist. Sort that out by hand before running this again."
fi

echo "Creating the LeNews release signing key."
echo
echo "  keystore  $keystore"
echo "  format    PKCS12, RSA 4096, 10000 days"
echo "  alias     $alias_name"
echo "  DN        $dname"
echo
echo "Choose a password. It opens both the keystore and the key, it is not"
echo "recoverable, and losing it costs exactly what losing the file costs."
echo

password=""
while :; do
    printf 'Password: '
    IFS= read -rs password
    echo
    printf 'Again:    '
    IFS= read -rs confirmation
    echo

    if [ "$password" != "$confirmation" ]; then
        echo "  They differ. Again."
        continue
    fi
    if [ "${#password}" -lt 6 ]; then
        echo "  keytool wants at least 6 characters. Again."
        continue
    fi
    # PKCS12 encrypts with a PBE that takes printable ASCII only, so keytool
    # refuses anything else — a tab, an accented letter — after the password has
    # been typed twice, with "Password is not ASCII" and nothing else. Say it
    # here instead. The password goes to grep on standard input, never in argv.
    if ! printf '%s' "$password" | LC_ALL=C grep -E '^[ -~]+$' >/dev/null; then
        echo "  keytool takes printable ASCII only — no tab, no accented letter. Again."
        continue
    fi
    break
done
unset confirmation

# Anything this script creates is the owner's alone, from the moment it exists.
umask 077

# keytool writes the file but not the directory holding it.
mkdir -p "$(dirname "$keystore")"

# The password reaches keytool on standard input: not -storepass, which would
# put it in every process listing on the machine. printf is a bash builtin, so
# the password is not an argument of any process either.
if ! printf '%s\n%s\n' "$password" "$password" |
    "$keytool" -genkeypair \
        -keystore "$keystore" \
        -alias "$alias_name" \
        -keyalg RSA \
        -keysize 4096 \
        -validity 10000 \
        -dname "$dname"; then
    rm -f "$keystore"
    die "keytool failed. Nothing was written."
fi

chmod 600 "$keystore"

escaped_password="$(properties_escape "$password")"
escaped_keystore="$(properties_escape "$keystore")"
escaped_alias="$(properties_escape "$alias_name")"

mkdir -p "$(dirname "$gradle_properties")"
if [ -e "$gradle_properties" ]; then
    echo "Tightening $gradle_properties to mode 600: it is about to hold a password."
fi
touch "$gradle_properties"
chmod 600 "$gradle_properties"

# The keystore is already there by now, so a failure here leaves a key with
# nothing pointing at it. Say which half exists rather than let set -e exit mute.
if ! {
    echo ""
    echo "# LeNews release signing (ticket 23). The key itself is $keystore;"
    echo "# this file is the only place its password lives on this machine, and"
    echo "# neither is ever in the repository or in a CI secret."
    echo "lenews.release.storeFile=$escaped_keystore"
    echo "lenews.release.storePassword=$escaped_password"
    echo "lenews.release.keyAlias=$escaped_alias"
    echo "lenews.release.keyPassword=$escaped_password"
} >>"$gradle_properties"; then
    die "$keystore was created but $gradle_properties could not be written. The key is fine — back it up as below and add the four lenews.release.* properties by hand. Do not run this script again; it would refuse the keystore that now exists."
fi

unset escaped_password escaped_keystore escaped_alias

echo
echo "✓ $keystore written, mode 600."
echo "✓ $gradle_properties updated, mode 600."
echo
echo "── The certificate ──"
echo
# The fingerprint and the expiry are public — they travel inside every APK —
# so printing them is safe, and they are two of the things to write down below.
# Printed whole rather than grepped: this host answers in French, so the labels
# around the fingerprint are not the ones a filter would look for.
printf '%s\n' "$password" |
    "$keytool" -list -v -keystore "$keystore" -alias "$alias_name" ||
    echo "(Run: $keytool -list -v -keystore $keystore -alias $alias_name)"

unset password
echo
cat <<'CHECKLIST'
── Back it up now, before anything else ──

A key that exists in one place is a key that is one dead disk away from making
every future LeNews a different application: Android refuses to upgrade across a
signature change, so every user would have to uninstall and lose their local
history. Full-disk encryption protects this file against a stolen machine, not
against a disk failure.

One KeePassXC entry, in a database that is backed up somewhere other than this
disk:

  Title      LeNews release signing key
  User name  lenews
  Password   the password you just typed
  Attachment lenews-release.jks  (Advanced tab → Attachments → Add,
             from ~/.android/lenews-release.jks)
  Notes      the path and format (PKCS12, RSA 4096), the alias `lenews`, that
             one password serves both the store and the key, the expiry and the
             SHA-256 fingerprint printed above, that the same password sits in
             ~/.gradle/gradle.properties on this machine, and what losing it
             costs (above).

Only the file and the password are irreducible. The alias is recoverable with
`keytool -list`, and the fingerprint is published in README.md.
CHECKLIST
