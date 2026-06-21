# Sources and Setup Models

## Playlists (UserPlaylist)

Playlists are the only "source" or "channel source" concept that exists in the backend.

### Table: `user_playlists`

| Field | Type | Required | Description |
|---|---|---|---|
| `id` | UUID | Auto | Server-side row ID |
| `user_id` | UUID FK | Auto (from JWT) | Owner account |
| `playlist_id` | VARCHAR(255) | Yes | Client-assigned stable ID for round-trip matching |
| `name` | VARCHAR(255) | Yes | User-visible playlist name |
| `playlist_type` | VARCHAR(20) | Yes | `m3u` or `xtream` |
| `url` | TEXT | M3U: required | M3U playlist URL |
| `epg_url` | TEXT | No | EPG (Electronic Program Guide) URL |
| `server` | TEXT | Xtream: required | Xtream server base URL |
| `username` | VARCHAR(255) | Xtream: required | Xtream username |
| `encrypted_password` | TEXT | No | Xtream password (Fernet encrypted) |
| `created_at` | TIMESTAMPTZ | Auto | Row creation time |
| `updated_at` | TIMESTAMPTZ | Auto | Last update time |

### Unique constraint

`(user_id, playlist_id)` -- one entry per client-assigned playlist ID per user.

### Validation Rules

**M3U playlists:**
- `url` is required (400 if missing)
- `epg_url` is optional
- `server`, `username`, `password` ignored

**Xtream playlists:**
- `server` and `username` required (400 if missing)
- `password` optional but typically needed
- Password encrypted before storage via Fernet
- `url` and `epg_url` ignored

**Shared:**
- `playlist_id` in URL path must match body (400 if mismatch)
- `playlist_type` must be `m3u` or `xtream` (422 if invalid)

### Deduplication

Upsert by `(user_id, playlist_id)`. If a playlist with the same client-assigned ID already exists, all fields are updated. No duplicates are created. On update, the existing encrypted password is preserved unless a new password is explicitly provided.

### Sync Behavior

- **Save:** Client calls `PUT /me/playlists/{playlist_id}` when adding or modifying a playlist
- **Restore:** On login, client calls `GET /me/playlists` (metadata list, no passwords), then `GET /me/playlists/{id}/credentials` per Xtream playlist to fetch the decrypted password
- **EPG URL:** M3U `epg_url` is account-backed source configuration. Clients must save, restore, and apply it on login, manual sync, and source edit.
- **EPG validation:** `POST /me/playlists/validate-epg` accepts `{ "epg_url": "..." }` and returns whether the URL is reachable and contains XMLTV-like guide data. Validation does not save the playlist.
- **No automatic sync:** The backend does not push playlist changes. Sync is client-initiated.

### Revoke / Delete

`DELETE /me/playlists/{playlist_id}` hard-deletes the row. Account deletion cascade-deletes all playlists.

### Credential Retrieval

`GET /me/playlists/{playlist_id}/credentials` returns:
```json
{ "playlist_id": "...", "password": "decrypted_password" }
```
For M3U playlists or Xtream without a stored password, returns `"password": null`.

### What Belongs to the Backend vs Client

| Concept | Where it lives |
|---|---|
| Playlist metadata (name, URL, server, username) | Backend (`user_playlists`) |
| M3U EPG URL | Backend (`user_playlists.epg_url`) |
| Xtream password | Backend (encrypted) |
| Channels parsed from playlist | Client-only (SQLite) |
| Favorites | Backend (`user_media_favorites`) for signed-in users |
| Recently watched / playback progress | Tracking integration or client feature, depending on app |
| EPG programme listings | Client-only cache fetched from the synced EPG URL |
| Channel groups/categories | Client-only (parsed from M3U/Xtream) |

## Addons / Extensions

Addons are backend entities in `user_addons` and are exposed through:

- `GET /me/addons`
- `POST /me/addons`
- `PATCH /me/addons/{addon_id}`
- `DELETE /me/addons/{addon_id}`
- `POST /me/addons/{addon_id}/toggle`

They represent Stremio-compatible manifest URLs plus metadata, enabled state,
and sort order. Clients restore addons by `manifest_url` / `addon_id` and must
avoid duplicating addons already present on the backend.

## Concepts and Backend Mapping

The following terms may appear in client UI or user communication:

| Term | Backend status |
|---|---|
| Source | No entity. Closest: `UserPlaylist` |
| Channel source | No entity. Client concept |
| Extension | `UserAddon` when it is a Stremio-compatible manifest |
| Addon | `UserAddon` |
| Media source | No entity. Client concept |
| URL import | No entity. Stored as `UserPlaylist` of type `m3u` |
| Service login | No entity. Stored as `UserPlaylist` of type `xtream` for channel sources |
| Provider | No entity. The `integration_type` field on `UserIntegration` serves as the provider key |
