# Settings Matrix

## Account Settings (server-synced)

Stored in `account_settings` table as a JSONB blob. One row per user. Syncs across all devices via `GET/PATCH /me/account-settings`.

### Known settings keys

| Key | Type | Default | Allowed values | Meaning | Syncs | Platform |
|---|---|---|---|---|---|---|
| `language` | string | `"en"` | Unclear from backend (client-defined) | App UI language | Yes | Shared |
| `home_layout` | string | `"default"` | Unclear from backend (client-defined) | Home screen layout preference | Yes | Shared |
| `ratings_provider` | string | `"imdb"` | Unclear from backend (client-defined) | Where to source ratings (e.g. IMDb, TMDB) | Yes | Shared |

### How settings work

- **Get:** `GET /me/account-settings` returns full JSONB blob + version + updated_at + updated_by_device_id
- **Update:** `PATCH /me/account-settings` with `{ "settings": { "key": "value" }, "device_id": "optional-uuid" }`
  - Merge semantics: incoming keys are merged into existing blob (last-write-wins)
  - Version counter increments by 1
  - `updated_by_device_id` records which device made the change
- **Create:** Auto-created on first access with defaults
- **Delete:** Cascade-deleted with account

### What the backend does NOT know

The backend stores settings as opaque JSONB. It does not:
- Validate setting keys or values
- Define the full list of valid settings
- Know which settings are mobile-only vs TV-only
- Enforce allowed values for any setting
- Know about device-local settings (those exist only on the client)

### Inferred: device-local settings (not in backend)

Based on the codebase architecture, the following are NOT stored server-side:
- Decoder preferences
- Audio passthrough settings
- Renderer quirks
- Hardware-specific playback toggles
- Remote/input quirks
- Buffer sizes
- Subtitle appearance
- Any hardware-specific configuration

These are entirely client-managed and not documented in backend code.

## Settings Conflict Resolution

Last-write-wins. No vector clocks, no CRDTs, no merge engine. The `version` field increments on each write but is informational only. The client can read it to detect if settings changed since last fetch, but no optimistic concurrency control is enforced.

## Migration / Fallback

- If `account_settings` row doesn't exist for a user, it is auto-created with defaults on first GET
- No schema migration for individual setting keys: new keys can be added by any PATCH without backend changes
- Old keys are never automatically removed
