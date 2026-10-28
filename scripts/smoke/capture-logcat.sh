#!/usr/bin/env bash
# Dump current logcat filtered to Torve-relevant tags. Captures the
# crash buffer too so a recent FATAL EXCEPTION shows up even if the
# main buffer rolled.
#
# Usage:
#   scripts/smoke/capture-logcat.sh [--device <serial>]
#                                  [--out <file>]
#                                  [--tags <comma-list>]
#
# Default tags include the noisy-but-relevant Torve subsystems. Pass
# --tags to override. Output path defaults to
# smoke-results/logs/<serial>-<timestamp>.log.
#
# Uses `adb logcat -d` (one-shot, no follow). Run while a failure is
# fresh; nothing here clears the buffer.

set -euo pipefail
LIB="$(dirname "$0")/_lib.sh"
# shellcheck source=_lib.sh
. "$LIB"

DEVICE=""
OUT=""
TAGS="TvNavDebug,TvFocusRestore,TvSettingsFocus,TvFocusWatchdog,TvMainActivity,TvPairingSignIn,PairingApi,SyncCoordinator,TransferReceiver,TransferSender,LanLibrary,Player,CellularGuard,AndroidRuntime,System.err"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --device) DEVICE="$2"; shift 2 ;;
    --out)    OUT="$2";    shift 2 ;;
    --tags)   TAGS="$2";   shift 2 ;;
    --help|-h)
      sed -n '2,16p' "$0"; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 1 ;;
  esac
done

require_adb
SERIAL=$(pick_device "$DEVICE" any)
if [[ -z "$SERIAL" ]]; then
  echo "SKIP: no adb device connected" >&2
  exit 2
fi

ROOT="$(project_root)"
TS=$(date +%Y%m%d-%H%M%S)
if [[ -z "$OUT" ]]; then
  OUT="$ROOT/smoke-results/logs/${SERIAL//[:.]/_}-$TS.log"
fi
mkdir -p "$(dirname "$OUT")"

# Build the -s tag-filter list: each tag mapped to verbose level V.
# Plus *:S to silence everything else.
FILTER=""
IFS=',' read -ra TAG_ARR <<< "$TAGS"
for t in "${TAG_ARR[@]}"; do
  FILTER+=" -s $t:V"
done
FILTER+=" *:S"

{
  echo "# Smoke logcat capture"
  echo "# device:    $SERIAL"
  echo "# timestamp: $TS"
  echo "# tags:      $TAGS"
  echo "# version:   $(package_version "$SERIAL")"
  echo "# --- main buffer ---"
  # shellcheck disable=SC2086
  adb -s "$SERIAL" logcat -d -v time $FILTER || true
  echo
  echo "# --- crash buffer ---"
  adb -s "$SERIAL" logcat -d -v time -b crash || true
} > "$OUT"

echo "$OUT"
