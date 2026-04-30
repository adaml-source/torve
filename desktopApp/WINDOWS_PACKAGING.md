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
- `plugins/` directory containing the full plugin set (≥ 100 `.dll` files —
  a clean VLC 3.x install ships ~280)
- The `plugins/` subtree must contain working playback families:
  `access/`, `codec/`, `demux/`, `video_output/` (a pruned plugins/ tree
  packages cleanly through jpackage but ships a player that can't open
  most files)
- VLC license notice (`COPYING.txt`, `LICENSE-VLC.txt`, or `LICENSE.txt`)

It also refuses to package when `desktopApp/runtime/windows/mpv/` contains
`libmpv-2.dll`, `mpv-2.dll`, or `mpv.exe` without a license notice
(`LICENSE-MPV.txt`, `COPYING`, `COPYING.txt`, or `LICENSE.txt`). libmpv is
LGPL-2.1+; the default release shape is **no MPV bundled** — that directory
ships only its README. If you re-enable MPV bundling, drop the upstream
license file alongside the binaries.

To run a packaging dry-run locally without a real VLC drop, set
`TORVE_PACKAGE_ALLOW_MISSING_RUNTIME=1`. This downgrades the gate to a warning.
**Released builds must not bypass this check.** When `TORVE_RELEASE_BUILD=1`
is set, the bypass is refused — the build fails fast even if both env vars
are present. CI release pipelines set `TORVE_RELEASE_BUILD=1` so a tired
operator can't accidentally ship a runtime-less package.

### Staging scripts

Two helpers under `desktopApp/scripts/`:

- `stage-windows-vlc-runtime.ps1` — Windows operator workflow. Defaults to
  `C:\Program Files\VideoLAN\VLC` as the source. Idempotent. Wipes the
  target plugins/ tree before re-copying. Pass `-Source <path>` to point
  elsewhere, `-SkipVerify` to skip the post-copy gate run.
- `stage-windows-vlc-runtime.sh` — Linux/macOS CI sibling. Reads
  `TORVE_VLC_PORTABLE` env var or `--source <path>`. No default Linux path.
  CI typically downloads a portable VLC zip, unzips it, and points the
  script at the extraction.

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

## Code Signing (Authenticode)

The Compose Gradle plugin produces an **unsigned** `.exe` and `.msi`. Real
release artifacts must be Authenticode-signed before publish, otherwise
SmartScreen / Defender flag them on download.

### Prerequisites

- A Windows Authenticode code-signing certificate (EV preferred, OV is
  acceptable but accumulates SmartScreen reputation more slowly).
  Stored in a USB token, an HSM, or a `.pfx` file. Never commit the
  `.pfx` or the password to the repo.
- `signtool.exe` from the Windows SDK on `PATH`. Typical install path:
  `C:\Program Files (x86)\Windows Kits\10\bin\<ver>\x64\signtool.exe`.

### Operator command (PFX file)

```powershell
$pfx     = "C:\secure\torve-codesign.pfx"
$pwd     = $env:TORVE_CODESIGN_PFX_PASSWORD       # set in operator shell only
$tsa     = "http://timestamp.digicert.com"
$exe     = "desktopApp\build\compose\binaries\main\exe\Torve-1.0.6.exe"

signtool sign `
  /f "$pfx" `
  /p "$pwd" `
  /tr $tsa /td SHA256 /fd SHA256 `
  /d "Torve" /du "https://torve.app/" `
  "$exe"

signtool verify /pa /v "$exe"
```

For an `.msi`, replace the path with the produced `Torve-1.0.6.msi`.

### Operator command (USB token / HSM via CSP)

```powershell
$tsa = "http://timestamp.digicert.com"
$exe = "desktopApp\build\compose\binaries\main\exe\Torve-1.0.6.exe"

signtool sign `
  /n "Torve" `
  /tr $tsa /td SHA256 /fd SHA256 `
  /d "Torve" /du "https://torve.app/" `
  "$exe"
```

`/n "Torve"` selects the cert by subject from the user's certificate
store; the OS-level CSP / smart card prompt handles the token PIN.

### Notes

- `/tr <tsa>` + `/td SHA256` + `/fd SHA256` is required. Without
  timestamping the signature stops verifying once the cert expires.
- Signing happens **after** `packageExe` / `packageMsi`. Don't try to
  hook it inside Gradle — the operator cert handling lives outside the
  build matrix.
- The macOS Developer ID identity, notarization Apple ID, and
  app-specific password are read from env vars by the Compose plugin
  (see `desktopApp/build.gradle.kts` `macOS { signing/notarization }`):
  - `TORVE_MAC_SIGN_IDENTITY` — e.g. `Developer ID Application: Torve (TEAMID)`
  - `TORVE_MAC_NOTARIZATION_USER` — Apple ID email
  - `TORVE_MAC_NOTARIZATION_PWD` — app-specific password
  When all three are set, `packageDmg` produces a notarization-ready
  `.dmg`; `xcrun notarytool submit` then staples and finalizes.

## Release Channels

Release artifacts carry a JVM system property `torve.desktop.channel` so
that a single source tree produces stable / beta / internal builds
without touching code. Set:

```sh
export TORVE_RELEASE_CHANNEL=stable      # or beta, internal-preview
export TORVE_RELEASE_BUILD=1              # refuses the runtime bypass
```

Then run `:desktopApp:packageMsi` (or `packageExe` / `packageDmg`).
The packaged binary's About dialog reads the channel from the JVM
property at startup. CI defines one job per channel; default is
`internal-preview` when the env var is unset.

## Update Path

The desktop shell shows a slim "Torve update available" banner at the top
when `TORVE_UPDATE_FEED` (or `TORVE_UPDATE_REPO`) points at a release
manifest. When the feed is a Sparkle/WinSparkle appcast and the latest
`<item>` includes a direct `<enclosure url="...">`, the banner shows
**Download & install** in addition to **View release**.

The handoff:

1. Refuses non-HTTPS installer URLs.
2. Streams the installer to `%TEMP%\torve-update-<filename>` (Windows) or
   `$TMPDIR/torve-update-<filename>` (macOS).
3. If the appcast `<enclosure>` carries `sparkle:installerSha256="..."`
   (or `sha256="..."`) verifies the download against that 64-hex digest;
   mismatched files are deleted and the banner reports failure.
4. Hands off to `java.awt.Desktop.getDesktop().open(file)` — the OS picks
   the right helper (Windows Installer for `.exe`/`.msi`, Mounter for
   `.dmg`).

The handoff is **not** native Sparkle/WinSparkle — there's no auto-relaunch,
delta update, or rollback. Authenticode signing on the downloaded
installer is what backstops integrity at install time; the `installerSha256`
attribute is a defence-in-depth check before launch.

To generate a sample appcast for your CDN:

```sh
./gradlew :desktopApp:generateSampleAppcast
# writes desktopApp/release/appcast.sample.xml
```

Edit version, URL, and SHA-256 (compute via `Get-FileHash -Algorithm SHA256`
on Windows or `shasum -a 256` on Unix), then host the file at a stable
URL and point `TORVE_UPDATE_FEED` at it for distributed builds.

## Known Remaining Release Work

1. VLC runtime must be supplied separately; the repo provides only the
   staging path and the staging scripts.
2. Authenticode certificate provisioning is operator workflow only — no
   key material is stored in repo.
3. A Windows `.ico` asset is not currently staged for packaging.
4. The update banner does NOT auto-relaunch after install — users start
   Torve from the Start menu after the OS installer finishes.
5. VLC LGPL license notice must be included in distributed packages
   (gate enforces this).
