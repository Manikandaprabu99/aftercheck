#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STOCKFISH_TAG="sf_19"
TEMP_ROOT="$(mktemp -d)"
trap 'rm -rf "$TEMP_ROOT"' EXIT

if ! command -v git >/dev/null 2>&1; then
  echo "git is required to fetch the pinned Stockfish source." >&2
  exit 2
fi
if ! command -v gradle >/dev/null 2>&1; then
  echo "Gradle 8.9 or a project Gradle wrapper is required. Open android/ in Android Studio or install Gradle 8.9." >&2
  exit 2
fi

git clone --depth 1 --branch "$STOCKFISH_TAG" https://github.com/official-stockfish/Stockfish.git "$TEMP_ROOT/Stockfish"
NATIVE_SOURCE="$ROOT/android/app/src/main/cpp/stockfish"
mkdir -p "$NATIVE_SOURCE"
rm -rf "$NATIVE_SOURCE"/*
cp -a "$TEMP_ROOT/Stockfish/src/." "$NATIVE_SOURCE/"

cd "$ROOT/android"
gradle testDebugUnitTest assembleDebug
echo "Debug APK: $ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
