# Profiles, Family policy, and shared connections

Family controls remain optional. The account backend now owns the enabled state, child profile rules, age limits, blocked title identities, approved source identifiers, and the parent PIN verifier. All reads and mutations are account scoped.

## Synchronization

Clients load `/me/family` before exposing a signed-in profile. They refresh Family policy while active every 15 seconds and when returning to the foreground. Profile definitions and navigation preferences use the existing household record sync, refreshed at profile selection and approximately every 30 seconds while active. Device selection and temporary adult unlocks do not sync.

Family updates require a short-lived parent management grant and the current server version. A stale save receives 409 and refreshes the local policy instead of overwriting another device's changes. Policy or PIN changes invalidate local content state and adult unlocks when received. Server PIN verification has a shared attempt limit across devices.

Previously enabled device-only Family settings migrate to the account. A failed migration retains the restrictive local policy and retries. Offline devices retain cached child restrictions; adult management needs online verification. A new device without a verified policy fails closed. An older application without this policy client cannot enforce the new restrictions on direct third-party sources; update all devices.

Saved list bookmarks, pinned list rows, and custom Home sections travel in profile-scoped household navigation records. Outbox entries and removal tombstones survive interrupted sync. Trakt list likes continue to use Trakt itself.

## Connection copying

Settings → Profiles & Family → Copy connections lets the user choose source and destination profiles, selected services, account synchronization, and whether existing destination connections may be replaced. When Family is enabled, copying requires parent verification. Account mode encrypts credentials through the existing integration storage; device-only copying uses secure local storage. No Torve watch history, favorites or watchlist are copied.

Trakt copies share the connected Trakt account, including its external history and lists. Account copies follow the same encrypted Trakt credential record. Token rotations use a compare-and-set update, and failed publication is retained in secure local storage for retry. Local copies sharing the old refresh token receive its rotation without affecting independently connected accounts.

## PIN recovery

Forgot parent PIN sends a six-digit code only to the verified account owner's email. Codes expire after 10 minutes, allow five failed attempts, and are single-use. Resending is limited to once per minute. Recovery is bound to the owner's current email; changing that email invalidates an older code. Resetting the PIN revokes earlier parent grants. The backend stores only a salted PIN verifier and an HMAC recovery proof, never a raw PIN or recovery code in ordinary settings.

## Backend enforcement and release evidence

The backend protects Family mutations, protected profile definition writes, and connection credentials. Server media resolvers require adult authorization when Family is enabled because arbitrary stream/NZB URLs have no trusted title certification. Direct provider requests continue to use the shared client's source, age, title, and playback checks. Existing account-level sensitive-content rules still apply.

This checkout's earlier migration history differs from production. The additive local migration is `0037_family_account_policies.py` after local `0036`; the production equivalent is `0044_family_account_policies.py` after production `0043`. The deployment in `output/profile-sync-2026-10-22/backend-stage` preserves the newer production device authorization and other existing server changes rather than overwriting them with this older checkout. See the deployment manifest and verification receipts in that task directory.

## Profile rating and connection follow-up (iOS 244)

The older profile editor's Max Rating now activates the same certification boundary even when optional Family controls are off. G permits known US G/TV-G/TV-Y certificates, hides unclassified titles, and does not use review scores or genres as age evidence. The most restrictive known classification wins. Cloud source restrictions and blocked titles remain in effect. Changing the active rating invalidates retained presentation and playback grants. NC-17 retains the existing unrestricted-profile meaning. PIN protection of management and adult switching remains an opt-in Family control.

Home, search, details, recommendations and the watching hub share this verifier. Detail pages show the supplied content classification; absent classification is labeled Not rated. Old cached detail records are enriched with certification evidence before presentation.

Settings validation, health probes and playback now refresh Trakt through one coordinator. Account copying marks the source as account-backed and hydrates the canonical credential at the destination. OAuth client identity is copied with a custom connection, and backend token rotations retain it while normalizing snake_case and camelCase fields. A provider-revoked credential may still need one new authorization.

OMDb copying migrates the older plaintext preference to secure storage, carries rating-display preferences, and hydrates account-only copies immediately. Saving OMDb now uses the existing consent-controlled account integration backup callback rather than re-creating its legacy plaintext preference. Copying an existing activated key does not request a new OMDb activation.

Release evidence is in `output/profile-auth-repair-2026-10-22`. Native iOS/tvOS compilation and focused shared/backend contracts are recorded separately from UI rendering. The authorization screen was rendered and visually reviewed. Automated native touch checks were unavailable because the Mac desktop was signed out; this is not reported as an XCTest pass.
