# Torve Auth API — Client Contract

Base URL: `https://api.torve.app`

Last updated: 2026-05-16

---

## Endpoints

### POST /auth/register

Alias for `/auth/signup`. Identical behavior.

### POST /auth/signup

**Request:**
```json
{
  "email": "user@example.com",
  "password": "min8chars",
  "display_name": "Optional Name",
  "device_name": "Pixel 9",
  "platform": "android",
  "device_type": "phone",
  "installation_id": "stable-install-id",
  "app_version": "1.4.0"
}
```

**201 Response:**
```json
{
  "tokens": {
    "access_token": "eyJ...",
    "refresh_token": "hex96chars...",
    "token_type": "bearer"
  },
  "user": {
    "id": "uuid",
    "email": "user@example.com",
    "display_name": "Optional Name",
    "is_active": true,
    "is_verified": false,
    "created_at": "2026-03-15T11:14:54.066615Z"
  },
  "device": {
    "id": "uuid",
    "device_type": "phone",
    "platform": "android",
    "display_name": "Pixel 9",
    "installation_id": "stable-install-id",
    "app_version": "1.4.0",
    "last_seen_at": "2026-05-16T12:00:00Z",
    "is_active": true,
    "revoked_at": null
  }
}
```

**Errors:** 409 (email taken), 422 (validation)

---

### POST /auth/login

**Request:**
```json
{
  "email": "user@example.com",
  "password": "min8chars",
  "device_name": "Pixel 9",
  "platform": "android",
  "device_type": "phone",
  "installation_id": "stable-install-id",
  "app_version": "1.4.0"
}
```

**200 Response:** Same shape as signup.

**Errors:** 401 (bad credentials), 403 (account disabled), 422 (validation), 429 (rate limited)

---

### POST /auth/refresh

**Request:**
```json
{
  "refresh_token": "hex96chars...",
  "device_name": "Pixel 9",
  "platform": "android",
  "device_type": "phone",
  "installation_id": "stable-install-id",
  "app_version": "1.4.0"
}
```

**200 Response:**
```json
{
  "tokens": {
    "access_token": "eyJ...",
    "refresh_token": "new-hex96chars...",
    "token_type": "bearer"
  },
  "user": {
    "id": "uuid",
    "email": "user@example.com",
    "display_name": "Optional Name",
    "is_active": true,
    "is_verified": true,
    "created_at": "2026-03-15T11:14:54.066615Z"
  },
  "device": {
    "id": "uuid",
    "device_type": "phone",
    "platform": "android",
    "display_name": "Pixel 9",
    "installation_id": "stable-install-id",
    "app_version": "1.4.0",
    "last_seen_at": "2026-05-16T12:00:00Z",
    "is_active": true,
    "revoked_at": null
  }
}
```

**Note:** refresh tokens rotate. Replace the locally stored refresh token with `tokens.refresh_token` whenever it is present. Treat the old refresh token as spent immediately after a successful refresh.

**Errors:** 401 (invalid/expired/revoked token)

---

### POST /auth/resend-verification

**Request:**
```json
{
  "email": "user@example.com"
}
```

**200 Response:**
```json
{
  "message": "If that email is registered and unverified, a verification link has been sent."
}
```

Always returns 200 regardless of whether email exists. Rate limited (1/min).

**Errors:** 429 (rate limited)

---

### POST /auth/password-reset/request

**Request:**
```json
{
  "email": "user@example.com"
}
```

**200 Response:**
```json
{
  "message": "If that email is registered, a reset link has been sent."
}
```

Always returns 200. Rate limited (1/min).

**Errors:** 429 (rate limited)

---

### POST /auth/password-reset/confirm

**Request:**
```json
{
  "token": "hex96chars...",
  "new_password": "min8chars"
}
```

**200 Response:**
```json
{
  "message": "Password has been reset successfully."
}
```

**Errors:** 400 (invalid/expired/used token), 422 (validation)

---

## Error shapes

### Structured error (from backend — JSON)
```json
{"detail": "Human-readable message"}
```

### Validation error (422 — JSON)
```json
{
  "detail": [
    {
      "type": "string_too_short",
      "loc": ["body", "password"],
      "msg": "String should have at least 8 characters",
      "input": "x",
      "ctx": {"min_length": 8}
    }
  ]
}
```

### Rate limit error (429 — HTML, not JSON!)
```html
<html>
<head><title>429 Too Many Requests</title></head>
<body>
<center><h1>429 Too Many Requests</h1></center>
</body>
</html>
```

**Critical:** 429 responses come from Nginx, not FastAPI. The body is HTML, not JSON.
Clients MUST check status code before attempting JSON parse.
No `Retry-After` header is provided. Use client-side backoff (60 seconds recommended).

---

## Key client rules

1. **`is_verified` is informational, not blocking.** Unverified users can log in and use the app.
2. **Refresh responses rotate `refresh_token`.** Store the new token securely and discard the old one.
3. **Refresh responses DO contain `user` and may contain `device`.** Update local user/device state on every refresh.
4. **429 is HTML.** Do not try to parse it as JSON.
5. **`detail` can be a string OR an array.** Check type before rendering.
6. **All auth responses nest tokens under `tokens`.** Not flat top-level fields.
7. **`display_name` is nullable.** Handle null in UI.
8. **`created_at` is ISO 8601 with timezone.** Parse with offset-aware datetime.
9. **Send stable device metadata on auth calls.** Include `installation_id`, `platform`, `device_type`, `device_name`, and `app_version` on signup/login/refresh so device activation, device limits, and sync ownership stay correct.

---

## Rate limits

| Endpoint | Limit | Burst |
|----------|-------|-------|
| /auth/login, /auth/signup, /auth/register | 10/min per IP | 5 |
| /auth/resend-verification | 1/min per IP | 3 |
| /auth/password-reset/request | 1/min per IP | 3 |
| All other endpoints | No limit | — |
