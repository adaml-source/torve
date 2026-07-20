# Bundling libmpv with Torve Desktop

Torve ships VLC as the default playback engine and falls back to it
automatically when `libmpv` is unavailable. To enable the experimental
MPV engine on installs that don't have mpv pre-installed, drop the
platform-appropriate `libmpv` binary into one of the directories below
*before* running the Compose `package*` task. The Gradle build wires
`appResourcesRootDir` to `desktopApp/runtime/`, so anything in those
directories is copied verbatim into the packaged distribution.

| Host    | Drop path                                    | File(s)                                 |
| ------- | -------------------------------------------- | --------------------------------------- |
| Windows | `desktopApp/runtime/windows/mpv/`            | `mpv-2.dll` (or `libmpv-2.dll`)         |
| macOS   | `desktopApp/runtime/macos/mpv/`              | `libmpv.2.dylib` (or `libmpv.dylib`)    |
| Linux   | `desktopApp/runtime/linux/mpv/`              | `libmpv.so.2` (or `libmpv.so`)          |

Sources:

- **Windows** — pre-built `libmpv-2.dll` is published with the
  [shinchiro/mpv-winbuild-cmake nightly releases](https://github.com/shinchiro/mpv-winbuild-cmake/releases)
  inside `mpv-dev-x86_64-*.7z` archives. Pin a specific release for
  reproducible packaging.
- **macOS** — install via `brew install mpv` and copy
  `/opt/homebrew/lib/libmpv.2.dylib` (Apple Silicon) or
  `/usr/local/lib/libmpv.2.dylib` (Intel) into the drop folder.
- **Linux** — most distros ship `libmpv` as a system package
  (`apt install libmpv2`, `dnf install mpv-libs`, etc.); a bundled copy
  is rarely needed because users usually install via their package
  manager.

License: `libmpv` is **LGPL v2.1+**. Distributing it dynamically is
allowed; the LICENSE in the bundled archive must be shipped alongside.

To verify staging without packaging, run:

```bash
./gradlew :desktopApp:verifyMpvRuntime
```

The runtime locator (`MpvRuntimeLocator.kt`) probes these directories at
app startup. Override paths can also be supplied via the
`TORVE_MPV_PATH` environment variable or the `torve.desktop.mpv.path`
JVM property if a tester wants to point at an arbitrary directory
without staging files.
