<img src="docs/icon.png" width="96" align="right" alt="Tender icon">

# Tender for Frigate

Native apps for [Frigate NVR](https://frigate.video) on iPhone, iPad, Mac and Android, built
from the mockups in `design/`. Tender is an independent app, compatible with Frigate NVR; it is
not affiliated with or endorsed by the Frigate project.

| App | Folder | Built with | Requires | Get it |
| --- | --- | --- | --- | --- |
| iPhone, iPad and Mac | [`ios/`](ios) | SwiftUI, Mac Catalyst, no third-party dependencies | iOS 17, Xcode 26 | [TestFlight](https://testflight.apple.com/join/s6KEWhy1) |
| Android | [`android/`](android) | Kotlin, Jetpack Compose, Material 3 | Android 9 (API 28) | Build it yourself, see [android/README.md](android/README.md) |

Both apps have the same screens and follow Frigate's web UI for names and flows. A feature or
fix in one goes into the other too, unless the platform does not allow it.

> **This app is fully vibe coded.** Every line of code, the Xcode and Gradle projects, the
> TestFlight setup and this README were written by an AI coding agent (Claude Code) from the
> design mockups and plain-language feedback. The apps were then tested in the iOS Simulator and
> the Android emulator against real Frigate 0.18 servers. No code was written by hand. Expect
> rough edges, and review the code before trusting it with anything important.

## Screenshots

From the iOS app.

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/1-live.png" width="200"><br><sub>Live</sub></td>
    <td align="center"><img src="docs/screenshots/2-camera.png" width="200"><br><sub>Camera</sub></td>
    <td align="center"><img src="docs/screenshots/3-review.png" width="200"><br><sub>Review</sub></td>
    <td align="center"><img src="docs/screenshots/4-event.png" width="200"><br><sub>Event</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/5-motion.png" width="200"><br><sub>Motion</sub></td>
    <td align="center"><img src="docs/screenshots/6-playback.png" width="200"><br><sub>Playback</sub></td>
    <td align="center"><img src="docs/screenshots/7-timeline.png" width="200"><br><sub>Timeline</sub></td>
    <td align="center"><img src="docs/screenshots/8-settings.png" width="200"><br><sub>Servers</sub></td>
  </tr>
</table>

<p align="center"><img src="docs/screenshots/9-landscape.png" width="820"><br><sub>Landscape playback: tap to hide the overlay, drag the timeline with a live preview</sub></p>

### iPad and Mac

A narrow rail instead of the tab bar, the video as large as the window allows, and Frigate's
review grid and timeline. The Mac version is a native Mac Catalyst app. Android tablets get the
same rail and layout.

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/ipad-2-camera.jpg" width="410"><br><sub>Camera</sub></td>
    <td align="center"><img src="docs/screenshots/ipad-3-review.jpg" width="410"><br><sub>Review with timeline</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/ipad-4-playback.jpg" width="410"><br><sub>Playback</sub></td>
    <td align="center"><img src="docs/screenshots/ipad-5-motion.jpg" width="410"><br><sub>Motion: every camera at one moment</sub></td>
  </tr>
</table>

### Android

The same screens in Kotlin and Jetpack Compose with Material 3, always dark like the iOS app.

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/android-1-live.png" width="200"><br><sub>Live</sub></td>
    <td align="center"><img src="docs/screenshots/android-2-camera.png" width="200"><br><sub>Camera</sub></td>
    <td align="center"><img src="docs/screenshots/android-3-review.png" width="200"><br><sub>Review</sub></td>
    <td align="center"><img src="docs/screenshots/android-4-event.png" width="200"><br><sub>Event</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/android-5-motion.png" width="200"><br><sub>Motion</sub></td>
    <td align="center"><img src="docs/screenshots/android-6-playback.png" width="200"><br><sub>Playback</sub></td>
    <td align="center"><img src="docs/screenshots/android-7-timeline.png" width="200"><br><sub>Timeline</sub></td>
    <td align="center"><img src="docs/screenshots/android-8-settings.png" width="200"><br><sub>Servers</sub></td>
  </tr>
</table>

## Run

**iOS, iPadOS and Mac:**

```bash
open ios/Frigate.xcodeproj
```

Pick an iPhone simulator, or "My Mac (Mac Catalyst)", and run.

**Android:** from `android/`:

```bash
./gradlew :app:installDebug
```

This installs the app on a running emulator or device. Or open `android/` in Android Studio.

On first launch the app scans the local network for Frigate servers (ports 8971 and 5000) and
lets you add one. On the Android emulator the scan finds nothing, so type the address there.
Several servers can be added; in Settings you choose whether the tabs show one server or all
of them.

## Features

| Screen | What it does |
| --- | --- |
| Live | Camera grid with refreshing snapshots, live objects and last activity from Frigate's websocket; red outline while an object moves and a dot for motion, automatic live view on activity, recent alerts as looping clips at the top (like Frigate's web UI); one or two columns |
| Camera groups | Frigate's camera groups with its Lucide icons; one group can span all your servers (same name and icon on each); add, edit and delete; "Now active" shows the cameras where something moves, for as long as Birdseye's inactivity threshold |
| Camera | Live stream through go2rtc (MSE, with WebRTC and MJPEG as fallbacks), audio, two-way talk for cameras with a speaker, snapshot, manual recording, PTZ (ONVIF cameras), full screen, Frigate's switches, today's events, debug view (boxes, zones, masks, motion, regions, object list) |
| Review | Alerts and detections across servers, as a grid with a timeline on iPad, Mac and tablets; filter by camera, label, zone, unreviewed and date (last 24 hours by default); mark reviewed; GenAI summaries with threat level |
| Motion | Every camera's preview at one moment with a motion timeline beside them; drag or play fast (4× to 16×), cameras with motion get a border, tap one to open its recording there |
| Event | Clip playback with the event range highlighted, score, zones, object lifecycle, AI analysis, save to Frigate or the device, share, delete |
| Explore | Tracked objects per label (like Frigate's Explore page), one search field for semantic search and license plates, Find similar, filters by camera, label, zone, sub label and date, object details with snapshot, lifecycle and GenAI description (edit, regenerate) |
| Playback | Recordings per camera and day, event list or scrubbable timeline with zoom and a live preview while dragging (like Frigate's web UI), calendar, filters by type, label and zone, gaps shown as "no recording", save a span to the device or export it to Frigate |
| Exports | Frigate's exports and cases: play, share, save, rename, move between cases, delete |
| Face Library | Recent recognitions and known faces: train, reclassify, upload, rename, delete |
| Classification | Frigate's custom classification models: examples, training and the new-model wizard |
| Chat | Ask Frigate's GenAI chat about your cameras and events, with streamed answers (when a server has a GenAI provider with the chat role, Frigate 0.18+) |
| Settings | Servers (add, edit, remove, reorder; login and a remote address), Live settings, and per server Frigate's own settings: cameras, record, detect, objects, motion, review, audio, enrichments, profiles, users, zones and masks (with an editor), streams, camera address, add-camera wizard, system status and logs, config editor, Frigate's web interface, restart |
| Everywhere | Pinch to zoom on live and recorded video, lighter streams on mobile data, landscape with an overlay that hides on tap, English and Dutch (follows the device language) |

What Frigate allows only admins (switching cameras and masks, deleting, renaming, settings) is
hidden from viewers or shown disabled, as in Frigate's web UI.

Credentials are kept in the Keychain on iOS and in `EncryptedSharedPreferences` on Android.
When a username is set the app logs in through `/api/login`; unauthenticated ports work without
one. An optional remote address is used when the primary address is unreachable.

## Implementation notes

- **Recordings are played in one-hour chunks.** Frigate's `/vod` endpoint returns 503 for ranges
  longer than about three hours. Chunks start on a recording segment boundary so the player
  position maps exactly to wall-clock time (`RecordingMap` in `PlaybackView.swift`).
- **HEVC recordings (iOS).** Many cameras deliver H.265 tagged `hev1`, which AVPlayer refuses over HLS.
  `HLSProxy` serves VOD through `127.0.0.1` and rewrites the init segment to `hvc1`; downloaded
  clips get the same patch. This is the same effect as Frigate's `-tag:v hvc1` recording option.
- **Motion activity** is requested per camera: when several cameras are requested at once, Frigate
  merges them into one series.
- **Live objects** come from Frigate's websocket: `camera_activity` once on connecting, then the
  `events` topic (new, update, end) and `<camera>/motion`. While the socket is down, the apps poll
  in-progress events instead.

## Deep links

The same on both platforms; useful for testing and shortcuts:

```
frigate://add?url=http://192.168.1.21:8971&name=Home
frigate://live | review | explore | chat | settings
frigate://review?view=motion
frigate://camera/<camera>[?server=<name>]
frigate://playback/<camera>[?day=yyyy-MM-dd&review=<id>&view=timeline]
frigate://event/<review id>
```

## Not yet implemented

- Push notifications, see below.
- Birdseye: without a go2rtc restream, Frigate sends it as jsmpeg, which the apps cannot play.

### Why there are no push notifications

Frigate sends its notifications with Web Push, the push system of browsers. A native app
cannot receive Web Push. iOS only delivers notifications through Apple's service (APNs), and
Android only through Google's (FCM). Both only accept notifications sent with the app
developer's key. Frigate can't hold that key, so every app with push runs a relay server in
between (the Home Assistant app does too). Tender deliberately has no server of its own: it
connects only to your Frigate servers and collects nothing. Polling in the background is not
reliable either, because both systems run apps in the background only now and then. The apps
can pause and resume Frigate's own notifications per camera, as Frigate's web UI does.

What works today:

- **Frigate's web app on your home screen.** Open Frigate in Safari or Chrome and add it to the
  home screen. Then register the device under Settings → Notifications. Frigate has to be
  reachable over HTTPS. Notifications open Frigate's web app.
- **Home Assistant** with the Frigate integration and the Home Assistant app.

If Frigate gains a channel such as [ntfy](https://ntfy.sh), Pushover or Apprise, notifications
could link to `frigate://event/<id>` and open the event in Tender.

## Releasing (iOS)

```bash
ios/scripts/testflight.sh             # internal testers
ios/scripts/testflight.sh --external  # also the external group, submitted for beta review
```

The script archives with a timestamp build number, signs with the "Frigate App Store" profile
and uploads the build. With `--external`, `ios/scripts/distribute.py` waits for processing, sets
"What to Test" from the commits since the previous external build and submits the build to the
group "Testers".

App Store Connect: "Tender for Frigate" (bundle ID `nl.wiebenieuwenhuis.frigate`, team
EBTM3728EJ). The internal group "Intern" gets every build automatically.

The Android app has no store release yet.

## Repository layout

```
ios/        iOS, iPadOS and Mac app (Xcode project, sources, release scripts)
android/    Android app (Gradle project); see android/docs/PORTING.md
docs/       UX rules for both apps (UX.md), screenshots, App Review notes
design/     HTML mockups and the app icon
scripts/    lucide-icons.py: the camera group icons for both apps
```

## License

Copyright (C) 2026 Wiebe Nieuwenhuis

Tender is free software: you can redistribute it and/or modify it under the terms of the
[GNU General Public License v3.0](LICENSE) as published by the Free Software Foundation.
It is distributed in the hope that it will be useful, but without any warranty.

Frigate is a trademark of Frigate, Inc. Tender is an independent project and is not
affiliated with or endorsed by Frigate.
