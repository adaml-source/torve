# Client Data Sync Contract

Last updated: 2026-05-16

This document is the implementation guide for Torve clients that sync account
data with the backend. It applies to desktop, mobile, and TV apps.

## Core rules

- Use `Authorization: Bearer <access_token>` for native app API calls.
- Store refresh tokens only in platform-secure storage.
- Store raw integration credentials, Xtream passwords, and provider API keys only
  in secure local storage when the client needs a local copy.
- Never log tokens, API keys, playlist passwords, Xtream credentials, or full
  tokenized stream/source URLs.
- Include a stable `installation_id` plus `platform`, `device_type`,
  `device_name`, and `app_version` on signup, login, refresh, and explicit
  device registration.
- Include `X-Torve-Installation-Id: <installation_id>` on authenticated calls
  where device-aware access state matters.
- Treat metadata list endpoints as non-secret. Fetch secrets only through the
  hidden credential restore endpoints, and only when needed.

## Login/startup/manual sync

After auth succeeds, run independent sync paths. A failure in one path must not
block or wipe the others.

Recommended parallel fetches:

1. `GET /me/access-state`
2. `GET /me/account-settings`
3. `GET /me/integrations`
4. `GET /me/addons`
5. `GET /me/playlists`
6. `GET /me/media-favorites`
7. `GET /me/devices` and `GET /me/pairings` where the app has device UI

Then fetch secrets with bounded concurrency:

- `GET /me/integrations/{type}/credentials` for account-mode integrations with
  `has_credentials=true`
- `GET /me/playlists/{playlist_id}/credentials` for Xtream playlists with
  `has_password=true`

## Account settings

Endpoints:

- `GET /me/account-settings`
- `PATCH /me/account-settings`

Settings are preference data only. Do not send API keys, access tokens, refresh
tokens, playlist passwords, or integration credentials in account settings.

The backend shallow-merges settings and increments `version`. Send only changed
keys.

## Integrations

Endpoints:

- `GET /me/integrations`
- `GET /me/integrations/{type}`
- `PUT /me/integrations/{type}`
- `PATCH /me/integrations/{type}/credentials`
- `DELETE /me/integrations/{type}`
- `POST /me/integrations/{type}/test`
- `GET /me/integrations/{type}/credentials`

`GET /me/integrations` returns metadata only:

- `integration_type`
- `storage_mode`
- `display_identifier`
- `config`
- `is_connected`
- `has_credentials`
- timestamps

`storage_mode="account"` stores encrypted credentials server-side and restores
them to signed-in devices. `storage_mode="device_only"` keeps secrets on the
originating device; the backend stores metadata/config only.

Use `PATCH /me/integrations/{type}/credentials` to merge individual credential
keys without replacing the full integration row.

## Playlists and EPG

Endpoints:

- `GET /me/playlists`
- `PUT /me/playlists/{playlist_id}`
- `DELETE /me/playlists/{playlist_id}`
- `GET /me/playlists/{playlist_id}/credentials`
- `POST /me/playlists/validate-epg`

M3U payloads must preserve `epg_url`:

```json
{
  "playlist_id": "stable-client-id",
  "playlist_type": "m3u",
  "name": "My Channels",
  "url": "https://example.com/playlist.m3u",
  "epg_url": "https://example.com/guide.xml"
}
```

Xtream payloads may also include a custom `epg_url`:

```json
{
  "playlist_id": "stable-client-id",
  "playlist_type": "xtream",
  "name": "My Provider",
  "server": "https://provider.example.com",
  "username": "user",
  "password": "secret",
  "epg_url": "https://example.com/guide.xml"
}
```

The playlist URL/server and EPG URL are account-backed configuration. They must
be saved, restored, and applied on login, app startup, manual sync, and source
edit across desktop, mobile, and TV. For Xtream sources, clients may derive the
provider XMLTV URL only when no custom `epg_url` is stored.

Xtream metadata, including the optional custom `epg_url`, is returned by
`GET /me/playlists`; the password is not. Fetch
the password with `GET /me/playlists/{playlist_id}/credentials` only when
restoring an Xtream source.

EPG validation:

```json
POST /me/playlists/validate-epg
{ "epg_url": "https://example.com/guide.xml" }
```

The response includes `success`, `status`, `message`, `http_status`,
`content_type`, `channel_count`, `programme_count`, and `bytes_checked`.
Validation checks reachability and XMLTV-like guide data. It does not save the
playlist; clients still call `PUT /me/playlists/{playlist_id}` to persist.
Playlist create, update, delete, and EPG URL edit operations emit
`PLAYLISTS_UPDATED` over `/me/events`; active signed-in clients should treat it
as an invalidation signal and refetch `GET /me/playlists`.

## Addons/extensions

Endpoints:

- `GET /me/addons`
- `POST /me/addons`
- `PATCH /me/addons/{addon_id}`
- `DELETE /me/addons/{addon_id}`
- `POST /me/addons/{addon_id}/toggle`

Addons are account-backed Stremio-compatible extension manifests. Restore them
by stable `manifest_url` or `addon_id`, respecting `is_enabled` and
`sort_order`. Avoid duplicating addons that already exist on the backend.

Install payload:

```json
{
  "manifest_url": "https://example.com/manifest.json",
  "addon_id": "optional",
  "name": "Optional",
  "installed_from": "app"
}
```

Backend content policy may return 403 with structured detail. Do not retry those
as transient failures.

## Media favorites

Endpoints:

- `GET /me/media-favorites`
- `PUT /me/media-favorites/{media_key}`
- `DELETE /me/media-favorites/{media_key}`

Favorites are account-backed. Clients should not describe them as local-only for
signed-in users. Store `version` and `updated_at`, and refetch on app resume,
manual sync, reconnect, or `MEDIA_FAVORITES_UPDATED`.

## Events

Endpoint:

- `GET /me/events`

Events are invalidation signals only. Known events include:

- `EMAIL_VERIFIED`
- `PLAYLISTS_UPDATED`
- `MEDIA_FAVORITES_UPDATED`

On an event, refetch the authoritative endpoint. Do not trust event payloads as
complete data.

## Error handling

- `401`: refresh once, retry the original request once, then sign out if refresh
  fails.
- `403`: premium, policy, or permission state. Do not delete local data.
- `409`: conflict such as device limit. Surface the conflict and refetch.
- `422`: client payload/form validation. Do not auto-retry.
- `429`: back off with jitter. Do not run parallel retry storms.
- `5xx/network`: keep the last good cache and queue pending writes.

## Partial failure behavior

Sync categories are independent. If playlist credential restore fails, addons
and favorites should still sync. If media favorites fail, playlists and settings
should still apply. Never wipe a working local category because another category
failed.
