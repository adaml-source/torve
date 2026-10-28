# Torve

Torve is a media companion for Android phones, Google TV, Amazon Fire TV and Windows. Connect your own services and sources to search for media, choose a source, and play it on your devices.

- Website: <https://torve.app>
- Public source and issues: <https://github.com/adaml-source/torve>
- License: [AGPL-3.0-or-later](LICENSE)

This repository contains the current shared Kotlin core, Android/Fire TV and desktop applications, account backend, and website source. The native iPhone, iPad and Apple TV project is maintained privately and is excluded from this snapshot. Android and Fire TV remain free; donations are optional and do not unlock features. Store availability is listed on the website.

## NZB indexers and TorBox

Saved Newznab indexers participate directly in movie and episode source selection, including when multiple indexers are configured. Selecting an NZB result retrieves its NZB file and prepares a playable video through TorBox. See [the Videothek and TorBox integration](docs/videothek-torbox.md) for its request identity, permissions and behavior.

## Source layout

| Path | Purpose |
| --- | --- |
| `shared/` | Shared Kotlin domain, data, presentation and tests |
| `androidApp/` | Android phone, Google TV and Amazon Fire TV variants |
| `desktopApp/` | Compose Desktop application and packaging |
| `server/` | FastAPI account/sync backend and migrations |
| `web/` | Public website and account pages |
| `tools/`, `scripts/` | Development and validation helpers |

## Local development

Use JDK 21 and the configured Gradle toolchains, Android SDK tooling for Android targets, and Python 3.12 for the backend. On Windows use `gradlew.bat` in place of `./gradlew`.

```sh
./gradlew :shared:desktopTest --tests com.torve.data.usenet.ConfiguredUsenetSourcesTest
./gradlew :desktopApp:compileKotlin
./gradlew :androidApp:assembleAmazonTvDebug
```

Google builds need your own Firebase client configuration in `androidApp/src/google/google-services.json`. Keep it outside Git. Provider keys such as `TMDB_API_KEY` are supplied through environment variables or private local configuration. Signing credentials and production services are not required for the shared mocked NZB tests.

See [server/README.md](server/README.md) for backend setup. Copy `server/.env.example` to an untracked `.env` and provide your own secrets and database.

## Accounts and privacy

An account provides cross-device synchronization, device linking, optional connection restoration, data export and account deletion. Users supply their own services, credentials and content rights. Torve does not supply media or subscriptions to third-party sources.

Never commit production configuration, API keys, signing files, service-account credentials, local databases, diagnostic logs, screenshots with account information, or generated installers. The native Apple project and its build/signing tooling are excluded from public updates.

See [CONTRIBUTING.md](CONTRIBUTING.md) and [SECURITY.md](SECURITY.md) for contribution and security reporting guidance. Publishing this source does not sign or release an application, deploy the backend, or update a store listing.
