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
AUDIO_BUNDLE_DIR="${BUILD_DIR}/directaudio-bundle"

# Prebuilt Android/Bionic ARM64 PRoot built from the Termux PRoot codebase.
# This release ships a statically linked talloc dependency.
PROOT_URL="https://github.com/ahmed-alnassif/proot/releases/download/v26.10.08-a179d3e/proot-aarch64.zip"
PROOT_SHA256="d621d71fb68bf07accbada37ffc4208cf4a963eed70916ee68f848cbd9555cc3"

# Prebuilt x86-64 WoW64 Wine with its native Wayland driver.
# This release uses the non-staging LUG Wine/TkG Wayland preset:
# _NOLIB32=wow64 and _wayland_driver=true. It runs under ARM64 Box64.
WINE_ARCHIVE_NAME="lug-wine-tkg-experimental-wayland-git-11.20-2.tar.gz"
WINE_URL="https://github.com/starcitizen-lug/lug-wine-experimental/releases/download/11.20-2/${WINE_ARCHIVE_NAME}"
WINE_SHA256="f20505464d0b54f685ba1862f99895f357fc44b9b3367562e8926ddb924e5136"

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
fetch_pinned "$WINE_URL" "$DOWNLOAD_DIR/$WINE_ARCHIVE_NAME" "$WINE_SHA256"

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

# Extract the published LUG Wine/TkG WoW64 + Wayland distribution.
rm -rf "$WINE_DIR"
mkdir -p "$WINE_DIR"
tar --extract --gzip --file "$DOWNLOAD_DIR/$WINE_ARCHIVE_NAME" \
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

# WoW64 distributions include both i386 and x86-64 PE tools. Select by
# the actual PE machine and optional-header magic, not nondeterministic find order.
WINEBOOT_FILE="$(python3 "$ROOT_DIR/tools/select_wineboot.py" "$WINE_ROOT")" || \
  fail "Wine distribution does not contain an AMD64 PE32+ wineboot.exe."

# Wawona is the only display server in this app. Without Wine's native
# Wayland driver, an EXE may start successfully but never create a visible
# window, so fail the package build instead of shipping a silent runtime.
WINE_WAYLAND_DRIVER=""
while IFS= read -r candidate; do
  [[ -n "$candidate" ]] || continue
  resolved_candidate="$(readlink -f "$candidate" 2>/dev/null || true)"
  [[ -n "$resolved_candidate" && -f "$resolved_candidate" ]] || continue
  if readelf -h "$resolved_candidate" 2>/dev/null | grep -q 'Advanced Micro Devices X86-64'; then
    WINE_WAYLAND_DRIVER="$candidate"
    break
  fi
done < <(find "$WINE_ROOT" \( -type f -o -type l \) \( -name winewayland.so -o -name winewayland.drv.so \) -print)

[[ -n "$WINE_WAYLAND_DRIVER" ]] || \
  fail "LUG Wine archive does not contain an x86-64 Wine Wayland Unix library (winewayland.so) required by Wawona."

[[ -n "$WINEBOOT_FILE" ]] || \
  fail "LUG Wine archive does not contain an AMD64 PE32+ wineboot.exe."
WINEBOOT_RELATIVE="${WINEBOOT_FILE#"$WINE_ROOT"/}"
[[ "$WINEBOOT_RELATIVE" != "$WINEBOOT_FILE" ]] || \
  fail "wineboot.exe is outside the Wine distribution directory."

mkdir -p "$ROOTFS/opt/ramenwine-wine"
cp -a "$WINE_ROOT/." "$ROOTFS/opt/ramenwine-wine/"

# The app's runtime contract expects rootfs/usr/bin/wine to resolve to
# an x86-64 ELF. Keep the upstream Wine directory layout intact.
mkdir -p "$ROOTFS/usr/bin" "$ROOTFS/usr/lib/wine/x86_64-windows"
ln -sfn "/opt/ramenwine-wine/${WINE_ELF_RELATIVE}" "$ROOTFS/usr/bin/wine"
ln -sfn "/opt/ramenwine-wine/${WINEBOOT_RELATIVE}" \
  "$ROOTFS/usr/lib/wine/x86_64-windows/wineboot.exe"

# Absolute Linux symlinks are rooted inside the guest rootfs. Do not use
# host-side readlink -f on /usr/bin/wine: it would resolve /opt against the
# GitHub runner instead of $ROOTFS.
GUEST_WINE_LAUNCHER="$ROOTFS/opt/ramenwine-wine/${WINE_ELF_RELATIVE}"
[[ -f "$GUEST_WINE_LAUNCHER" ]] || \
  fail "The copied LUG Wine Wine launcher is missing from the guest rootfs."
[[ "$(readlink "$ROOTFS/usr/bin/wine")" == "/opt/ramenwine-wine/${WINE_ELF_RELATIVE}" ]] || \
  fail "The guest Wine launcher link does not match the selected distribution file."
readelf -h "$GUEST_WINE_LAUNCHER" | \
  grep -q 'Advanced Micro Devices X86-64' || \
  fail "The selected LUG Wine Wine launcher is not x86-64."

# Verify Wineboot's AMD64 PE32+ header before making the APK payload.
# As with /usr/bin/wine above, resolve this guest-absolute symlink manually:
# host-side Python must read the real file below $ROOTFS, not /opt on the runner.
GUEST_WINEBOOT="$ROOTFS/opt/ramenwine-wine/${WINEBOOT_RELATIVE}"
[[ -f "$GUEST_WINEBOOT" ]] || \
  fail "The copied LUG Wine wineboot.exe is missing from the guest rootfs."
[[ "$(readlink "$ROOTFS/usr/lib/wine/x86_64-windows/wineboot.exe")" == "/opt/ramenwine-wine/${WINEBOOT_RELATIVE}" ]] || \
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

# Stage the pinned DirectAudio source-build outputs. These are produced by the
# same-workflow audio job; no player download or post-install helper is needed.
AUDIO_RELAY="$AUDIO_BUNDLE_DIR/host/bin/directaudio-relay"
AUDIO_WINE_ROOT="$AUDIO_BUNDLE_DIR/rootfs/opt/ramenwine-audio/lib/wine"
[[ -x "$AUDIO_RELAY" ]] || fail "DirectAudio Android relay build artifact is missing."
for audio_file in \
  "$AUDIO_WINE_ROOT/x86_64-unix/winedirectaudio.so" \
  "$AUDIO_WINE_ROOT/x86_64-windows/winedirectaudio.drv" \
  "$AUDIO_WINE_ROOT/i386-windows/winedirectaudio.drv"; do
  [[ -s "$audio_file" ]] || fail "DirectAudio build artifact is missing: $audio_file"
done

readelf -h "$AUDIO_RELAY" | grep -q 'AArch64' || \
  fail "DirectAudio Android relay is not an AArch64 ELF."
readelf -d "$AUDIO_RELAY" | grep -q 'libaaudio.so' || \
  fail "DirectAudio Android relay does not link Android AAudio."
readelf -h "$AUDIO_WINE_ROOT/x86_64-unix/winedirectaudio.so" | \
  grep -q 'Advanced Micro Devices X86-64' || \
  fail "DirectAudio Wine Unix library is not an x86-64 ELF."
! readelf -d "$AUDIO_WINE_ROOT/x86_64-unix/winedirectaudio.so" | \
  grep -qi 'libaaudio' || \
  fail "DirectAudio Wine Unix library must use the Android relay, not Bionic AAudio."

mkdir -p "$ROOTFS/opt/ramenwine-audio/lib/wine" "$RUNTIME_ROOT/host/share/ramenwine"
cp -a "$AUDIO_WINE_ROOT/." "$ROOTFS/opt/ramenwine-audio/lib/wine/"
install -m 0755 "$AUDIO_RELAY" "$RUNTIME_ROOT/host/bin/directaudio-relay"
cp -a "$AUDIO_BUNDLE_DIR/host/share/ramenwine/." "$RUNTIME_ROOT/host/share/ramenwine/"

# Emit the exact package format consumed by RuntimePackageInstaller.
python3 "$ROOT_DIR/tools/package_runtime.py" \
  --root "$RUNTIME_ROOT" \
  --backend glibc-proot-box64 \
  --version "prebuilt-proot-26.10.08-box64-${BOX64_DEBIAN_VERSION}-wine-11.20-2-wayland-directaudio-52aedbf8" \
  --output "$OUTPUT_ZIP"

echo "Bundled runtime asset ready: $OUTPUT_ZIP"
