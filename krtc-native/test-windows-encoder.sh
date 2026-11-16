#!/bin/sh
# Run inside the Windows cross toolchain, then run the resulting EXE on Windows or in Wine.
set -eu
DIR="$(cd "$(dirname "$0")" && pwd)"
WEBRTC="${1:-$DIR/.deps/win-x64/win-x64-release}"
OUT="${2:-$DIR/.deps/build/tests/windows-encoder}"
mkdir -p "$OUT"
TARGET=win
. "$DIR/toolchain.sh"
INC="$WEBRTC/include"
DEFINES=$(grep -m1 '^defines = ' "$WEBRTC/webrtc.ninja" | sed 's/^defines = //')
eval "set -- $DEFINES"
for source in "$DIR/src/shim/krtc_windows_encoder.cpp" "$DIR/src/shim/tests/windows_encoder_test.cpp"; do
    object="$OUT/$(basename "$source" .cpp).obj"
    $CXX $TARGET_FLAGS /c /std:c++20 /O2 /MT /GR- /EHs-c- -Wno-deprecated-declarations -Wno-nullability-completeness "$@" /UNDEBUG \
        -I"$INC" -I"$INC/third_party/abseil-cpp" -I"$INC/third_party/libyuv/include" -I"$DIR/src/shim" "$source" /Fo"$object"
done
lld-link /nologo /machine:x64 /winsysroot:"${WINSYSROOT:-/xwin}" /out:"$OUT/windows-encoder-test.exe" /opt:ref /opt:icf \
    "$OUT/windows_encoder_test.obj" "$OUT/krtc_windows_encoder.obj" "$WEBRTC/lib/webrtc.lib" \
    winmm.lib secur32.lib ws2_32.lib iphlpapi.lib msdmo.lib dmoguids.lib wmcodecdspuuid.lib strmiids.lib \
    ole32.lib oleaut32.lib crypt32.lib d3d11.lib dxgi.lib dwmapi.lib user32.lib gdi32.lib shell32.lib \
    advapi32.lib shcore.lib uuid.lib mfuuid.lib
