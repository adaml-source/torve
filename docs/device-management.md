# Device Management

Torve device management is for account security, sync correctness, abuse prevention, and technical stability. It is not a paid device-slot system.

Torve is free software. There are no subscriptions, no paid tiers, no premium features, and no purchase required. Donations are optional and never unlock features.

## Device Policy

| Rule | Default | Purpose |
| --- | ---: | --- |
| Max active devices per account | `DEVICE_MAX_ACTIVE` | Abuse prevention and sync stability |
| Stale device auto-expiry | `DEVICE_STALE_DAYS` | Remove unused devices from account state |
| Max user removals per 30 days | `DEVICE_MAX_SWAPS_PER_30D` | Prevent churn abuse |

These limits are technical and security controls. They must not be described as paid device caps, premium slots, or subscription benefits.

## Definitions

- **Device registration**: backend records a device identity for account, sync, and security flows.
- **Active device**: current device has `activated_at` set and `removed_at` is null.
- **Active device count**: number of devices with `activated_at IS NOT NULL AND removed_at IS NULL`.
- **Stale device**: active device whose `last_seen_at` is older than the configured stale-device window.

## Access State Contract

The primary startup endpoint is `GET /me/access-state`. It returns account and device state. Product access is free/default for authenticated active accounts.

Device limit responses should be handled as technical/security states, not paid-access states.

## Flows

### Startup

1. App starts and checks the current session.
2. App refreshes account and device state from `GET /me/access-state`.
3. If the account is active and the device is valid, product features remain available.
4. If a device safety or abuse-prevention limit is reached, the client should show device-management copy that avoids paid-slot language.

### Device Removal And Auto-Activation

1. User opens device management.
2. User removes an old or unused device.
3. Backend frees the device record if ownership and swap-limit checks pass.
4. Current device can activate again if technical policy permits it.

### Stale Device Auto-Expiry

1. Backend prunes devices not seen within the configured stale-device window.
2. Pruned devices get `removed_at` set and `removal_reason = auto_expired`.
3. Auto-expired devices do not count toward the user-initiated swap limit.

## API Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/me/access-state` | Account and device startup state |
| GET | `/me/devices` | List managed devices |
| POST | `/me/devices/activate-current` | Activate current device if technical policy permits |
| POST | `/me/devices/{id}/remove` | Remove an owned device |
| PATCH | `/me/devices/{id}` | Rename an owned device |

## Audit Trail

Device lifecycle events are logged in `device_activation_events`, including registration, activation, removal, auto-expiry, reactivation, and technical denials.
