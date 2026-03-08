# Device Management

## Overview

Torve Pro premium access requires BOTH:
1. A valid premium entitlement (from any store purchase)
2. The current device being activated under the 5-device policy

Owning premium does NOT automatically unlock every device.

## Device Policy

| Rule | Value | Config Var |
|------|-------|------------|
| Max active devices per account | 5 | `DEVICE_MAX_ACTIVE` |
| Stale device auto-expiry | 45 days | `DEVICE_STALE_DAYS` |
| Max slot-freeing removals per 30 days | 3 | `DEVICE_MAX_SWAPS_PER_30D` |

There is no TV-specific sub-limit. Valid example: 1 phone + 1 tablet + 3 TVs = 5 devices.

## Definitions

- **Entitlement ownership**: User has a verified purchase granting `torve_pro_lifetime` or `torve_pro_monthly`
- **Device activation**: Current device has `activated_at` set and `removed_at` is null
- **Premium access on device**: `has_entitlement AND device_active`
- **Active device count**: Number of devices with `activated_at IS NOT NULL AND removed_at IS NULL`
- **Stale device**: Active device whose `last_seen_at` is older than 45 days

## Access State Contract

The primary startup endpoint is `GET /me/access-state`. It returns:

```json
{
  "user": { "id": "...", "email": "..." },
  "premium": {
    "has_entitlement": true,
    "premium_access": false,
    "reason": "device_cap_reached",
    "entitlements": [...]
  },
  "device": {
    "id": "...",
    "name": "Bedroom Fire TV",
    "platform": "amazon_fire_tv",
    "device_type": "tv",
    "is_active": false,
    "active_device_count": 5,
    "max_active_devices": 5
  },
  "device_limit": {
    "cap_reached": true,
    "swaps_remaining": 1,
    "stale_devices_pruned": 0,
    "active_devices": [...]
  }
}
```

### Reason Values

| Reason | Meaning |
|--------|---------|
| `active_lifetime_and_device_active` | Lifetime entitlement + device active |
| `active_monthly_and_device_active` | Monthly entitlement + device active |
| `no_entitlement` | No premium entitlement |
| `device_cap_reached` | Has entitlement but device cap prevents activation |
| `entitlement_revoked` | Entitlement was revoked |

## Flows

### Startup Flow
1. App starts, user is signed in
2. `SubscriptionViewModel.loadSubscription()` calls `refreshFromBackendDetailed()`
3. Backend calls `GET /me/access-state` which runs device activation engine
4. If active: premium unlocked
5. If `device_cap_reached`: app shows Device Limit Reached screen
6. If no entitlement: free tier shown

### Purchase Flow
1. User completes native store purchase
2. App sends verification to backend (`/purchases/apple/verify`, `/purchases/google/verify`, `/purchases/amazon/verify`)
3. Backend verifies, grants entitlement, attempts device activation
4. If under cap: `premium_access = true`, app unlocks premium
5. If cap reached: `premium_access = false`, `entitlements` is non-empty, app shows Device Limit Reached

### Device Removal + Auto-Activation
1. User views active devices on Device Limit Reached screen
2. User removes an existing device
3. Backend frees slot immediately
4. `DeviceGovernanceViewModel` auto-calls `activateCurrentDevice()`
5. Current device activates instantly
6. UI observes `premiumAccess = true` and auto-navigates back
7. Premium unlocks without app restart

### Stale Device Auto-Expiry
1. On any `try_activate_device` call, backend prunes devices not seen in 45+ days
2. Pruned devices get `removed_at` set and `removal_reason = auto_expired`
3. Auto-expired devices do NOT count toward the swap limit
4. Freed slots are immediately available

## API Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/me/access-state` | Primary startup gating |
| GET | `/me/devices` | List managed devices |
| POST | `/me/devices/activate-current` | Attempt device activation |
| POST | `/me/devices/{id}/remove` | Remove device (swap-limited) |
| PATCH | `/me/devices/{id}` | Rename device |

## Swap Limit

Only user-initiated slot-freeing removals count toward the swap limit. Auto-expired devices do not. The limit resets on a rolling 30-day window.

## Audit Trail

All device lifecycle events are logged in `device_activation_events`:
- `registered`, `activated`, `removed`, `auto_expired`, `reactivated`, `denied_cap_reached`, `denied_swap_limit`
