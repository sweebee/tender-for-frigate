#!/bin/zsh
# Builds the signed release bundle for Google Play: app/build/outputs/bundle/release/app-release.aab.
# Signs with the upload key in ~/.android-keys/tender-upload.jks; its password is read from the
# macOS Keychain item "Tender Android upload key". Google Play re-signs the app (Play App Signing).
set -euo pipefail
cd "$(dirname "$0")/.."
export ORG_GRADLE_PROJECT_tenderUploadPassword=$(security find-generic-password -s "Tender Android upload key" -w)
./gradlew :app:bundleRelease -q
AAB=app/build/outputs/bundle/release/app-release.aab
echo "$AAB ($(du -h $AAB | cut -f1)), version code $(git rev-list --count HEAD)"
