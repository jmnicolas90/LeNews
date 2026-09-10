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

# Puts a store the size of a heavy reader's under an installed build of the app
# on the emulator, so that the app opens on a timeline rather than on a login
# screen. Ticket 24 needs one to generate a baseline profile from and to measure
# a scroll against; nothing in the gate calls this.
#
#   scripts/seed-store.sh [article count] [application id]
#
# Defaults: 100000 articles, app.lenews — which is what the release,
# benchmarkRelease and nonMinifiedRelease builds are all called. Pass
# app.lenews.debug to seed a debug build instead.
#
# The database is built here, on this machine, out of the Room schema this tree
# commits — db/schemas/app.lenews.db.Database/1.json — rather than out of a copy
# of the DDL written down in this file. The schema JSON carries every table's
# createSql, every index and the identity hash Room compares against on open, so
# a schema change is picked up the next time this runs instead of producing a
# database Room refuses.
#
# It is pinned to the emulator, hard, and refuses any other device. A real phone
# is usually attached to this machine over adb, it is out of bounds, and this
# script overwrites an application's entire store.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="$("$ROOT/scripts/android-sdk-path.sh")"
ADB="$SDK/platform-tools/adb"

# The database is built here, not on the device, so this needs a sqlite3 on this
# machine. The SDK ships one beside adb; a distribution's own is just as good.
# Asked for by name rather than assumed, because the failure without it is a
# bare "command not found" in the middle of a heredoc.
SQLITE="$SDK/platform-tools/sqlite3"
if [ ! -x "$SQLITE" ]; then
  SQLITE="$(command -v sqlite3 || true)"
fi
if [ -z "$SQLITE" ]; then
  echo "no sqlite3 on this machine, and none in $SDK/platform-tools." >&2
  echo "  install one (dnf install sqlite) or add platform-tools to the SDK." >&2
  exit 1
fi

DEVICE_SERIAL='emulator-5554'
EMULATOR_AVD='bench-pixel6-aosp'

ARTICLES="${1:-100000}"
PACKAGE="${2:-app.lenews}"

# Same shape as db/src/androidTest/.../benchmark/ArticleStoreSeeder.kt, which is
# what the database tests measure against: ten folders, a hundred feeds, a year
# of articles, the newest 2500 of them unread and a thousand starred.
#
# Two descriptions of one fixture in two languages, which will drift unless
# somebody keeps them together. They are not merged because they cannot be: that
# one builds its database through Room on a device, this one builds a file here
# out of the committed schema, and neither can call the other. What has to stay
# in step is these five numbers and the shape of an article row; the one place
# they deliberately differ is open_in_ask, and the comment on Feed says why.
FOLDERS=10
FEEDS=100
UNREAD=2500
STARRED=1000
YEAR_MILLIS=$(( 365 * 86400000 ))
DAY_MILLIS=86400000

SCHEMA="$ROOT/db/schemas/app.lenews.db.Database/1.json"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

DB="$WORK/lenews-db"

if [ ! -f "$SCHEMA" ]; then
  echo "no Room schema at $SCHEMA" >&2
  exit 1
fi

# --- the device -------------------------------------------------------------

if ! "$ADB" devices | awk -v s="$DEVICE_SERIAL" '$1 == s && $2 == "device" { found = 1 } END { exit found ? 0 : 1 }'
then
  echo "nothing is running on $DEVICE_SERIAL." >&2
  echo "  start it with:" >&2
  echo "    \"\$ANDROID_HOME/emulator/emulator\" -avd $EMULATOR_AVD -port 5554 \\" >&2
  echo "        -no-window -no-audio -no-snapshot -gpu host -feature -GnssGrpcV1" >&2
  exit 1
fi

# The serial says which port answers, not which machine is behind it. Ask the
# emulator console what it is before writing anything into an application's
# store, exactly as scripts/check.sh does before installing a test APK.
avd_name="$("$ADB" -s "$DEVICE_SERIAL" emu avd name 2>/dev/null | head -n 1 | tr -d '\r\n' || true)"
if [ "$avd_name" != "$EMULATOR_AVD" ]; then
  echo "$DEVICE_SERIAL is '$avd_name', not $EMULATOR_AVD. Refusing to touch it." >&2
  exit 1
fi

if ! "$ADB" -s "$DEVICE_SERIAL" shell pm path "$PACKAGE" > /dev/null 2>&1; then
  echo "$PACKAGE is not installed on $DEVICE_SERIAL — install it before seeding it." >&2
  exit 1
fi

# --- the schema, read out of the tree ---------------------------------------

schema_sql() {
  local expression="$1"
  "$SQLITE" -noheader -batch :memory: \
    "with schema(json) as (select readfile('$SCHEMA')) $expression"
}

identity_hash="$(schema_sql "select json_extract(json, '\$.database.identityHash') from schema")"
version="$(schema_sql "select json_extract(json, '\$.database.version') from schema")"

if [ -z "$identity_hash" ] || [ -z "$version" ]; then
  echo "could not read the identity hash and version out of $SCHEMA" >&2
  exit 1
fi

# Every table, then every index, with Room's ${TABLE_NAME} placeholder resolved.
tables_sql="$(schema_sql "
  select replace(json_extract(entity.value, '\$.createSql'), '\${TABLE_NAME}',
                 json_extract(entity.value, '\$.tableName')) || ';'
  from schema, json_each(json_extract(schema.json, '\$.database.entities')) as entity")"

indices_sql="$(schema_sql "
  select replace(json_extract(index_.value, '\$.createSql'), '\${TABLE_NAME}',
                 json_extract(entity.value, '\$.tableName')) || ';'
  from schema,
       json_each(json_extract(schema.json, '\$.database.entities')) as entity,
       json_each(json_extract(entity.value, '\$.indices')) as index_")"

# --- the store --------------------------------------------------------------

now_millis=$(( $(date +%s) * 1000 ))
oldest=$(( now_millis - YEAR_MILLIS ))
step=$(( YEAR_MILLIS / ARTICLES ))
unread_from=$(( ARTICLES > UNREAD ? ARTICLES - UNREAD : 0 ))
starred_stride=$(( ARTICLES / STARRED ))
if [ "$starred_stride" -lt 1 ]; then starred_stride=1; fi

echo "building a $ARTICLES-article store for $PACKAGE …"

"$SQLITE" "$DB" > /dev/null <<SQL
Pragma journal_mode = off;
Pragma user_version = $version;

-- What Room writes for itself, and reads back on open. A database without these
-- two is one Room throws on rather than one it adopts.
Create Table If Not Exists android_metadata (locale Text);
Insert Into android_metadata Values ('en_US');
Create Table If Not Exists room_master_table (id Integer Primary Key, identity_hash Text);
Insert Or Replace Into room_master_table Values (42, '$identity_hash');

$tables_sql
$indices_sql

Insert Into Account(id, url, name, displayed_name, cursor, token, write_token,
                    notifications_enabled)
Values (1, 'https://rss.example', 'FreshRSS', 'Bench', 0, 't', 'w', 0);

With Recursive folder(i) As (
  Select 1 Union All Select i + 1 From folder Where i < $FOLDERS
)
Insert Into Folder(id, name, remote_id)
Select i, 'Folder ' || i, 'user/-/label/Folder ' || i From folder;

With Recursive feed(i) As (
  Select 1 Union All Select i + 1 From feed Where i < $FEEDS
)
-- open_in_ask is 0, which is the one place this fixture deliberately differs
-- from the database seeder the timing tests use. Left at 1, the first tap on an
-- article of a feed puts up the "Open Feed in" dialog instead of opening the
-- article, and what gets measured — or recorded into a profile — is the dialog.
-- A reader answers that question once per feed and then reads articles; 0 is
-- that reader.
Insert Into Feed(id, name, description, url, siteUrl, last_updated, color,
                 icon_url, folder_id, remote_id, notification_enabled, open_in,
                 open_in_ask)
Select i, 'Feed ' || i, 'A feed',
       'https://feed' || i || '.example/rss', 'https://feed' || i || '.example',
       '', 0, 'https://feed' || i || '.example/icon.png',
       ((i - 1) % $FOLDERS) + 1, 'feed/https://feed' || i || '.example/rss',
       1, 'LOCAL_VIEW', 0
From feed;

-- Articles oldest first, which is the order a year of daily syncs leaves them
-- in, with a few days of jitter on the publication date so the row order is not
-- a perfect stand-in for the date order. A FreshRSS id is the Unix second the
-- server first saw the article times a million plus a counter within that
-- second, so the ids here have the shape and the magnitude the store really
-- holds. The bodies are filler of the length a real article's are.
With Recursive article(i) As (
  Select 0 Union All Select i + 1 From article Where i + 1 < $ARTICLES
),
body(content, description, clean_description) As (
  Select substr(replace(hex(zeroblob(80)), '0', 'corem ipsum dolor sit amet '), 1, 1000),
         substr(replace(hex(zeroblob(80)), '0', 'dorem ipsum dolor sit amet '), 1, 300),
         substr(replace(hex(zeroblob(80)), '0', 'torem ipsum dolor sit amet '), 1, 250)
),
dated(i, pub_date) As (
  Select i,
         $oldest + i * $step
           + (((i * 2654435761) % (6 * $DAY_MILLIS)) - 3 * $DAY_MILLIS)
  From article
)
Insert Into Article(id, title, description, clean_description, link, image_link,
                    author, pub_date, content, feed_id, read_time, read, starred,
                    read_at)
Select (pub_date / 1000) * 1000000 + i,
       'Article ' || i || ' about something that happened',
       body.description, body.clean_description,
       'https://feed' || (i % $FEEDS + 1) || '.example/' || i,
       'https://feed' || (i % $FEEDS + 1) || '.example/' || i || '.jpg',
       'Author ' || (i % 40),
       pub_date, body.content, (i % $FEEDS) + 1, 1.5,
       Case When i < $unread_from Then 1 Else 0 End,
       Case When i % $starred_stride = 0 Then 1 Else 0 End,
       Case When i < $unread_from Then pub_date + $DAY_MILLIS Else Null End
From dated, body;

-- The state a phone is in after one sync: the model runs PRAGMA optimize at the
-- end of every one, and ticket 11 found the indexes buy nothing until the
-- planner has statistics. A fixture without them measures a phone nobody has.
Analyze;
SQL

articles="$("$SQLITE" "$DB" 'Select count(*) From Article')"
unread="$("$SQLITE" "$DB" 'Select count(*) From Article Where read = 0')"
starred="$("$SQLITE" "$DB" 'Select count(*) From Article Where starred = 1')"
if [ "$articles" != "$ARTICLES" ]; then
  echo "the store came out with $articles articles, not $ARTICLES" >&2
  exit 1
fi
echo "  $articles articles, $unread unread, $starred starred, $(du -h "$DB" | cut -f1) on disk"

# --- onto the device --------------------------------------------------------

"$ADB" -s "$DEVICE_SERIAL" root > /dev/null
"$ADB" -s "$DEVICE_SERIAL" wait-for-device

if [ "$("$ADB" -s "$DEVICE_SERIAL" shell id -u | tr -d '\r')" != "0" ]; then
  echo "adb is not root on $DEVICE_SERIAL, so it cannot write into an app's store." >&2
  echo "  bench-pixel6-aosp is an AOSP image and allows it; a Play image would not." >&2
  exit 1
fi

# The app has to have run once for its data directory to exist, and it must not
# be running now: a live process holds the database open and would write its own
# pages over what is pushed here.
"$ADB" -s "$DEVICE_SERIAL" shell am force-stop "$PACKAGE"
"$ADB" -s "$DEVICE_SERIAL" shell "mkdir -p /data/data/$PACKAGE/databases"

"$ADB" -s "$DEVICE_SERIAL" push "$DB" "/data/local/tmp/lenews-db" > /dev/null
"$ADB" -s "$DEVICE_SERIAL" shell "
  set -e
  owner=\$(stat -c %u:%g /data/data/$PACKAGE)
  # The write-ahead log and the shared-memory file belong to the database that
  # was there before. Left behind, SQLite replays them over the one just pushed.
  rm -f /data/data/$PACKAGE/databases/lenews-db*
  cp /data/local/tmp/lenews-db /data/data/$PACKAGE/databases/lenews-db
  rm -f /data/local/tmp/lenews-db
  # The directory too: mkdir above ran as root, so on the first seeding of a
  # build that has never opened its database it belongs to root and the app
  # cannot create the write-ahead log beside the file it was handed.
  chown \$owner /data/data/$PACKAGE/databases /data/data/$PACKAGE/databases/lenews-db
  chmod 771 /data/data/$PACKAGE/databases
  chmod 660 /data/data/$PACKAGE/databases/lenews-db
  restorecon -R /data/data/$PACKAGE/databases
"

echo "✓ $PACKAGE on $DEVICE_SERIAL now holds $articles articles"
