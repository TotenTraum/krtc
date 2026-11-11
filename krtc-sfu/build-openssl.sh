#!/bin/sh
# Builds OpenSSL 3 as static libraries for a Kotlin/Native Linux target, with Kotlin/Native's own clang and sysroot,
# into .deps/openssl-<target>/{include,lib} (not committed). Gradle packs them into the krtc-sfu klib, so Linux
# binaries of the SFU need no system OpenSSL and link against Kotlin/Native's old glibc without version clashes.
#   ./build-openssl.sh <linux-x64 | linux-arm64>
# Works on macOS and Linux hosts (cross-compiles; nothing here runs on the target). Needs perl, make and curl.
set -eu
TARGET="$1"
VERSION="${OPENSSL_VERSION:-3.5.9}"
DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="$DIR/.deps/openssl-$TARGET"
KONAN="${KONAN_DATA_DIR:-$HOME/.konan}/dependencies"

case "$TARGET" in
linux-x64) TRIPLE=x86_64-unknown-linux-gnu; CONFIG=linux-x86_64-clang; EXTRA="" ;;
# Kotlin/Native's libgcc predates AArch64 outline atomics; inline them instead.
linux-arm64) TRIPLE=aarch64-unknown-linux-gnu; CONFIG=linux-aarch64; EXTRA="-mno-outline-atomics" ;;
*) echo "build-openssl.sh: unknown target $TARGET" >&2; exit 1 ;;
esac

if [ -f "$OUT/lib/libssl.a" ] && [ "$(cat "$OUT/VERSION" 2>/dev/null)" = "$VERSION" ]; then
    echo "OpenSSL $VERSION for $TARGET: present"
    exit 0
fi

# Kotlin/Native's own toolchain: the clang and the target sysroot it links Linux binaries with. Gradle compiles a
# Kotlin module for the target first, which makes Kotlin/Native download them; if that compile came from the build
# cache, fetch them here the way Kotlin/Native does (names from its konan.properties, same download server).
case "$(uname -s)-$(uname -m)" in
Darwin-arm64) HOST=macos_arm64 ;; Darwin-x86_64) HOST=macos_x64 ;; Linux-x86_64) HOST=linux_x64 ;; *) HOST="" ;;
esac
KTARGET=$(echo "$TARGET" | tr - _)
PROPS=$(ls -d "${KONAN_DATA_DIR:-$HOME/.konan}"/kotlin-native-prebuilt-*/konan/konan.properties 2>/dev/null | sort | tail -1)
prop() { [ -n "$PROPS" ] && sed -n "s/^$1 *= *//p" "$PROPS" | head -1; }
fetch() { # <dependency name>: download and unpack into $KONAN unless present
    [ -d "$KONAN/$1" ] && return 0
    BASE=$(prop dependenciesUrl)
    mkdir -p "$KONAN/cache"
    echo "fetching Kotlin/Native dependency $1"
    curl -fsSL --retry 3 -o "$KONAN/cache/$1.tar.gz" "${BASE:-https://download.jetbrains.com/kotlin/native}/$1.tar.gz"
    tar -xzf "$KONAN/cache/$1.tar.gz" -C "$KONAN"
}
SYSROOT=$(ls -d "$KONAN"/$TRIPLE-gcc-*/$TRIPLE/sysroot 2>/dev/null | sort | tail -1)
if [ -z "$SYSROOT" ]; then
    NAME=$(prop "toolchainDependency.$KTARGET")
    [ -n "$NAME" ] || { echo "no Kotlin/Native $TRIPLE sysroot in $KONAN and no konan.properties to find it" >&2; exit 1; }
    fetch "$NAME"
    SYSROOT="$KONAN/$NAME/$TRIPLE/sysroot"
fi
LLVM=$(ls -d "$KONAN"/llvm-*/bin 2>/dev/null | sort | tail -1)
if [ -z "$LLVM" ]; then
    NAME=$([ -n "$HOST" ] && prop "llvm.$HOST.user")
    [ -n "$NAME" ] || { echo "no Kotlin/Native LLVM in $KONAN and no konan.properties to find it" >&2; exit 1; }
    fetch "$NAME"
    LLVM="$KONAN/$NAME/bin"
fi

WORK="$DIR/.deps/build/openssl-$TARGET"
rm -rf "$WORK" "$OUT"
mkdir -p "$WORK"
TARBALL="$DIR/.deps/openssl-$VERSION.tar.gz"
if [ ! -f "$TARBALL" ]; then
    URL="https://github.com/openssl/openssl/releases/download/openssl-$VERSION/openssl-$VERSION.tar.gz"
    curl -fsSL --retry 3 -o "$TARBALL.tmp" "$URL"
    EXPECTED=$(curl -fsSL --retry 3 "$URL.sha256" | awk '{print $1}')
    ACTUAL=$( (sha256sum "$TARBALL.tmp" 2>/dev/null || shasum -a 256 "$TARBALL.tmp") | awk '{print $1}')
    [ "$EXPECTED" = "$ACTUAL" ] || { echo "OpenSSL tarball checksum mismatch" >&2; rm -f "$TARBALL.tmp"; exit 1; }
    mv "$TARBALL.tmp" "$TARBALL"
fi
tar -xzf "$TARBALL" -C "$WORK" --strip-components=1

cd "$WORK"
LOG="$WORK/build.log"
run() { "$@" >>"$LOG" 2>&1 || { echo "OpenSSL build failed: $*" >&2; tail -40 "$LOG" >&2; exit 1; }; }
# Only what the SFU uses (EVP ciphers and MACs, DTLS, X.509): no shared libraries, apps, engines or loadable modules.
export CC="$LLVM/clang --target=$TRIPLE --sysroot=$SYSROOT" AR="$LLVM/llvm-ar" RANLIB="$LLVM/llvm-ar s"
run perl ./Configure "$CONFIG" no-shared no-dso no-engine no-module no-apps no-tests no-docs no-ui-console \
    --prefix="$OUT" --libdir=lib -O2 -fPIC $EXTRA
JOBS=$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)
run make -s -j"$JOBS" build_libs
run make -s install_dev
echo "$VERSION" > "$OUT/VERSION"
rm -rf "$WORK"
echo "built OpenSSL $VERSION for $TARGET in $OUT"
