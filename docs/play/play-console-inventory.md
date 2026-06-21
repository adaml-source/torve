# Google Play Console Inventory Worksheet

Do not include personal subscriber data, reviewer credentials, signing material,
service account JSON, Firebase production credentials, or backend secrets in
this worksheet.

## App Identity

| Field | Value |
| --- | --- |
| Production version code/name | `[REQUIRED_FROM_PLAY_CONSOLE]` |
| Package name | `com.torve.app` |
| Active countries/regions | `[REQUIRED_FROM_PLAY_CONSOLE]` |
| Category | `[REQUIRED_FROM_PLAY_CONSOLE]` |
| Content rating | `[REQUIRED_FROM_PLAY_CONSOLE]` |
| Privacy URL | `https://torve.app/privacy.html` |
| Account deletion URL | `https://torve.app/account-deletion.html` |
| Website URL | `https://torve.app` |
| Support details | `[REQUIRED_FROM_PLAY_CONSOLE]` |

## Tracks

| Track | Version | Rollout | Status | Pending review | Notes |
| --- | --- | --- | --- | --- | --- |
| Production | `[REQUIRED]` | `[REQUIRED]` | `[REQUIRED]` | `[REQUIRED]` |  |
| Open testing | `[REQUIRED]` | `[REQUIRED]` | `[REQUIRED]` | `[REQUIRED]` |  |
| Closed testing | `[REQUIRED]` | `[REQUIRED]` | `[REQUIRED]` | `[REQUIRED]` |  |
| Internal testing | `[REQUIRED]` | `[REQUIRED]` | `[REQUIRED]` | `[REQUIRED]` |  |

## Subscriptions

| Product ID | Base plan ID | Offer ID | Active status | Auto-renewing/prepaid | Regions | Active subscriber count | New purchases possible |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `[REQUIRED]` | `[REQUIRED]` | `[REQUIRED]` | `[REQUIRED]` | `[REQUIRED]` | `[REQUIRED]` | `[AGGREGATE_ONLY]` | `[REQUIRED]` |

## One-Time Products

| Product ID | Status | Regions | New purchases possible | Historical purpose |
| --- | --- | --- | --- | --- |
| `[REQUIRED]` | `[REQUIRED]` | `[REQUIRED]` | `[REQUIRED]` | `[REQUIRED]` |

## Data Safety

| Category | Current Play Console answer | Expected behavior to compare |
| --- | --- | --- |
| Account info | `[REQUIRED]` | Account email and profile data are processed for auth/account services. |
| Device identifiers | `[REQUIRED]` | Device registration, installation IDs, linking, and device status are processed. |
| Diagnostics | `[REQUIRED]` | Crash reports, diagnostics, request metadata, and service logs may be processed. |
| App activity/watch state | `[REQUIRED]` | Watch state, favorites, and preferences may sync when signed in. |
| User settings/content | `[REQUIRED]` | Account-backed settings, playlist metadata, and integration metadata may sync. |
| Sharing | `[REQUIRED]` | Data is not sold; processors and configured third-party services may receive necessary data. |
| Encryption | `[REQUIRED]` | Transport encryption and encrypted account-mode credential storage should be represented accurately. |
| Deletion | `[REQUIRED]` | In-app deletion where supported and web deletion URL are available. |

## App Access

| Field | Value |
| --- | --- |
| Login required for sync | Yes |
| Reviewer instructions present | `[REQUIRED_FROM_PLAY_CONSOLE]` |
| Reviewer credentials present in Console only | `[REQUIRED_FROM_PLAY_CONSOLE]` |
| Instructions mention local-only use | `[REQUIRED_FROM_PLAY_CONSOLE]` |
| Instructions mention account deletion | `[REQUIRED_FROM_PLAY_CONSOLE]` |

## Policy Warnings

| Warning category | Current status | Notes |
| --- | --- | --- |
| SDK | `[REQUIRED_FROM_PLAY_CONSOLE]` |  |
| Target API | `[REQUIRED_FROM_PLAY_CONSOLE]` |  |
| Permissions | `[REQUIRED_FROM_PLAY_CONSOLE]` |  |
| Subscriptions | `[REQUIRED_FROM_PLAY_CONSOLE]` |  |
| Account deletion | `[REQUIRED_FROM_PLAY_CONSOLE]` |  |
| Data Safety | `[REQUIRED_FROM_PLAY_CONSOLE]` |  |
| App access | `[REQUIRED_FROM_PLAY_CONSOLE]` |  |
