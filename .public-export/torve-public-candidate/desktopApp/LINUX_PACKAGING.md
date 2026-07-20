# Torve Linux Packaging

This document covers the beta Linux desktop package path for Torve.

## Target

- Ubuntu/Debian x86_64 first.
- Outputs:
  - `.deb`
  - AppImage
- End users should not need a system Java install. Compose Desktop/jpackage
  produces a runtime image inside the package.

## Current Runtime Policy

Linux beta packages do not yet bundle a playback runtime by default. Smoke hosts
must have at least one supported native playback runtime installed:

- MPV/libmpv: preferred for Linux beta validation.
- VLC/libvlc: supported fallback path.

For Ubuntu 24.04 smoke hosts, install:

```bash
sudo apt update
sudo apt install mpv libmpv2 vlc
```

Release-grade Linux packages must revisit this policy. Either bundle a known
runtime under `desktopApp/runtime/linux/{mpv,vlc}` or declare/package native
dependencies explicitly.

## Build Prerequisites

Run Linux packaging on a Linux host or Linux CI runner. Windows can compile the
desktop app but should not be used to produce `.deb` or AppImage artifacts.

Required build tools on Ubuntu:

```bash
sudo apt update
sudo apt install openjdk-21-jdk dpkg fakeroot file
```

If AppImage packaging reports additional tooling requirements, install the
missing package and update this runbook.

## Build Commands

From the repo root:

```bash
./gradlew :desktopApp:compileKotlin
./gradlew :desktopApp:test
./gradlew :desktopApp:packageDeb
./gradlew :desktopApp:packageAppImage
```

Or use the release helper:

```bash
./release/release-desktop-linux.sh
```

The helper compiles, tests, builds both package formats, and prints SHA-256
checksums for produced artifacts.

## Artifact Locations

Compose Desktop writes Linux packages under:

```text
desktopApp/build/compose/binaries/main/deb/
desktopApp/build/compose/binaries/main/app-image/
```

Release builds may use corresponding `release` output folders depending on the
Gradle task invoked.

## Package Checks

The Gradle task `verifyLinuxPackagingPrereqs` runs before Linux package tasks.
It checks:

- the current host is Linux;
- `jpackage` is available from the active JDK or `TORVE_JPACKAGE_JDK`;
- basic Debian/AppImage packaging tools are present;
- whether a bundled Linux playback runtime is staged.

Missing bundled playback runtime is currently a warning for beta packaging, not
a hard failure. Smoke machines still need MPV/libmpv or VLC installed.

## Ubuntu Smoke Checklist

`.deb`:

```bash
sha256sum torve*.deb
sudo apt install ./torve*.deb
dpkg -l | grep torve
dpkg -L torve | head
```

Launch Torve from the app menu and from a terminal if a launcher command is
available.

AppImage:

```bash
sha256sum Torve*.AppImage
chmod +x Torve*.AppImage
./Torve*.AppImage
```

Runtime smoke:

- Sign in with a test account.
- Open Settings and confirm the desktop platform is Linux.
- Confirm app data lands under `$XDG_DATA_HOME/Torve` or
  `~/.local/share/Torve`.
- Play a local MP4.
- Play an HTTP/HLS stream.
- Enter and exit fullscreen.
- Close and reopen.

Negative runtime smoke:

- On a clean VM without MPV/VLC, attempt playback.
- Confirm the missing-runtime message is clear.
- Confirm the app does not crash.

## Known Limitations

- Linux in-app installer handoff is disabled for beta. Users should use the
  release/download link.
- AppImage update support is not implemented.
- Runtime bundling is not finalized.
- Wayland/X11 playback behavior still needs separate validation.

