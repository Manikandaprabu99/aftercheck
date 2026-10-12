#!/usr/bin/env bash
set -euo pipefail

if [[ -z "${ANDROID_NDK_HOME:-}" || ! -d "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin" ]]; then
  echo "Set ANDROID_NDK_HOME to your installed Android NDK directory (Linux host)." >&2
  exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
TAG="sf_19"

git clone --depth 1 --branch "$TAG" https://github.com/official-stockfish/Stockfish.git "$WORK/Stockfish"
export PATH="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"
cd "$WORK/Stockfish/src"
make net
make -j2 build ARCH=armv8 COMP=ndk
mkdir -p "$ROOT/android/app/src/main/assets/engine"
cp stockfish "$ROOT/android/app/src/main/assets/engine/stockfish-arm64-v8a"
make clean
make net
make -j2 build ARCH=x86_64 COMP=ndk
cp stockfish "$ROOT/android/app/src/main/assets/engine/stockfish-x86_64"

echo "Stockfish $TAG engine assets written under android/app/src/main/assets/engine/."
