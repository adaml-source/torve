#!/usr/bin/env bash
# Shared helpers for the smoke harness scripts. Source me, don't run.
#
# Conventions:
#  - All scripts are POSIX-compatible bash so they work in Git Bash on
#    Windows, macOS, and Linux.
#  - Every script supports --help.
#  - Output goes to stdout; errors to stderr; exit codes mean PASS=0,
#    FAIL=1, SKIP=2 so run-smoke.sh can differentiate.

set -u

PKG="com.torve.app"

# Find the project root by walking up until we hit androidApp/.
project_root() {
  local dir
  dir="$(cd "$(dirname "${BASH_SOURCE[1]:-$0}")" && pwd)"
  while [[ "$dir" != "/" && ! -d "$dir/androidApp" ]]; do
    dir="$(dirname "$dir")"
  done
  echo "$dir"
}

# Require adb on PATH; bail with SKIP if missing so a workstation
# without it doesn't fail the smoke run, it just records SKIP.
require_adb() {
  if ! command -v adb >/dev/null 2>&1; then
    echo "SKIP: adb not on PATH" >&2
    exit 2
  fi
}

# List connected adb devices (one serial per line, no header noise).
list_devices() {
  adb devices | tail -n +2 | awk '$2=="device" {print $1}'
}

# Returns "tv" or "mobile" for a serial. Uses
# `pm has-feature android.software.leanback` because the TV emulator's
# `ro.build.characteristics` returns "emulator", not "tv" — leanback is
# the canonical Android TV signal and works on real Fire TV / Android
# TV devices too.
device_kind() {
  local serial="$1"
  local has_leanback
  has_leanback=$(adb -s "$serial" shell pm has-feature android.software.leanback 2>/dev/null | tr -d '\r')
  if [[ "$has_leanback" == "true" ]]; then echo "tv"; else echo "mobile"; fi
}

# Pick a serial via --device <serial> flag or the first available one
# matching the optional kind ("mobile"|"tv"|"any").
pick_device() {
  local explicit="$1" kind="$2"
  if [[ -n "$explicit" ]]; then
    echo "$explicit"
    return 0
  fi
  local serials
  serials=$(list_devices)
  if [[ -z "$serials" ]]; then
    echo "" ; return 0
  fi
  if [[ "$kind" == "any" ]]; then
    echo "$serials" | head -n 1
    return 0
  fi
  while IFS= read -r serial; do
    [[ -z "$serial" ]] && continue
    local k
    k=$(device_kind "$serial")
    if [[ "$k" == "$kind" ]]; then echo "$serial"; return 0; fi
  done <<< "$serials"
  echo ""
}

# Print versionName / versionCode for the installed package on a device.
package_version() {
  local serial="$1"
  adb -s "$serial" shell dumpsys package "$PKG" 2>/dev/null \
    | tr -d '\r' \
    | awk '/versionName=|versionCode=/ {for (i=1;i<=NF;i++) if ($i ~ /version(Name|Code)=/) print $i}' \
    | sort -u \
    | paste -sd' ' -
}
