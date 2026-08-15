# Contributing to Torve

Torve is free software under `AGPL-3.0-or-later`. Bug reports, focused fixes,
tests, documentation, accessibility improvements, and platform-specific
verification are welcome.

## Before opening an issue

- Search existing issues and include the Torve version, platform, device model,
  and reproducible steps.
- Remove account emails, tokens, API keys, playlist URLs, stream URLs, server
  addresses, and copyrighted media from screenshots and logs.
- Describe the user outcome that is blocked. For TV issues, include the exact
  D-pad sequence and which control owned focus before and after the problem.
- Do not request bundled content, credentials, playlists, or features intended
  to bypass access rights. Torve connects user-authorized services and sources.

## Local checks

Use Java 21 and the Gradle wrapper. The main host-runnable gate is:

```powershell
./gradlew.bat :shared:allTests :desktopApp:test :androidApp:testAmazonTvDebugUnitTest :androidApp:testGoogleTvDebugUnitTest :androidApp:testGoogleMobileDebugUnitTest --console=plain
```

Compile release surfaces without installing them on a device:

```powershell
./gradlew.bat :androidApp:compileAmazonTvReleaseKotlin :androidApp:compileGoogleTvReleaseKotlin :androidApp:compileGoogleMobileReleaseKotlin --console=plain
```

Backend development and migration instructions are in
[`server/README.md`](server/README.md). Never use production credentials or
production user data in tests.

## Pull requests

- Keep changes scoped to one coherent problem and preserve existing
  architecture unless evidence requires a broader change.
- Add regression coverage using the project’s existing conventions.
- For Android TV changes, preserve D-pad navigation, Back behavior, stable
  focus identity, and a visible focus owner after recomposition.
- Do not commit generated builds, signing keys, `.env` files, device dumps,
  private logs, or screenshots containing account data.
- State which commands actually passed. Do not describe unrun device or store
  checks as verified.

By contributing, you agree that your contribution is provided under the
project’s `AGPL-3.0-or-later` license.
