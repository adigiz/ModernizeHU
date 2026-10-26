#!/usr/bin/env bash
# Disable every package in a list on the head unit (one package per line, # comments allowed).
# Packages are disabled for user 0 with pm disable-user, never uninstalled.
# Before the first change it records each package's current state in record/<list>.state, so
# scripts/restore.sh <same list> re-enables only what this script turned off.
#
# Usage: HU_HOST=<head-unit-ip> scripts/disable.sh debloat.txt
set -euo pipefail
LIST="${1:?usage: disable.sh <list>}"
HOST="${HU_HOST:?set HU_HOST to the head unit IP}"
DEV="$HOST:5555"
cd "$(dirname "$0")/.."

PKGS=$(sed -E 's/#.*$//; s/[[:space:]]+$//; s/^[[:space:]]+//' "$LIST" | grep -v '^$' || true)
[ -n "$PKGS" ] || { echo "no packages in $LIST" >&2; exit 1; }
BAD=$(printf '%s\n' "$PKGS" | grep -vE '^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$' || true)
[ -z "$BAD" ] || { echo "not a package name: $BAD" >&2; exit 1; }

# Packages the head unit needs to drive the car hardware, show the reversing camera or boot.
PROTECTED='^(android|com\.android\.(systemui|settings|shell|phone|launcher|packageinstaller|defcontainer|providers\..*|server\.telecom|inputdevices|bluetooth|car\.setting)|com\.google\.android\.(gms|gsf|gsf\.login|inputmethod\.latin)|com\.android\.vending|com\.nwd\.(backcar|can\.setting|can\.help|kernel|kernel\.setting|setting\.service|radio\.service|audioset|factory\.setting|android\.phone|bt\.music)|com\.bt\.bc03)$'
HIT=$(printf '%s\n' "$PKGS" | grep -E "$PROTECTED" || true)
[ -z "$HIT" ] || { echo "refusing: the head unit needs these, nothing changed:" >&2; printf '  %s\n' $HIT >&2; exit 1; }

adb connect "$DEV" >/dev/null
mkdir -p record
REC="record/$(basename "$LIST" .txt).state"
DISABLED=$(adb -s "$DEV" shell pm list packages -d | tr -d '\r' | sed 's/^package://')
INSTALLED=$(adb -s "$DEV" shell pm list packages | tr -d '\r' | sed 's/^package://')
if [ ! -f "$REC" ]; then
  for s in window_animation_scale transition_animation_scale animator_duration_scale; do
    echo "setting $s $(adb -s "$DEV" shell settings get global "$s" | tr -d '\r')" >> "$REC"
  done
fi
for p in $PKGS; do
  grep -q " $p\$" "$REC" 2>/dev/null && continue
  if ! printf '%s\n' "$INSTALLED" | grep -qx "$p"; then echo "absent $p" >> "$REC"
  elif printf '%s\n' "$DISABLED" | grep -qx "$p"; then echo "was-disabled $p" >> "$REC"
  else echo "was-enabled $p" >> "$REC"; fi
done
echo "record: $REC (keep it; restore.sh reads it)"

for p in $PKGS; do
  grep -q "^absent $p\$" "$REC" && { echo "skip $p (not installed)"; continue; }
  echo "\$ pm disable-user --user 0 $p"
  adb -s "$DEV" shell pm disable-user --user 0 "$p" | tr -d '\r'
done

NOW=$(adb -s "$DEV" shell pm list packages -d | tr -d '\r' | sed 's/^package://')
FAIL=0
for p in $PKGS; do
  grep -q "^absent $p\$" "$REC" && continue
  printf '%s\n' "$NOW" | grep -qx "$p" || { echo "STILL ENABLED: $p" >&2; FAIL=1; }
done
exit $FAIL
