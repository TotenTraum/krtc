#!/bin/sh
# Builds libkrtc_jni (src/shim/krtc_jni.cpp + the shim + libwebrtc) as a shared library for the JVM or Android.
#   ./build-jni.sh <libwebrtc dir> <shim output dir (from build-shim.sh)> <target: macos | android-arm64>
# Only the JNI entry points (Java_krtc_native_* and JNI_OnLoad) are exported, so libwebrtc's C++ runtime and
# BoringSSL stay private to the library.
set -eu
WEBRTC="$1"
OUT="$2"
TARGET="$3"
DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$DIR/src/shim"
. "$DIR/toolchain.sh"
CFLAGS="-O2 -fPIC -fvisibility=hidden -std=c++20 $TARGET_FLAGS -I$SRC"
case "$TARGET" in
macos)
    JDK="${JAVA_HOME:-$(/usr/libexec/java_home)}"
    $CXX -c $CFLAGS -I"$JDK/include" -I"$JDK/include/darwin" "$SRC/krtc_jni.cpp" -o "$OUT/krtc_jni.o"
    printf '_Java_krtc_native_*\n_JNI_OnLoad\n' > "$OUT/jni.exports"
    $CXX -dynamiclib $TARGET_FLAGS -o "$OUT/libkrtc_jni.dylib" "$OUT/krtc_jni.o" "$OUT/libkrtc_shim.a" "$WEBRTC/lib/libwebrtc.a" \
        -Wl,-exported_symbols_list,"$OUT/jni.exports" -ObjC -lc++ \
        -framework Foundation -framework AVFoundation -framework Security -framework CoreAudio -framework AudioToolbox \
        -framework AppKit -framework CoreMedia -framework CoreGraphics -framework VideoToolbox -framework CoreVideo \
        -framework OpenGL -framework Metal -framework MetalKit -framework QuartzCore -framework IOKit -framework IOSurface \
        -framework ScreenCaptureKit
    echo "built $OUT/libkrtc_jni.dylib"
    ;;
android-*)
    # The NDK sysroot provides jni.h.
    $CXX -c $CFLAGS "$SRC/krtc_jni.cpp" -o "$OUT/krtc_jni.o"
    printf '{ global: Java_krtc_native_*; JNI_OnLoad; local: *; };\n' > "$OUT/jni.map"
    $CXX -shared $TARGET_FLAGS -o "$OUT/libkrtc_jni.so" "$OUT/krtc_jni.o" "$OUT/libkrtc_shim.a" "$WEBRTC/lib/libwebrtc.a" \
        -static-libstdc++ -Wl,--version-script,"$OUT/jni.map" -Wl,--exclude-libs,ALL -Wl,--gc-sections \
        -Wl,-z,max-page-size=16384 -Wl,--no-undefined -llog -landroid -lOpenSLES
    echo "built $OUT/libkrtc_jni.so"
    ;;
*) echo "build-jni.sh: unsupported target $TARGET" >&2; exit 1 ;;
esac
