#!/bin/zsh
# Builds the current branch and installs it on the paired iPhone (cable or, once paired, Wi-Fi),
# then opens it. Replaces the TestFlight app on that phone; its servers and logins stay.
# Usage: scripts/install-iphone.sh [device name or UDID]
set -euo pipefail
cd "$(dirname "$0")/.."
KEY_ID=TDLK875PQH
ISSUER=69a6de8f-8c00-47e3-e053-5b8c7c11a4d1
DEVICE=${1:-$(xcrun devicectl list devices 2>/dev/null | awk '/iPhone/ && !/simulated/ { for (i = 1; i <= NF; i++) if ($i ~ /^[0-9A-F]{8}-[0-9A-F]{16}$/) { print $i; exit } }')}
[[ -n "$DEVICE" ]] || { echo "No paired iPhone found (connect it once by cable and trust this Mac)."; exit 1; }
xcodebuild -project Frigate.xcodeproj -scheme Frigate -configuration Debug -destination "id=$DEVICE" \
  -derivedDataPath build/device -allowProvisioningUpdates -allowProvisioningDeviceRegistration \
  -authenticationKeyPath "$HOME/.appstoreconnect/private_keys/AuthKey_$KEY_ID.p8" \
  -authenticationKeyID $KEY_ID -authenticationKeyIssuerID $ISSUER \
  GIT_COMMIT=$(git rev-parse --short HEAD)$(git diff --quiet HEAD || echo -dirty) build | grep -E "error:|BUILD"
xcrun devicectl device install app --device "$DEVICE" build/device/Build/Products/Debug-iphoneos/Frigate.app | tail -1
xcrun devicectl device process launch --device "$DEVICE" nl.wiebenieuwenhuis.frigate | tail -1
