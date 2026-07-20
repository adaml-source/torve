# Premium Access Flow

Obsolete after free-software conversion. Retained for historical reference only.

Torve no longer has premium access, paid tiers, subscriptions, purchase-required flows, trial gates, paid device caps, or donation-based access. Donations are optional and never unlock features.

Current behavior:

- authenticated active accounts receive free/default product access
- `/me/access-state` reports free/default access
- missing entitlements, missing purchases, expired subscriptions, canceled subscriptions, failed purchase verification, and restore state do not block product features
- historical billing and entitlement records remain only for compatibility, migration, refunds, reconciliation, or audit context

Use [auth-and-entitlements.md](auth-and-entitlements.md) and [device-management.md](device-management.md) for the current model.
