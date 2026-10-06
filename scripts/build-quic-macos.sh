#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
# Requires Rust >= 1.88, Xcode command-line tools, CMake and libclang.
cargo build --manifest-path native/quic/Cargo.toml --release --locked
mkdir -p runtime
cp native/quic/target/release/liblanlink_quic.dylib runtime/
codesign --force --sign - runtime/liblanlink_quic.dylib
