# Cross-Platform Test Plan

## Backend Tests (Automated)

Run from `server/` directory:
```bash
pip install pytest pytest-asyncio httpx aiosqlite
pytest tests/ -v
```

### Auth Tests (`test_auth.py`)
- [x] Register new user → returns tokens
- [x] Register duplicate email → 409
- [x] Login success → returns tokens
- [x] Login wrong password → 401
- [x] GET /me authenticated → user info + empty entitlements
- [x] GET /me unauthenticated → 401
- [x] Refresh token → new access token
- [x] Logout → revokes session

### Entitlement Tests (`test_entitlements.py`)
- [x] Apple verify → grants lifetime entitlement
- [x] Apple verify idempotent → same result on retry
- [x] Apple verify wrong product → 400
- [x] Google verify dev mode → grants entitlement
- [x] Google verify idempotent
- [x] Amazon verify dev mode → grants entitlement
- [x] Entitlements after purchase → premium_access = true
- [x] Cross-platform: buy on iOS, check on Android → premium
- [x] Different user same receipt → 409 conflict
- [x] Purchase history
- [x] Verify unauthenticated → 401
- [x] Restore purchases (no existing) → premium_access = false
- [x] Lifetime overrides monthly

### Device Governance Tests (`test_device_policy.py`)
- [x] First device activates on premium account
- [x] Activate 2nd through 5th device
- [x] 6th device denied with reason "device_cap_reached"
- [x] Repeated activation of same device is idempotent (no extra slot consumed)
- [x] Remove active device frees slot immediately
- [x] Remove already-removed device is safe/idempotent
- [x] Swap limit enforced after 3 qualifying removals in rolling 30 days
- [x] Premium owned but device blocked → correct state returned
- [x] No entitlement → no access
- [x] Restore on 2nd device under cap succeeds
- [x] Purchase on full account gives entitlement but not device access
- [x] GET /me/access-state returns device-aware payload
- [x] GET /me/devices shows correct current device flag
- [x] POST /me/devices/activate-current is idempotent
- [x] PATCH /me/devices/{id} renames safely
- [x] Cross-user device removal denied
- [x] Remove + activate succeeds immediately

## Manual Integration Tests

### iOS → Android Cross-Platform
1. Install iOS build
2. Create Torve account (email + password)
3. Purchase com.torve.pro.lifetime via StoreKit
4. Verify premium unlocked on iOS
5. Install Android build
6. Sign in with same email/password
7. Verify premium unlocked without repurchase
8. Check /me/entitlements shows source_store = "apple"

### Android → Fire TV Cross-Platform
1. Install Google Play mobile build
2. Sign into Torve account
3. Purchase com.torve.pro.lifetime via Play Billing
4. Verify premium unlocked
5. Install Amazon Fire TV build (sideload or Appstore)
6. Sign in with same Torve account
7. Verify premium unlocked without repurchase

### Fire TV → iOS Cross-Platform
1. Install Amazon Fire TV build
2. Sign into Torve account
3. Purchase com.torve.pro.lifetime.amazon via Amazon IAP
4. Verify premium unlocked on Fire TV
5. Install iOS build
6. Sign in with same Torve account
7. Verify premium unlocked

### Offline Purchase Recovery
1. Enable airplane mode on Android
2. Complete Play Billing purchase (will succeed locally)
3. Verify premium activated locally
4. Disable airplane mode
5. Force refresh (pull to refresh or restart)
6. Verify backend verification completes
7. Sign in on another device — verify cross-device sync

### Restore on New Device
1. Factory reset or get new device
2. Install app
3. Sign into existing Torve account
4. Verify premium unlocked from backend entitlement

### Password Change
1. Purchase premium on device A
2. Change password
3. Device A should remain logged in (existing tokens still valid)
4. Sign in on device B with new password
5. Verify premium available on device B

### Device Limit Reached Flow
1. Sign into 5 devices with same premium account
2. Sign into 6th device
3. Verify startup shows Device Limit Reached screen (not premium active)
4. Verify active device list shows 5 real devices
5. Remove one device from the screen
6. Verify current device activates immediately
7. Verify premium unlocks without app restart
8. Verify swap count decremented

### Manage Devices Screen
1. Open Settings → Manage Devices
2. Verify shows correct active/inactive device counts
3. Verify current device marked
4. Rename a device → verify name updates
5. Remove a non-current device → verify slot freed
6. Verify swap limit shown correctly

### Stale Device Auto-Expiry
1. Set DEVICE_STALE_DAYS=0 in test env
2. Wait a moment, then hit /me/access-state
3. Verify stale_devices_pruned > 0
4. Verify freed slot available for new device

### Refund / Revocation
1. Purchase premium
2. Admin revokes purchase via database
3. Client fetches /me/entitlements
4. Verify premium_access = false
