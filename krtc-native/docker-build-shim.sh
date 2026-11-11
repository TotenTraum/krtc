#!/bin/sh
# Builds the shim in Docker.
#   ./docker-build-shim.sh <arm64|x64>       Linux (docker/Dockerfile): libkrtc_webrtc.a
#   ./docker-build-shim.sh <arm64|x64> jni   the same plus libkrtc_jni.so for the desktop JVM on Linux
#   ./docker-build-shim.sh win           Windows x64 (docker/windows/Dockerfile, clang-cl + xwin): krtc.dll
set -eu
ARCH="$1"
DIR="$(cd "$(dirname "$0")" && pwd)"
if [ "$ARCH" = win ]; then
    # The cross toolchain runs natively on the host's architecture; JNI headers come from the host JDK.
    JDK="${JAVA_HOME:-$(/usr/libexec/java_home 2>/dev/null || dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
    docker build -q -t krtc-win-toolchain "$DIR/docker/windows" >/dev/null
    docker run --rm -v "$DIR:/krtc-native" -v "$JDK/include:/jdk/include:ro" -w /krtc-native krtc-win-toolchain \
        sh -c "./build-shim.sh .deps/win-x64/win-x64-release .deps/build/win-x64 win && ./build-jni.sh .deps/win-x64/win-x64-release .deps/build/win-x64 win"
    exit 0
fi
PLATFORM=linux/$( [ "$ARCH" = x64 ] && echo amd64 || echo arm64 )
WEBRTC=".deps/linux-$ARCH/linux-$ARCH-release"
OUT=".deps/build/linux-$ARCH"
docker build -q --platform "$PLATFORM" -t "krtc-linux-toolchain:$ARCH" "$DIR/docker" >/dev/null
if [ "${2:-}" = jni ]; then
    # jni.h comes from the host JDK (it is the same everywhere); jni_md.h for Linux from src/shim/unix.
    JDK="${JAVA_HOME:-$(/usr/libexec/java_home 2>/dev/null || dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
    docker run --rm --platform "$PLATFORM" -v "$DIR:/krtc-native" -v "$JDK/include:/jdk/include:ro" -w /krtc-native "krtc-linux-toolchain:$ARCH" \
        sh -c "./build-shim.sh $WEBRTC $OUT && ./build-jni.sh $WEBRTC $OUT linux"
    exit 0
fi
docker run --rm --platform "$PLATFORM" -v "$DIR:/krtc-native" -w /krtc-native "krtc-linux-toolchain:$ARCH" \
    ./build-shim.sh "$WEBRTC" "$OUT"
