Torve MPV Runtime (Windows) — DEPRECATED, NOT SHIPPED
=======================================================

Embedded MPV playback was retired in favor of embedded VLC. This directory
intentionally contains no binaries and is NOT part of any released Torve
build today.

If you re-enable MPV bundling
-----------------------------
Drop:
  libmpv-2.dll   (or mpv-2.dll)
  LICENSE-MPV.txt    (libmpv is LGPL-2.1+; ship the upstream COPYING)

The Gradle gate `verifyWindowsPackagingPrereqs` enforces this — it fails
the Windows packaging task if any MPV binary is staged here without an
accompanying license notice. This prevents an accidental release that
ships unlicensed mpv binaries.

For development, mpv discovery via TORVE_MPV_PATH / -Dtorve.desktop.mpv.path
remains supported by MpvRuntimeLocator; it just won't be present in
packaged installers.

To bypass the gate for a packaging dry-run set
  TORVE_PACKAGE_ALLOW_MISSING_RUNTIME=1
Released builds MUST NOT bypass it.
