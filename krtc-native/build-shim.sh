#!/bin/sh
# Builds the krtc C shim (src/shim/krtc.cpp) against a prebuilt libwebrtc into a static library.
#   ./build-shim.sh <libwebrtc dir with include/ lib/ webrtc.ninja> <output dir> [target]
# target: macos | ios | ios-simulator | android-arm64 | linux | win (default: macos on macOS, linux on Linux).
# Compiler flags mirror libwebrtc's own (defines read from webrtc.ninja); symbols are hidden except krtc_*.
#
# macOS, iOS: Apple clang against the system libc++ (LiveKit builds them with use_custom_libcxx=false).
# Android:    the NDK's clang and libc++ (ANDROID_NDK_HOME, or the newest NDK under ~/Library/Android/sdk).
# Linux:      clang 21+ against the libc++ bundled in libwebrtc (namespace std::__Cr), never the system one;
#             run it on Linux (docker-build-shim.sh), for the target's architecture.
# Windows:    clang-cl against the MSVC CRT/STL (/MT, like libwebrtc) into krtc_shim.lib; build-jni.sh win then
#             links it with libwebrtc into krtc.dll. Cross-compiled in Docker (docker-build-shim.sh win).
set -eu
WEBRTC="$1"
OUT="$2"
case "$(uname -s)" in Darwin) DEFAULT_TARGET=macos ;; *) DEFAULT_TARGET=linux ;; esac
TARGET="${3:-$DEFAULT_TARGET}"
mkdir -p "$OUT"
DEFINES=$(grep -m1 '^defines = ' "$WEBRTC/webrtc.ninja" | sed 's/^defines = //')
INC="$WEBRTC/include"
eval "set -- $DEFINES"
DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$DIR/src/shim"
. "$DIR/toolchain.sh"
COMMON="-O2 -fPIC -fvisibility=hidden -fvisibility-inlines-hidden -Wno-deprecated-declarations -Wno-nullability-completeness $TARGET_FLAGS"
INCLUDES="-I$INC -I$INC/third_party/abseil-cpp -I$INC/third_party/libyuv/include -I$SRC"
case "$TARGET" in
macos | ios | ios-simulator)
    INCLUDES="$INCLUDES -I$INC/sdk/objc -I$INC/sdk/objc/base"
    PLATFORM_SRC="krtc_apple.mm"
    ;;
android-*) PLATFORM_SRC="krtc_software.cpp krtc_android.cpp" ;;
linux)
    COMMON="$COMMON -nostdinc++ -isystem $INC/buildtools/third_party/libc++ -isystem $INC/third_party/libc++/src/include -isystem $INC/third_party/libc++abi/src/include"
    PLATFORM_SRC="krtc_software.cpp"
    ;;
esac
if [ "$TARGET" = win ]; then
    # libwebrtc is built without exceptions and RTTI (its defines include _HAS_EXCEPTIONS=0).
    OBJS=""
    for f in krtc.cpp krtc_audio.cpp krtc_software.cpp; do
        $CXX $TARGET_FLAGS /c /std:c++20 /O2 /MT /GR- /EHs-c- /Zc:inline /Gw /Gy -Wno-deprecated-declarations \
            -Wno-nullability-completeness -Wno-unknown-pragmas "$@" $INCLUDES "$SRC/$f" /Fo"$OUT/${f%.*}.obj"
        OBJS="$OBJS $OUT/${f%.*}.obj"
    done
    rm -f "$OUT/krtc_shim.lib"
    $AR /nologo /out:"$OUT/krtc_shim.lib" $OBJS
    echo "built $OUT/krtc_shim.lib"
    exit 0
fi
OBJS=""
for f in krtc.cpp krtc_audio.cpp $PLATFORM_SRC; do
    case "$f" in
    *.mm) $CXX -c -std=c++20 -fobjc-arc $COMMON "$@" $INCLUDES "$SRC/$f" -o "$OUT/${f%.*}.o" ;;
    *) $CXX -c -std=c++20 $COMMON "$@" $INCLUDES "$SRC/$f" -o "$OUT/${f%.*}.o" ;;
    esac
    OBJS="$OBJS $OUT/${f%.*}.o"
done
rm -f "$OUT/libkrtc_shim.a"
$AR rcs "$OUT/libkrtc_shim.a" $OBJS
echo "built $OUT/libkrtc_shim.a"

if [ "$TARGET" = linux ]; then
    # Symbol isolation: libwebrtc carries its own libc++/libc++abi and BoringSSL with global symbols that
    # collide with Kotlin/Native's libstdc++ and with OpenSSL. Pull the shim plus exactly the libwebrtc
    # objects it needs into one relocatable object, then make everything but krtc_* local.
    API=$(nm --defined-only -g "$OUT/libkrtc_shim.a" 2>/dev/null | awk '$2 == "T" && $3 ~ /^krtc_/ { print $3 }' | sort -u)
    printf '%s\n' $API > "$OUT/krtc.exports"
    UNDEF=""
    for s in $API; do UNDEF="$UNDEF -u $s"; done
    # compiler-rt builtins too: libwebrtc uses helpers (AArch64 outline atomics) that Kotlin/Native's
    # older libgcc lacks.
    BUILTINS=$($CXX --rtlib=compiler-rt -print-libgcc-file-name)
    ld.lld -r $UNDEF -o "$OUT/krtc_webrtc.o" "$OUT/libkrtc_shim.a" "$WEBRTC/lib/libwebrtc.a" "$BUILTINS"
    objcopy --keep-global-symbols="$OUT/krtc.exports" "$OUT/krtc_webrtc.o"
    rm -f "$OUT/libkrtc_webrtc.a"
    ar rcs "$OUT/libkrtc_webrtc.a" "$OUT/krtc_webrtc.o"
    rm -f "$OUT/krtc_webrtc.o"
    echo "built $OUT/libkrtc_webrtc.a (shim + libwebrtc, exports: $(echo $API | wc -w) krtc_* symbols)"
fi
