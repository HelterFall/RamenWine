#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
  cat <<'EOF'
Build the Android ARM64 PRoot host executable from checksum-pinned sources.

Usage:
  tools/build_proot_android.sh [--sources DIR] [--output DIR]

The source directory must already be populated by fetch_runtime_sources.py.
This builds PRoot only. It does not build Box64, Wine, or a Linux rootfs.
EOF
}

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCES_DIR="${ROOT_DIR/build/runtime-sources"
OUTPUT_DIR="${ROOT_DIR/build/proot-host"
while (($#)); do
  case "$1" in
    --sources)
      SOURCES_DIR="${{2:?missing value for --sources}"
      shift 2
      ;;
    --output)
      OUTPUT_DIR="${{2:?missing value for --output}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

SOURCES_DIR="$(mkdir -p "$SOURCES_DIR" && cd "$SOURCES_DIR" && pwd)"
OUTPUT_DIR="$(mkdir -p "$(dirname "$OUTPUT_DIR")" && cd "$(dirname "$OUTPUT_DIR")" && pwd)/$(basename "$OUTPUT_DIR")"

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}"
if [[ -z "$NDK" ]]; then
  echo "ANDROID_NDK_HOME or ANDROID_NDK_ROOT must point to Android NDK 30.0.16248370." >&2
  exit 2
fi
if [[ ! -d "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin" ]]; then
  echo "Android NDK Linux toolchain was not found under: $NDK" >&2
  exit 2
fi

TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
TARGET="aarch64-linux-android"
API="${ANDROID_API_LEVEL:-26}"
CC="$TOOLCHAIN/${TARGET${API-clang"
AR="$TOOLCHAIN/llvm-ar"
RANLIB="$TOOLCHAIN/llvm-ranlib"
STRIP="$TOOLCHAIN/llvm-strip"
OBJCOPY="$TOOLCHAIN/llvm-objcopy"
OBJDUMP="$TOOLCHAIN/llvm-objdump"
READELF="$TOOLCHAIN/llvm-readelf"
JOBS="${BUILD_JOBS:-2}"

for tool in "$CC" "$AR" "$RANLIB" "$STRIP" "$OBJCOPY" "$OBJDUMP" "$READELF"; do
  [[ -x "$tool" ]] || { echo "Required NDK tool is missing: $tool" >&2; exit 2; }
done

PROOT_SRC="$SOURCES_DIR/sources/termux-proot"
TALLOC_ARCHIVE="$SOURCES_DIR/archives/talloc-2.5.0.tar.gz"
SHMEM_ARCHIVE="$SOURCES_DIR/archives/libandroid-shmem-0.7.tar.gz"
PROVENANCE="$SOURCES_DIR/fetched-sources.json"
for required in "$PROOT_SRC/src/GNUmakefile" "$TALLOC_ARCHIVE" "$SHMEM_ARCHIVE" "$PROVENANCE"; do
  [[ -e "$required" ]] || { echo "Pinned input is missing: $required. Run fetch_runtime_sources.py first." >&2; exit 2; }
done

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT
DEPS_PREFIX="$WORK_DIR/deps"
STAGE_DIR="$WORK_DIR/stage"
mkdir -p "$DEPS_PREFIX/include" "$DEPS_PREFIX/lib" "$STAGE_DIR/host/bin" "$STAGE_DIR/host/libexec/proot"

tar -xzf "$TALLOC_ARCHIVE" -C "$WORK_DIR"
TALLOC_SRC="$(find "$WORK_DIR" -mindepth 1 -maxdepth 2 -type f -name configure -print -quit | xargs -r dirname)"
[[ -n "$TALLOC_SRC" && -f "$TALLOC_SRC/configure" ]] || {
  echo "Could not locate the extracted talloc configure script." >&2
  exit 2
}

cat > "$WORK_DIR/talloc-cross-answers.txt" <<'EOF'
Checking uname sysname type: "Linux"
Checking uname machine type: "dontcare"
Checking uname release type: "dontcare"
Checking uname version type: "dontcare"
Checking simple C program: OK
building library support: OK
Checking for large file support: OK
Checking for -D_FILE_OFFSET_BITS=64: OK
Checking for WORDS_BIGENDIAN: OK
Checking for C99 vsnprintf: OK
Checking for HAVE_SECURE_MKSTEMP: OK
rpath library support: OK
-Wl,--version-script support: FAIL
Checking correct behavior of strtoll: OK
Checking correct behavior of strptime: OK
Checking for HAVE_IFACE_GETIFADDRS: OK
Checking for HAVE_IFACE_IFCONF: OK
Checking for HAVE_IFACE_IFREQ: OK
Checking getconf LFS_CFLAGS: OK
Checking for large file support without additional flags: OK
Checking for working strptime: OK
Checking for HAVE_SHARED_MMAP: OK
Checking for HAVE_MREMAP: OK
Checking for HAVE_INCOHERENT_MMAP: OK
Checking getconf large file support flags work: OK
EOF

echo "[1/3] Building static libtalloc for Android ARM64"
(
  cd "$TALLOC_SRC"
  CC="$CC" AR="$AR" RANLIB="$RANLIB" CFLAGS="-O2 -fPIC" \
    ./configure \
      --prefix="$DEPS_PREFIX" \
      --disable-rpath \
      --disable-python \
      --cross-compile \
      --cross-answers="$WORK_DIR/talloc-cross-answers.txt"
  make -j"$JOBS" CC="$CC" AR="$AR" RANLIB="$RANLIB"
  make install
  mkdir -p "$DEPS_PREFIX/lib"
  "$AR" rcs "$DEPS_PREFIX/lib/libtalloc.a" bin/default/talloc*.o
  rm -f "$DEPS_PREFIX"/lib/libtalloc.so "$DEPS_PREFIX"/lib/libtalloc.so.*
)
[[ -f "$DEPS_PREFIX/include/talloc.h" && -s "$DEPS_PREFIX/lib/libtalloc.a" ]] || {
  echo "Static libtalloc output is incomplete." >&2
  exit 1
}

echo "[2/3] Building static libandroid-shmem for Android ARM64"
tar -xzf "$SHMEM_ARCHIVE" -C "$WORK_DIR"
SHMEM_C="$(find "$WORK_DIR" -mindepth 2 -type f -name shmem.c -print -quit)"
[[ -n "$SHMEM_C" ]] || { echo "Could not locate libandroid-shmem/shmem.c." >&2; exit 1; }
SHMEM_SRC="$(dirname "$SHMEM_C")"
"$CC" -O2 -fPIC -std=c11 -Wall -Wextra -I"$SHMEM_SRC" \
  -c "$SHMEM_C" -o "$WORK_DIR/shmem.o"
"$AR" rcs "$DEPS_PREFIX/lib/libandroid-shmem.a" "$WORK_DIR/shmem.o"
mkdir -p "$DEPS_PREFIX/include/sys"
install -m 0644 "$SHMEM_SRC/shm.h" "$DEPS_PREFIX/include/sys/shm.h"
[[ -s "$DEPS_PREFIX/lib/libandroid-shmem.a" && -f "$DEPS_PREFIX/include/sys/shm.h" ]] || {
  echo "Static libandroid-shmem output is incomplete." >&2
  exit 1
}

echo "[3/3] Building the Android ARM64 PRoot executable"
export CC
export AR
export RANLIB
export CFLAGS="-O2 -fPIE -DARG_MAX=131072 -DVERSION=\\\"RamenWine-PRoot-Android-${API${\\\""
export CPPFLAGS="-I$DEPS_PREFIX/include"
export LDFLAGS="-pie -L$DEPS_PREFIX/lib -Wl,-Bstatic -landroid-shmem -Wl,-Bdynamic -llog -landroid"
export PROOT_UNBUNDLE_LOADER="$STAGE_DIR/host/libexec/proot"
export PROOT_WITH_LIBANDROID_SHMEM=true

make -C "$PROOT_SRC/src" clean
make -C "$PROOT_SRC/src" -j"$JOBS" \
  CC="$CC" LD="$CC" STRIP="$STRIP" OBJCOPY="$OBJCOPY" OBJDUMP="$OBJDUMP" \
  PROOT_UNBUNDLE_LOADER="$PROOT_UNBUNDLE_LOADER" \
  PROOT_WITH_LIBANDROID_SHMEM=true V=1

install -m 0755 "$PROOT_SRC/src/proot" "$STAGE_DIR/host/bin/proot"
install -m 0755 "$PROOT_SRC/src/loader/loader" "$STAGE_DIR/host/libexec/proot/loader"
"$STRIP" --strip-unneeded "$STAGE_DIR/host/bin/proot"

"$READELF" -h "$STAGE_DIR/host/bin/proot" > "$WORK_DIR/proot-elf-header.txt"
grep -q 'AArch64' "$WORK_DIR/proot-elf-header.txt" || {
  cat "$WORK_DIR/proot-elf-header.txt" >&2
  echo "Built PRoot is not an AArch64 ELF binary." >&2
  exit 1
}
"$READELF" -d "$STAGE_DIR/host/bin/proot" > "$WORK_DIR/proot-dynamic.txt"
if grep -Eq '(libtalloc|libandroid-shmem)\\.so' "$WORK_DIR/proot-dynamic.txt"; then
  cat "$WORK_DIR/proot-dynamic.txt" >&2
  echo "A PRoot build dependency unexpectedly remains dynamically linked." >&2
  exit 1
fi

mkdir -p "$OUTPUT_DIR"
rm -rf "$OUTPUT_DIR/host"
cp -a "$STAGE_DIR/host" "$OUTPUT_DIR/host"
cp "$PROVENANCE" "$OUTPUT_DIR/fetched-sources.json"
cp "$WORK_DIR/proot-elf-header.txt" "$OUTPUT_DIR/proot-elf-header.txt"
cp "$WORK_DIR/proot-dynamic.txt" "$OUTPUT_DIR/proot-dynamic.txt"
sha256sum "$OUTPUT_DIR/host/bin/proot" "$OUTPUT_DIR/host/libexec/proot/loader" \
  > "$OUTPUT_DIR/SHA256SUMS"

cat > "$OUTPUT_DIR/BUILD-INFO.txt" <<EOF
RamenWine Android ARM64 PRoot host stage
Android NDK: 30.0.16248370
Android API level: $API
PRoot source commit: 52d65a4dc140ca554d241a1438ddd4f6b00c9813
libtalloc: 2.5.0 (static)
libandroid-shmem: 0.7 (static)
This is a PRoot host-stage artifact, not a complete runtime package.
Box64, Wine, the glibc rootfs and the application installer package are not included.
EOF

python3 - "$OUTPUT_DIR" "$OUTPUT_DIR/proot-android-arm64-stage.zip" <<'PY'
import os
import sys
import zipfile
from pathlib import Path

root = Path(sys.argv[1])
archive = Path(sys.argv[2])
with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as output:
    for path in sorted(root.rglob("*")):
        if path.is_file() and path != archive:
            info = zipfile.ZipInfo(path.relative_to(root).as_posix(), (1980, 1, 1, 0, 0, 0))
            info.external_attr = (0o100755 if os.access(path, os.X_OK) else 0o100644) << 16
            output.writestr(info, path.read_bytes())
print(f"PRoot ARM64 host stage: {archive}")
PY

echo "PRoot Android ARM64 build completed."
echo "Artifact directory: $OUTPUT_DIR"
