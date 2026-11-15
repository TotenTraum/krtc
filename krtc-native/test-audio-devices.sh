#!/bin/sh
# Hardware-free regression checks for device selection, including reselecting the only active microphone.
set -eu
DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="${1:-$DIR/.deps/build/tests}"
mkdir -p "$OUT"
"${CXX:-c++}" -std=c++20 -Wall -Wextra -Werror -I"$DIR/src/shim" \
    "$DIR/src/shim/tests/audio_devices_test.cpp" -o "$OUT/audio-devices-test"
"$OUT/audio-devices-test"
