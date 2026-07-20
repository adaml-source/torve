# Device Pairing and Sync

## Device Registration

### How devices are registered

Devices are registered automatically during login/signup/refresh when device info is provided in the request body. They can also be registered explicitly via `POST /me/devices/register`.

### Device model fields

| Field | Type | Description |
|---|---|---|
| `id` | UUID | Server-assigned device ID |
| `user_id` | UUID FK | Owner account |
| `device_type` | VARCHAR(20) | `phone`, `tablet`, or `tv` |
| `platform` | VARCHAR(50) | e.g. `android`, `firetv`, `android_tv`, `googletv`, `ios` |
| `display_name` | VARCHAR(200) | Device model name (e.g. "SM-G991B", "Fire TV Stick") |
| `installation_id` | VARCHAR(255) | Client-generated stable ID for deduplication |
| `app_version` | VARCHAR(50) | Current app version |
| `last_seen_at` | TIMESTAMPTZ | Last heartbeat or login |
| `is_active` | BOOLEAN | True = active, False = revoked |
| `revoked_at` | TIMESTAMPTZ | When revoked (null if active) |

### Device limit

**Maximum 5 active devices per account.** Enforced atomically via `SELECT FOR UPDATE`.

When limit is reached, login/signup returns 409 with:
```json
{
  "code": "DEVICE_LIMIT_REACHED",
  "message": "You have reached your 5-device limit.",
  "active_devices": [...],
  "max_devices": 5
}
```

### Device deduplication

If `installation_id` matches an existing device (active OR revoked) for the same user, the existing row is reused and updated (last_seen, app_version, platform, display_name). Revoked devices are reactivated. No duplicate rows are created.

### Platform inference

If `device_type` is not sent, it is inferred from `platform`:
- `firetv`, `fire_tv`, `androidtv`, `android_tv`, `googletv`, `chromecast` → `tv`
- `android`, `ios` → `phone`
- `ipad`, `tablet` → `tablet`
- Any other non-empty string → `phone` (fallback)

### Device states

| State | `is_active` | `revoked_at` | User can use | Appears in default list |
|---|---|---|---|---|
| Active | true | null | Yes | Yes |
| Revoked | false | timestamp | No (until reactivated) | No (unless `include_revoked=true`) |
| Reactivated | true | null (cleared) | Yes | Yes |

### Device management endpoints

| Endpoint | Purpose |
|---|---|
| `GET /me/devices` | List active devices (add `?include_revoked=true` for all) |
| `POST /me/devices/register` | Explicit device registration |
| `POST /me/devices/{id}/heartbeat` | Update last_seen and app_version |
| `POST /me/devices/{id}/revoke` | Soft-revoke device (frees slot) |
| `POST /me/devices/{id}/remove` | Alias for revoke |
| `DELETE /me/devices/{id}` | Alias for revoke |
| `POST /me/devices/{id}/rename` | Update display_name |

All also available under `/devices/...` prefix (compat aliases, hidden from API docs).

---

## Phone-to-TV Pairing

### Pairing flow (code-based)

1. **TV generates code:** `POST /pairing/code` with `{ "device_id": "tv-uuid" }`
   - TV must be a registered active device owned by the authenticated user
   - Returns a 6-character alphanumeric code (uppercase + digits), valid for 10 minutes
   - Previous unclaimed codes for the same TV are invalidated
   - Code is unique among active unclaimed codes (retries up to 10 times on collision)

2. **TV displays code:** User sees 6-character code on TV screen

3. **Phone claims code:** `POST /pairing/claim` with `{ "code": "ABC123", "device_id": "phone-uuid" }`
   - Code is normalized (uppercase, trimmed)
   - Must be unclaimed, unexpired
   - Must belong to the same user (cross-user claims silently rejected as "not found")
   - Phone and TV must be different devices (self-pairing rejected with 400)
   - If an active pairing already exists between these devices, returns existing pairing

4. **Pairing created:** `DevicePairing` row with `status=active`

### Pairing management

| Endpoint | Purpose |
|---|---|
| `GET /me/pairings` | List active pairings |
| `POST /me/pairings` | Create pairing directly by device IDs (alternative to code flow) |
| `POST /me/pairings/{id}/revoke` | Revoke a pairing |

### Pairing constraints

- Both devices must belong to the same user
- Both must be active
- Self-pairing is rejected
- Duplicate active pairings (same controller+target) are idempotent (returns existing)
- One phone can pair with multiple TVs
- One TV can be paired from multiple phones

### Pairing states

| `status` | Meaning |
|---|---|
| `active` | Pairing is live |
| `revoked` | Pairing has been revoked by user |

---

## What Syncs and What Does Not

### Syncs via backend

| Data | Mechanism | Scope |
|---|---|---|
| Account settings (language, layout, ratings) | `GET/PATCH /me/account-settings` | Account-wide, all devices |
| Integration credentials (account mode) | `GET/PUT /me/integrations` | Account-wide, all devices |
| Addons/extensions | `GET/POST/PATCH/DELETE /me/addons` | Account-wide, all devices |
| Playlist backups | `GET/PUT /me/playlists` | Account-wide, all devices |
| M3U EPG URL | `GET/PUT /me/playlists` (`epg_url`) | Account-wide source config |
| Media favorites | `GET/PUT/DELETE /me/media-favorites` | Account-wide, all devices |
| Device list | `GET /me/devices` | Account-wide |
| Pairing state | `GET /me/pairings` | Account-wide |
| Email verification status | SSE `EMAIL_VERIFIED` event | Account-wide, real-time push |
| Media favorite invalidation | SSE `MEDIA_FAVORITES_UPDATED` event | Account-wide, real-time push |

### Does NOT sync via backend

| Data | Where it lives |
|---|---|
| Channels / channel list | Client-only (SQLite, parsed from playlist) |
| Watch history / continue watching | Client-only |
| EPG programme listings | Client-only cache, refreshed from synced EPG URL |
| Device-local settings | Client-only |
| Decoder/renderer preferences | Client-only |
| Device-only integration credentials | Client-only |

---

## SSE (Server-Sent Events)

**Endpoint:** `GET /me/events`

**Auth:** Bearer JWT required

**Mechanism:** PostgreSQL LISTEN/NOTIFY for cross-worker delivery. Each uvicorn worker runs a background listener thread.

**Event types currently emitted:**

| Event | Trigger | Purpose |
|---|---|---|
| `EMAIL_VERIFIED` | Successful email verification | Notify phone that email was verified (possibly on another device) |
| `MEDIA_FAVORITES_UPDATED` | Favorite saved or deleted | Tell clients to refetch `GET /me/media-favorites` |

**Heartbeat:** `: heartbeat\n\n` comment sent every 30 seconds to keep connection alive through proxies.

**Client contract:** Do not trust event payload as source of truth. On receipt,
call the authoritative endpoint (`GET /me`, `/auth/refresh`, or
`GET /me/media-favorites`, depending on the event).

**nginx config:** `proxy_buffering off`, `proxy_read_timeout 3600s`, `X-Accel-Buffering: no` for the SSE location.

---

## Conflict Handling

- **Account settings:** Last-write-wins. Version counter increments on each patch. No conflict detection or merge.
- **Integrations:** Upsert by `(user_id, integration_type)`. Last save wins.
- **Playlists:** Upsert by `(user_id, playlist_id)`. Last save wins.
- **Devices:** Upsert by `(user_id, installation_id)`. Same physical device always resolves to same row.
