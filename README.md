# ModernizeHU

A replacement home screen and an ADB debloat for cheap Android 7.1 car head units running NWD
(Nuoweida) firmware, such as the ones sold in Indonesia under the Brainbox name. Home is a dark map
centred on the car, with the clock, weather, prayer times, the playing song and an app dock that fades
when you are not touching it. It also does simple turn-by-turn navigation.

Everything runs over ADB from a computer and needs no root. Nothing on the system partition changes,
and every step can be undone.

## Features

- The map is OpenStreetMap data, drawn on the device in a dark style and cached on disk, so roads you
  have driven show without internet. The car is a blue dot, or an arrow while navigating.
- Search a place, preview the route and tap Go. A banner shows the next turn, its distance, the arrival
  time and what is left, the map turns with the road, and leaving the route finds a new one. There is no
  voice guidance and no live traffic.
- Weather and the area name for where the car is, refreshed every 20 minutes or after driving a few
  kilometres.
- Prayer times computed on the device by the Indonesian Ministry of Religious Affairs (Kemenag RI)
  method, with the next one highlighted.
- The song playing from Bluetooth or any media app, with its cover and lyrics that follow along. Both
  are cached, so songs played before load offline.
- Bluetooth music starts without opening NWD's player, whose Back key stops the music.
- The app dock fades after 10 seconds; tap the map to bring it back. All apps opens over the map.
- `debloat.txt` disables NWD's ad and app-store services and apps the owner did not use.

## Supported devices

| Device | Status |
|---|---|
| NWD "QUAD-CORE T3" k2001-nwd (Brainbox): Allwinner T3, 2 GB RAM, 1024x600, Android 7.1.1, firmware `K2001N_MCX_S212B15.20220228.145840` | Tested: everything works |
| Android emulator: API 25, 1024x600, 160 dpi | Tested: everything except the Bluetooth music integration |
| Other head units with NWD firmware | Untested. Likely to work if they have the same `com.nwd.*` and `com.bt.*` apps |
| Other Android 7.1+ head units | Untested. Home, map, navigation, weather, prayer times and media apps should work; the Bluetooth music integration and `debloat.txt` are NWD-specific |

Requirements:

- Android 7.1 (API 25) or newer.
- A 32-bit or 64-bit ARM CPU. The TLS library is native code built only for ARM.
- A landscape screen. Layouts are made for 1024x600 at 160 dpi; other sizes are untested.
- ADB access, over the network or USB.
- Internet, through Wi-Fi or a phone hotspot, for maps, routes, weather, covers and lyrics.

To check whether your unit runs NWD firmware:

```sh
adb shell getprop ro.build.display.id
adb shell pm list packages | grep -E 'com\.nwd\.|com\.bt\.'
```

If you try it on another unit, please open an issue saying what worked and what did not.

## Install

You need a computer with `adb`, JDK 17 and the Android SDK (platform 34). Point Gradle at the SDK with
`ANDROID_HOME` or with `sdk.dir` in `launcher/local.properties`.

1. On the head unit, enable Developer options and turn on USB debugging. On the tested unit this also
   opens ADB over Wi-Fi on port 5555. Find the unit's IP address in its Wi-Fi settings.
2. Check that the computer can reach it:

   ```sh
   export HU_HOST=<head-unit-ip>
   adb connect $HU_HOST:5555
   ```

3. Optional: debloat. Read `debloat.txt` first and remove anything you still use.

   ```sh
   scripts/disable.sh debloat.txt
   ```

4. Build and install the home screen:

   ```sh
   scripts/install-launcher.sh
   ```

   This installs the app, grants location and notification access (for the playing song), and makes it
   the Home app.

On NWD units, network ADB often shows "offline" after the unit boots. Turn USB debugging off and on
again to fix it.

### Undo

```sh
scripts/restore.sh debloat.txt
```

This re-enables what `disable.sh` turned off, sets the animation speeds in Developer options back to
what they were before the first run, and puts the stock home screen back. It needs
`record/debloat.state`, which `disable.sh` writes before its first change.
Without a computer, Settings > Apps > (app) > Enable brings a single app back.

To switch back to the stock home screen only:

```sh
adb shell cmd package set-home-activity com.android.launcher/com.android.launcher2.Launcher
```

## Debloat rules

- Apps are disabled with `pm disable-user`, never uninstalled.
- `scripts/disable.sh` refuses to touch packages the car needs, such as the reversing camera, CAN bus,
  Bluetooth, radio service, DSP and system UI.
- The stock launcher stays enabled: it also hosts NWD's steering-wheel learning, reset and screen-off
  tools.
- Never open `com.nwd.factory.setting` or `com.nwd.whole.machine`; they are factory tools.

## Customise

The dock is the `DOCK` list at the top of
[`HomeActivity.java`](launcher/app/src/main/java/com/carhome/HomeActivity.java): package names and labels,
in order. Apps that are missing or disabled are skipped. The default dock has Google Maps, Spotify,
NewPipe (labelled YouTube, because Google no longer updates the YouTube app for Android 7.1), Phone,
BT Music, Car settings and Settings. Install NewPipe yourself, for example from F-Droid.

## How it works

The launcher is plain Java with no AndroidX, in `launcher/app/src/main/java/com/carhome`.

`MapTiles` downloads OpenFreeMap vector tiles, `VectorTile` decodes them, `MapRenderer` draws them and
`MapView` places the car and the route. Drawing uses the 2D canvas only: the unit's Mali-400 GPU has
no OpenGL ES 3, which vector map apps such as Organic Maps require.

`Places` searches with Photon. `Router` asks the FOSSGIS Valhalla server for a route, at most once a
second. `Route` works out where the car is on the route, and `Navigation` runs the search, preview and
driving screens.

Android 7.1 supports TLS 1.2 at most and the routing server accepts only TLS 1.3, so all requests go
through OkHttp 3.12 with Conscrypt as the TLS provider (`Http`, `Tls`). Conscrypt's native library is
most of the APK's 5 MB.

NWD's Bluetooth module reports the song and play/pause in broadcasts and accepts play, previous and
next commands (`BtMusic`). It never reports the playback position, so lyrics for phone audio run on a
timer that follows Android's "audio is playing" state. Tap a lyric line to sync it, or use −2s / +2s.

`SongLookup` fetches a cover and lyrics once per song and keeps about the last 400 songs on disk. A
song with no match is tried again after two weeks.

`PrayerTimes` uses Subuh at 20°, Isya at 18° and the Shafi'i Ashar, plus 2 minutes of ihtiyat.

## Privacy

There are no accounts, analytics or ads. These services receive data:

| Service | What it receives |
|---|---|
| OpenFreeMap | Which map tiles are shown, which reveals the area |
| Open-Meteo | The car's position, rounded to about 100 m |
| Nominatim (OpenStreetMap) | The car's position, rounded to about 100 m, to name the area |
| Photon (komoot) | Search text and the car's position, rounded to about 10 m, to sort results by distance |
| FOSSGIS Valhalla | The car's position, heading and destination |
| iTunes Search API | Title and artist of the playing song |
| LRCLIB | Title and artist of the playing song |

The launcher's data is excluded from Android backups.

## Development

```sh
cd launcher
./gradlew testReleaseUnitTest   # unit tests: prayer times, routes, vector tiles, weather
./gradlew lintRelease
./gradlew assembleRelease       # app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the debug key, because it is sideloaded over ADB. For an emulator,
create an API 25 Google APIs image with a 1024x600 screen at 160 dpi. Set its position with
`adb emu geo fix <lon> <lat>`.

## Credits and third-party services

- Map data © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors (ODbL), tiles from
  [OpenFreeMap](https://openfreemap.org).
- Weather by [Open-Meteo](https://open-meteo.com) (CC BY 4.0). Its free API is for non-commercial use
  only.
- Place search by [Photon](https://photon.komoot.io), area names by
  [Nominatim](https://nominatim.org).
- Routes by the [FOSSGIS Valhalla](https://valhalla1.openstreetmap.de) server.
- Covers from the [iTunes Search API](https://performance-partners.apple.com/search-api), lyrics from
  [LRCLIB](https://lrclib.net).
- Prayer time code ported from PrayTimes.js by [PrayTimes.org](http://praytimes.org) (LGPL v3.0).
- [Conscrypt](https://github.com/google/conscrypt) and [OkHttp](https://square.github.io/okhttp/)
  (Apache 2.0).

OpenFreeMap, Photon, Nominatim, FOSSGIS Valhalla and LRCLIB are free public servers with usage
policies. Keep the request limits in the code as they are, and host your own instance for anything
beyond personal use.

## Disclaimer

Not affiliated with NWD, Brainbox or any of the services above. You use this at your own risk.
Navigation is a visual aid without traffic data; keep your eyes on the road and do not operate the
screen while driving.

## License

[MIT](LICENSE), except
[`PrayerTimes.java`](launcher/app/src/main/java/com/carhome/PrayerTimes.java), which is a modified port
of PrayTimes.js and stays under the GNU LGPL v3.0.
