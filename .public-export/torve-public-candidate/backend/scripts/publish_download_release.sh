#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DOWNLOADS_ROOT="/opt/torve/downloads"
BASE_URL="https://torve.app/downloads"
PUBLIC_DOWNLOAD_URL="${TORVE_PUBLIC_DOWNLOAD_URL:-https://torve.app/download.html}"
VERSION=""
CHANNEL="stable"
RELEASE_NOTES_SUMMARY=""
WINDOWS_MSI=""
ANDROID_TV_APK=""
ANDROID_MOBILE_APK=""

usage() {
  cat <<'EOF'
Usage:
  publish_download_release.sh --version 1.0.0 --channel stable \
    --windows-msi /path/to/Torve-1.0.0.msi \
    --android-tv-apk /path/to/androidApp-amazon-tv-release.apk \
    [--android-mobile-apk /path/to/androidApp-mobile-release.apk]

Options:
  --version VERSION              Release version, for example 1.0.0
  --channel CHANNEL              Release channel, default: stable
  --windows-msi PATH             Windows MSI artifact to publish
  --android-tv-apk PATH          Android TV / Fire TV sideload APK artifact to publish
  --android-mobile-apk PATH      Optional direct Android mobile APK artifact
  --downloads-root PATH          Static downloads root, default: /opt/torve/downloads
  --base-url URL                 Public downloads base URL, default: https://torve.app/downloads
  --public-download-url URL      Public download page, default: TORVE_PUBLIC_DOWNLOAD_URL or https://torve.app/download.html
  --release-notes-summary TEXT   Optional short release notes summary for Discord
  -h, --help                     Show this help
EOF
}

die() {
  printf 'Error: %s\n' "$*" >&2
  exit 1
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --version)
      VERSION="${2:-}"
      shift 2
      ;;
    --channel)
      CHANNEL="${2:-}"
      shift 2
      ;;
    --windows-msi)
      WINDOWS_MSI="${2:-}"
      shift 2
      ;;
    --android-tv-apk)
      ANDROID_TV_APK="${2:-}"
      shift 2
      ;;
    --android-mobile-apk)
      ANDROID_MOBILE_APK="${2:-}"
      shift 2
      ;;
    --downloads-root)
      DOWNLOADS_ROOT="${2:-}"
      shift 2
      ;;
    --base-url)
      BASE_URL="${2:-}"
      shift 2
      ;;
    --public-download-url)
      PUBLIC_DOWNLOAD_URL="${2:-}"
      shift 2
      ;;
    --release-notes-summary)
      RELEASE_NOTES_SUMMARY="${2:-}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      die "unknown option: $1"
      ;;
  esac
done

[ -n "$VERSION" ] || die "--version is required"
[ -n "$CHANNEL" ] || die "--channel is required"
[ -n "$WINDOWS_MSI" ] || die "--windows-msi is required"
[ -n "$ANDROID_TV_APK" ] || die "--android-tv-apk is required"

[[ "$VERSION" =~ ^[0-9]+(\.[0-9]+){1,3}([._-][A-Za-z0-9]+)?$ ]] || die "invalid version: $VERSION"
[[ "$CHANNEL" =~ ^[A-Za-z0-9._-]+$ ]] || die "invalid channel: $CHANNEL"
[[ "$BASE_URL" =~ ^https:// ]] || die "--base-url must be an https URL"
[[ "$PUBLIC_DOWNLOAD_URL" =~ ^https:// ]] || die "--public-download-url must be an https URL"

command -v sha256sum >/dev/null || die "sha256sum is required"
command -v python3 >/dev/null || die "python3 is required"
command -v stat >/dev/null || die "stat is required"
command -v curl >/dev/null || die "curl is required"

[ -f "$WINDOWS_MSI" ] || die "Windows MSI not found: $WINDOWS_MSI"
[ -f "$ANDROID_TV_APK" ] || die "Android TV APK not found: $ANDROID_TV_APK"
[ "${WINDOWS_MSI##*.}" = "msi" ] || die "Windows artifact must end in .msi"
[ "${ANDROID_TV_APK##*.}" = "apk" ] || die "Android TV artifact must end in .apk"
if [ -n "$ANDROID_MOBILE_APK" ]; then
  [ -f "$ANDROID_MOBILE_APK" ] || die "Android mobile APK not found: $ANDROID_MOBILE_APK"
  [ "${ANDROID_MOBILE_APK##*.}" = "apk" ] || die "Android mobile artifact must end in .apk"
fi

WINDOWS_DIR="$DOWNLOADS_ROOT/windows"
ANDROID_DIR="$DOWNLOADS_ROOT/android"
MANIFEST="$DOWNLOADS_ROOT/releases.json"

install -d -m 0755 "$WINDOWS_DIR" "$ANDROID_DIR"

if [ -f "$MANIFEST" ]; then
  python3 -m json.tool "$MANIFEST" >/dev/null || die "existing manifest is not valid JSON: $MANIFEST"
fi

copy_artifact() {
  local source="$1"
  local destination="$2"
  local filename
  filename="$(basename "$destination")"

  cp "$source" "$destination"
  chmod 0644 "$destination"

  local hash
  hash="$(sha256sum "$destination" | awk '{print $1}')"
  printf '%s  %s\n' "$hash" "$filename" > "$destination.sha256"
  chmod 0644 "$destination.sha256"
}

size_bytes() {
  stat -c '%s' "$1"
}

sha_value() {
  sha256sum "$1" | awk '{print $1}'
}

validate_public_url() {
  local url="$1"
  curl -fsSI --max-time 8 --retry 1 --retry-delay 1 "$url" >/dev/null \
    || die "published URL is not publicly downloadable: $url"
}

release_build_type() {
  local channel_lc
  channel_lc="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')"
  case "$channel_lc" in
    stable|release|public|prod|production)
      printf 'public'
      ;;
    *beta*)
      printf 'beta'
      ;;
    *)
      printf '%s' "$1"
      ;;
  esac
}

notify_discord_release() {
  local platform="$1"
  local filename="$2"
  local sha256="$3"
  local bytes="$4"
  local python_bin="python3"
  local build_type
  build_type="$(release_build_type "$CHANNEL")"
  if [ -x "$REPO_ROOT/venv/bin/python" ]; then
    python_bin="$REPO_ROOT/venv/bin/python"
  fi

  local notes_args=()
  if [ -n "$RELEASE_NOTES_SUMMARY" ]; then
    notes_args=(--release-notes-summary "$RELEASE_NOTES_SUMMARY")
  fi

  (
    cd "$REPO_ROOT"
    "$python_bin" -m app.discord_release_notifier \
      --platform "$platform" \
      --version "$VERSION" \
      --build-type "$build_type" \
      --download-page-url "$PUBLIC_DOWNLOAD_URL" \
      --file-size-bytes "$bytes" \
      --checksum-sha256 "$sha256" \
      --artifact-filename "$filename" \
      "${notes_args[@]}"
  ) || printf 'Warning: Discord release notification skipped\n' >&2
}

WINDOWS_FILE="torve-windows-$VERSION.msi"
ANDROID_TV_FILE="torve-android-tv-$VERSION.apk"
ANDROID_MOBILE_FILE="torve-android-mobile-$VERSION.apk"

WINDOWS_TARGET="$WINDOWS_DIR/$WINDOWS_FILE"
ANDROID_TV_TARGET="$ANDROID_DIR/$ANDROID_TV_FILE"
ANDROID_MOBILE_TARGET="$ANDROID_DIR/$ANDROID_MOBILE_FILE"

[ ! -e "$WINDOWS_TARGET" ] || die "refusing to overwrite existing file: $WINDOWS_TARGET"
[ ! -e "$WINDOWS_TARGET.sha256" ] || die "refusing to overwrite existing checksum: $WINDOWS_TARGET.sha256"
[ ! -e "$ANDROID_TV_TARGET" ] || die "refusing to overwrite existing file: $ANDROID_TV_TARGET"
[ ! -e "$ANDROID_TV_TARGET.sha256" ] || die "refusing to overwrite existing checksum: $ANDROID_TV_TARGET.sha256"
if [ -n "$ANDROID_MOBILE_APK" ]; then
  [ ! -e "$ANDROID_MOBILE_TARGET" ] || die "refusing to overwrite existing file: $ANDROID_MOBILE_TARGET"
  [ ! -e "$ANDROID_MOBILE_TARGET.sha256" ] || die "refusing to overwrite existing checksum: $ANDROID_MOBILE_TARGET.sha256"
fi

copy_artifact "$WINDOWS_MSI" "$WINDOWS_TARGET"
copy_artifact "$ANDROID_TV_APK" "$ANDROID_TV_TARGET"
if [ -n "$ANDROID_MOBILE_APK" ]; then
  copy_artifact "$ANDROID_MOBILE_APK" "$ANDROID_MOBILE_TARGET"
fi

WINDOWS_SHA="$(sha_value "$WINDOWS_TARGET")"
ANDROID_TV_SHA="$(sha_value "$ANDROID_TV_TARGET")"
WINDOWS_SIZE="$(size_bytes "$WINDOWS_TARGET")"
ANDROID_TV_SIZE="$(size_bytes "$ANDROID_TV_TARGET")"

if [ -n "$ANDROID_MOBILE_APK" ]; then
  ANDROID_MOBILE_SHA="$(sha_value "$ANDROID_MOBILE_TARGET")"
  ANDROID_MOBILE_SIZE="$(size_bytes "$ANDROID_MOBILE_TARGET")"
  ANDROID_MOBILE_STATUS="optional"
else
  ANDROID_MOBILE_SHA=""
  ANDROID_MOBILE_SIZE="0"
  ANDROID_MOBILE_STATUS="unavailable"
fi

UPDATED_AT="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
TMP_MANIFEST="$(mktemp "$DOWNLOADS_ROOT/releases.json.tmp.XXXXXX")"

python3 - "$MANIFEST" "$TMP_MANIFEST" "$UPDATED_AT" "$CHANNEL" "$VERSION" "$BASE_URL" \
  "$WINDOWS_FILE" "$WINDOWS_SHA" "$WINDOWS_SIZE" \
  "$ANDROID_TV_FILE" "$ANDROID_TV_SHA" "$ANDROID_TV_SIZE" \
  "$ANDROID_MOBILE_FILE" "$ANDROID_MOBILE_SHA" "$ANDROID_MOBILE_SIZE" "$ANDROID_MOBILE_STATUS" <<'PY'
import json
import os
import sys

(
    manifest_path,
    tmp_path,
    updated_at,
    channel,
    version,
    base_url,
    windows_file,
    windows_sha,
    windows_size,
    android_tv_file,
    android_tv_sha,
    android_tv_size,
    android_mobile_file,
    android_mobile_sha,
    android_mobile_size,
    android_mobile_status,
) = sys.argv[1:]

if os.path.exists(manifest_path):
    with open(manifest_path, "r", encoding="utf-8") as handle:
        manifest = json.load(handle)
else:
    manifest = {}

manifest["updated_at"] = updated_at
channels = manifest.setdefault("channels", {})
release = channels.setdefault(channel, {})

def entry(platform_path, filename, sha256, size_bytes, status):
    return {
        "version": version,
        "file": filename,
        "url": f"{base_url}/{platform_path}/{filename}",
        "sha256": sha256 or None,
        "sha256_url": f"{base_url}/{platform_path}/{filename}.sha256",
        "size_bytes": int(size_bytes),
        "status": status,
    }

release["windows"] = entry("windows", windows_file, windows_sha, windows_size, "available")
release["android_tv"] = entry("android", android_tv_file, android_tv_sha, android_tv_size, "available")
release["android_mobile"] = entry(
    "android",
    android_mobile_file,
    android_mobile_sha,
    android_mobile_size,
    android_mobile_status,
)

with open(tmp_path, "w", encoding="utf-8") as handle:
    json.dump(manifest, handle, indent=2, sort_keys=False)
    handle.write("\n")
PY

mv "$TMP_MANIFEST" "$MANIFEST"
chmod 0644 "$MANIFEST"

validate_public_url "$PUBLIC_DOWNLOAD_URL"
validate_public_url "$BASE_URL/releases.json"
validate_public_url "$BASE_URL/windows/$WINDOWS_FILE"
validate_public_url "$BASE_URL/android/$ANDROID_TV_FILE"
if [ -n "$ANDROID_MOBILE_APK" ]; then
  validate_public_url "$BASE_URL/android/$ANDROID_MOBILE_FILE"
fi

notify_discord_release "windows" "$WINDOWS_FILE" "$WINDOWS_SHA" "$WINDOWS_SIZE"
notify_discord_release "android_tv" "$ANDROID_TV_FILE" "$ANDROID_TV_SHA" "$ANDROID_TV_SIZE"
if [ -n "$ANDROID_MOBILE_APK" ]; then
  notify_discord_release "android_mobile" "$ANDROID_MOBILE_FILE" "$ANDROID_MOBILE_SHA" "$ANDROID_MOBILE_SIZE"
fi

printf 'Published Torve %s (%s)\n' "$VERSION" "$CHANNEL"
printf '%s/windows/%s\n' "$BASE_URL" "$WINDOWS_FILE"
printf '%s/android/%s\n' "$BASE_URL" "$ANDROID_TV_FILE"
if [ -n "$ANDROID_MOBILE_APK" ]; then
  printf '%s/android/%s\n' "$BASE_URL" "$ANDROID_MOBILE_FILE"
fi
