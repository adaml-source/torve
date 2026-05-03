# Account Restore Behavior

## Overview

When a user signs in on a new device or after sign-out, the Android client restores account-backed data from the backend. There are three independent restore paths:

1. **Integration restore** (API keys, service credentials)
2. **Addon/extension restore** (community extensions)
3. **Playlist restore** (M3U and Xtream channel sources)

Each path runs independently. A failure in one does not block the others.

## Integration restore

- Client calls `GET /me/integrations` to get the list
- For each account-mode integration, calls `GET /me/integrations/{type}/credentials` to retrieve decrypted credentials
- Credentials are stored locally and the integration is activated
- Requires premium access (403 if not entitled)

## Addon/extension restore

- Client calls `GET /me/addons` to get the list
- For each addon, the client installs it locally using the manifest URL
- Respects `is_enabled` and `sort_order` from the server
- Local addons not on the server are pushed via `POST /me/addons`
- Write operations require premium access

## Playlist restore

- Client calls `GET /me/playlists` to get the playlist list
- M3U playlists are restored using the stored URL and EPG URL
- Xtream playlists require an additional credential fetch:
  - Client calls `GET /me/playlists/{playlist_id}/credentials` for each Xtream playlist
  - If credentials return 429 (rate limited), the client retries with short backoff
  - If credentials cannot be retrieved after retries, the client skips that playlist instead of importing a broken source
  - If credentials are returned, the Xtream source is restored locally with server, username, and password
- Save operations require premium access

### Important: rate limiting and Xtream restore

The credentials endpoint may return 429 under rate limiting. This can temporarily block Xtream playlist restore. The Android client handles this by:

1. Retrying the credentials fetch with backoff
2. Skipping the playlist if credentials are still unavailable
3. Never attempting a broken Xtream import without a password

Backend operators should be aware that aggressive rate limiting on `GET /me/playlists/{id}/credentials` can delay or prevent Xtream playlist restore on sign-in.

## Restore sequence

Typical restore order after sign-in:

1. `POST /auth/login` or `POST /auth/refresh`
2. `GET /me/access-state` (check entitlement and device activation)
3. `POST /me/devices/register` (register or refresh device)
4. `GET /me/account-settings`
5. `GET /me/addons` + addon sync
6. `GET /me/integrations` + per-integration credential fetch
7. `GET /me/playlists` + per-Xtream credential fetch
8. `GET /me/devices`
9. `GET /me/pairings`

## Changelog

Fixed Android account restore for Xtream playlists. The client now reliably fetches playlist credentials after playlist list restore, retries credentials fetch on transient 429 responses, and skips incomplete Xtream restores instead of importing broken sources.
