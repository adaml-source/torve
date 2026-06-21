# Torve Linux Desktop Implementation Plan

## Goal

Ship a Linux desktop build of Torve from the existing Compose Desktop/KMP codebase.
The first target is x86_64 Ubuntu/Debian, producing `.deb` and AppImage artifacts
that do not require a system Java install. The initial beta can rely on documented
system playback dependencies; a release-grade build should either bundle a known
playback runtime or enforce dependencies clearly during packaging.

## Current State

- `desktopApp` already exists and builds as a JVM/Compose Desktop app.
- `shared` already exposes a desktop JVM target.
- Gradle already declares Linux distribution formats:
  - `TargetFormat.Deb`
  - `TargetFormat.AppImage`
- `:desktopApp:compileKotlin` passes on the current Windows host.
- The Linux path is not release-ready because some desktop packaging and runtime
  code is still Windows-biased.

## Key Risks

1. `createDistributable` and `packageDistributionForCurrentOS` currently depend on
   `verifyWindowsPackagingPrereqs`. On Linux this would try to validate Windows
   prerequisites such as `jpackage.exe` and `desktopApp/runtime/windows/vlc`.
2. VLC runtime discovery checks some generic locations, but its bundled-runtime
   search is still centered on `runtime/windows/vlc`.
3. Device metadata reports the desktop platform as `windows`.
4. Linux installer update handoff is intentionally disabled, so beta releases
   should use "View release" rather than in-app install.
5. Playback needs a clear Linux policy: system VLC/MPV dependency for beta, or
   bundled runtime for release.

## Implementation Phases

### Phase 1: Make Packaging Host-Aware

- Update `desktopApp/build.gradle.kts`.
- Keep `verifyWindowsPackagingPrereqs` wired only to Windows packaging tasks:
  - `packageExe`
  - `packageMsi`
  - `packageReleaseExe`
  - `packageReleaseMsi`
  - `packageMsiCloseApp`
- Remove the Windows gate from:
  - `createDistributable`
  - `packageDistributionForCurrentOS`
- Add small host helpers:
  - `isWindowsHost()`
  - `isLinuxHost()`
  - optionally `isMacHost()`
- Ensure Linux tasks can run without checking Windows VLC or WiX/JDK `.exe` paths.

Acceptance:

- On Windows, Windows package tasks still fail early when VLC/JDK/WiX prerequisites
  are missing.
- On Linux, `packageDeb` and `packageAppImage` are not blocked by Windows-specific
  checks.

### Phase 2: Add Linux Packaging Prereq Checks

- Add `verifyLinuxPackagingPrereqs`.
- Check only Linux-relevant requirements:
  - Linux host.
  - JDK with `jpackage`.
  - package tooling expected by Compose Desktop/jpackage.
  - optional playback runtime/dependency policy.
- Wire it to:
  - `packageDeb`
  - `packageAppImage`
  - `packageReleaseDeb`
  - `packageReleaseAppImage`

Acceptance:

- Linux packaging fails with a clear message if required build tools are missing.
- The task does not require Windows runtime files.

### Phase 3: Fix Platform Identity and Data Paths

- Update `DesktopDeviceIdProvider`.
- Return the real desktop platform:
  - `windows`
  - `macos`
  - `linux`
- Use platform-aware fallback device names:
  - `Windows Desktop`
  - `macOS Desktop`
  - `Linux Desktop`
- Keep Windows data path behavior unchanged.
- Prefer XDG data location on Linux:
  - `$XDG_DATA_HOME/Torve`
  - fallback `~/.local/share/Torve`
- Consider legacy fallback reads from `~/.torve` if prior Linux test builds exist.

Acceptance:

- Backend/device registration sees Linux desktops as Linux.
- Existing Windows installations keep using `%LOCALAPPDATA%/Torve`.

### Phase 4: Make VLC Discovery OS-Aware

- Update `VlcRuntimeLocator`.
- Add an `osFolder()` helper matching the MPV locator:
  - `windows`
  - `macos`
  - `linux`
- Search bundled runtime paths using `runtime/${osFolder()}/vlc`.
- Keep compatibility fallbacks:
  - `runtime/vlc`
  - `vlc`
- Add Linux standard library locations:
  - `/usr/lib`
  - `/usr/lib/x86_64-linux-gnu`
  - `/usr/local/lib`
- Confirm plugin discovery for Linux. If bundled VLC is used, native library and
  plugin paths may need process environment setup before LibVLC loads.

Acceptance:

- A Linux build can find either bundled Linux VLC or a documented system VLC
  install.
- Missing runtime diagnostics remain user-facing and support-friendly.

### Phase 5: Decide Linux Playback Policy

Recommended beta policy:

- Prefer MPV on Linux when `libmpv.so`/`libmpv.so.2` is available.
- Fall back to VLC when `libvlc.so` is available.
- Document required system packages for beta smoke:
  - `mpv` or `libmpv2`
  - `vlc`

Release policy options:

- Bundle `libmpv` under `desktopApp/runtime/linux/mpv`.
- Bundle Linux VLC under `desktopApp/runtime/linux/vlc`.
- Declare `.deb` package dependencies and keep AppImage runtime-bundled.

Acceptance:

- Clean Linux VM behavior is intentional:
  - either playback works out of the box, or
  - the app clearly explains the missing runtime and docs list install commands.

### Phase 6: Linux Package Metadata

- Verify Compose Desktop Linux metadata:
  - package name: `torve`
  - menu group/category: `AudioVideo`
  - shortcut enabled
  - maintainer set
- Ensure Linux icons use PNG resources, not the Windows `.ico`.
- Check generated `.desktop` launcher content.

Acceptance:

- `.deb` installs with a launcher visible in the app menu.
- App icon appears correctly in launcher and window shell.

### Phase 7: Update Flow

- Keep Linux installer handoff disabled for beta.
- Ensure the update UI presents "View release" rather than "Install" on Linux.
- Later release-grade options:
  - AppImage update flow.
  - apt repository.
  - manual release downloads with checksums.

Acceptance:

- Linux users are not offered a broken in-app installer path.
- Release notes/download links remain accessible.

### Phase 8: Add Linux Release Script

- Add `release/release-desktop-linux.sh`.
- Script responsibilities:
  - confirm Linux host.
  - run desktop compile/tests.
  - build `.deb` and AppImage.
  - compute SHA-256 checksums.
  - print artifact paths and sizes.
- Keep it separate from the Windows PowerShell release helper.

Acceptance:

- A Linux operator can produce beta artifacts with one documented command.

### Phase 9: Add CI

- Add a GitHub Actions job on `ubuntu-24.04`.
- Install required tools:
  - JDK 21
  - `fakeroot`
  - `binutils`
  - any Compose Desktop AppImage requirements discovered during first CI run
  - optional `vlc`/`mpv` for smoke tests
- Run:

```bash
./gradlew :desktopApp:compileKotlin :desktopApp:test
./gradlew :desktopApp:packageDeb :desktopApp:packageAppImage
```

Acceptance:

- CI publishes or stores Linux package artifacts.
- Packaging regressions are caught before release.

### Phase 10: Smoke Matrix

Ubuntu 24.04 `.deb`:

- Install package.
- Launch from app menu.
- Sign in.
- Open Settings.
- Confirm runtime diagnostics.
- Play a local file.
- Play an HTTP/HLS stream.
- Enter/exit fullscreen.
- Close/reopen.

Ubuntu 24.04 AppImage:

- Mark executable.
- Launch directly.
- Repeat minimal login/settings/playback path.

Negative runtime test:

- Clean VM without system VLC/MPV, unless runtime is bundled.
- Confirm missing-runtime UI is clear and actionable.

Acceptance:

- Linux beta is not declared ready until `.deb` and AppImage have both launched
  successfully on a clean VM.

## Milestones

1. Linux package tasks are unblocked.
2. `.deb` builds on a Linux machine or CI runner.
3. AppImage builds on a Linux machine or CI runner.
4. `.deb` installs and launches on Ubuntu 24.04.
5. Playback works with the chosen runtime policy.
6. Linux beta release script and docs exist.
7. Release-grade runtime/update policy is decided and implemented.

## Effort Estimate

- Internal Linux artifact: about 1 day.
- Beta-quality Linux package with smoke-tested playback: 2-4 days.
- Release-quality Linux distribution: 1-2 weeks, mostly playback runtime,
  CI hardening, docs, and update/release policy.

