#!/bin/sh
# Downloads LiveKit's prebuilt libwebrtc into .deps/<platform> (not committed), skipping platforms already there.
#   ./fetch-deps.sh [platform...]   default: every platform krtc-native builds
# Platforms: mac-arm64 linux-arm64 linux-x64 ios-simulator-arm64 ios-device-arm64 android-arm64 win-x64
set -eu
RELEASE="${WEBRTC_RELEASE:-webrtc-a545de4}"
DIR="$(cd "$(dirname "$0")" && pwd)/.deps"
[ $# -gt 0 ] || set -- mac-arm64 linux-arm64 linux-x64 ios-simulator-arm64 ios-device-arm64 android-arm64 win-x64
for p in "$@"; do
    if [ -f "$DIR/$p/$p-release/lib/libwebrtc.a" ] || [ -f "$DIR/$p/$p-release/lib/webrtc.lib" ]; then
        echo "$p: present"
        continue
    fi
    mkdir -p "$DIR/$p"
    echo "$p: downloading $RELEASE"
    curl -fsSL --retry 3 -o "$DIR/$p/w.zip" "https://github.com/livekit/rust-sdks/releases/download/$RELEASE/webrtc-$p-release.zip"
    unzip -qo "$DIR/$p/w.zip" -d "$DIR/$p"
    rm "$DIR/$p/w.zip"
done
