#!/bin/sh
# Builds the Linux shim in Docker (docker/Dockerfile): ./docker-build-shim.sh <arch: arm64|x64>
set -eu
ARCH="$1"
DIR="$(cd "$(dirname "$0")" && pwd)"
PLATFORM=linux/$( [ "$ARCH" = x64 ] && echo amd64 || echo arm64 )
docker build -q --platform "$PLATFORM" -t krtc-linux-toolchain "$DIR/docker" >/dev/null
docker run --rm --platform "$PLATFORM" -v "$DIR:/krtc-native" -w /krtc-native krtc-linux-toolchain \
    ./build-shim.sh ".deps/linux-$ARCH/linux-$ARCH-release" ".deps/build/linux-$ARCH"
