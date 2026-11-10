# Sourced by build-shim.sh and build-jni.sh: sets CXX, AR and TARGET_FLAGS for $TARGET.
case "$TARGET" in
macos) CXX="clang++"; AR="ar"; TARGET_FLAGS="-mmacosx-version-min=11.0" ;;
ios) CXX="xcrun --sdk iphoneos clang++"; AR="ar"; TARGET_FLAGS="-target arm64-apple-ios13.0" ;;
ios-simulator) CXX="xcrun --sdk iphonesimulator clang++"; AR="ar"; TARGET_FLAGS="-target arm64-apple-ios13.0-simulator" ;;
android-arm64)
    NDK="${ANDROID_NDK_HOME:-$(ls -d "$HOME/Library/Android/sdk/ndk/"* 2>/dev/null | sort -V | tail -1)}"
    [ -d "$NDK" ] || { echo "no Android NDK: set ANDROID_NDK_HOME" >&2; exit 1; }
    BIN="$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin | head -1)"
    CXX="$BIN/clang++"; AR="$BIN/llvm-ar"; TARGET_FLAGS="--target=aarch64-linux-android24"
    ;;
linux) CXX="${CXX:-clang++}"; AR="ar"; TARGET_FLAGS="" ;;
*) echo "unknown target $TARGET" >&2; exit 1 ;;
esac
