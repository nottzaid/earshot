#!/usr/bin/env bash
# Builds roc-toolkit 0.4.0 for arm64 Android and installs libroc.so + headers into the app.
# Roc's Maven artifacts stop at 0.2.x, and the PC side (pipewire-roc) uses 0.4.0, so we build our own.
set -euo pipefail

ROC_VERSION=0.4.0
ROC_SHA256=60501dfdc8c0de385898dbd1eb8239d93ef962667ddb064ad796b887a41a1a46
API=29
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
NDK=${ANDROID_NDK_ROOT:-$SDK/ndk/30.0.16248370}
HERE=$(cd "$(dirname "$0")" && pwd)
APP=$HERE/../android/app/src/main
WORK=${WORK:-$HOME/.cache/earshot-roc}

mkdir -p "$WORK"
cd "$WORK"
if [ ! -d roc-toolkit-$ROC_VERSION ]; then
    curl -sSfL -o roc.tar.gz "https://github.com/roc-streaming/roc-toolkit/archive/refs/tags/v$ROC_VERSION.tar.gz"
    echo "$ROC_SHA256  roc.tar.gz" | sha256sum -c -
    tar xzf roc.tar.gz
fi
cd roc-toolkit-$ROC_VERSION

# Roc pins its 3rd-party builds to API 21, where libuv 1.35 misses pthread barriers.
sed -i "s/env\['ROC_ANDROID_PLATFORM'\] = '21'/env['ROC_ANDROID_PLATFORM'] = '$API'/" SConstruct

export ANDROID_HOME=$SDK ANDROID_NDK_ROOT=$NDK
export PATH="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"
export CMAKE_POLICY_VERSION_MINIMUM=3.5   # libuv 1.35's CMakeLists predates CMake 4
# OpenSSL is only for DTLS/SRTP, which Earshot doesn't use.
scons -Q -j2 --disable-soversion --disable-tools --disable-openssl --build-3rdparty=all \
    --compiler=clang --host=aarch64-linux-android$API

mkdir -p "$APP/jniLibs/arm64-v8a" "$APP/cpp/roc/include"
cp bin/aarch64-linux-android$API/libroc.so "$APP/jniLibs/arm64-v8a/"
rm -rf "$APP/cpp/roc/include/roc"
cp -r src/public_api/include/roc "$APP/cpp/roc/include/"
echo "installed libroc.so ($ROC_VERSION, API $API) into the app"
