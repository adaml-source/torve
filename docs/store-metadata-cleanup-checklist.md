# External Store Metadata Cleanup Checklist

This checklist tracks external store work that must happen outside this repository. Do not treat repository cleanup as store metadata cleanup.

Canonical app positioning:

Torve is free software. There are no subscriptions, no paid tiers, no premium features, and no purchase required. Donations are optional and never unlock features.

## Google Play Mobile

- Remove in-app products and subscriptions.
- Remove premium, paywall, purchase, subscription, and paid-tier listing copy.
- Remove paywall and purchase screenshots.
- Verify donation links are hidden unless policy-reviewed.
- Review Firebase key restrictions before any public release. The checked-in Android Firebase files are placeholders; production config must be supplied locally or by protected CI.

## Google TV / Android TV

- Remove TV in-app products and subscriptions.
- Remove premium, purchase, subscription, lifetime, and paid-tier listing copy.
- Remove paywall screenshots.
- Verify donation links are hidden by default.
- Review Firebase key restrictions before any public release. The checked-in Android Firebase files are placeholders; production config must be supplied locally or by protected CI.

## Amazon Appstore / Fire TV

- Remove Amazon IAP products.
- Remove subscription, premium, lifetime, checkout, and restore wording.
- Remove paywall screenshots.
- Verify donation links are hidden by default.

## iOS App Store

- Remove IAP and subscription products.
- Remove paywall screenshots.
- Remove premium, purchase, subscription, and paid-tier listing copy.
- Verify donation links are hidden by default unless policy-reviewed.

## Desktop

- Ensure packaging embeds AGPL-3.0-or-later license text or a pointer to the top-level LICENSE.
- Ensure release signing credentials, appcast credentials, and release-admin secrets are never included.
- If donation links are added later, ensure they are optional, low-pressure, and non-gating.
