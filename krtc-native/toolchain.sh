# Sourced by build-shim.sh and build-jni.sh: sets CXX, AR and TARGET_FLAGS for $TARGET.
case "$TARGET" in
macos) CXX="clang++"; AR="ar"; TARGET_FLAGS="-mmacosx-version-min=11.0" ;;
ios) CXX="xcrun --sdk iphoneos clang++"; AR="ar"; TARGET_FLAGS="-target arm64-apple-ios13.0" ;;
ios-simulator) CXX="xcrun --sdk iphonesimulator clang++"; AR="ar"; TARGET_FLAGS="-target arm64-apple-ios13.0-simulator" ;;
android-arm64)
    # An NDK from the usual variables (CI runners set them), else the newest one in the Android SDK.
    NDK=""
    for d in "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_LATEST_HOME:-}" "${ANDROID_NDK_ROOT:-}"; do
        if [ -n "$d" ] && [ -d "$d/toolchains/llvm" ]; then NDK="$d"; break; fi
    done
    if [ -z "$NDK" ]; then
        for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
            [ -n "$sdk" ] && [ -d "$sdk/ndk" ] || continue
            NDK=$(ls -d "$sdk/ndk/"* 2>/dev/null | sort -t. -k1,1n -k2,2n -k3,3n | tail -1)
            [ -n "$NDK" ] && break
        done
    fi
    [ -n "$NDK" ] && [ -d "$NDK" ] || { echo "no Android NDK: set ANDROID_NDK_HOME" >&2; exit 1; }
    BIN="$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin | head -1)"
    CXX="$BIN/clang++"; AR="$BIN/llvm-ar"; TARGET_FLAGS="--target=aarch64-linux-android24"
    ;;
linux) CXX="${CXX:-clang++}"; AR="ar"; TARGET_FLAGS="" ;;
# Windows x64, MSVC ABI: clang-cl and lld-link with the MSVC CRT/STL and Windows SDK in $WINSYSROOT (xwin
# splat --use-winsysroot-style); run it in docker-build-shim.sh win.
win) CXX="clang-cl"; AR="llvm-lib"; TARGET_FLAGS="--target=x86_64-pc-windows-msvc /winsysroot ${WINSYSROOT:-/xwin}" ;;
*) echo "unknown target $TARGET" >&2; exit 1 ;;
esac
