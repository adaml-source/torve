# mpv-android Native Bundle

Torve vendors the Android native playback stack from the upstream `mpv-android` project in
`androidApp/src/main/jniLibs`.

Current source:
- Project: `https://github.com/mpv-android/mpv-android`
- Release tag: `2025-12-27`
- Release assets used:
  - `app-default-arm64-v8a-release.apk`
  - `app-default-armeabi-v7a-release.apk`
  - `debug-objs.zip` (native debug symbols)
- Debug archive SHA-256:
  `407a4d3c1b1b930d47504716e7d699a3ec79a5585fd5f9dec0401c4f19ed1559`

Bundled ABIs:
- `arm64-v8a`
- `armeabi-v7a`

Bundled native libraries:
- `libplayer.so`
- `libmpv.so`
- `libavcodec.so`
- `libavdevice.so`
- `libavfilter.so`
- `libavformat.so`
- `libavutil.so`
- `libswresample.so`
- `libswscale.so`
- `libc++_shared.so`

Reason:
- Fire TV devices such as `AFTGAZL` do not expose a platform MP2 decoder for channels like `3sat HD`.
- Torve's TV build needs a packaged software decoder path so Live TV can follow the intended MPV
  engine instead of failing in ExoPlayer with `audio/mpeg-L2`.

## Google Play native symbols

The release APKs contain stripped native libraries. Copying those libraries from Gradle's
`merged_native_libs` output does **not** create a usable debug-symbol archive.

Download the matching `debug-objs.zip` from the pinned release, verify its SHA-256 above, then run:

```powershell
.\gradlew.bat :androidApp:packageGooglePlayNativeSymbols `
  -PmpvDebugObjectsZip=C:\path\to\debug-objs.zip
```

The task verifies the archive checksum, checks every shipped mpv/FFmpeg/player runtime binary
against its pinned checksum, confirms that all 18 unstripped symbol libraries are present, and
then writes:

- `androidApp/build/outputs/native-debug-symbols/googleMobileRelease/native-debug-symbols.zip`
- `androidApp/build/outputs/native-debug-symbols/googleTvRelease/native-debug-symbols.zip`

Each ZIP has the ABI directories at its root, as required by Google Play. Upload the matching ZIP
for each app in Play Console's App bundle explorer. `libc++_shared.so` is not included because the
upstream release does not publish its matching unstripped library.
