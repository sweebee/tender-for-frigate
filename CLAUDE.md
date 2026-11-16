# Tender for Frigate

Two native apps for Frigate NVR in one repo:

- `ios/`: SwiftUI app for iOS, iPadOS and Mac Catalyst (`ios/Frigate.xcodeproj`, scheme `Frigate`).
  Strings in English, translations in `ios/Frigate/Localizable.xcstrings`. Release scripts in
  `ios/scripts/`.
- `android/`: Kotlin and Jetpack Compose port of the iOS app; see
  [android/docs/PORTING.md](android/docs/PORTING.md). Strings in `res/values`, Dutch in
  `res/values-nl`.

Keep the apps at the same level: a feature or fix in one belongs in the other too, unless the
platform does not allow it.

- Before building or changing UI, read [docs/UX.md](docs/UX.md) and follow it (on Android,
  adapted to Material 3).
- Follow Frigate's web UI (source in `../frigate`) for names, flows and API use.
- `scripts/lucide-icons.py` writes the camera group icons for both apps.
