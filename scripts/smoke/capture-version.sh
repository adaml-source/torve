#!/usr/bin/env bash
# Print the installed Torve package version on a connected device.
# One line per device: "<serial> <kind> versionName=… versionCode=…"
#
# Usage:
#   scripts/smoke/capture-version.sh [--device <serial>]
#
# Without --device, dumps every connected adb device.

set -euo pipefail
LIB="$(dirname "$0")/_lib.sh"
# shellcheck source=_lib.sh
. "$LIB"

DEVICE=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --device) DEVICE="$2"; shift 2 ;;
    --help|-h)
      sed -n '2,9p' "$0"; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 1 ;;
  esac
done

require_adb

if [[ -n "$DEVICE" ]]; then
  serials="$DEVICE"
else
  serials=$(list_devices)
fi

if [[ -z "$serials" ]]; then
  echo "SKIP: no adb devices connected" >&2
  exit 2
fi

while IFS= read -r serial; do
  [[ -z "$serial" ]] && continue
  kind=$(device_kind "$serial")
  ver=$(package_version "$serial")
  echo "$serial $kind $ver"
done <<< "$serials"
