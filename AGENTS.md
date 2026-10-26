# Instructions for AI agents

This repository changes a real car head unit over ADB. A mistake can disable the reversing camera,
Bluetooth calls or the steering-wheel buttons, so follow these rules and ask the owner whenever a
step is not covered here.

## Connect

The owner gives the head unit's IP address. Use it as `HU_HOST`; ADB listens on port 5555.

```sh
export HU_HOST=<head-unit-ip>
adb connect $HU_HOST:5555
```

If the device shows "offline", ask the owner to turn USB debugging off and on again on the head unit.
Address it with `adb -s $HU_HOST:5555`, because an emulator may be connected too.

## Before changing anything

1. Read the unit's details and compare them with "Supported devices" in `README.md`. Report what you
   find. If the firmware is not NWD, say which parts will not work and ask before going on.

   ```sh
   adb -s $HU_HOST:5555 shell getprop ro.build.version.release
   adb -s $HU_HOST:5555 shell getprop ro.build.display.id
   adb -s $HU_HOST:5555 shell pm list packages | grep -E 'com\.nwd\.|com\.bt\.'
   ```

   Use the version from `getprop`. Settings on these units often shows a higher Android version.

2. Back up the current state into `backup/`, which git ignores:

   ```sh
   mkdir -p backup
   adb -s $HU_HOST:5555 shell getprop > backup/getprop.txt
   adb -s $HU_HOST:5555 shell pm list packages > backup/packages-all.txt
   adb -s $HU_HOST:5555 shell pm list packages -d > backup/packages-disabled.txt
   for t in global secure system; do adb -s $HU_HOST:5555 shell settings list $t > backup/settings-$t.txt; done
   adb -s $HU_HOST:5555 shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME > backup/home.txt
   ```

## Rules

- Disable apps with `scripts/disable.sh` only. Never uninstall, and never run `pm uninstall`,
  `pm clear` or anything that needs root or remounts the system partition.
- `debloat.txt` was written for one unit. For another unit, make a new list, explain each package in
  it and get the owner's approval for every one before running `disable.sh`.
- Do not disable or force-stop anything `scripts/disable.sh` protects: the reversing camera, CAN bus,
  Bluetooth, radio service, DSP, system UI, Settings, Play Services and the keyboard.
- Keep the stock launcher `com.android.launcher` enabled. It hosts the steering-wheel learning, reset
  and screen-off tools.
- Never start `com.nwd.factory.setting` or `com.nwd.whole.machine`. They are factory tools.
- Never change car settings (CAN bus, amplifier, steering wheel, reversing camera) unless the owner asks
  for that exact change.
- Read state with `adb shell` and take a screenshot (`adb exec-out screencap -p`) to see the screen.
  Taps with `adb shell input` are for testing the launcher, not for exploring NWD's settings apps.
- Say what you are about to do before each change, and how to undo it.

## Build and install the launcher

Needs JDK 17 and the Android SDK.

```sh
cd launcher && ./gradlew testReleaseUnitTest lintRelease   # must pass before installing
scripts/install-launcher.sh                                 # builds, installs and sets Home
```

After installing, take a screenshot and check for crashes:

```sh
adb -s $HU_HOST:5555 logcat -d | grep -E 'FATAL|AndroidRuntime'
```

To change the dock apps, edit the `DOCK` list in `HomeActivity.java`. Check each package is installed
with `pm list packages` first.

## Undo

```sh
scripts/restore.sh debloat.txt
```

Without a record file, re-enable single apps with `adb shell pm enable <package>`.

## When done

- Ask the owner to turn off USB debugging, which closes ADB on the network.
- Do not commit `backup/`, `record/`, screenshots, IP addresses or device serial numbers.
