#!/bin/sh
# Builds OpenSSL 3 as static libraries for a Kotlin/Native Linux target, with Kotlin/Native's own clang and sysroot,
# into .deps/openssl-<target>/{include,lib} (not committed). Gradle packs them into the krtc-sfu klib, so Linux
# binaries of the SFU need no system OpenSSL and link against Kotlin/Native's old glibc without version clashes.
#   ./build-openssl.sh <linux-x64 | linux-arm64>
# Works on macOS and Linux hosts (cross-compiles; nothing here runs on the target). Needs perl and make, and
# Kotlin/Native's Linux toolchain in ~/.konan (Gradle downloads it on the first Linux build).
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

SYSROOT=$(ls -d "$KONAN"/$TRIPLE-gcc-*/$TRIPLE/sysroot 2>/dev/null | sort -V | tail -1)
LLVM=$(ls -d "$KONAN"/llvm-*/bin 2>/dev/null | sort -V | tail -1)
[ -n "$SYSROOT" ] && [ -n "$LLVM" ] || { echo "no Kotlin/Native $TRIPLE toolchain in $KONAN: run a Gradle Linux build first" >&2; exit 1; }

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
# Only what the SFU uses (EVP ciphers and MACs, DTLS, X.509): no shared libraries, apps, engines or loadable modules.
CC="$LLVM/clang --target=$TRIPLE --sysroot=$SYSROOT" AR="$LLVM/llvm-ar" RANLIB="$LLVM/llvm-ar s" \
    perl ./Configure "$CONFIG" no-shared no-dso no-engine no-module no-apps no-tests no-docs no-ui-console \
    --prefix="$OUT" --libdir=lib -O2 -fPIC $EXTRA >/dev/null
JOBS=$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)
make -s -j"$JOBS" build_libs >/dev/null
make -s install_dev >/dev/null
echo "$VERSION" > "$OUT/VERSION"
rm -rf "$WORK"
echo "built OpenSSL $VERSION for $TARGET in $OUT"
