#!/bin/sh
# Test SCK source metadata without Screen Recording permission or libwebrtc downloads (macOS 13+).
set -eu
DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="${1:-$DIR/.deps/build/tests}"
mkdir -p "$OUT"
clang++ -std=c++20 -fobjc-arc -mmacosx-version-min=13.0 -Wall -Wextra -Werror -I"$DIR/src/shim" \
    "$DIR/src/shim/tests/macos_screen_sources_test.mm" -framework Foundation -framework CoreGraphics \
    -framework ScreenCaptureKit -o "$OUT/macos-screen-sources-test"
"$OUT/macos-screen-sources-test"
