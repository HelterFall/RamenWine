#!/usr/bin/env bash
# Assemble a RamenWine runtime from prebuilt binaries and binary OS packages.
# This script does not compile PRoot, Box64 or Wine from source.
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="${ROOT_DIR}/build/prebuilt-runtime"
DOWNLOAD_DIR="${BUILD_DIR}/downloads"
PROOT_DIR="${BUILD_DIR}/proot-unpacked"
WINE_DIR="${BUILD_DIR}/wine-unpacked"
RUNTIME_ROOT="${BUILD_DIR}/glibc-proot-box64"
ROOTFS="${RUNTIME_ROOT}/rootfs"
ASSET_DIR="${ROOT_DIR}/app/src/main/assets/runtime"
OUTPUT_ZIP="${ASSET_DIR}/ramenwine-runtime.zip"

# Prebuilt Android/Bionic ARM64 PRoot built from the Termux PRoot codebase.
# This release ships a statically linked talloc dependency.
PROOT_URL="https://github.com/ahmed-alnassif/proot/releases/download/v26.10.08-a179d3e/proot-aarch64.zip"
PROOT_SHA256="d621d71fb68bf07accbada37ffc4208cf4a963eed70916ee68f848cbd9555cc3"

# Kron4ek publishes Linux x86-64 Wine, not Android ARM64 Wine.
# The new WoW64 build runs under the ARM64 Box64 guest.
WINE_URL="https://github.com/Kron4ek/Wine-Builds/releases/download/11.21/wine-11.21-amd64-wow64.tar.xz"
WINE_SHA256="33f3c5b56b1deb8f6725539ea006f27b5d781b3b4ae6a8a1f492d8af4fb9dc83"

BOX64_DEBIAN_VERSION="0.3.4+dfsg-1"

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "Required command not found: $1"
}

for command in curl sha256sum unzip tar docker readelf python3 readlink; do
  require_command "$command"
done

[[ -f "${ROOT_DIR}/tools/package_runtime.py" ]] || \
  fail "Run this script from the RamenWine repository; tools/package_runtime.py is missing."
[[ -f "${ROOT_DIR}/tools/audit_runtime_elf.py" ]] || \
  fail "Runtime ELF auditor is missing."

mkdir -p "$DOWNLOAD_DIR" "$PROOT_DIR" "$WINE_DIR" "$RUNTIME_ROOT/host/bin" \
  "$RUNTIME_ROOT/host/libexec/proot" "$ROOTFS" "$ASSET_DIR"

fetch_pinned() {
  local url="$1"
  local destination="$2"
  local expected_sha256="$3"

  if [[ ! -f "$destination" ]] || ! echo "${expected_sha256}  ${destination}" | sha256sum --check --status; then
    rm -f "$destination" "${destination}.part"
    echo "Downloading $(basename "$destination")"
    curl --fail --location --retry 3 --retry-delay 2 \
      --output "${destination}.part" "$url"
    echo "${expected_sha256}  ${destination}.part" | sha256sum --check --status || \
      fail "SHA-256 mismatch for $(basename "$destination")."
    mv "${destination}.part" "$destination"
  else
    echo "Using cached $(basename "$destination")"
  fi
}

fetch_pinned "$PROOT_URL" "$DOWNLOAD_DIR/proot-aarch64.zip" "$PROOT_SHA256"
fetch_pinned "$WINE_URL" "$DOWNLOAD_DIR/wine-11.21-amd64-wow64.tar.xz" "$WINE_SHA256"

# PRoot is an Android/Bionic AArch64 executable from a prebuilt release.
rm -rf "$PROOT_DIR"
mkdir -p "$PROOT_DIR"
unzip -q "$DOWNLOAD_DIR/proot-aarch64.zip" -d "$PROOT_DIR"
PROOT_BINARY="$(find "$PROOT_DIR" -type f -name proot -print -quit)"
[[ -n "$PROOT_BINARY" ]] || fail "proot-aarch64.zip does not contain a proot executable."
readelf -h "$PROOT_BINARY" | grep -q 'AArch64' || \
  fail "Downloaded PRoot is not an AArch64 ELF binary."
install -m 0755 "$PROOT_BINARY" "$RUNTIME_ROOT/host/bin/proot"

while IFS= read -r loader_file; do
  [[ -n "$loader_file" ]] || continue
  loader_name="$(basename "$loader_file")"
  install -m 0755 "$loader_file" "$RUNTIME_ROOT/host/libexec/proot/${loader_name}"
done < <(find "$PROOT_DIR" -type f \( -name loader -o -name loader-m32 \) -print)

# Use the Buildx-exported Debian ARM64 guest rootfs. CI builds this from
# runtime-rootfs.Dockerfile and caches the expensive apt layer between runs.
# The fallback keeps this preparation script usable outside GitHub Actions.
if [[ ! -x "$ROOTFS/usr/bin/env" || ! -x "$ROOTFS/usr/bin/box64" ]] || \
   { [[ ! -e "$ROOTFS/lib64/ld-linux-x86-64.so.2" ]] && \
     [[ ! -e "$ROOTFS/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2" ]]; }; then
  rm -rf "$ROOTFS"
  mkdir -p "$ROOTFS"
  echo "Building Debian ARM64 guest rootfs from the pinned package recipe"
  docker buildx build \
    --platform linux/arm64 \
    --file "$ROOT_DIR/tools/runtime-rootfs.Dockerfile" \
    --output "type=local,dest=$ROOTFS" \
    "$ROOT_DIR"
else
  echo "Using prepared Debian ARM64 guest rootfs"
fi

[[ -x "$ROOTFS/usr/bin/env" ]] || fail "Debian rootfs is missing /usr/bin/env."
[[ -x "$ROOTFS/usr/bin/box64" ]] || fail "Debian rootfs is missing /usr/bin/box64."
[[ -e "$ROOTFS/lib64/ld-linux-x86-64.so.2" || \
   -e "$ROOTFS/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2" ]] || \
  fail "Debian rootfs is missing the x86-64 glibc loader."
readelf -h "$ROOTFS/usr/bin/box64" | grep -q 'AArch64' || \
  fail "Debian Box64 package is not an AArch64 ELF binary."

# Extract the prebuilt Kron4ek WoW64 archive into a stable guest path.
rm -rf "$WINE_DIR"
mkdir -p "$WINE_DIR"
tar --extract --xz --file "$DOWNLOAD_DIR/wine-11.21-amd64-wow64.tar.xz" \
  --directory "$WINE_DIR"

WINE_ELF=""
while IFS= read -r candidate; do
  [[ -n "$candidate" ]] || continue
  resolved_candidate="$(readlink -f "$candidate" 2>/dev/null || true)"
  [[ -n "$resolved_candidate" && -f "$resolved_candidate" ]] || continue
  if readelf -h "$resolved_candidate" 2>/dev/null | grep -q 'Advanced Micro Devices X86-64'; then
    WINE_ELF="$candidate"
    break
  fi
done < <(find "$WINE_DIR" \( -type f -o -type l \) \( -name wine64 -o -name wine \) -print)
[[ -n "$WINE_ELF" ]] || \
  fail "Could not find an x86-64 ELF Wine launcher (expected bin/wine64)."

WINE_ROOT="$(cd "$(dirname "$WINE_ELF")/.." && pwd)"
WINE_ELF_RELATIVE="${WINE_ELF#"$WINE_ROOT"/}"
[[ "$WINE_ELF_RELATIVE" != "$WINE_ELF" ]] || \
  fail "Wine launcher is outside its expected distribution directory."

WINEBOOT_FILE="$(find "$WINE_ROOT" -type f -iname wineboot.exe -print -quit)"
[[ -n "$WINEBOOT_FILE" ]] || fail "Kron4ek archive does not contain wineboot.exe."
WINEBOOT_RELATIVE="${WINEBOOT_FILE#"$WINE_ROOT"/}"
[[ "$WINEBOOT_RELATIVE" != "$WINEBOOT_FILE" ]] || \
  fail "wineboot.exe is outside the Wine distribution directory."

mkdir -p "$ROOTFS/opt/kron4ek-wine"
cp -a "$WINE_ROOT/." "$ROOTFS/opt/kron4ek-wine/"

# The app's runtime contract expects rootfs/usr/bin/wine to resolve to
# an x86-64 ELF. Keep the upstream Wine directory layout intact.
mkdir -p "$ROOTFS/usr/bin" "$ROOTFS/usr/lib/wine/x86_64-windows"
ln -sfn "/opt/kron4ek-wine/${WINE_ELF_RELATIVE}" "$ROOTFS/usr/bin/wine"
ln -sfn "/opt/kron4ek-wine/${WINEBOOT_RELATIVE}" \
  "$ROOTFS/usr/lib/wine/x86_64-windows/wineboot.exe"

# Absolute Linux symlinks are rooted inside the guest rootfs. Do not use
# host-side readlink -f on /usr/bin/wine: it would resolve /opt against the
# GitHub runner instead of $ROOTFS.
GUEST_WINE_LAUNCHER="$ROOTFS/opt/kron4ek-wine/${WINE_ELF_RELATIVE}"
[[ -f "$GUEST_WINE_LAUNCHER" ]] || \
  fail "The copied Kron4ek Wine launcher is missing from the guest rootfs."
[[ "$(readlink "$ROOTFS/usr/bin/wine")" == "/opt/kron4ek-wine/${WINE_ELF_RELATIVE}" ]] || \
  fail "The guest Wine launcher link does not match the selected distribution file."
readelf -h "$GUEST_WINE_LAUNCHER" | \
  grep -q 'Advanced Micro Devices X86-64' || \
  fail "The selected Kron4ek Wine launcher is not x86-64."

# Verify Wineboot's AMD64 PE32+ header before making the APK payload.
# As with /usr/bin/wine above, resolve this guest-absolute symlink manually:
# host-side Python must read the real file below $ROOTFS, not /opt on the runner.
GUEST_WINEBOOT="$ROOTFS/opt/kron4ek-wine/${WINEBOOT_RELATIVE}"
[[ -f "$GUEST_WINEBOOT" ]] || \
  fail "The copied Kron4ek wineboot.exe is missing from the guest rootfs."
[[ "$(readlink "$ROOTFS/usr/lib/wine/x86_64-windows/wineboot.exe")" == "/opt/kron4ek-wine/${WINEBOOT_RELATIVE}" ]] || \
  fail "The guest wineboot.exe link does not match the selected distribution file."
python3 - "$GUEST_WINEBOOT" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
data = path.read_bytes()
if len(data) < 64 or data[:2] != b"MZ":
    raise SystemExit("wineboot.exe is not a valid DOS/PE executable")
pe_offset = int.from_bytes(data[0x3C:0x40], "little")
if pe_offset < 64 or pe_offset + 26 > len(data):
    raise SystemExit("wineboot.exe has an invalid PE header offset")
header = data[pe_offset:pe_offset + 26]
if header[:4] != b"PE\0\0":
    raise SystemExit("wineboot.exe is missing the PE signature")
if int.from_bytes(header[4:6], "little") != 0x8664:
    raise SystemExit("wineboot.exe is not AMD64")
if int.from_bytes(header[24:26], "little") != 0x20B:
    raise SystemExit("wineboot.exe is not PE32+")
print("wineboot.exe: AMD64 PE32+")
PY

# Emit the exact package format consumed by RuntimePackageInstaller.
python3 "$ROOT_DIR/tools/package_runtime.py" \
  --root "$RUNTIME_ROOT" \
  --backend glibc-proot-box64 \
  --version "prebuilt-proot-26.10.08-box64-${BOX64_DEBIAN_VERSION}-wine-11.21" \
  --output "$OUTPUT_ZIP"

echo "Bundled runtime asset ready: $OUTPUT_ZIP"
