# Torve

Torve is a cross-platform media companion for accounts, devices, playback surfaces, watch state, source health, diagnostics, and personal media workflows.

Public source repository: <https://github.com/adaml-source/torve>

Website: <https://torve.app>

Issue tracker: <https://github.com/adaml-source/torve/issues>

## Free-Software Statement

Torve is free software. There are no subscriptions, no paid tiers, no premium features, and no purchase required. Donations are optional and never unlock features.

Torve is licensed under `AGPL-3.0-or-later`.

## Account and Sync Model

Torve can be used locally without a paid plan. An account is required for cross-device sync, device linking, account-backed data, data export, and account deletion.

Login is an account, ownership, privacy, and sync boundary. It must not become a payment, entitlement, donor, supporter, or paid-plan gate.

Privacy policy: <https://torve.app/privacy.html>

Account deletion: <https://torve.app/account-deletion.html>

## Repository Layout

| Path | Purpose |
| --- | --- |
| `backend/` | FastAPI backend, Alembic migrations, backend tests, and maintainer deployment helpers |
| `shared/` | Kotlin Multiplatform shared domain, data, presentation, and test code |
| `androidApp/` | Android mobile, Google TV, and Amazon Fire TV app variants |
| `iosApp/` | iOS app shell and Swift UI integration around the shared KMP framework |
| `desktopApp/` | Compose Desktop app and desktop packaging code |
| `docs/` | Architecture notes, release notes, testing plans, and historical implementation docs |
| `release/` | Store and release checklists; not a deployment trigger |
| `scripts/` | Local utility and smoke-test scripts |

## Local Development

Install a JDK compatible with the Gradle build, Android SDK tooling for Android tasks, and Python 3.12 for the backend.

Common checks:

```bash
./gradlew :shared:compileKotlinMetadata
./gradlew :shared:allTests
./gradlew :desktopApp:test

cd backend
pytest
```

Android and desktop builds may require platform SDKs or host-specific tooling. iOS checks require macOS and Xcode.

## Build Matrix

| Area | Local check |
| --- | --- |
| Backend | `cd backend && pytest` |
| Shared KMP metadata | `./gradlew :shared:compileKotlinMetadata` |
| Shared tests | `./gradlew :shared:allTests` |
| Android mobile | `./gradlew :androidApp:assembleGoogleMobileDebug` |
| Google TV | `./gradlew :androidApp:assembleGoogleTvDebug` |
| Amazon Fire TV | `./gradlew :androidApp:assembleAmazonTvDebug` |
| Desktop | `./gradlew :desktopApp:build` |
| iOS | Run shared iOS Gradle tasks from the repo root, then Xcode checks on macOS |

## Backend Setup

The public backend source lives in `backend/`.

```bash
cd backend
python -m venv venv
source venv/bin/activate
python -m pip install --upgrade pip
pip install -r requirements.txt
cp .env.example .env
alembic upgrade head
pytest
```

Use local-only dummy values for development. Production deployment, production secrets, provider credentials, signing material, service accounts, and database URLs are maintainer-only.

## Secret-Safe Development

Never commit real secrets, signing material, local runtime state, generated release artifacts, logs, dumps, or database files.

Do not commit:

- `.env` or `.env.*`
- `keystore.properties`
- `local.properties`
- service-account JSON
- private keys, certificates, provisioning profiles, or signing files
- production database URLs, webhook secrets, API keys, or admin tokens
- generated APK, AAB, MSI, ZIP, archive, log, dump, or database artifacts

The tracked examples must contain placeholders only.

## Donations

Donations are optional and never unlock features. Store-distributed builds hide donation links by default unless they have been policy-reviewed.

This repository does not currently publish a maintainer-approved donation destination in `.github/FUNDING.yml`.

## Contributing

Issues and pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md).

Contributions must preserve the free-software product model: no subscriptions, no paid tiers, no premium features, no purchase requirements, no donor-only functionality, and no supporter benefits.

## License

Torve is licensed under `AGPL-3.0-or-later`. See [LICENSE](LICENSE).

## Store Availability Status

Store availability can vary by platform and review status. Check <https://torve.app> for the current public availability status.

Store packaging and release checklists in this repository are documentation and maintainer tooling only. They do not publish releases, sign builds, upload store artifacts, or deploy services by themselves.
