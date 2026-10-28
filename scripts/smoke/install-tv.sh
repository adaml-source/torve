#!/usr/bin/env bash
# Install the TV google APK on a connected TV device or emulator.
#
# Usage:
#   scripts/smoke/install-tv.sh [--device <serial>] [--variant debug|release]
#                              [--apk <path>]
#
# Defaults: picks the first TV adb device (ro.build.characteristics
# contains "tv"); uses the debug APK so the developer Google account
# isn't required for sideloading.

set -euo pipefail
LIB="$(dirname "$0")/_lib.sh"
# shellcheck source=_lib.sh
. "$LIB"

DEVICE=""
VARIANT="debug"
APK=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --device)  DEVICE="$2";  shift 2 ;;
    --variant) VARIANT="$2"; shift 2 ;;
    --apk)     APK="$2";     shift 2 ;;
    --help|-h)
      sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 1 ;;
  esac
done

case "$VARIANT" in
  debug|release) : ;;
  *) echo "--variant must be debug|release, got '$VARIANT'" >&2; exit 1 ;;
esac

require_adb
ROOT="$(project_root)"
SERIAL=$(pick_device "$DEVICE" tv)
if [[ -z "$SERIAL" ]]; then
  echo "SKIP: no TV adb device connected" >&2
  exit 2
fi

if [[ -z "$APK" ]]; then
  APK="$ROOT/androidApp/build/outputs/apk/googleTv/$VARIANT/androidApp-google-tv-$VARIANT.apk"
fi
if [[ ! -f "$APK" ]]; then
  echo "SKIP: APK not found at $APK (run :androidApp:assembleGoogleTv${VARIANT^})" >&2
  exit 2
fi

echo "Installing $APK on $SERIAL"
if adb -s "$SERIAL" install -r "$APK"; then
  echo "OK $(package_version "$SERIAL")"
  exit 0
fi
exit 1
