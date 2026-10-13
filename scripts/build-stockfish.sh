#!/usr/bin/env bash
# Builds Stockfish from pinned upstream source.
#
#   scripts/build-stockfish.sh            Android executables → app/src/main/jniLibs/<abi>/lib*.so
#   scripts/build-stockfish.sh --host     Linux build + network for the JVM engine tests
#
# Android only lets apps execute binaries from their native library directory, so the engine is
# packaged as lib*.so and launched as a separate process. The NNUE network is not embedded
# (NNUE_EMBEDDING_OFF); the app downloads and verifies it once on first use.
set -euo pipefail

SF_TAG="sf_16"
SF_SHA256="a1600ebdaf4e324ba3e10cec2e0c9a810dc64c6f0db5cc955b2fd5e1eefa1cc6"
NNUE="nn-5af11540bbfe.nnue"
API_LEVEL=26

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${SF_WORK:-$ROOT/build/stockfish}"
JNI_OUT="$ROOT/app/src/main/jniLibs"
JOBS="$(nproc 2>/dev/null || echo 4)"

fetch_source() {
  mkdir -p "$WORK"
  local tarball="$WORK/$SF_TAG.tar.gz"
  if [[ ! -f "$tarball" ]] || ! echo "$SF_SHA256  $tarball" | sha256sum -c --status; then
    curl --fail --location --retry 3 -o "$tarball" \
      "https://github.com/official-stockfish/Stockfish/archive/refs/tags/$SF_TAG.tar.gz"
  fi
  echo "$SF_SHA256  $tarball" | sha256sum -c --status || { echo "Checksum mismatch for $tarball" >&2; exit 1; }
  rm -rf "$WORK/src"
  mkdir -p "$WORK/src"
  tar -xzf "$tarball" -C "$WORK/src" --strip-components=1
}

build_variant() { # <ARCH> <output> [extra make args...]
  local arch="$1" out="$2"; shift 2
  ( cd "$WORK/src/src"
    make objclean >/dev/null
    make -j"$JOBS" ARCH="$arch" EXTRACXXFLAGS="-DNNUE_EMBEDDING_OFF" "$@" all >/dev/null
    mkdir -p "$(dirname "$out")"
    cp stockfish "$out" )
}

build_android() {
  local ndk="${ANDROID_NDK_HOME:-}"
  if [[ -z "$ndk" ]]; then
    local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    ndk="$(ls -d "$sdk"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
  fi
  [[ -d "$ndk" ]] || { echo "Android NDK not found; set ANDROID_NDK_HOME" >&2; exit 1; }
  local bin="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
  export PATH="$bin:$PATH"

  build_variant armv8 "$JNI_OUT/arm64-v8a/libstockfish.so" COMP=ndk \
    CXX="aarch64-linux-android$API_LEVEL-clang++"
  build_variant armv8-dotprod "$JNI_OUT/arm64-v8a/libstockfish_dotprod.so" COMP=ndk \
    CXX="aarch64-linux-android$API_LEVEL-clang++"
  build_variant x86-64-sse41-popcnt "$JNI_OUT/x86_64/libstockfish.so" COMP=ndk \
    CXX="x86_64-linux-android$API_LEVEL-clang++"
  for f in "$JNI_OUT"/*/libstockfish*.so; do "$bin/llvm-strip" "$f"; done
  ls -l "$JNI_OUT"/*/
}

build_host() {
  build_variant x86-64-sse41-popcnt "$WORK/host/stockfish" COMP=gcc
  strip "$WORK/host/stockfish" || true
  local net="$WORK/host/$NNUE"
  if [[ ! -f "$net" ]]; then
    curl --fail --location --retry 3 -o "$net.tmp" "https://tests.stockfishchess.org/api/nn/$NNUE" ||
      curl --fail --location --retry 3 -o "$net.tmp" "https://github.com/official-stockfish/networks/raw/master/$NNUE"
    mv "$net.tmp" "$net"
  fi
  [[ "nn-$(sha256sum "$net" | cut -c1-12).nnue" == "$NNUE" ]] || { echo "Network checksum mismatch" >&2; rm -f "$net"; exit 1; }
  echo "AFTERCHECK_STOCKFISH=$WORK/host/stockfish"
  echo "AFTERCHECK_NNUE=$net"
}

fetch_source
case "${1:-}" in
  --host) build_host ;;
  "") build_android ;;
  *) echo "usage: $0 [--host]" >&2; exit 2 ;;
esac
