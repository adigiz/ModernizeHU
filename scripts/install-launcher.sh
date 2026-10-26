#!/usr/bin/env bash
# Build the car launcher, install it, let it read the playing track, and make it Home.
# The stock launcher stays enabled: it also carries the NWD tools (steering-wheel learning, screen off).
#
# Usage: HU_HOST=<head-unit-ip> scripts/install-launcher.sh
set -euo pipefail
HOST="${HU_HOST:?set HU_HOST to the head unit IP}"
DEV="$HOST:5555"
cd "$(dirname "$0")/../launcher"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
./gradlew --no-daemon -q assembleRelease

adb connect "$DEV" >/dev/null
adb -s "$DEV" install -r app/build/outputs/apk/release/app-release.apk
adb -s "$DEV" shell settings put secure enabled_notification_listeners com.carhome/com.carhome.MediaListener
adb -s "$DEV" shell pm grant com.carhome android.permission.ACCESS_FINE_LOCATION
adb -s "$DEV" shell pm grant com.carhome android.permission.ACCESS_COARSE_LOCATION
adb -s "$DEV" shell cmd package set-home-activity com.carhome/.HomeActivity
adb -s "$DEV" shell input keyevent HOME
sleep 2
adb -s "$DEV" shell dumpsys activity activities | grep mFocusedActivity
