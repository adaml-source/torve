# Torve

Torve is a cross-platform media companion for accounts, devices, playback surfaces, watch state, source health, diagnostics, and personal media workflows.

Torve is free software. There are no subscriptions, no paid tiers, no premium features, and no purchase required. Donations are optional and never unlock features.

## Current Status

This repository is private and should not be made public directly. The public release should be created from a fresh sanitized source export after final audit.

The private repository has been converted so product access is free/default for authenticated active accounts. Historical payment, billing, entitlement, purchase, rebate, and donation records do not unlock or remove product features.

## License

Torve is licensed under `AGPL-3.0-or-later`.

See [LICENSE](LICENSE).

## Platform Build Matrix

| Area | Purpose | Local check |
| --- | --- | --- |
| `server/` | FastAPI backend and sync services | `cd server && pytest` |
| `shared/` | Kotlin Multiplatform client core | `./gradlew :shared:compileKotlinMetadata` |
| `androidApp/` Google mobile | Android mobile build | `./gradlew :androidApp:assembleGoogleMobileDebug` |
| `androidApp/` Google TV | Android TV / Google TV build | `./gradlew :androidApp:assembleGoogleTvDebug` |
| `androidApp/` Amazon TV | Fire TV / Amazon Appstore build | `./gradlew :androidApp:assembleAmazonTvDebug` |
| `iosApp/` | iOS app using the shared KMP framework | Run shared iOS Gradle tasks from the repo root, then Xcode checks on macOS |
| `desktopApp/` | Compose Desktop app and packaging | `./gradlew :desktopApp:build` |

Store-distributed apps hide donation links by default. Any optional donation UI must use safe copy: "Donations are optional and never unlock features."

## Secret-Safe Local Development

Real secrets must stay outside the repository. Do not commit production `.env` files, signing material, service-account files, keystores, app-store credentials, webhook secrets, database dumps, logs, or generated release artifacts.

Ignored local files include:

- `.env`
- `.env.*`
- `keystore.properties`
- `local.properties`

Firebase `google-services.json` files require public-safe review before reuse in a public export. Android signing keys, TMDB keys, app-store service accounts, updater/admin secrets, and any credential copied into bundles should be rotated if exposure is suspected.

## Public Release Path

Use a fresh sanitized public repository created from a clean source export. Do not flip this private repository public unless the complete git history, local files, archives, generated bundles, and release artifacts have been audited.

See [PUBLIC_RELEASE_CHECKLIST.md](PUBLIC_RELEASE_CHECKLIST.md) and [docs/store-metadata-cleanup-checklist.md](docs/store-metadata-cleanup-checklist.md).

## Contributions

Issues and pull requests are welcome after the public release path is established.

Torve is maintained as free software. Issues and pull requests are welcome, but there is no guaranteed response time.
