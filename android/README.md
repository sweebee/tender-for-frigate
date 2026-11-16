# Tender for Frigate (Android)

Native Android client for [Frigate NVR](https://frigate.video): a port of the iOS app
[Tender for Frigate](https://github.com/sweebee/tender-for-frigate) to Kotlin, Jetpack Compose and
Material 3. It is always dark, as the iOS app, with the wallpaper's colours where Android offers them,
in English and Dutch.
Tender is an independent app; it is not affiliated with or endorsed by the Frigate project.

> Like the iOS app, this port was written by an AI coding agent (Claude Code) and tested in the
> Android emulator against real Frigate 0.18 servers. Review the code before trusting it with
> anything important.

## Requirements

- JDK 17 or newer (Android Studio's bundled JBR works)
- Android SDK with platform 37 and build tools (Android Studio, or the command-line tools)
- An emulator or device on Android 9 (API 28) or newer

## Build and run

```bash
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug           # install on the running emulator or device
adb shell am start -n nl.wiebenieuwenhuis.tender/.MainActivity
```

Or open the folder in Android Studio and run the `app` configuration.

Create an emulator from the command line if you have none:

```bash
sdkmanager "system-images;android-34;google_apis;arm64-v8a"
avdmanager create avd -n tender_phone -k "system-images;android-34;google_apis;arm64-v8a" -d pixel_7
emulator -avd tender_phone
```

The emulator is behind NAT, so the network scan on the first screen finds nothing there; type
the server's address (e.g. `192.168.1.20` or `frigate.local:8971`) instead.

## Deep links

Useful for testing (`adb shell am start -a android.intent.action.VIEW -d "<link>"`):

```
frigate://add?url=http://192.168.1.21:8971&name=Home
frigate://live | review | explore | chat | settings
frigate://review?view=motion
frigate://camera/<camera>[?server=<name>]
frigate://playback/<camera>[?day=yyyy-MM-dd&review=<id>&view=timeline]
frigate://event/<review id>
```

## Features

| Screen | What it does |
| --- | --- |
| Live | Camera grid with refreshing pictures, live objects (Frigate's websocket: `camera_activity`, then `events` new/update/end and `<camera>/motion`), red outline while an object moves, motion dot, automatic live view on activity, recent alerts as looping clips, camera groups across servers with Frigate's Lucide icons (add, edit, delete), "Now active" (lingers as long as `birdseye.inactivity_threshold`), profile switcher, server problems banner |
| Camera | Live stream through go2rtc in a web view (MSE, then WebRTC, then MJPEG), stream choice, sound, two-way talk (WebRTC with the microphone, for cameras with a backchannel), snapshot, manual recording, PTZ, full screen, pinch to zoom, Frigate's switches, debug view |
| Review | Alerts, detections and motion across servers, filters, mark reviewed, select, delete, GenAI summaries and threat level, timeline on tablets |
| Event | Clip with the event span, objects and lifecycle, AI analysis, save, share, delete |
| Playback | Recordings per camera and day in one-hour chunks, events list or zoomable timeline with preview while scrubbing, calendar, save a clip, export to Frigate |
| Explore | Tracked objects per label, semantic search, Find similar, license plates, filters; object details with GenAI description (edit, regenerate) |
| Exports | Frigate's exports and cases: play, share, save, rename, move, delete |
| Face Library, Classification | Faces and recent recognitions; custom classification models with training and the model wizard |
| Chat | Frigate's GenAI chat, streamed |
| Settings | Servers (add, edit, remove, reorder; login, remote address), Live settings, and per server Frigate's settings: cameras, record, detect, objects, motion, review, audio, notifications, enrichments, profiles, users, zones and masks (editor), streams, camera address, add-camera wizard, camera order, MQTT, system status and logs, config editor (YAML), web interface, restart |

Passwords are kept with Android's `EncryptedSharedPreferences` (a key in the Android Keystore).
Frigate's login cookie lives in the system's web cookie store, shared by the app's HTTP client,
its image loader and the web views that play live video.

## Notifications

As in the iOS app, there are no push notifications: Frigate sends Web Push, which only browsers
receive, and Tender has no relay server of its own. The camera menus can pause and resume
Frigate's own notifications per camera, as Frigate's web UI does.

## Project layout

See [docs/PORTING.md](docs/PORTING.md) for the structure and the conventions used while porting.
`tools/gen_nl.py` fills the Dutch string files from the iOS app's translations
(`tools/ios-strings-nl.json`).

## License

Copyright (C) 2026 Wiebe Nieuwenhuis. GNU General Public License v3.0, as the iOS app.
Frigate is a trademark of Frigate, Inc.
