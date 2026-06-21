# Integrations Inventory

## Backend Integration Model

The backend does NOT define individual integration types. It provides a **generic integration credential store** (`user_integrations` table) that accepts any `integration_type` string from the client. The backend is type-agnostic.

### Known integration types (observed in production data)

| `integration_type` value | Inferred user-facing label | What it stores | Platform |
|---|---|---|---|
| `OMDB_API_KEY` | OMDB | Movie metadata API key | Mobile, TV |
| `DEBRID_API_KEY_REAL_DEBRID` | Real-Debrid | Debrid service API key | Mobile, TV |
| `TRAKT_TOKENS` | Trakt | OAuth access token (refresh token missing; known issue) | Mobile, TV |
| `SIMKL_ACCESS_TOKEN` | Simkl | OAuth access token | Mobile, TV |

**Note:** These type names are client-defined. The backend stores whatever `integration_type` string the client sends.

## Storage Modes

Each integration has a `storage_mode`:

| Mode | Backend behavior | Credentials stored | Restored on login |
|---|---|---|---|
| `account` | Encrypted at rest (Fernet), stored in DB | Yes | Yes |
| `device_only` | Only metadata/config stored, no secrets | No | Config/metadata only |

## Per-Integration Details

### OMDB (OMDB_API_KEY)

- **Purpose:** Movie and TV show metadata lookup
- **Credential type:** Single API key (string)
- **Optional/Required:** Optional
- **Prerequisites:** User obtains their own OMDB API key from omdbapi.com
- **Success:** API key stored, `is_connected=true`
- **Failure states:** 422 if credentials format invalid, 400 if empty credentials in account mode
- **Caveats:** Backend stores as `{"value": "key"}` when client sends raw string (backward compat normalization)

### Real-Debrid (DEBRID_API_KEY_REAL_DEBRID)

- **Purpose:** Premium link resolution / debrid service
- **Credential type:** Single API key (string)
- **Optional/Required:** Optional, advanced
- **Prerequisites:** Active Real-Debrid account and API key
- **Success:** API key stored encrypted
- **Caveats:** Same string-to-dict normalization as OMDB

### Trakt (TRAKT_TOKENS)

- **Purpose:** Watch history, ratings, and list sync
- **Credential type:** OAuth tokens
- **Optional/Required:** Optional
- **Prerequisites:** Trakt account and completed OAuth flow
- **Known issue:** Client currently sends only a single token string. Trakt OAuth requires both `access_token` and `refresh_token` for session refresh. This causes restore failures. Backend stores whatever is sent.
- **Impact:** Trakt does not restore correctly after sign-out/sign-in

### Simkl (SIMKL_ACCESS_TOKEN)

- **Purpose:** Anime/TV tracking and sync
- **Credential type:** OAuth access token (single string)
- **Optional/Required:** Optional
- **Prerequisites:** Simkl account and completed OAuth flow
- **Success:** Token stored, restores on login

## Backend Capabilities Per Integration

All integrations share the same API surface:

| Action | Endpoint | Method |
|---|---|---|
| Save/update | `PUT /me/integrations/{type}` | Upsert by `(user_id, integration_type)` |
| List all | `GET /me/integrations` | Metadata only, no secrets |
| Get one | `GET /me/integrations/{type}` | Metadata only |
| Get credentials | `GET /me/integrations/{type}/credentials` | Decrypted secrets (hidden from API docs) |
| Test | `POST /me/integrations/{type}/test` | Format validation only (no live service check) |
| Remove | `DELETE /me/integrations/{type}` | Hard delete |

## Backend Validations

- `integration_type`: max 50 chars
- `storage_mode`: must be `account` or `device_only` (regex validated)
- `credentials` (account mode): must be non-empty
- `display_identifier`: max 255 chars
- Unique constraint: one integration per type per user
- All operations scoped to authenticated user (JWT bearer)

## What the Backend Does NOT Do

- Does not validate credentials against the external service (test endpoint only checks format)
- Does not define the list of valid integration types
- Does not enforce which storage mode to use
- Does not initiate OAuth flows (client-side only)
- Does not refresh expired OAuth tokens
- Does not know about integration-specific field names (api_key vs access_token vs oauth_token)
