#!/usr/bin/env bash
# Install the mobile google release APK on a connected mobile device.
#
# Usage:
#   scripts/smoke/install-mobile.sh [--device <serial>] [--apk <path>]
#
# Defaults: picks the first non-TV adb device; uses the latest release
# APK from androidApp/build/outputs/apk/googleMobile/release/.
#
# Exits 0 on success, 1 on failure, 2 on skipped (no device / no APK).

set -euo pipefail
LIB="$(dirname "$0")/_lib.sh"
# shellcheck source=_lib.sh
. "$LIB"

DEVICE=""
APK=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --device) DEVICE="$2"; shift 2 ;;
    --apk)    APK="$2";    shift 2 ;;
    --help|-h)
      sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 1 ;;
  esac
done

require_adb
ROOT="$(project_root)"
SERIAL=$(pick_device "$DEVICE" mobile)
if [[ -z "$SERIAL" ]]; then
  echo "SKIP: no mobile adb device connected" >&2
  exit 2
fi

# Default APK = latest release output. The release variant filename is
# fixed by the Gradle bundle config so no globbing required.
if [[ -z "$APK" ]]; then
  APK="$ROOT/androidApp/build/outputs/apk/googleMobile/release/androidApp-google-mobile-release.apk"
fi
if [[ ! -f "$APK" ]]; then
  echo "SKIP: APK not found at $APK (run :androidApp:assembleGoogleMobileRelease)" >&2
  exit 2
fi

echo "Installing $APK on $SERIAL"
if adb -s "$SERIAL" install -r "$APK"; then
  echo "OK $(package_version "$SERIAL")"
  exit 0
fi
exit 1
