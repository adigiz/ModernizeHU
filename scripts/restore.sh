#!/usr/bin/env bash
# Undo scripts/disable.sh: re-enable every package the record says was enabled before, put the
# animation scales back, and make the stock launcher Home again.
#
# Usage: HU_HOST=<head-unit-ip> scripts/restore.sh debloat.txt
set -euo pipefail
LIST="${1:?usage: restore.sh <list>}"
HOST="${HU_HOST:?set HU_HOST to the head unit IP}"
DEV="$HOST:5555"
cd "$(dirname "$0")/.."
REC="record/$(basename "$LIST" .txt).state"
[ -f "$REC" ] || { echo "no record at $REC; re-enable apps from Settings > Apps instead" >&2; exit 1; }

adb connect "$DEV" >/dev/null
while read -r kind a b; do
  case "$kind" in
    was-enabled)
      echo "\$ pm enable $a"
      adb -s "$DEV" shell pm enable --user 0 "$a" | tr -d '\r' ;;
    setting)
      [ "$b" = "null" ] && b=1.0
      adb -s "$DEV" shell settings put global "$a" "$b" ;;
  esac
done < "$REC"

adb -s "$DEV" shell cmd package set-home-activity com.android.launcher/com.android.launcher2.Launcher | tr -d '\r'
# No app had notification access before the launcher was installed.
adb -s "$DEV" shell settings delete secure enabled_notification_listeners >/dev/null
echo "restored. The new launcher stays installed; remove it in Settings > Apps if you want it gone."
