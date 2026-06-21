#!/usr/bin/env bash
# Stage the Windows VLC runtime under desktopApp/runtime/windows/vlc/
# from a portable VLC extraction (CI helper sibling of the .ps1).
#
# Usage:
#   ./stage-windows-vlc-runtime.sh [--source <vlc-portable-dir>] [--skip-verify]
#
# Defaults:
#   --source: $TORVE_VLC_PORTABLE if set, otherwise fails with a useful
#             message. Unlike the Windows .ps1 there's no
#             "C:\Program Files\VideoLAN\VLC" default on Linux/macOS.
#
# Like the .ps1, this is for *release packaging* only. Day-to-day
# `:desktopApp:run` doesn't need it.

set -euo pipefail

SOURCE="${TORVE_VLC_PORTABLE:-}"
SKIP_VERIFY=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --source)        SOURCE="$2"; shift 2;;
        --skip-verify)   SKIP_VERIFY=1; shift;;
        -h|--help)
            sed -n '2,17p' "$0"
            exit 0;;
        *) echo "Unknown arg: $1" >&2; exit 2;;
    esac
done

if [[ -z "$SOURCE" ]]; then
    echo "ERROR: VLC portable source not provided." >&2
    echo "Set TORVE_VLC_PORTABLE or pass --source <path-to-vlc-portable-extraction>." >&2
    echo "Download a VLC portable .zip from https://www.videolan.org/ and unzip it first." >&2
    exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DESKTOP_APP_DIR="$(dirname "$SCRIPT_DIR")"
PROJECT_ROOT="$(dirname "$DESKTOP_APP_DIR")"
TARGET_DIR="$DESKTOP_APP_DIR/runtime/windows/vlc"

echo "Torve VLC runtime stager"
echo "  source : $SOURCE"
echo "  target : $TARGET_DIR"
echo

for req in libvlc.dll libvlccore.dll plugins; do
    if [[ ! -e "$SOURCE/$req" ]]; then
        echo "ERROR: required VLC item missing in source: $SOURCE/$req" >&2
        exit 2
    fi
done

LICENSE_SRC=""
for name in COPYING.txt COPYING LICENSE-VLC.txt LICENSE.txt; do
    if [[ -f "$SOURCE/$name" ]]; then
        LICENSE_SRC="$SOURCE/$name"
        break
    fi
done
if [[ -z "$LICENSE_SRC" ]]; then
    echo "ERROR: VLC license notice (COPYING.txt) not found in $SOURCE." >&2
    echo "VLC's LGPL-2.1 obligations require the upstream COPYING to travel with the bundle." >&2
    exit 2
fi

mkdir -p "$TARGET_DIR"
rm -rf "$TARGET_DIR/plugins"
cp -f "$SOURCE/libvlc.dll"     "$TARGET_DIR/libvlc.dll"
cp -f "$SOURCE/libvlccore.dll" "$TARGET_DIR/libvlccore.dll"
cp -rf "$SOURCE/plugins"        "$TARGET_DIR/"
cp -f "$LICENSE_SRC"            "$TARGET_DIR/COPYING.txt"

PLUGIN_COUNT=$(find "$TARGET_DIR/plugins" -name '*.dll' | wc -l)
echo "Staged successfully:"
echo "  plugins/*.dll : $PLUGIN_COUNT file(s)"
echo

if [[ "$SKIP_VERIFY" -eq 1 ]]; then
    echo "Skipping :desktopApp:verifyWindowsPackagingPrereqs (--skip-verify)."
    exit 0
fi

cd "$PROJECT_ROOT"
echo "Running :desktopApp:verifyWindowsPackagingPrereqs ..."
./gradlew :desktopApp:verifyWindowsPackagingPrereqs
echo "Runtime is release-ready."
