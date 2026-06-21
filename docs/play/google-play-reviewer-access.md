# Google Play Reviewer Access

Do not commit real reviewer credentials. Store real credentials only in Play
Console reviewer instructions.

## Test Account

- Email: `[PLAY_REVIEWER_EMAIL_PLACEHOLDER]`
- Password: `[PLAY_REVIEWER_PASSWORD_PLACEHOLDER]`
- Region limitations: `[NONE_OR_DESCRIBE_LIMITATIONS]`
- Device limitations: Android phone/tablet build; TV flows use the separate TV build where applicable.

## Login Steps

1. Install the Google Play mobile build.
2. Open Torve.
3. Use local-only surfaces signed out where supported.
4. Open account or sync.
5. Sign in with the Play Console reviewer credentials.
6. Verify the account opens sync and account-backed services.

## Features Requiring Authentication

- Cross-device sync.
- Device linking.
- Account-backed watch state and preferences.
- Account-backed integrations and cloud backup where supported.
- Account data controls.
- Account deletion.

## Sync Test

1. Sign in on the Android mobile build.
2. Change a supported account preference.
3. Trigger sync or revisit the account-backed surface.
4. Sign in on another test device if available.
5. Confirm the account-backed state is scoped to the same account only.

## Account Deletion Test

1. Sign in with a disposable reviewer account.
2. Open settings/account deletion.
3. Follow the in-app deletion path.
4. If in-app deletion is unavailable on a device, use `https://torve.app/account-deletion.html`.
5. Confirm post-deletion login fails for the deleted account.

## Free-Software Notes For Reviewers

Torve is free software. There are no subscriptions, no paid tiers, no premium
features, and no purchase required. Sign in is required for sync, device
linking, and account-scoped cloud data. Donations are optional and never unlock
features.
