#!/bin/sh
# Builds the krtc-sfu image from the release binaries (multi-arch with buildx).
#   deploy/docker/build-image.sh <tag> [--push]          both linux/amd64 and linux/arm64
#   PLATFORMS=linux/arm64 deploy/docker/build-image.sh krtc-sfu:dev --load    one architecture into the local daemon
# Builds the binaries first unless SKIP_GRADLE=1 (CI copies them from the build jobs).
set -eu
TAG="${1:?usage: build-image.sh <tag> [--push|--load]}"
shift
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PLATFORMS="${PLATFORMS:-linux/amd64,linux/arm64}"
CONTEXT="$ROOT/build/docker-context"
rm -rf "$CONTEXT"
for platform in $(echo "$PLATFORMS" | tr ',' ' '); do
    arch="${platform#linux/}"
    target=$([ "$arch" = amd64 ] && echo LinuxX64 || echo LinuxArm64)
    [ "${SKIP_GRADLE:-0}" = 1 ] || (cd "$ROOT" && ./gradlew -q ":krtc-sfu-server:linkReleaseExecutable$target")
    mkdir -p "$CONTEXT/linux-$arch"
    cp "$ROOT/krtc-sfu-server/build/bin/$(echo "$target" | sed 's/^L/l/')/releaseExecutable/krtc-sfu.kexe" "$CONTEXT/linux-$arch/krtc-sfu"
done
docker buildx build --platform "$PLATFORMS" -f "$ROOT/deploy/docker/Dockerfile" -t "$TAG" "$@" "$CONTEXT"
