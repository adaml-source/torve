# Client Security Contract

Torve clients are treated as untrusted. The backend is the only authority for
premium access, purchase verification, resolver authorization, device slot
state, and playback handoff.

## Access State

Clients must use `GET /me/access-state` before premium-sensitive work. Send the
active installation id either as:

- `X-Torve-Installation-Id: <installation_id>`
- or `?installation_id=<installation_id>`

The response fields `has_premium_access`, `access_tier`, `source`,
`is_device_activated`, `device_block_reason`, and `needs_verification` are the
only supported client gating inputs. Clients must not send or trust local
`hasPremium`, `purchaseType`, or store claims as proof of access.

Access tiers are `free`, `beta`, `premium_subscription`, and
`premium_lifetime`. A `beta` tier is backed by an active server-side beta grant,
not by a paid store entitlement.

## Resolver Calls

Resolver endpoints require:

- Bearer auth.
- An active registered device via `X-Torve-Installation-Id`.
- Backend-confirmed premium access for that user/device.

Missing or unauthorized devices return stable sanitized errors:

- `device_required`
- `device_not_authorized`
- `premium_required`
- `rate_limited`

Resolver responses never include provider credentials, debrid tokens, or raw
upstream URLs. Playback URLs are Torve handoff URLs containing short-lived
server-signed tokens.

## Playback Handoff

Handoff tokens are backend-signed and short-lived. They include:

- `user_id`
- `device_id` when the resolver caller supplied a registered device
- `content_id`
- `stream_id`
- `issued_at`
- `expires_at`
- `jti`

Clients should treat handoff URLs as temporary playback URLs and should refresh
through the resolver flow if they expire.

## Trust Signals

Clients should POST trust context to `POST /me/trust-signals` after login,
foreground, and app update.

Payload fields:

- `platform`
- `appVersion`
- `buildNumber`
- `distributionChannel`
- `packageName` or `applicationId`
- `installerPackage`
- `signingCertificateSha256`
- `isDebuggable`
- `isEmulator` or `likelyVirtualDevice`
- `hasKnownHookingIndicators`
- `hasKnownRootIndicators`
- `integrityProvider`
- `integrityToken` when available
- `generatedAtEpochMillis`

Google Play builds may provide Play Integrity tokens. Amazon and desktop builds
are not rejected merely because Play Integrity is absent. Trust signals are risk
signals only and never grant premium.

## Purchase Verification

Store purchase success in the client is pending until backend verification or a
verified webhook grants an entitlement. Sensitive failures use stable sanitized
codes including:

- `already_verified`
- `config_missing`
- `service_account_failure`
- `upstream_unreachable`
- `product_mismatch`
- `not_verified`
- `duplicate_active_entitlement`
- `cross_store_conflict`
- `entitlement_expired`
- `entitlement_revoked`
- `unknown`

Clients must show generic retry/support UI for these codes and must never grant
premium locally.
