# Account Restore Behavior

## Overview

When a user signs in on a new device or after sign-out, every native client
(Android mobile, Android TV, desktop, and any future app shell) restores
account-backed data from the backend. There are four independent restore paths:

1. **Integration restore** (API keys, service credentials)
2. **Addon/extension restore** (community extensions)
3. **Playlist restore** (M3U and Xtream channel sources)
4. **Media favorite restore** (account-backed movie/series favorites)

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
- M3U playlists are restored using the stored playlist URL and `epg_url`
- Xtream playlists are restored using the stored server, username, password
  credentials, and optional custom `epg_url`. If no custom Xtream guide URL is
  stored, clients may derive the provider XMLTV endpoint from the Xtream
  credentials.
- The `epg_url` is source configuration for both M3U and Xtream and must be
  applied on login, app-start sync, and manual sync. Do not treat it as
  local-only data.
- Clients can validate a guide URL with `POST /me/playlists/validate-epg`:
  ```json
  { "epg_url": "https://example.com/guide.xml" }
  ```
  A successful response means the backend could fetch the URL and detected
  XMLTV guide data. Validation does not save the playlist; the client must
  still call `PUT /me/playlists/{playlist_id}` to persist changes.
- Playlist create, update, delete, and EPG URL edit operations emit
  `PLAYLISTS_UPDATED` over `/me/events`. Active signed-in clients should
  refetch `GET /me/playlists` and apply the server copy instead of waiting
  for the next login or foreground sync.
- Xtream playlists require an additional credential fetch:
  - Client calls `GET /me/playlists/{playlist_id}/credentials` for each Xtream playlist
  - If credentials return 429 (rate limited), the client retries with short backoff
  - If credentials cannot be retrieved after retries, the client skips that playlist instead of importing a broken source
  - If credentials are returned, the Xtream source is restored locally with server, username, password, and optional custom `epg_url`
- Save operations require premium access

## Media favorite restore

- Client calls `GET /me/media-favorites` to get the account favorite list
- The backend response contains `favorites`, `version`, and `updated_at`
- The backend is authoritative; clients should replace or reconcile their
  local account-favorite state from this response
- Saves use `PUT /me/media-favorites/{media_key}`
- Deletes use `DELETE /me/media-favorites/{media_key}`
- `/me/events` emits `MEDIA_FAVORITES_UPDATED`; clients should refetch
  `GET /me/media-favorites` when this event arrives

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
8. `GET /me/media-favorites`
9. `GET /me/devices`
10. `GET /me/pairings`

## Changelog

- 2026-05-16: Documented cross-client restore requirements for M3U
  `epg_url`, EPG validation, and account-backed media favorites.
- Fixed Android account restore for Xtream playlists. The client now reliably
  fetches playlist credentials after playlist list restore, retries credentials
  fetch on transient 429 responses, and skips incomplete Xtream restores instead
  of importing broken sources.
