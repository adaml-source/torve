# Torve Auth And Access State

Torve uses a unified account system for authentication, session management, device registration, privacy, and account ownership.

Torve is free software. There are no subscriptions, no paid tiers, no premium features, and no purchase required. Donations are optional and never unlock features.

## Current Access Model

For authenticated active accounts, product access is free/default. The backend endpoint `GET /me/access-state` reports free/default access. Paid entitlements, subscriptions, purchases, beta grants, rebates, lifetime ledgers, Stripe state, Paddle state, Google state, Amazon state, Apple state, and donations do not unlock or remove product features.

Remaining restrictions are limited to:

- authentication and session validity
- account active/inactive state
- ownership and privacy boundaries
- device registration and security checks
- parental or profile controls where applicable
- backend availability
- abuse prevention
- technical stability

## Account Model

- Email/password registration via `POST /auth/register`
- JWT access and refresh tokens for session management
- Password hashing
- Device registration on login or device pairing
- Token rotation on refresh

## Compatibility Surfaces

Historical entitlement and purchase endpoints may remain for record keeping, migration, refunds, reconciliation, or source compatibility. They are not the source of product access.

Compatibility endpoints and DTOs must be treated as historical unless a later doc explicitly says otherwise:

- `/me/access-state`
- `/me/entitlements`
- `/me/purchases/*/verify`
- `/me/purchases/restore`
- billing checkout or portal endpoints
- rebate or promo endpoints

Clients must not treat empty entitlement fields, missing subscription records, expired subscriptions, canceled subscriptions, missing receipts, failed purchase verification, deprecated payment endpoints, or `access_tier = "free"` as restricted product access.

## Device Governance

Device management exists for account security, sync correctness, abuse prevention, and technical stability. It is not a paid device-slot system. See [device-management.md](device-management.md).
