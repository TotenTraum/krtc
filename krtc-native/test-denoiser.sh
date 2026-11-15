#!/bin/sh
# After build-shim.sh: sh ./test-denoiser.sh <libwebrtc dir> <shim output dir> <macos|linux>
set -eu
WEBRTC="$1"
OUT="$2"
TARGET="$3"
DIR="$(cd "$(dirname "$0")" && pwd)"
. "$DIR/toolchain.sh"
DEFINES=$(sed -n 's/^defines = //p' "$WEBRTC/webrtc.ninja" | head -1)
eval "set -- $DEFINES"
INC="$WEBRTC/include"
case "$TARGET" in
macos) LINK_FLAGS="-framework Foundation -framework Security" ;;
linux)
    TARGET_FLAGS="$TARGET_FLAGS -nostdinc++ -isystem $INC/buildtools/third_party/libc++ -isystem $INC/third_party/libc++/src/include -isystem $INC/third_party/libc++abi/src/include"
    LINK_FLAGS="-nostdlib++ -lpthread -ldl -lm $($CXX --rtlib=compiler-rt -print-libgcc-file-name)"
    ;;
*) echo "test-denoiser.sh: use macos or linux on the target host" >&2; exit 1 ;;
esac
$CXX -std=c++20 -O2 -Wno-nullability-completeness $TARGET_FLAGS "$@" -DRNNOISE_EXPORT= \
    -I"$INC" -I"$INC/third_party/abseil-cpp" -I"$DIR/src/shim" -I"$DIR/.deps/rnnoise-0.2/include" \
    "$DIR/src/shim/tests/denoiser_test.cpp" "$OUT/libkrtc_shim.a" "$WEBRTC/lib/libwebrtc.a" \
    $LINK_FLAGS -o "$OUT/denoiser-test"
"$OUT/denoiser-test"
