# Torve Auth & Entitlement System

## Overview

Torve uses a unified account system where premium access is tied to a Torve account, not a specific device or app store. A user who purchases Torve Pro on any supported platform can sign into the same account on any other device and get immediate premium access.

## Architecture

```
┌─────────────┐     ┌──────────────┐     ┌───────────────┐
│  iOS App     │     │  Android App │     │  Fire TV App  │
│  StoreKit 2  │     │  Play Billing│     │  Amazon IAP   │
└──────┬───────┘     └──────┬───────┘     └──────┬────────┘
       │                    │                    │
       │  transaction JWS   │  purchase token    │  receipt ID
       │                    │                    │
       └────────────────────┼────────────────────┘
                            │
                     ┌──────▼──────┐
                     │ Torve API   │
                     │  /purchases │
                     │  /verify    │
                     └──────┬──────┘
                            │
                     ┌──────▼──────┐
                     │  Store API  │
                     │  (Apple/    │
                     │  Google/    │
                     │  Amazon)    │
                     └──────┬──────┘
                            │
                     ┌──────▼──────┐
                     │ Entitlement │
                     │  Granted    │
                     └──────┬──────┘
                            │
                     ┌──────▼──────┐
                     │ All devices │
                     │ see premium │
                     └─────────────┘
```

## Account Model

- **Email/password registration** via POST /auth/register
- **JWT access + refresh tokens** for session management
- **Bcrypt** password hashing
- **Device registration** on every login
- **Token rotation** on refresh (old refresh token is revoked)

## Entitlement Resolution

Premium access requires BOTH entitlement ownership AND device activation.

1. Client calls GET /me/access-state (primary startup endpoint)
2. Backend resolves entitlement ownership:
   - If any `torve_pro_lifetime` entitlement is active → `has_entitlement = true`
   - Else if any `torve_pro_monthly` entitlement is active and not expired → `has_entitlement = true`
   - Else → `has_entitlement = false`
3. Backend runs device activation engine:
   - Prunes stale devices (inactive 45+ days)
   - If device already active → `premium_access = true`
   - If under 5-device cap → activates device, `premium_access = true`
   - If at cap → `premium_access = false`, `reason = device_cap_reached`
4. Client updates local state based on `premium_access`
5. If `has_entitlement = true` but `premium_access = false` → shows Device Limit Reached flow

See [device-management.md](device-management.md) for full device governance details.

## Purchase Flow

1. User signs into Torve account (or creates one)
2. User taps "Buy" → native store purchase flow (StoreKit / Play Billing / Amazon IAP)
3. Store returns purchase confirmation to app
4. App sends verification data to Torve backend:
   - iOS: JWS signed transaction
   - Google Play: product_id + purchase_token
   - Amazon: receipt_id + amazon_user_id + product_id
5. Backend verifies with the respective store API
6. Backend creates purchase record + grants entitlement
7. Backend returns entitlement state to app
8. App unlocks premium

## Idempotency

- Purchase verification is idempotent: submitting the same receipt twice returns the existing entitlement
- Unique constraint on (store, original_transaction_id) prevents duplicate purchases
- Unique constraint on (user_id, entitlement_key, source_purchase_id) prevents duplicate entitlements

## Offline Behavior

- If the backend is unreachable during purchase, the app activates locally
- On next app start, the app retries backend verification
- Local subscription state serves as cache; backend is source of truth when reachable
