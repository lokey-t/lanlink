#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
ROOT=$(pwd)
BUILD="$ROOT/.tools/ffmpeg-macos-build"
DEST="$ROOT/.tools/video-macos-dist"
mkdir -p "$BUILD"
if [ ! -f "$BUILD/source.tar.xz" ]; then
  curl -fL --retry 2 https://ffmpeg.org/releases/ffmpeg-8.0.3.tar.xz -o "$BUILD/source.tar.xz"
fi
printf '%s  %s\n' 6136812ea6d4e68bdba27e33c2a94382711cdf4f8602ffef056ff792bd6f9818 "$BUILD/source.tar.xz" | shasum -a 256 -c -
SIGNATURE=$(shasum -a 256 "$ROOT/scripts/build-video-macos.sh" | cut -d ' ' -f 1)
if [ ! -x "$DEST/bin/ffmpeg" ] || [ "$(cat "$DEST/.build-signature" 2>/dev/null || true)" != "$SIGNATURE" ]; then
  tar -xf "$BUILD/source.tar.xz" -C "$BUILD"
  cd "$BUILD"
  ./ffmpeg-8.0.3/configure --prefix="$DEST" --disable-everything --disable-autodetect --disable-doc --disable-debug --disable-ffplay --disable-ffprobe --disable-network --disable-gpl --disable-nonfree --enable-videotoolbox --enable-decoder=h264,mjpeg,wrapped_avframe,rawvideo --enable-encoder=mjpeg,h264_videotoolbox --enable-parser=h264,mjpeg --enable-demuxer=h264,image2pipe --enable-indev=lavfi --enable-muxer=h264,image2pipe,null --enable-filter=scale,format,color,null,testsrc2 --enable-protocol=file,pipe --enable-bsf=h264_metadata > configure.log 2>&1
  make -j6 > build.log 2>&1
  make install > install.log 2>&1
  printf '%s\n' "$SIGNATURE" > "$DEST/.build-signature"
fi
mkdir -p "$DEST/notices"
cp "$BUILD/ffmpeg-8.0.3/COPYING.LGPLv2.1" "$DEST/LICENSE.txt"
cp "$BUILD/source.tar.xz" "$DEST/notices/ffmpeg-8.0.3.tar.xz"
cp "$ROOT/scripts/build-video-macos.sh" "$DEST/notices/"
"$DEST/bin/ffmpeg" -version > "$DEST/notices/build-configuration.txt" 2>&1
echo "Built macOS video runtime: $DEST"
