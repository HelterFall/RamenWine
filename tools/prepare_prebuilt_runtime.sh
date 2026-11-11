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

for command in curl sha256sum unzip tar docker readelf nm python3 readlink; do
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
done < <(find "$PROOT_DIR" -type f -name loader -print)

# Use the Buildx-exported Debian ARM64 guest rootfs. CI builds this from
# runtime-rootfs.Dockerfile and caches the expensive apt layer between runs.
# The fallback keeps this preparation script usable outside GitHub Actions.
ROOTFS_EXPORT="${RUNTIME_ROOT}/rootfs-export"
if [[ ! -x "$ROOTFS/usr/bin/env" || ! -x "$ROOTFS/usr/bin/box64" ]] || \
   { [[ ! -e "$ROOTFS/lib64/ld-linux-x86-64.so.2" ]] && \
     [[ ! -e "$ROOTFS/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2" ]]; }; then
  rm -rf "$ROOTFS" "$ROOTFS_EXPORT"
  mkdir -p "$ROOTFS" "$ROOTFS_EXPORT"
  echo "Building Debian ARM64 guest rootfs from the pinned package recipe"
  docker buildx build \
    --platform linux/arm64 \
    --file "$ROOT_DIR/tools/runtime-rootfs.Dockerfile" \
    --output "type=local,dest=$ROOTFS_EXPORT" \
    "$ROOT_DIR"
  ROOTFS_ARCHIVE="$ROOTFS_EXPORT/ramenwine-arm64-rootfs.tar"
  [[ -s "$ROOTFS_ARCHIVE" ]] || fail "Docker did not export the Debian rootfs archive."
  tar --numeric-owner --xattrs --acls -xpf "$ROOTFS_ARCHIVE" -C "$ROOTFS"
  mkdir -p "$ROOTFS/dev" "$ROOTFS/proc" "$ROOTFS/sys" "$ROOTFS/run"
  rm -rf "$ROOTFS_EXPORT"
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

# The final LUG binary must use the same mmdevapi system-thread table as
# the DirectAudio driver built in the audio job. Upstream DirectAudio uses
# this exact winepulse.so import as its runtime ABI probe.
WINEPULSE_UNIX=""
while IFS= read -r candidate; do
  [[ -n "$candidate" ]] || continue
  resolved_candidate="$(readlink -f "$candidate" 2>/dev/null || true)"
  [[ -n "$resolved_candidate" && -f "$resolved_candidate" ]] || continue
  if readelf -h "$resolved_candidate" 2>/dev/null | \
      grep -q 'Advanced Micro Devices X86-64'; then
    WINEPULSE_UNIX="$resolved_candidate"
    break
  fi
done < <(find "$WINE_ROOT" \( -type f -o -type l \) -name winepulse.so -print)

[[ -n "$WINEPULSE_UNIX" ]] || \
  fail "LUG Wine is missing its x86-64 winepulse.so ABI probe."
nm -D --undefined-only "$WINEPULSE_UNIX" | grep -q 'PsCreateSystemThread' || \
  fail "LUG Wine mmdevapi ABI does not match the DirectAudio system-thread build."
WINE_ELF_RELATIVE="${WINE_ELF#"$WINE_ROOT"/}"
[[ "$WINE_ELF_RELATIVE" != "$WINE_ELF" ]] || \
  fail "Wine launcher is outside its expected distribution directory."

# wineserver owns Wine's registry hives. Do not let PRoot kill it on exit:
# shut it down normally and wait for its lock to be released before leaving PRoot.
WINE_SERVER_ELF=""
while IFS= read -r candidate; do
  [[ -n "$candidate" ]] || continue
  resolved_candidate="$(readlink -f "$candidate" 2>/dev/null || true)"
  [[ -n "$resolved_candidate" && -f "$resolved_candidate" ]] || continue
  if readelf -h "$resolved_candidate" 2>/dev/null | \
      grep -q 'Advanced Micro Devices X86-64'; then
    WINE_SERVER_ELF="$candidate"
    break
  fi
done < <(find "$WINE_ROOT" \( -type f -o -type l \) -name wineserver -print)
[[ -n "$WINE_SERVER_ELF" ]] || \
  fail "LUG Wine archive does not contain an x86-64 wineserver ELF."
WINE_SERVER_RELATIVE="${WINE_SERVER_ELF#"$WINE_ROOT"/}"
[[ "$WINE_SERVER_RELATIVE" != "$WINE_SERVER_ELF" ]] || \
  fail "wineserver is outside the Wine distribution directory."

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
ln -sfn "/opt/ramenwine-wine/${WINE_SERVER_RELATIVE}" "$ROOTFS/usr/bin/wineserver"
ln -sfn "/opt/ramenwine-wine/${WINEBOOT_RELATIVE}" \
  "$ROOTFS/usr/lib/wine/x86_64-windows/wineboot.exe"

WINE_SERVER_GUEST="$ROOTFS/opt/ramenwine-wine/${WINE_SERVER_RELATIVE}"
[[ -f "$WINE_SERVER_GUEST" ]] || fail "The copied wineserver binary is missing."
readelf -h "$WINE_SERVER_GUEST" | grep -q 'Advanced Micro Devices X86-64' || \
  fail "The selected wineserver is not an x86-64 ELF."

# All Wine commands use this wrapper. After the Windows command exits, it asks
# wineserver to shut down and waits until the server releases its prefix lock.
# This lets Wine flush its registry hives before the parent PRoot command exits.
WINE_COMMAND_WRAPPER="$ROOTFS/opt/ramenwine-tools/run-wine-command.sh"
mkdir -p "$(dirname "$WINE_COMMAND_WRAPPER")"
cat > "$WINE_COMMAND_WRAPPER" <<'WINE_WRAPPER'
#!/bin/sh
if [ "$#" -lt 1 ]; then
  echo "RamenWine: run-wine-command.sh requires a Wine program argument." >&2
  exit 64
fi

/usr/bin/box64 /usr/bin/wine "$@"
wine_status=$?

/usr/bin/box64 /usr/bin/wineserver -k
kill_status=$?
/usr/bin/box64 /usr/bin/wineserver -w
wait_status=$?

if [ "$kill_status" -ne 0 ]; then
  echo "RamenWine: wineserver -k returned $kill_status; waited for server shutdown." >&2
fi
if [ "$wait_status" -ne 0 ]; then
  echo "RamenWine: wineserver did not release its lock (exit $wait_status)." >&2
fi
if [ "$wine_status" -ne 0 ]; then
  exit "$wine_status"
fi
exit "$wait_status"
WINE_WRAPPER
chmod 0755 "$WINE_COMMAND_WRAPPER"
test -x "$ROOTFS/bin/sh" || fail "Debian rootfs is missing /bin/sh."
test -x "$ROOTFS/usr/bin/wineserver" || fail "Guest wineserver link is not executable."
test -x "$WINE_COMMAND_WRAPPER" || fail "Wine shutdown wrapper is not executable."

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
[[ -f "$AUDIO_RELAY" && -s "$AUDIO_RELAY" ]] || fail "DirectAudio Android relay build artifact is missing or empty."
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

# The DirectAudio unixlib imports ntdll.so from the main Wine module tree.
# Fail with a precise packaging error if the pinned Wine distribution lacks it.
WINE_NTDLL_UNIX="$(find "$ROOTFS/opt/ramenwine-wine" -type f \
  -path '*/x86_64-unix/ntdll.so' -print -quit)"
[[ -n "$WINE_NTDLL_UNIX" ]] || \
  fail "The pinned Wine distribution is missing its x86-64 ntdll.so unix module."
readelf -h "$WINE_NTDLL_UNIX" | grep -q 'Advanced Micro Devices X86-64' || \
  fail "Wine ntdll.so unix module is not an x86-64 ELF."

GDI_SMOKE_SOURCE="$AUDIO_BUNDLE_DIR/rootfs/opt/ramenwine-tests/ramenwine-gdi-smoke.exe"
[[ -s "$GDI_SMOKE_SOURCE" ]] || fail "Win32 GDI smoke executable is missing from the build artifact."
mkdir -p "$ROOTFS/opt/ramenwine-tests"
install -m 0644 "$GDI_SMOKE_SOURCE" "$ROOTFS/opt/ramenwine-tests/ramenwine-gdi-smoke.exe"
python3 - "$ROOTFS/opt/ramenwine-tests/ramenwine-gdi-smoke.exe" <<'PY'
import pathlib
import struct
import sys

data = pathlib.Path(sys.argv[1]).read_bytes()
if len(data) < 0x9A or data[:2] != b"MZ":
    raise SystemExit("Win32 GDI smoke executable is not a DOS/PE image")
offset = struct.unpack_from("<I", data, 0x3C)[0]
if offset + 26 > len(data) or data[offset:offset + 4] != b"PE\0\0":
    raise SystemExit("Win32 GDI smoke executable has no PE header")
machine, = struct.unpack_from("<H", data, offset + 4)
subsystem, = struct.unpack_from("<H", data, offset + 24)
if machine != 0x8664 or subsystem != 0x20B:
    raise SystemExit("Win32 GDI smoke executable must be AMD64 PE32+")
PY

install -m 0755 "$AUDIO_RELAY" "$RUNTIME_ROOT/host/bin/directaudio-relay"

# Android 10+ blocks execve() of files extracted into the writable app filesDir
# for apps targeting API 29+. Package Android-host executables as native APK
# libraries so PackageManager extracts them into ApplicationInfo.nativeLibraryDir.
NATIVE_LIBRARY_DIR="$ROOT_DIR/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$NATIVE_LIBRARY_DIR"
install -m 0755 "$RUNTIME_ROOT/host/bin/proot" "$NATIVE_LIBRARY_DIR/libproot.so"
install -m 0755 "$RUNTIME_ROOT/host/bin/directaudio-relay" "$NATIVE_LIBRARY_DIR/libdirectaudio-relay.so"

readelf -h "$NATIVE_LIBRARY_DIR/libproot.so" | grep -q 'AArch64' || \
  fail "Packaged Android PRoot is not an AArch64 ELF."
readelf -h "$NATIVE_LIBRARY_DIR/libdirectaudio-relay.so" | grep -q 'AArch64' || \
  fail "Packaged DirectAudio relay is not an AArch64 ELF."

# PRoot normally extracts its ELF loader into PROOT_TMP_DIR. Android denies
# executing that writable filesDir/cacheDir copy on targetSdk 29+, so ship
# the ARM64 loader in nativeLibraryDir and point PRoot at that APK file.
PROOT_LOADER_BINARY="$(find "$PROOT_DIR" -type f -name loader -print -quit)"
[[ -n "$PROOT_LOADER_BINARY" && -s "$PROOT_LOADER_BINARY" ]] || \
  fail "The Android PRoot archive does not contain its ELF loader."
install -m 0755 "$PROOT_LOADER_BINARY" "$NATIVE_LIBRARY_DIR/libproot-loader.so"
readelf -h "$NATIVE_LIBRARY_DIR/libproot-loader.so" | grep -q 'AArch64' || \
  fail "Packaged PRoot ELF loader is not an AArch64 ELF."

# The bundled Debian guest is ARM64 only. PRoot's optional loader-m32 is for
# 32-bit ARM guest ELF programs and is not needed by this guest; x86 games run
# under Box64/Wine rather than as 32-bit ARM ELF binaries.

cp -a "$AUDIO_BUNDLE_DIR/host/share/ramenwine/." "$RUNTIME_ROOT/host/share/ramenwine/"

# Emit the exact package format consumed by RuntimePackageInstaller.
python3 "$ROOT_DIR/tools/package_runtime.py" \
  --root "$RUNTIME_ROOT" \
  --backend glibc-proot-box64 \
  --version "prebuilt-proot-26.10.08-box64-${BOX64_DEBIAN_VERSION}-wine-11.20-2-wayland-directaudio-52aedbf8" \
  --output "$OUTPUT_ZIP"

echo "Bundled runtime asset ready: $OUTPUT_ZIP"
