#!/bin/sh
# Builds libkrtc_jni (src/shim/krtc_jni.cpp + the shim + libwebrtc) as a shared library for the JVM or Android.
#   ./build-jni.sh <libwebrtc dir> <shim output dir (from build-shim.sh)> <target: macos | android-arm64 | win>
# Only the JNI entry points (Java_krtc_native_* and JNI_OnLoad) are exported, so libwebrtc's C++ runtime and
# BoringSSL stay private to the library.
# Windows: one krtc.dll for both the JVM and Kotlin/Native (mingwX64), so it also exports the krtc_* C API, plus
# import libraries for MSVC (krtc.lib) and MinGW (libkrtc.dll.a). The CRT is linked statically (/MT), so the DLL
# needs nothing beyond system DLLs. JDK headers: $JNI_INCLUDE (default /jdk/include, mounted by docker-build-shim.sh).
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
win)
    # jni.h comes from any JDK; jni_md.h for Windows from src/shim/win32 (the host JDK has its own OS's).
    $CXX $TARGET_FLAGS /c /std:c++20 /O2 /MT /GR- /EHs-c- -I"${JNI_INCLUDE:-/jdk/include}" -I"$SRC/win32" -I"$SRC" \
        "$SRC/krtc_jni.cpp" /Fo"$OUT/krtc_jni.obj"
    { echo "LIBRARY krtc.dll"; echo "EXPORTS"
      llvm-nm --defined-only -g "$OUT/krtc_shim.lib" 2>/dev/null | awk '$2 == "T" && $3 ~ /^krtc_/ { print "    " $3 }' | sort -u
    } > "$OUT/krtc.def"
    lld-link /nologo /dll /machine:x64 /winsysroot:"${WINSYSROOT:-/xwin}" /out:"$OUT/krtc.dll" /def:"$OUT/krtc.def" \
        /implib:"$OUT/krtc.lib" /opt:ref /opt:icf "$OUT/krtc_jni.obj" "$OUT/krtc_shim.lib" "$WEBRTC/lib/webrtc.lib" \
        winmm.lib secur32.lib ws2_32.lib iphlpapi.lib msdmo.lib dmoguids.lib wmcodecdspuuid.lib strmiids.lib \
        ole32.lib oleaut32.lib crypt32.lib d3d11.lib dxgi.lib dwmapi.lib user32.lib gdi32.lib shell32.lib \
        advapi32.lib shcore.lib uuid.lib
    llvm-dlltool -m i386:x86-64 -d "$OUT/krtc.def" -D krtc.dll -l "$OUT/libkrtc.dll.a"
    echo "built $OUT/krtc.dll ($(grep -c '^    krtc_' "$OUT/krtc.def") krtc_* exports + JNI)"
    ;;
*) echo "build-jni.sh: unsupported target $TARGET" >&2; exit 1 ;;
esac
