#!/bin/sh
# Builds the krtc C shim (src/shim/krtc.cpp) against a prebuilt libwebrtc into a static library.
#   ./build-shim.sh <libwebrtc dir with include/ lib/ webrtc.ninja> <output dir>
# Compiler flags mirror libwebrtc's own (defines read from webrtc.ninja); symbols are hidden except krtc_*.
#
# macOS: Apple clang against the system libc++ (LiveKit builds it with use_custom_libcxx=false).
# Linux: clang 21+ against the libc++ bundled in libwebrtc (namespace std::__Cr), never the system one;
#        run it on Linux (e.g. docker-build-shim.sh), the target's architecture.
set -eu
WEBRTC="$1"
OUT="$2"
mkdir -p "$OUT"
DEFINES=$(grep -m1 '^defines = ' "$WEBRTC/webrtc.ninja" | sed 's/^defines = //')
INC="$WEBRTC/include"
eval "set -- $DEFINES"
SRC="$(dirname "$0")/src/shim"
CXX="${CXX:-clang++}"
COMMON="-O2 -fPIC -fvisibility=hidden -fvisibility-inlines-hidden -Wno-deprecated-declarations -Wno-nullability-completeness"
INCLUDES="-I$INC -I$INC/third_party/abseil-cpp -I$INC/third_party/libyuv/include -I$SRC"
case "$(uname -s)" in
Darwin)
    COMMON="$COMMON -mmacosx-version-min=11.0"
    INCLUDES="$INCLUDES -I$INC/sdk/objc -I$INC/sdk/objc/base"
    PLATFORM_SRC="krtc_apple.mm"
    ;;
Linux)
    COMMON="$COMMON -nostdinc++ -isystem $INC/buildtools/third_party/libc++ -isystem $INC/third_party/libc++/src/include -isystem $INC/third_party/libc++abi/src/include"
    PLATFORM_SRC="krtc_linux.cpp"
    ;;
*) echo "unsupported host $(uname -s)" >&2; exit 1 ;;
esac
OBJS=""
for f in krtc.cpp krtc_audio.cpp $PLATFORM_SRC; do
    case "$f" in
    *.mm) "$CXX" -c -std=c++20 -fobjc-arc $COMMON "$@" $INCLUDES "$SRC/$f" -o "$OUT/${f%.*}.o" ;;
    *) "$CXX" -c -std=c++20 $COMMON "$@" $INCLUDES "$SRC/$f" -o "$OUT/${f%.*}.o" ;;
    esac
    OBJS="$OBJS $OUT/${f%.*}.o"
done
rm -f "$OUT/libkrtc_shim.a"
ar rcs "$OUT/libkrtc_shim.a" $OBJS
echo "built $OUT/libkrtc_shim.a"

if [ "$(uname -s)" = Linux ]; then
    # Symbol isolation: libwebrtc carries its own libc++/libc++abi and BoringSSL with global symbols that
    # collide with Kotlin/Native's libstdc++ and with OpenSSL. Pull the shim plus exactly the libwebrtc
    # objects it needs into one relocatable object, then make everything but krtc_* local.
    API=$(nm --defined-only -g "$OUT/libkrtc_shim.a" 2>/dev/null | awk '$2 == "T" && $3 ~ /^krtc_/ { print $3 }' | sort -u)
    printf '%s\n' $API > "$OUT/krtc.exports"
    UNDEF=""
    for s in $API; do UNDEF="$UNDEF -u $s"; done
    # compiler-rt builtins too: libwebrtc uses helpers (AArch64 outline atomics) that Kotlin/Native's
    # older libgcc lacks.
    BUILTINS=$("$CXX" --rtlib=compiler-rt -print-libgcc-file-name)
    ld.lld -r $UNDEF -o "$OUT/krtc_webrtc.o" "$OUT/libkrtc_shim.a" "$WEBRTC/lib/libwebrtc.a" "$BUILTINS"
    objcopy --keep-global-symbols="$OUT/krtc.exports" "$OUT/krtc_webrtc.o"
    rm -f "$OUT/libkrtc_webrtc.a"
    ar rcs "$OUT/libkrtc_webrtc.a" "$OUT/krtc_webrtc.o"
    rm -f "$OUT/krtc_webrtc.o"
    echo "built $OUT/libkrtc_webrtc.a (shim + libwebrtc, exports: $(echo $API | wc -w) krtc_* symbols)"
fi
