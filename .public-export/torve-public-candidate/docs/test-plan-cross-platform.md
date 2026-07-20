# Cross-Platform Test Plan

This test plan reflects the free-software access model.

Torve is free software. There are no subscriptions, no paid tiers, no premium features, and no purchase required. Donations are optional and never unlock features.

## Backend Tests

Run from `server/`:

```bash
pytest
```

Required coverage:

- registration, login, refresh, logout, and authenticated `/me`
- `/me/access-state` returns free/default access for authenticated active accounts
- inactive, suspended, deleted, or unauthenticated accounts remain restricted for account/security reasons
- historical entitlements, purchases, rebates, beta grants, and donations do not unlock or remove product features
- device registration, removal, stale expiry, and swap limits behave as technical/security controls
- public tests run without private secrets

## Shared KMP Tests

Run from repo root:

```bash
./gradlew :shared:compileKotlinMetadata
./gradlew :shared:allTests
```

Required coverage:

- `access_tier = "free"` means normal product access
- missing, expired, canceled, or empty entitlement/subscription fields do not block features
- purchase verification, restore, checkout, portal, rebate, promo, lifetime, admin, community, and donation states do not affect access
- stream filters work for everyone
- auth, ownership, privacy, backend availability, device capability, abuse-prevention, and technical-stability states still behave correctly

## Platform Smoke Tests

Android mobile / Google Play:

- no Play Billing startup requirement
- no paywall route in normal navigation
- donation links hidden by default
- stream playback, downloads, filters, playlists, watched state, sync, integrations, diagnostics, AI search, and add-ons are not paid-gated

Android TV / Google TV:

- no Play Billing access requirement
- no subscription section or focus target
- donation links hidden by default
- remote focus does not point to removed billing controls

Fire TV / Amazon:

- no Amazon IAP or Stripe checkout access requirement
- no restore-purchase requirement
- donation links hidden by default
- remote focus does not point to removed billing controls

iOS:

- no StoreKit access requirement
- no receipt or restore requirement
- no paywall in normal navigation
- donation links hidden by default

Desktop:

- no Stripe checkout or customer portal requirement
- no premium overlays
- no paid license activation
- optional donation UI, if enabled later, is non-gating

## Manual Checks

- Account and privacy surfaces remain available and accurate.
- Device management copy does not imply paid slots.
- Store-distributed builds do not show donation links unless policy-reviewed.
- Legal/about copy matches `AGPL-3.0-or-later`.
