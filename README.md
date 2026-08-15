# Torve

Torve is a cross-platform media companion for accounts, devices, playback surfaces, watch state, source health, diagnostics, and personal media workflows.

Torve is free software. There are no subscriptions, no paid tiers, no premium features, and no purchase required. Donations are optional and never unlock features.

## Current Status

This is the public Torve source repository. Product access is free/default for authenticated active accounts. Historical payment, billing, entitlement, purchase, rebate, and donation records do not unlock or remove product features.

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

## Contributions

Torve is maintained as free software. Issues and pull requests are welcome, but there is no guaranteed response time.

## Accounts, local use, and self-hosting

A Torve account is not a payment gate. It is used for cross-device sync,
device linking, account-backed watch state and preferences, encrypted
connection restoration when explicitly selected, data export, and account
deletion. Device-local browsing, configuration, and playback workflows do not
require a paid plan.

The account and sync service is also free software in [`server/`](server/).
To run it yourself, follow [`server/README.md`](server/README.md): provision
PostgreSQL, copy `server/.env.example` to a private `.env`, set the documented
authentication and encryption secrets, run Alembic migrations, and start the
FastAPI application. Production secrets, signing keys, and user data must never
be committed to this repository.
