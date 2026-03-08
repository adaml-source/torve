# Premium Access Flow

## User Journeys

### New user buys on iOS
1. User installs Torve from App Store
2. User browses free content (search, discover)
3. User taps a premium feature → paywall shown
4. User taps "Create Account" → enters email + password
5. Backend creates account, returns JWT tokens
6. User taps "Buy Torve Pro Lifetime — $9.99"
7. StoreKit presents payment sheet
8. User authenticates with Face ID / Touch ID
9. StoreKit returns verified transaction
10. App sends JWS to backend → POST /purchases/apple/verify
11. Backend verifies, grants torve_pro_lifetime entitlement
12. App updates UI to show premium

### Same user signs in on Fire TV (under device cap)
1. User installs Torve on Fire TV
2. On first launch, shows "Local Profile" with pairing option
3. User navigates to Settings → Account → Sign In
4. User enters email + password from iOS
5. Backend returns JWT tokens + device is registered
6. App calls GET /me/access-state (device-aware)
7. Backend returns torve_pro_lifetime entitlement AND activates this device (under 5-device cap)
8. Fire TV app unlocks premium immediately — no repurchase needed

### Same user signs in on 6th device (cap reached)
1. User already has 5 active devices
2. User installs on 6th device and signs in
3. App calls GET /me/access-state
4. Backend returns: has_entitlement=true, premium_access=false, reason=device_cap_reached
5. App shows Device Limit Reached screen with list of 5 active devices
6. User removes one device from the screen
7. Backend frees slot immediately
8. App auto-retries activation → success
9. Premium unlocks without app restart

### Restore on new phone
1. User gets new Android phone, installs from Play Store
2. User taps Settings → Account → Sign In
3. User enters email + password
4. App calls GET /me/entitlements
5. Backend returns existing entitlement
6. Premium unlocked immediately

## Access Check Priority

```
Backend access-state (entitlement + device activation, source of truth)
    ↓ fallback if offline
Local SQLite subscription record (cached state)
```

**Important:** Premium access requires BOTH a valid entitlement AND an activated device under the 5-device cap. See [device-management.md](device-management.md) for full details.

## Failure Scenarios

| Scenario | Behavior |
|----------|----------|
| Backend unreachable during purchase | Activate locally, retry verification on next launch |
| Backend unreachable during app start | Use cached local subscription state |
| Purchase valid but user not logged in | Activate locally, prompt to sign in for cross-device sync |
| Purchase valid but device cap reached | Entitlement granted, premium_access=false, show Device Limit Reached |
| Device removed by user | Slot freed immediately, current device auto-activates |
| Device stale 45+ days | Auto-expired on next access-state call, slot freed |
| Duplicate receipt submission | Idempotent — returns existing entitlement |
| Receipt from different user | 409 Conflict — "already associated with different account" |
| Refund/revocation | Admin revokes purchase → entitlement status set to "revoked" |
| Password change | Entitlements unaffected — tied to user ID, not password |

## UX Messaging

### After purchase
> "Your Torve Pro access is linked to your Torve account and available across your signed-in devices."

### Restore prompt
> "Sign into your Torve account to restore premium access across devices."

### Offline purchase
> "Purchase activated locally. Sign in to sync across devices."
