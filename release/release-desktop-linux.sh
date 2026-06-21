#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "Linux desktop packaging must run on Linux." >&2
  exit 1
fi

echo "[release-linux] Compiling and testing Torve Desktop..."
./gradlew :desktopApp:compileKotlin :desktopApp:test

echo "[release-linux] Building .deb and AppImage packages..."
./gradlew :desktopApp:packageDeb :desktopApp:packageAppImage

deb_dir="$repo_root/desktopApp/build/compose/binaries/main/deb"
appimage_dir="$repo_root/desktopApp/build/compose/binaries/main/app-image"

mapfile -t debs < <(find "$deb_dir" -maxdepth 1 -type f -name '*.deb' 2>/dev/null | sort)
mapfile -t appimages < <(find "$appimage_dir" -maxdepth 1 -type f -name '*.AppImage' 2>/dev/null | sort)

if [[ "${#debs[@]}" -eq 0 ]]; then
  echo "No .deb artifact found under $deb_dir" >&2
  exit 1
fi

if [[ "${#appimages[@]}" -eq 0 ]]; then
  echo "No AppImage artifact found under $appimage_dir" >&2
  exit 1
fi

echo "[release-linux] Artifacts:"
for artifact in "${debs[@]}" "${appimages[@]}"; do
  bytes="$(stat -c '%s' "$artifact")"
  hash="$(sha256sum "$artifact" | awk '{print $1}')"
  echo "  path:   $artifact"
  echo "  bytes:  $bytes"
  echo "  sha256: $hash"
done

