# mpv-android Native Bundle

Torve vendors the Android native playback stack from the upstream `mpv-android` project in
`androidApp/src/main/jniLibs`.

Current source:
- Project: `https://github.com/mpv-android/mpv-android`
- Release tag: `2025-12-27`
- Release assets used:
  - `app-default-arm64-v8a-release.apk`
  - `app-default-armeabi-v7a-release.apk`

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
