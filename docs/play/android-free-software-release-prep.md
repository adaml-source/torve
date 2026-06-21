# Torve Android Free-Software Release Prep

Date: 2026-06-21

This document is tracked placeholder-safe release preparation material for the
Google Play free-software update. Do not put reviewer credentials, signing
material, service account JSON, keystore properties, or production secrets in
this file.

## Authentication Boundary Matrix

| Operation | Boundary | Notes |
| --- | --- | --- |
| Local browsing and local configuration | Local/signed-out allowed where supported | Must not require billing, purchase, entitlement, donation, or account state. |
| Login | Local/signed-out allowed | Creates authenticated session for sync/account services. |
| Signup | Local/signed-out allowed | Creates account and may register the current device. |
| Token refresh | Technical capability only | Requires a valid refresh token; does not grant anonymous sync. |
| Sync upload/download | Authenticated account required, owner-only | Includes account settings, supported preferences, playlists, favorites, and account-backed app data. |
| Watch state | Authenticated account required, owner-only | `/me/watch_state/*` rejects unauthenticated requests and isolates rows by user. |
| Preferences | Authenticated account required, owner-only | `/me/account-settings` stores only safe keys; secret-bearing keys are stripped. |
| Device linking | Authenticated account required, owner-only | Pairing code claim requires both devices to belong to the authenticated account. |
| Device management | Authenticated account required, owner-only | Device list, revoke, heartbeat, rename, and delete are scoped to the authenticated account. |
| Integrations | Authenticated account required, owner-only | Account-mode credentials require a registered device for restore; device-only credentials remain local. |
| Cloud backup | Authenticated account required, owner-only | Account-backed backup/restore must not be anonymous. |
| Account export | Authenticated account required, owner-only | No public backend export route was found in this source snapshot; keep any future route owner-only. |
| Account deletion | Authenticated account required, owner-only | Mobile should expose in-app deletion; TV should link to `https://torve.app/account-deletion.html` where in-app deletion is impractical. |
| Diagnostics upload | Authenticated account required for account-attached reports; technical capability otherwise | Bug reports must not include credentials or sensitive user data. |
| Bug reports | Authenticated account required by current backend support route | Public issue reports must not include credentials or sensitive user data. |
| Admin tools | Admin-only | Admin secrets are maintainer-only and must not ship in app builds. |

## Billing Cleanup Status

- `googleMobile` uses `HAS_BILLING=false`.
- `googleMobile` uses `SUPPORTS_TV_BILLING=false`.
- `TORVE_SHOW_DONATION_LINKS=false`.
- `TORVE_DONATION_URL=""`.
- No Google Play Billing dependency is declared in `androidApp/build.gradle.kts`.
- No `BillingClient` initialization was found in the Android source.
- Historical purchase, subscription, premium, and entitlement names remain in compatibility code and tests, but must not determine feature access.

## Versioning

- Source base version code: `82`.
- Google mobile version code formula: `10000 + baseVersionCode`.
- Current Google mobile source version code: `10082`.
- Current version name: `1.0.72`.
- Production Play version code: maintainer input required.
- Approved release version name: maintainer input required.

Do not change version code or version name until the current Play production
version code and approved release version are supplied. The next version code
must be strictly greater than production.

## Firebase

Use production Firebase configuration only through local untracked files or
protected CI secret injection. Public placeholder files must remain
publication-safe. The tracked `google-services.json` files require maintainer
review before public release if they contain production project identifiers.

## Account Deletion

Required final URL: `https://torve.app/account-deletion.html`

Mobile builds should expose a prominent signed-in account deletion path. TV
builds should surface the website deletion URL where in-app account deletion is
not practical.

## Upgrade Test Checklist

- Upgrade from the previous paid production build to the free build.
- Confirm user data is preserved.
- Confirm account remains usable.
- Confirm sync works after update.
- Confirm startup is not blocked by subscription state.
- Confirm no purchase restore is required.
- Confirm previously paid users receive no special access because all users have full access.
- Confirm no billing UI appears.
- Confirm removed billing state does not crash app startup or settings.

## Build Commands

Run from repository root:

```powershell
.\gradlew :shared:allTests
.\gradlew :androidApp:testGoogleMobileDebugUnitTest
.\gradlew :androidApp:assembleGoogleMobileDebug
.\gradlew :androidApp:bundleGoogleMobileRelease
.\gradlew :androidApp:lintGoogleMobileRelease
.\gradlew :androidApp:dependencies --configuration googleMobileReleaseRuntimeClasspath
```

Release signing material must remain outside the repository or be injected by
protected CI.
