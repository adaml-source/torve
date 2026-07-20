Torve VLC Runtime (Windows)
============================

This directory ships the VLC native runtime that backs Torve's embedded player.
A release-ready Windows package MUST contain a complete drop here — the
`verifyWindowsPackagingPrereqs` Gradle task fails the build otherwise.

Required contents
-----------------
  libvlc.dll
  libvlccore.dll
  plugins/         (full upstream plugin tree — typically 60+ .dll files)
  COPYING.txt      (or LICENSE-VLC.txt — VLC's LGPL-2.1 license notice)

How to stage
------------
1. Install VLC media player 64-bit from https://www.videolan.org/
   (or download the .zip portable distribution).
2. Copy the contents of `C:\Program Files\VideoLAN\VLC\` into this directory:
     - libvlc.dll
     - libvlccore.dll
     - plugins/
     - COPYING.txt
3. Run `./gradlew :desktopApp:verifyWindowsPackagingPrereqs` to confirm.
   Successful run produces no output; failures explain what's missing.

Local development without a bundled drop
----------------------------------------
For day-to-day `:desktopApp:run` development you don't need to stage these
files — the locator falls back to a system VLC install at the standard
Windows path, an explicit `TORVE_VLC_PATH` env var, or the
`-Dtorve.desktop.vlc.path` JVM property. See VlcRuntimeLocator.kt for the
full discovery order.

For packaging dry-runs without a real VLC drop, set
  TORVE_PACKAGE_ALLOW_MISSING_RUNTIME=1
This downgrades the gate to a warning. Released builds MUST NOT bypass it.

License obligations
-------------------
VLC / libVLC is LGPL-2.1. When Torve bundles VLC binaries, the upstream
COPYING.txt must travel with the package. The Gradle gate enforces its
presence. vlcj is GPL-3.0; its license obligations are covered separately
by the vlcj jar shipped on the runtime classpath.
