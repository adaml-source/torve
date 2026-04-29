# Torve Windows Packaging Checklist

This checklist covers the Windows desktop packaging flow for `:desktopApp` with embedded VLC playback.

## VLC Runtime Setup

Torve uses LibVLC (via vlcj) for embedded media playback. The VLC runtime must be available at build time for packaging and at runtime for playback.

### Expected Runtime Folder Structure

```
desktopApp/runtime/windows/vlc/
├── libvlc.dll              # Core VLC library
├── libvlccore.dll          # VLC core
├── plugins/                # VLC plugin modules (REQUIRED)
│   ├── access/
│   ├── audio_filter/
│   ├── audio_output/
│   ├── codec/
│   ├── demux/
│   ├── video_chroma/
│   ├── video_filter/
│   ├── video_output/
│   └── ...
└── (other VLC DLLs as needed)
```

### How to Obtain VLC Runtime Files

1. Install VLC media player 64-bit from https://www.videolan.org/
2. Copy the contents of `C:\Program Files\VideoLAN\VLC\` into `desktopApp/runtime/windows/vlc/`
3. At minimum, copy: `libvlc.dll`, `libvlccore.dll`, and the entire `plugins/` directory

### Runtime Discovery Order

At startup, Torve discovers VLC native libraries in this order:

1. **Bundled runtime**: `desktopApp/runtime/windows/vlc/` (or Compose app resources dir)
2. **JVM property**: `-Dtorve.desktop.vlc.path=/path/to/vlc`
3. **Environment variable**: `TORVE_VLC_PATH=/path/to/vlc`
4. **Standard Windows install**: `C:\Program Files\VideoLAN\VLC\`
5. **VLC_PLUGIN_PATH**: Parent of the VLC_PLUGIN_PATH environment variable

### Licensing

VLC/LibVLC is licensed under LGPL-2.1. When distributing Torve with bundled VLC runtime:
- Include the VLC license text (`COPYING.txt` from the VLC installation) in
  `desktopApp/runtime/windows/vlc/`. The packaging gate will fail if it's missing.
- The vlcj Java bindings are licensed under GPL-3.0; their notice rides with the
  vlcj jar already on the runtime classpath.

### Release gate (enforced by Gradle)

The `verifyWindowsPackagingPrereqs` task is a hard dependency of the Windows packaging
tasks (`packageExe`, `packageMsi`, `createDistributable`, `packageDistributionForCurrentOS`).
It fails the build when any of the following is missing under
`desktopApp/runtime/windows/vlc/`:

- `libvlc.dll`
- `libvlccore.dll`
- `plugins/` directory containing the full plugin set (≥ 30 `.dll` files)
- VLC license notice (`COPYING.txt`, `LICENSE-VLC.txt`, or `LICENSE.txt`)

It also refuses to package when `desktopApp/runtime/windows/mpv/` contains
`libmpv-2.dll`, `mpv-2.dll`, or `mpv.exe` without a license notice
(`LICENSE-MPV.txt`, `COPYING`, `COPYING.txt`, or `LICENSE.txt`). libmpv is
LGPL-2.1+; the default release shape is **no MPV bundled** — that directory
ships only its README. If you re-enable MPV bundling, drop the upstream
license file alongside the binaries.

To run a packaging dry-run locally without a real VLC drop, set
`TORVE_PACKAGE_ALLOW_MISSING_RUNTIME=1`. This downgrades the gate to a warning.
**Released builds must not bypass this check.**

## Prerequisites

1. Use a JDK that includes `jpackage.exe`.
   The current Android Studio JBR on this machine does not include it.
2. Stage the VLC runtime at:
   `desktopApp/runtime/windows/vlc/` (with libvlc.dll, libvlccore.dll, plugins/)
3. Keep `TMDB_API_KEY` available for the desktop build through one of:
   - Gradle property `TMDB_API_KEY`
   - environment variable `TMDB_API_KEY`
   - `local.properties`

## Local Development Run

1. Either:
   - Install VLC 64-bit on the system (standard path), OR
   - Stage VLC runtime under `desktopApp/runtime/windows/vlc/`, OR
   - Set `TORVE_VLC_PATH` to a VLC installation directory
2. Run:
   `./gradlew :desktopApp:run`
3. Sign in and start playback. The embedded VLC player should appear in the app window with Torve-branded controls.

## Distributable Build

1. Verify prerequisites:
   `./gradlew :desktopApp:verifyWindowsPackagingPrereqs`
2. Print the current checklist path and VLC drop location:
   `./gradlew :desktopApp:printWindowsPackagingChecklist`
3. Build the app image:
   `./gradlew :desktopApp:createDistributable`

## MSI / EXE Creation

1. Ensure the JDK used by Gradle includes `jpackage.exe`.
2. Build:
   - `./gradlew :desktopApp:packageExe`
   - `./gradlew :desktopApp:packageMsi`

## Testing Packaged Builds

1. Confirm `desktopApp/runtime/windows/vlc/libvlc.dll` existed before packaging.
2. Install or unpack the generated Windows distribution.
3. Launch Torve.
4. Sign in and start playback from any content page.
5. Verify:
   - Embedded VLC player renders video inside the Torve window
   - Torve-branded controls overlay appears on mouse move
   - Play/pause, seek, volume, fullscreen all work
   - Audio and subtitle track selection works
   - Playback speed changes work
   - Double-click toggles fullscreen
   - Escape exits fullscreen
   - Close returns to the shell with correct state

## Manual Verification Checklist

- [ ] VLC runtime discovery reports success in console logs
- [ ] Video plays inside Torve window (not in separate VLC window)
- [ ] Play/pause button works
- [ ] Timeline seek works (drag and click)
- [ ] Volume slider works
- [ ] Mute toggle works (M key and button)
- [ ] 10s back / 30s forward buttons work
- [ ] Left/Right arrow keys seek
- [ ] Up/Down arrow keys change volume
- [ ] Space toggles play/pause
- [ ] F key toggles fullscreen
- [ ] Double-click toggles fullscreen
- [ ] Escape exits fullscreen
- [ ] Fullscreen restores exact pre-fullscreen window state
- [ ] Audio track menu shows available tracks
- [ ] Subtitle track menu shows available tracks
- [ ] Subtitle disable works
- [ ] Audio delay adjustment works (K/L keys, menu)
- [ ] Subtitle delay adjustment works (H/J keys, menu)
- [ ] Playback speed menu works
- [ ] Close button stops playback and returns to shell
- [ ] Nav rail is hidden during embedded playback
- [ ] Nav rail reappears after closing player
- [ ] Chrome auto-hides after 3s of no mouse activity in fullscreen
- [ ] Chrome reappears on mouse move
- [ ] Chrome stays visible when paused
- [ ] No controls are non-clickable or blocked
- [ ] Error state shows meaningful message if VLC is missing

## Known Remaining Release Work

1. VLC runtime must be supplied separately; the repo provides only the staging path.
2. Windows code signing is not configured.
3. A Windows `.ico` asset is not currently staged for packaging.
4. Installer polish and public-release hardening are pending.
5. VLC LGPL license notice must be included in distributed packages.
