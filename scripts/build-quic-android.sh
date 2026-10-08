#!/bin/sh
# Builds the shared native QUIC engine for Android and stages it into jniLibs.
# macOS/Linux counterpart of scripts/build-quic-android.cmd.
#
# Requirements:
#   - Android NDK r27+ (ANDROID_NDK_HOME, or the newest one under the SDK)
#   - rustup with the aarch64-linux-android target (a workspace-local toolchain in
#     .tools/cargo is reused automatically when present)
#   - cmake and a libclang (Xcode Command Line Tools provide both on macOS)
#
# Usage: sh scripts/build-quic-android.sh [arm64-v8a|x86_64|all]
set -e

ABI=${1:-arm64-v8a}
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"

# --- toolchain locations -------------------------------------------------------------------
if [ -z "$ANDROID_NDK_HOME" ]; then
  for candidate in "$HOME/Library/Android/sdk/ndk"/* "$HOME/Android/Sdk/ndk"/* /usr/local/lib/android/sdk/ndk/*; do
    [ -d "$candidate" ] && ANDROID_NDK_HOME=$candidate
  done
fi
[ -n "$ANDROID_NDK_HOME" ] && [ -d "$ANDROID_NDK_HOME" ] || { echo "Android NDK not found; set ANDROID_NDK_HOME" >&2; exit 1; }
# boring-sys reads this from the environment, so it must be exported.
export ANDROID_NDK_HOME

case "$(uname -s)" in
  Darwin) HOST_TAG=darwin-x86_64 ;;
  Linux)  HOST_TAG=linux-x86_64 ;;
  *) echo "Unsupported host; use scripts/build-quic-android.cmd on Windows" >&2; exit 1 ;;
esac
NDK_BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin"
[ -d "$NDK_BIN" ] || { echo "NDK toolchain missing at $NDK_BIN" >&2; exit 1; }

if [ -x "$ROOT/.tools/cargo/bin/cargo" ]; then
  export RUSTUP_HOME="$ROOT/.tools/rustup"
  export CARGO_HOME="$ROOT/.tools/cargo"
  CARGO="$ROOT/.tools/cargo/bin/cargo"
  "$ROOT/.tools/cargo/bin/rustup" target add aarch64-linux-android >/dev/null 2>&1 || true
else
  CARGO=cargo
fi
export PATH="$NDK_BIN:$PATH"
export LIBCLANG_PATH=${LIBCLANG_PATH:-$(xcode-select -p 2>/dev/null)/Toolchains/XcodeDefault.xctoolchain/usr/lib}
[ -e "$LIBCLANG_PATH/libclang.dylib" ] || LIBCLANG_PATH=/Library/Developer/CommandLineTools/usr/lib

# The NDK toolchain falls back to armeabi-v7a unless the ABI is preset, and the Rust cmake crate
# does not forward ANDROID_ABI, so pin it through a small wrapper toolchain file.
WRAPPER="$ROOT/.tools/android-toolchain.cmake"
mkdir -p "$ROOT/.tools"
cat > "$WRAPPER" <<EOF
set(ANDROID_ABI "$ABI" CACHE STRING "Android ABI" FORCE)
set(ANDROID_PLATFORM "android-29" CACHE STRING "Android platform" FORCE)
set(ANDROID_NDK "$ANDROID_NDK_HOME" CACHE PATH "Android NDK" FORCE)
include("$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake")
EOF

# --- build --------------------------------------------------------------------------------
build_abi() {
  abi=$1
  case "$abi" in
    arm64-v8a) target=aarch64-linux-android; api=29; jni=arm64-v8a ;;
    x86_64)    target=x86_64-linux-android;  api=29; jni=x86_64 ;;
    *) echo "Unsupported ABI: $abi" >&2; exit 1 ;;
  esac
  suffix=$(printf '%s' "$target" | tr '-' '_')
  upper=$(printf '%s' "$target" | tr 'a-z-' 'A-Z_')
  export "CC_$suffix=$NDK_BIN/${target}${api}-clang"
  export "CXX_$suffix=$NDK_BIN/${target}${api}-clang++"
  export "AR_$suffix=$NDK_BIN/llvm-ar"
  export "RANLIB_$suffix=$NDK_BIN/llvm-ranlib"
  export "CARGO_TARGET_${upper}_LINKER=$NDK_BIN/${target}${api}-clang"
  echo "Building $target for $jni ..."
  CMAKE_TOOLCHAIN_FILE="$WRAPPER" "$CARGO" build \
    --manifest-path native/quic/Cargo.toml --release --locked --target "$target"
  mkdir -p "android/app/src/main/jniLibs/$jni"
  cp "native/quic/target/$target/release/liblanlink_quic.so" "android/app/src/main/jniLibs/$jni/"
  cp "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/sysroot/usr/lib/${target}/libc++_shared.so" \
     "android/app/src/main/jniLibs/$jni/"
  echo "Staged android/app/src/main/jniLibs/$jni/{liblanlink_quic.so,libc++_shared.so}"
}

if [ "$ABI" = "all" ]; then build_abi arm64-v8a; build_abi x86_64; else build_abi "$ABI"; fi
echo "Done. Rebuild the APK (./gradlew :app:assembleDebug) to package the library."
