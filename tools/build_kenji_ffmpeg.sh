#!/usr/bin/env bash
# Rebuild only the Android FFmpeg libraries used by Kenji, not an APK.
# LGPL configuration, no third-party codec libraries or GPL/nonfree options.
set -euo pipefail
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to the Android NDK root}"
out=${1:?Output directory for shared libraries}
work=${2:-/tmp/symbiosis-kenji-ffmpeg}
version=5.1.8
sha=56d4daf10c17330a45c8fe11bc260997677ca2432d3d5951dbeb5515c26028cb
mkdir -p "$work" "$out"
out=$(cd "$out" && pwd)
archive="$work/ffmpeg-$version.tar.xz"
curl -fsSL --retry 3 --max-time 120 "https://ffmpeg.org/releases/ffmpeg-$version.tar.xz" -o "$archive"
echo "$sha  $archive" | sha256sum -c -
rm -rf "$work/ffmpeg-$version" "$work/install"
tar -xJf "$archive" -C "$work"
cd "$work/ffmpeg-$version"
tc="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
./configure \
  --prefix="$work/install" --target-os=android --arch=aarch64 --cpu=armv8-a \
  --enable-cross-compile --cc="$tc/aarch64-linux-android26-clang" \
  --cxx="$tc/aarch64-linux-android26-clang++" --ar="$tc/llvm-ar" \
  --ranlib="$tc/llvm-ranlib" --strip="$tc/llvm-strip" --nm="$tc/llvm-nm" \
  --disable-autodetect --enable-pic --enable-shared --disable-static \
  --disable-programs --disable-doc --disable-avdevice --disable-avformat \
  --disable-avfilter --disable-postproc --disable-network --disable-debug \
  --enable-small --extra-ldflags='-Wl,-z,max-page-size=16384' \
  --disable-everything --enable-decoder=h264 --enable-decoder=vp8
make -j"${JOBS:-4}"
make install
for name in avutil swresample avcodec swscale; do
  test -s "$work/install/lib/lib${name}.so"
  cp "$work/install/lib/lib${name}.so" "$out/"
  "$tc/llvm-readelf" -d "$out/lib${name}.so" | grep -q "SONAME.*lib${name}.so"
  sha256sum "$out/lib${name}.so"
done
printf '%s\n' 'KENJI_FFMPEG_BUILD_OK version=5.1.8 avcodec=59.37.100 decoders=h264,vp8'
