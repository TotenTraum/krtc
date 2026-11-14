#!/bin/sh
# Downloads LiveKit's prebuilt libwebrtc into .deps/<platform> (not committed), skipping platforms already there,
# and the RNNoise sources (Xiph, BSD-3-Clause; compiled into the shim on every platform) into .deps/rnnoise-<version>.
#   ./fetch-deps.sh [platform...]   default: every platform krtc-native builds
# Platforms: mac-arm64 linux-arm64 linux-x64 ios-simulator-arm64 ios-device-arm64 android-arm64 win-x64
set -eu
RELEASE="${WEBRTC_RELEASE:-webrtc-a545de4}"
DIR="$(cd "$(dirname "$0")" && pwd)/.deps"

RNNOISE_VERSION=0.2
RNNOISE_SHA256=90fce4b00b9ff24c08dbfe31b82ffd43bae383d85c5535676d28b0a2b11c0d37
if [ -f "$DIR/rnnoise-$RNNOISE_VERSION/src/denoise.c" ]; then
    echo "rnnoise: present"
else
    mkdir -p "$DIR"
    echo "rnnoise: downloading $RNNOISE_VERSION"
    curl -fsSL --retry 3 -o "$DIR/rnnoise.tar.gz" "https://github.com/xiph/rnnoise/releases/download/v$RNNOISE_VERSION/rnnoise-$RNNOISE_VERSION.tar.gz"
    if command -v sha256sum >/dev/null; then SUM=$(sha256sum "$DIR/rnnoise.tar.gz"); else SUM=$(shasum -a 256 "$DIR/rnnoise.tar.gz"); fi
    [ "${SUM%% *}" = "$RNNOISE_SHA256" ] || { echo "rnnoise: checksum mismatch" >&2; rm -f "$DIR/rnnoise.tar.gz"; exit 1; }
    tar -xzf "$DIR/rnnoise.tar.gz" -C "$DIR"
    rm "$DIR/rnnoise.tar.gz"
fi
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
