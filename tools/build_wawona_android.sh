#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PINNED_REVISION="dd745564ffe4e28628c9cf2525c620bcfd4ebed5"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/ramenwine-wawona.XXXXXX")"

cleanup() {
  rm -rf "$WORK_DIR"
}
trap cleanup EXIT

command -v git >/dev/null || { echo "git is required" >&2; exit 2; }
command -v nix >/dev/null || { echo "Nix is required to build the pinned Wawona Android target" >&2; exit 2; }
command -v python3 >/dev/null || { echo "python3 is required" >&2; exit 2; }

git clone --filter=blob:none --no-checkout https://github.com/Wawona/Wawona.git "$WORK_DIR/source"
git -C "$WORK_DIR/source" fetch --depth=1 origin "$PINNED_REVISION"
git -C "$WORK_DIR/source" checkout --detach "$PINNED_REVISION"

ACTUAL_REVISION="$(git -C "$WORK_DIR/source" rev-parse HEAD)"
if [[ "$ACTUAL_REVISION" != "$PINNED_REVISION" ]]; then
  echo "Unexpected Wawona source revision: $ACTUAL_REVISION" >&2
  exit 2
fi

echo "Building Wawona Android from $ACTUAL_REVISION"
BUILD_OUTPUT="$(cd "$WORK_DIR/source" && nix build .#wawona-android --no-link --print-out-paths)"
APK_PATH="$BUILD_OUTPUT/bin/Wawona.apk"
if [[ ! -s "$APK_PATH" ]]; then
  echo "Wawona Android build did not produce $APK_PATH" >&2
  exit 2
fi

SOURCE_VERSION="$(tr -d '\r\n' < "$WORK_DIR/source/VERSION")"
python3 "$ROOT/tools/import_wawona_android.py" \
  --apk "$APK_PATH" \
  --source-revision "$ACTUAL_REVISION" \
  --source-version "$SOURCE_VERSION" \
  --app-main "$ROOT/app/src/main"

echo "Wawona arm64-v8a libraries and support assets staged into RamenWine."
