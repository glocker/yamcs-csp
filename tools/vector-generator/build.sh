#!/usr/bin/env bash
#
# Builds pinned libcsp in both CRC variants and generates reference vectors with each of them
#
# Usage:   bash build.sh
# Needs:   git, cmake, ninja and C compiler (Ubuntu: sudo apt install build-essential cmake ninja-build git)
# Output:  vectors-<variant>.csv and vectors-<variant>.meta.toml in $WORK_DIR/out
#
# Environment overrides: WORK_DIR (default ~/vector-generator-work), OUT_DIR (default $WORK_DIR/out),
# LIBCSP_REPO (default: upstream on GitHub).

set -euo pipefail

LIBCSP_REPO="${LIBCSP_REPO:-https://github.com/libcsp/libcsp.git}"
LIBCSP_TAG="v2.1"
LIBCSP_COMMIT="48f7fb0b57f610bf65bab1aa2d1357c3b9722782"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK_DIR="${WORK_DIR:-$HOME/vector-generator-work}"
OUT_DIR="${OUT_DIR:-$WORK_DIR/out}"
SRC_DIR="$WORK_DIR/libcsp"

for tool in git cmake ninja cc; do
  if ! command -v "$tool" > /dev/null; then
    echo "missing tool: $tool" >&2
    exit 1
  fi
done

mkdir -p "$WORK_DIR" "$OUT_DIR"

echo "==> libcsp sources"
if [ ! -d "$SRC_DIR/.git" ]; then
  git clone --quiet "$LIBCSP_REPO" "$SRC_DIR"
fi
git -c advice.detachedHead=false -C "$SRC_DIR" checkout --quiet "$LIBCSP_COMMIT"

actual="$(git -C "$SRC_DIR" rev-parse HEAD)"
if [ "$actual" != "$LIBCSP_COMMIT" ]; then
  echo "libcsp is at $actual, expected $LIBCSP_COMMIT" >&2
  exit 1
fi
if [ -n "$(git -C "$SRC_DIR" status --porcelain)" ]; then
  echo "libcsp working tree has local changes: $SRC_DIR" >&2
  exit 1
fi
tag_commit="$(git -C "$SRC_DIR" rev-parse "${LIBCSP_TAG}^{commit}" 2> /dev/null || true)"
if [ "$tag_commit" != "$LIBCSP_COMMIT" ]; then
  echo "warning: tag $LIBCSP_TAG points to '${tag_commit:-nothing}', not to the pinned commit" >&2
fi
echo "libcsp $LIBCSP_TAG at $actual"

write_meta() {
  local name="$1"
  local cflags="$2"
  cat > "$OUT_DIR/vectors-$name.meta.toml" << EOF
name = "vectors-$name"
file = "vectors-$name.csv"
libcsp_repo = "https://github.com/libcsp/libcsp.git"
libcsp_tag = "$LIBCSP_TAG"
libcsp_commit = "$LIBCSP_COMMIT"
build = "cmake-static"
cmake_c_flags = "$cflags"
crc_mode = "$name"
command = "tools/vector-generator/build.sh"
protocol = "CSP 2.x frame as sent by the libcsp UDP interface: 6 byte header, payload, optional 4 byte CRC32 trailer"
meaning = "Frames built by libcsp (csp_crc32_append, then csp_id_prepend). crc_mode says what CRC covers: data (payload only, default libcsp build) or header_data (6 byte header followed by payload, libcsp built with CSP_21). Frames without CRC32 flag are identical in both files. The expected column is verdict of csp_crc32_verify, or too_short for a frame below header size."
EOF
}

build_variant() {
  local name="$1"
  local cflags="$2"
  local build_dir="$WORK_DIR/build-$name"
  local generator="$WORK_DIR/vector_generator-$name"
  local output="$OUT_DIR/vectors-$name.csv"

  echo "==> libcsp build: $name (CMAKE_C_FLAGS='$cflags')"
  cmake -G Ninja -S "$SRC_DIR" -B "$build_dir" -DBUILD_SHARED_LIBS=OFF "-DCMAKE_C_FLAGS=$cflags"
  cmake --build "$build_dir"

  echo "==> generator build: $name"
  cc -std=gnu11 -Wall -Wextra -Wpedantic \
    -I"$SRC_DIR/include" -I"$SRC_DIR/src" -I"$build_dir/include" \
    "$HERE/vector_generator.c" "$build_dir/libcsp.a" -lpthread -lrt -lutil \
    -o "$generator"

  echo "==> generate: $output"
  "$generator" > "$output"
  if ! grep -q "^# crc_mode=${name}\$" "$output"; then
    echo "$output: CRC mode detected by generator does not match variant '$name'" >&2
    exit 1
  fi
  write_meta "$name" "$cflags"
}

build_variant data ""
build_variant header_data "-DCSP_21=1"

echo "==> done"
ls -l "$OUT_DIR"
