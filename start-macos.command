#!/bin/sh
set -eu
cd "$(dirname "$0")"
case "$(uname -m)" in arm64) ARCH=arm64;; *) ARCH=x64;; esac
VERSION=$(node -p "JSON.parse(require('fs').readFileSync('package.json', 'utf8')).version")
APP="dist/LanLink-$VERSION-macos-$ARCH/LanLink.app"
if [ ! -d "$APP" ]; then sh scripts/package-macos.sh; fi
open "$APP"
