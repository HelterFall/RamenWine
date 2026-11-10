#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DOWNLOAD_DIR="${ROOT_DIR}/build/prebuilt-wawona"
APK_PATH="${DOWNLOAD_DIR}/Wawona-26.8.12.apk"
APK_PART="${APK_PATH}.part"

RELEASE_TAG="v26.8.12"
SOURCE_VERSION="26.8.12"
SOURCE_REVISION="75ffe2982bd7d99b29b7e743c4f70a9becf61f86"
APK_URL="https://github.com/Wawona/Wawona/releases/download/v26.8.12/Wawona-26.8.12.apk"
APK_SHA256="f9a3f82ac9c6e47701622f9f3d9be355cb549438e34c49760c7ac9b528dc5570"

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

for command in curl sha256sum python3; do
  command -v "$command" >/dev/null 2>&1 || fail "Required command not found: $command"
done

mkdir -p "$DOWNLOAD_DIR"

if [[ ! -s "$APK_PATH" ]] || \
   ! printf '%s  %s\n' "$APK_SHA256" "$APK_PATH" | sha256sum --check --status; then
  rm -f "$APK_PATH" "$APK_PART"
  echo "Downloading official Wawona ${RELEASE_TAG} Android release asset"
  curl --fail --location --retry 3 --retry-delay 2 --connect-timeout 20 \
    --max-time 600 --output "$APK_PART" "$APK_URL"
  printf '%s  %s\n' "$APK_SHA256" "$APK_PART" | sha256sum --check --status || \
    fail "Official Wawona APK SHA-256 mismatch."
  mv "$APK_PART" "$APK_PATH"
else
  echo "Using checksum-verified cached official Wawona APK"
fi

python3 "$ROOT_DIR/tools/import_wawona_android.py" \
  --apk "$APK_PATH" \
  --source-revision "$SOURCE_REVISION" \
  --source-version "$SOURCE_VERSION" \
  --app-main "$ROOT_DIR/app/src/main"

echo "Official Wawona Android release staged; no Wawona source compilation was performed."
