# Contributing to Torve

Torve is free software. There are no subscriptions, no paid tiers, no premium features, and no purchase required. Donations are optional and never unlock features.

## Local Setup

Install:

- JDK compatible with the Gradle build
- Android SDK tooling for Android tasks
- Python 3.12 and Postgres for backend work
- Xcode on macOS for iOS work

Backend:

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

Shared and app checks:

```bash
./gradlew :shared:compileKotlinMetadata
./gradlew :shared:allTests
./gradlew :androidApp:testGoogleMobileDebugUnitTest
./gradlew :androidApp:testGoogleTvDebugUnitTest
./gradlew :androidApp:testAmazonTvDebugUnitTest
./gradlew :desktopApp:test
```

## Platform Expectations

Run the checks that match the files you touched.

- Backend changes should include `cd backend && pytest`.
- Shared KMP changes should include shared metadata and relevant shared tests.
- Android changes should include the relevant compile, unit test, or assemble task for the affected variant.
- Desktop changes should include `:desktopApp:test` or `:desktopApp:build` when practical.
- iOS changes require macOS/Xcode validation when they touch Swift app behavior.

If a host cannot run a platform-specific check, say so in the pull request and describe what was run instead.

## Product Invariants

Do not add:

- billing flows
- subscriptions
- purchase requirements
- paid tiers
- premium feature gates
- donor-only features
- supporter benefits
- login checks that behave like payment or entitlement gates

Login may protect sync, device linking, account-backed data, data export, account deletion, ownership, privacy, anti-abuse, and technical stability. It must not become monetization.

## Secrets and Artifacts

Never commit real credentials or local runtime artifacts.

Do not commit:

- `.env` or `.env.*`
- signing keys, certificates, provisioning profiles, or keystores
- service-account JSON
- production database URLs, API keys, webhook secrets, or admin tokens
- generated APK, AAB, MSI, ZIP, archive, log, dump, or database files

Use placeholder-only examples and dummy CI values.

## Licensing

By contributing, you agree that your contribution is provided under Torve's `AGPL-3.0-or-later` license.

If you add third-party code, assets, or dependencies, include compatible license information and avoid assets whose terms prevent redistribution, modification, or public source distribution.
