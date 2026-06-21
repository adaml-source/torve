# Auth and Account Flows

## Signup

**Endpoints:** `POST /auth/signup` (canonical), `POST /auth/register` (alias)

**Request:**
```json
{
  "email": "user@example.com",
  "password": "min8chars",
  "display_name": "optional",
  "device": {
    "device_type": "phone",
    "platform": "android",
    "device_name": "Pixel 8",
    "installation_id": "uuid-string",
    "app_version": "1.0.0"
  }
}
```
Also accepts flat top-level device fields for backward compat.

**What happens:**
1. Email normalized to lowercase, trimmed
2. Duplicate email check (409 if exists)
3. User row created (`is_active=true`, `is_verified=false`)
4. Refresh token created (90-day expiry)
5. Device registered/upserted if device info provided (enforces 5-device limit)
6. Default account settings created (`language: en`, `home_layout: default`, `ratings_provider: imdb`)
7. Email verification token created and verification email sent

**Response (201):**
```json
{
  "tokens": { "access_token": "jwt...", "refresh_token": "hex...", "token_type": "bearer" },
  "user": { "id": "uuid", "email": "...", "display_name": null, "is_active": true, "is_verified": false, "created_at": "..." },
  "device": { "id": "uuid", ... } // or null if no device info sent
}
```

**Errors:**
- 409: "Email already registered"
- 422: Validation (email format, password < 8 chars)
- 409: DEVICE_LIMIT_REACHED (if 5 devices already active)

---

## Login

**Endpoint:** `POST /auth/login`

**Request:** Same shape as signup (email, password, optional device info)

**What happens:**
1. Email lookup
2. Password verification (bcrypt)
3. Active check
4. Refresh token created
5. Device registered/upserted if device info provided

**Response (200):** Same `AuthResponse` shape as signup

**Errors:**
- 401: "Invalid email or password" (covers both wrong email and wrong password)
- 403: "Account is disabled" (is_active=false)
- 409: DEVICE_LIMIT_REACHED

---

## Token Refresh

**Endpoint:** `POST /auth/refresh`

**Request:**
```json
{
  "refresh_token": "hex-string",
  "device": { "device_type": "phone", "platform": "android", ... }
}
```

**What happens:**
1. Refresh token hash lookup
2. Revocation check
3. Expiry check
4. User active check
5. Device registered/upserted if device info provided
6. New access token issued (existing refresh token reused, rotation not yet enabled)

**Response (200):**
```json
{
  "tokens": { "access_token": "jwt...", "token_type": "bearer" },
  "user": { ... },
  "device": { ... } // or null
}
```

**Errors:**
- 401: "Refresh token is invalid or revoked"
- 401: "Refresh token has expired"
- 401: "User account is inactive"

---

## Get Current User

**Endpoint:** `GET /me`

**Auth:** Bearer JWT required

**Response (200):** `UserOut` with `is_verified` from DB (authoritative)

**Purpose:** Clients call this after SSE EMAIL_VERIFIED event or manual verification check.

---

## Email Verification

**Send:** Automatic on signup. Manual via `POST /auth/resend-verification`

**Verify:** `GET /auth/verify-email?token=...` (browser click)

**What happens on verify:**
1. Token hash lookup
2. Expiry check, used check
3. `user.is_verified = True`
4. Commit
5. SSE `EMAIL_VERIFIED` event emitted via pg NOTIFY (all workers, all active sessions)
6. Welcome email sent

**Redirects to:** `https://torve.app/verify-email?status=success|invalid|expired`

**Token expiry:** 48 hours

**Resend:** `POST /auth/resend-verification` with `{ "email": "..." }` -- always returns generic message, never reveals email existence.

---

## Password Reset

**Request:** `POST /auth/password-reset/request` with `{ "email": "..." }`
- Always returns generic message
- Creates reset token if email exists and user is active
- Token expiry: 60 minutes
- Invalidates previous unused reset tokens

**Confirm:** `POST /auth/password-reset/confirm` with `{ "token": "...", "new_password": "..." }`
- Validates token (hash, used, expiry)
- Updates password hash
- Marks token as used
- Revokes ALL existing refresh tokens for user

**Errors:** 400 for invalid/used/expired tokens

---

## Account Deletion

**Endpoints:** `DELETE /me/account` (canonical), `DELETE /auth/account` (compat alias)

**Auth:** Bearer JWT required

**What happens:**
1. User row deleted
2. All child records cascade-deleted: refresh tokens, reset tokens, verification tokens, devices, pairings, pairing codes, account settings, integrations, playlists

**Response (200):** `{ "message": "Your account and associated data have been deleted." }`

---

## Logout

**No backend endpoint exists.** Logout is client-side only:
- Clear access token
- Clear refresh token
- Clear local cached state

`POST /auth/logout` returns 404 if called.

---

## Token Details

| Token | Type | Storage | Expiry | Rotation |
|---|---|---|---|---|
| Access token | JWT (HS256) | Client memory | 30 minutes | New on every login/refresh |
| Refresh token | Opaque hex (48 bytes) | Client persistent storage, hash in DB | 90 days | Not yet rotated (TODO in code) |
| Password reset | Opaque hex, SHA-256 hash in DB | Email link | 60 minutes | Single-use |
| Email verification | Opaque hex, SHA-256 hash in DB | Email link | 48 hours | Single-use |

## JWT Claims

The JWT contains only:
- `sub`: user ID (UUID string)
- `exp`: expiration timestamp

It does NOT contain: `is_verified`, `is_active`, email, device info, or any other claims.

## Account States

| State | `is_active` | `is_verified` | Can login | Can use app |
|---|---|---|---|---|
| Just signed up | true | false | Yes | Yes (with verification prompt) |
| Verified | true | true | Yes | Yes |
| Disabled | false | any | No (403) | No |
| Deleted | row gone | n/a | No (401) | No |

## Rate Limits (nginx-level)

| Path pattern | Limit |
|---|---|
| `/auth/resend-verification`, `/auth/password-reset/request` | 1 req/min per IP, burst 3 |
| `/auth/login`, `/auth/signup`, `/auth/register` | 10 req/min per IP, burst 5 |
| All other routes | No rate limit |
