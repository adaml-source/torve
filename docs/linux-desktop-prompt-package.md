# Torve Linux Desktop Implementation and Testing Prompt Package

## Purpose

This package turns `docs/linux-desktop-implementation-plan.md` into a set of
copy-ready engineering prompts. Use it to drive implementation, CI packaging,
Linux runtime validation, and release smoke testing without losing the original
constraints.

The target outcome is a Linux beta path for Torve Desktop:

- x86_64 Ubuntu/Debian first.
- `.deb` and AppImage artifacts.
- No system Java required for users.
- Playback behavior is explicit and tested.
- Linux packaging is not blocked by Windows-only prerequisites.
- Windows packaging behavior remains intact.

## Shared Repo Context

Torve is a Kotlin Multiplatform project with these relevant modules:

- `shared`: KMP shared business/data/presentation logic.
- `desktopApp`: Compose Desktop JVM application.
- `androidApp`: Android application.
- `release`: platform release helpers.
- `docs`: release, smoke, and platform planning docs.

Important files:

- `desktopApp/build.gradle.kts`
- `desktopApp/src/main/kotlin/com/torve/desktop/platform/DesktopDeviceIdProvider.kt`
- `desktopApp/src/main/kotlin/com/torve/desktop/player/VlcRuntimeLocator.kt`
- `desktopApp/src/main/kotlin/com/torve/desktop/mpv/MpvRuntimeLocator.kt`
- `desktopApp/src/main/kotlin/com/torve/desktop/playback/DesktopPlayerMode.kt`
- `desktopApp/src/main/kotlin/com/torve/desktop/playback/PlayerModePreferences.kt`
- `desktopApp/src/main/kotlin/com/torve/desktop/updates/UpdateInstallerHandoff.kt`
- `desktopApp/WINDOWS_PACKAGING.md`
- `docs/linux-desktop-implementation-plan.md`

Current known state from planning:

- `desktopApp` compiles on the current Windows host.
- Gradle already lists `TargetFormat.Deb` and `TargetFormat.AppImage`.
- `createDistributable` and `packageDistributionForCurrentOS` were identified
  as being incorrectly tied to `verifyWindowsPackagingPrereqs`.
- `DesktopDeviceIdProvider.getPlatform()` reports `windows` unconditionally.
- `VlcRuntimeLocator` can recognize `libvlc.so`, but bundled path discovery is
  still Windows-centered.
- Linux update installer handoff is intentionally unsupported for now.

## Non-Negotiable Constraints

- Do not break Windows packaging.
- Do not remove or weaken the Windows VLC runtime release gate for Windows MSI
  and EXE packaging.
- Do not require Linux users to install Java.
- Do not claim Linux release readiness until an actual Linux VM or Linux CI job
  has built and smoke-tested artifacts.
- Keep changes scoped to Linux desktop packaging/runtime support unless a build
  failure requires a narrow supporting change.
- Preserve existing user data locations on Windows.
- Do not add a new playback engine. Work with the existing VLC and MPV paths.
- Do not implement a half-working Linux installer handoff. For beta, keep Linux
  updates as "View release" unless a complete, tested updater path is added.
- Do not commit generated build outputs, package artifacts, cache directories,
  or staged native runtimes unless explicitly requested.

## Definition of Done

Implementation is done for Linux beta when:

- Windows packaging tasks are still gated by Windows prerequisites.
- Linux package tasks are not gated by Windows prerequisites.
- Linux package tasks have Linux-specific prerequisite checks.
- Desktop platform identity reports `linux` on Linux.
- Linux data path uses XDG data home or `~/.local/share/Torve`.
- VLC runtime discovery is OS-aware.
- MPV/VLC runtime diagnostics are clear on Linux.
- `.deb` builds on Linux.
- AppImage builds on Linux.
- At least one clean Ubuntu 24.04 VM or CI runner can launch the app.
- Playback has been tested or missing-runtime UX has been verified, depending
  on the chosen beta runtime policy.
- Docs and release script explain how to reproduce the Linux package build.

Implementation is done for release grade only when:

- Runtime policy is final, not provisional.
- AppImage behavior is tested on a clean machine.
- `.deb` dependency behavior is tested on a clean machine.
- Checksums are produced and documented.
- Update/release path for Linux is intentionally documented.

## Prompt 1: Implementation Agent

Use this prompt for the first coding pass.

```text
You are working in the Torve repo. Implement Linux desktop packaging support
according to docs/linux-desktop-implementation-plan.md.

Primary goal:
Make the existing Compose Desktop app buildable as Linux .deb and AppImage on a
Linux host or CI runner without being blocked by Windows-only packaging checks.

Scope:
1. Update desktopApp/build.gradle.kts so Windows-only prerequisite checks only
   gate Windows packaging tasks.
2. Add Linux-specific packaging prerequisite checks and wire them to Linux
   package tasks.
3. Fix desktop platform identity and Linux data directory behavior.
4. Make VLC runtime discovery OS-aware.
5. Keep Linux installer handoff disabled for beta, but verify UI/install action
   behavior remains sensible.
6. Add Linux packaging documentation and a Linux release helper script.

Important constraints:
- Preserve Windows MSI/EXE runtime validation.
- Do not remove verifyWindowsPackagingPrereqs.
- Do not require Java on end-user Linux machines.
- Do not commit build outputs or native runtime binaries.
- Keep edits narrow and consistent with existing Kotlin/Gradle style.

Expected file changes:
- desktopApp/build.gradle.kts
- desktopApp/src/main/kotlin/com/torve/desktop/platform/DesktopDeviceIdProvider.kt
- desktopApp/src/main/kotlin/com/torve/desktop/player/VlcRuntimeLocator.kt
- release/release-desktop-linux.sh
- desktopApp/LINUX_PACKAGING.md
- tests if existing test structure supports the affected logic

Implementation details:

Gradle:
- Add host helpers near other top-level build helper functions:
  - isWindowsHost()
  - isLinuxHost()
  - isMacHost() if useful
- Keep verifyWindowsPackagingPrereqs as the authoritative Windows release gate.
- Remove verifyWindowsPackagingPrereqs from createDistributable and
  packageDistributionForCurrentOS.
- Keep or add Windows gate wiring for:
  - packageExe
  - packageMsi
  - packageReleaseExe
  - packageReleaseMsi
  - packageMsiCloseApp
- Add verifyLinuxPackagingPrereqs.
- verifyLinuxPackagingPrereqs should fail fast on non-Linux hosts only when a
  Linux package task directly invokes it. It should not affect normal Windows
  development.
- Linux prerequisite check should validate:
  - os.name looks Linux-like.
  - jpackage is present under java.home/bin/jpackage or a configured JDK.
  - expected external tools are present when practical, such as dpkg-deb,
    fakeroot, or appimagetool if the current Compose Desktop plugin needs them.
  - runtime policy is clear. For beta, warn or document when no bundled Linux
    MPV/VLC runtime is present instead of pretending playback is bundled.
- Wire verifyLinuxPackagingPrereqs to:
  - packageDeb
  - packageAppImage
  - packageReleaseDeb
  - packageReleaseAppImage

Platform identity:
- Update DesktopDeviceIdProvider.getPlatform():
  - windows when os.name contains win
  - macos when os.name contains mac or darwin
  - linux when os.name contains linux, nux, or nix
  - desktop as a fallback only if unknown
- Update default device name:
  - Windows Desktop
  - macOS Desktop
  - Linux Desktop
  - Desktop
- Update desktopDataDir():
  - Windows: keep LOCALAPPDATA/Torve when LOCALAPPDATA exists.
  - macOS can keep current fallback unless there is an established macOS path.
  - Linux: prefer XDG_DATA_HOME/Torve, then ~/.local/share/Torve.
  - Do not change Windows persisted path.

VLC runtime discovery:
- Add osFolder() to VlcRuntimeLocator, matching MpvRuntimeLocator style.
- Search runtime/${osFolder()}/vlc before runtime/vlc and vlc.
- In packaged resources, search vlc and ${osFolder()}/vlc.
- Add standard Linux library locations:
  - /usr/lib
  - /usr/lib/x86_64-linux-gnu
  - /usr/local/lib
- Keep standard Windows locations.
- Keep libvlc.dll, libvlccore.dll, libvlc.so, and libvlc.dylib recognition.
- Improve diagnostic text only if needed; keep it user-facing and concise.

Update handoff:
- Do not add Linux installer handoff in this pass.
- Verify Linux users fall back to viewing the release. If UI text is misleading,
  adjust it narrowly.

Docs and scripts:
- Add desktopApp/LINUX_PACKAGING.md with:
  - supported target: Ubuntu/Debian x86_64 beta.
  - build prerequisites.
  - build commands.
  - output artifact locations.
  - runtime policy for beta.
  - clean VM smoke checklist.
  - known limitations.
- Add release/release-desktop-linux.sh:
  - bash script.
  - set -euo pipefail.
  - fail if not Linux.
  - run ./gradlew :desktopApp:compileKotlin :desktopApp:test.
  - run ./gradlew :desktopApp:packageDeb :desktopApp:packageAppImage.
  - locate produced .deb and AppImage.
  - compute sha256sum for each.
  - print artifact paths, sizes, and hashes.

Testing on this host:
- If running on Windows, run:
  - .\gradlew.bat :desktopApp:compileKotlin
  - .\gradlew.bat :desktopApp:test
  - .\gradlew.bat :desktopApp:tasks --all
- Do not claim Linux packaging succeeded from Windows.

Testing on Linux:
- Run:
  - ./gradlew :desktopApp:compileKotlin
  - ./gradlew :desktopApp:test
  - ./gradlew :desktopApp:packageDeb
  - ./gradlew :desktopApp:packageAppImage
  - ./release/release-desktop-linux.sh

Final response:
- List changed files.
- State which checks passed.
- State clearly whether Linux packaging was actually run on Linux.
- Call out any remaining blockers.
```

## Prompt 2: Linux CI Agent

Use this prompt after the implementation pass, or as a separate CI-focused pass.

```text
You are working in the Torve repo after Linux desktop packaging support has been
implemented. Add or update GitHub Actions CI so Linux desktop packages are built
on an Ubuntu runner.

Primary goal:
Create a Linux CI path that builds Torve Desktop .deb and AppImage artifacts and
catches packaging regressions.

Scope:
- Inspect .github/workflows.
- Add the smallest maintainable Linux desktop packaging workflow or extend an
  existing release/build workflow.
- Do not disturb Android, server, or unrelated workflows.

Target runner:
- ubuntu-24.04

Required setup:
- JDK 21 with jpackage.
- Gradle wrapper.
- Linux packaging tools required by Compose Desktop/jpackage.
- Likely packages to install:
  - fakeroot
  - binutils
  - dpkg
  - file
  - desktop-file-utils if launcher validation is added
  - libfuse2 or libfuse2t64 if AppImage build or smoke requires it
  - vlc and/or mpv only if runtime smoke runs on CI

CI commands:
- ./gradlew :desktopApp:compileKotlin :desktopApp:test
- ./gradlew :desktopApp:packageDeb :desktopApp:packageAppImage

Artifact handling:
- Upload .deb artifact.
- Upload AppImage artifact.
- Upload SHA-256 checksum text if generated.

Cache:
- Use Gradle cache if an existing workflow pattern already does.
- Do not introduce fragile cache keys if the repo already has a convention.

Validation:
- CI should fail if no .deb is produced.
- CI should fail if no AppImage is produced.
- CI should print artifact names and sizes.

Final response:
- List workflow files changed.
- Explain runner prerequisites.
- State commands the workflow runs.
- State any remaining Linux smoke tests that still require a real VM.
```

## Prompt 3: Linux Packaging Debug Agent

Use this prompt if Linux CI or a Linux VM package build fails.

```text
You are debugging Linux desktop packaging for Torve. The implementation plan is
in docs/linux-desktop-implementation-plan.md and the prompt package is in
docs/linux-desktop-prompt-package.md.

Goal:
Fix the specific Linux packaging failure while preserving Windows packaging.

Inputs to inspect:
- Full Gradle error output.
- desktopApp/build.gradle.kts.
- Generated files under desktopApp/build/compose only as diagnostics.
- .github/workflows if failure is CI-only.
- release/release-desktop-linux.sh if the release helper failed.

Rules:
- Do not bypass a real missing prerequisite with a silent ignore.
- Do not make Windows packaging depend on Linux tools.
- Do not make Linux packaging depend on Windows VLC/WiX/MSI files.
- Keep fixes narrow.
- Prefer clear Gradle prerequisite messages over obscure jpackage failures.

Common failure classes:

1. Linux package task invokes verifyWindowsPackagingPrereqs.
   - Fix task wiring.
   - Confirm packageDeb/packageAppImage do not depend on Windows gate.

2. jpackage missing.
   - Verify Linux runner uses a full JDK, not a stripped runtime.
   - Update docs or CI setup.
   - Do not require jpackage.exe on Linux.

3. dpkg/fakeroot/binutils missing.
   - Add package installation to CI.
   - Add a clear verifyLinuxPackagingPrereqs message.

4. AppImage tooling/fuse problem.
   - Determine whether failure is build-time or run-time.
   - Add required build package if needed.
   - Document run-time FUSE requirement if AppImage launch needs it.

5. Icon/desktop metadata problem.
   - Inspect generated .desktop file and icon resources.
   - Use PNG icon for Linux.

6. Native playback runtime missing.
   - Confirm whether this is expected by beta policy.
   - If expected, ensure diagnostics and docs are clear.
   - If not expected, fix runtime staging or dependency declaration.

Final response:
- Describe root cause.
- List changed files.
- List exact commands rerun.
- State what remains unverified.
```

## Prompt 4: Linux Runtime and Playback Test Agent

Use this prompt on a Linux desktop VM after package artifacts exist.

```text
You are validating Torve Desktop Linux runtime behavior on a clean Ubuntu 24.04
x86_64 VM.

Artifacts:
- One .deb package.
- One AppImage.
- SHA-256 checksums if available.

Goal:
Verify that Torve launches, identifies as Linux, uses the expected data path,
and handles playback runtime availability according to the beta policy.

Pre-test setup:
- Record OS version:
  - lsb_release -a
  - uname -a
- Record Java state:
  - java -version if installed, but note that the app should not require system Java.
- Record playback packages:
  - dpkg -l | grep -E 'vlc|mpv|libmpv'

Checksum:
- sha256sum artifact.deb
- sha256sum artifact.AppImage
- Compare with released checksums if provided.

.deb install test:
1. Install:
   - sudo apt install ./torve*.deb
2. Confirm package registration:
   - dpkg -l | grep torve
   - dpkg -L torve | head
3. Confirm launcher metadata:
   - find /usr/share/applications -iname '*torve*'
   - inspect .desktop file.
4. Launch from terminal:
   - torve
   - or the installed launcher path if the command name differs.
5. Launch from app menu if a GUI is available.

AppImage test:
1. Mark executable:
   - chmod +x Torve*.AppImage
2. Launch from terminal:
   - ./Torve*.AppImage
3. Confirm it starts without system Java.

Runtime behavior:
- Sign in or use an available test account flow.
- Open Settings.
- Open diagnostics/runtime section.
- Confirm platform text shows Linux, not Windows.
- Confirm app data path is:
  - $XDG_DATA_HOME/Torve when XDG_DATA_HOME is set, or
  - ~/.local/share/Torve by default.
- Confirm no unexpected writes to %LOCALAPPDATA%-style paths.

Playback tests:
- With documented playback dependencies installed:
  - Play a local MP4 file.
  - Play an HTTP/HLS stream.
  - Enter fullscreen.
  - Exit fullscreen.
  - Pause/resume.
  - Close/reopen app.
- Without playback dependencies installed, if testing negative path:
  - Attempt playback.
  - Confirm missing-runtime message is clear.
  - Confirm the app does not crash.

Update behavior:
- Open update UI if available.
- Confirm Linux does not offer a broken in-app installer handoff.
- Confirm "View release" or equivalent path works.

Logs to collect:
- Terminal stdout/stderr.
- Torve app data logs if present.
- Screenshot of runtime diagnostics.
- Screenshot or note for launcher presence.

Pass criteria:
- .deb installs.
- AppImage launches.
- App does not require system Java.
- App identifies Linux correctly.
- Data dir is Linux-appropriate.
- Playback either works with documented dependencies or fails with clear UX.
- Update path is not misleading.

Final report:
- OS details.
- Artifact filenames and hashes.
- Install/launch result for .deb.
- Launch result for AppImage.
- Runtime diagnostics result.
- Playback result.
- Update UI result.
- Bugs found, with exact reproduction steps.
```

## Prompt 5: Windows Regression Test Agent

Use this prompt after Linux changes to make sure Windows packaging behavior was
not accidentally weakened.

```text
You are validating that Linux desktop packaging changes did not regress Windows
desktop packaging behavior.

Goal:
Ensure Windows MSI/EXE packaging still requires the Windows VLC runtime and JDK
jpackage prerequisites, while normal desktop compile/test remains unaffected.

Host:
- Windows.

Commands:
- .\gradlew.bat :desktopApp:compileKotlin
- .\gradlew.bat :desktopApp:test
- .\gradlew.bat :desktopApp:tasks --all
- .\gradlew.bat :desktopApp:verifyWindowsPackagingPrereqs

If runtime is staged:
- .\gradlew.bat :desktopApp:packageMsiCloseApp

If runtime is intentionally removed or unavailable:
- verifyWindowsPackagingPrereqs should fail with a clear message.
- packageMsi/packageExe should also be gated.

Regression checks:
- createDistributable should not be needlessly blocked unless packaging requires
  the Windows gate by design.
- packageMsiCloseApp must still depend on verifyWindowsPackagingPrereqs.
- packageMsi/packageExe must still depend on verifyWindowsPackagingPrereqs.
- Windows runtime search paths remain valid.
- Existing Windows app data path remains %LOCALAPPDATA%/Torve.

Final report:
- Commands run.
- Pass/fail.
- Any Windows packaging gate behavior changes.
- Any unrelated dirty files.
```

## Prompt 6: Documentation and Release Readiness Review

Use this prompt before marking Linux beta ready.

```text
You are reviewing Torve Linux desktop release readiness.

Inputs:
- docs/linux-desktop-implementation-plan.md
- docs/linux-desktop-prompt-package.md
- desktopApp/LINUX_PACKAGING.md
- release/release-desktop-linux.sh
- GitHub Actions Linux packaging workflow, if present
- Linux VM smoke reports

Goal:
Decide whether Torve Linux Desktop can be called beta-ready.

Review checklist:
- Linux package tasks build on Linux.
- .deb installs on Ubuntu 24.04.
- AppImage launches on Ubuntu 24.04.
- App does not require system Java.
- Platform identity reports linux.
- Data path is XDG-friendly.
- Playback runtime policy is documented and tested.
- Missing-runtime UX is acceptable.
- Windows packaging gate remains intact.
- CI produces artifacts or documents manual artifact creation clearly.
- Checksums are generated.
- Linux update/install flow is not misleading.

Output:
- GO or NO-GO.
- Blocking issues.
- Non-blocking follow-ups.
- Exact artifact versions/hashes reviewed.
- Exact smoke environment reviewed.
```

## Suggested Implementation Order

1. Host-aware Gradle gate split.
2. Linux prerequisite task.
3. Platform identity and data path fix.
4. VLC OS-aware locator.
5. Linux docs.
6. Linux release script.
7. Windows compile/test sanity check.
8. Linux CI package build.
9. Linux VM smoke.
10. Windows packaging regression check.

## Suggested Commit Strategy

Prefer small commits:

1. `build: split desktop packaging prerequisites by host`
2. `fix: report Linux desktop platform metadata`
3. `fix: discover VLC runtime by desktop OS`
4. `docs: add Linux desktop packaging runbook`
5. `ci: build Linux desktop packages`

If working in a single branch, keep generated artifacts out of commits.

## Command Reference

Windows local sanity:

```powershell
.\gradlew.bat :desktopApp:compileKotlin
.\gradlew.bat :desktopApp:test
.\gradlew.bat :desktopApp:tasks --all
.\gradlew.bat :desktopApp:verifyWindowsPackagingPrereqs
```

Linux build sanity:

```bash
./gradlew :desktopApp:compileKotlin
./gradlew :desktopApp:test
./gradlew :desktopApp:packageDeb
./gradlew :desktopApp:packageAppImage
```

Linux release helper:

```bash
./release/release-desktop-linux.sh
```

Linux package smoke:

```bash
sudo apt install ./torve*.deb
dpkg -L torve | head
chmod +x Torve*.AppImage
./Torve*.AppImage
```

Checksum:

```bash
sha256sum torve*.deb
sha256sum Torve*.AppImage
```

## Known Follow-Ups After Beta

- Decide whether AppImage should bundle MPV/VLC runtime.
- Decide whether `.deb` should declare package dependencies for VLC/MPV.
- Add AppImage update support or document manual update path permanently.
- Add broader distro smoke:
  - Debian stable.
  - Fedora if RPM support is later added.
  - Linux Mint.
- Test Wayland and X11 playback differences.
- Add accessibility and system tray validation across GNOME/KDE.

