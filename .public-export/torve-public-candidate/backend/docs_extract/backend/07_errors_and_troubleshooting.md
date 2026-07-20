# Errors and Troubleshooting Matrix

## Auth Errors

| Endpoint | HTTP | Error text | User symptom | Root cause | User-fixable |
|---|---|---|---|---|---|
| `POST /auth/login` | 401 | "Invalid email or password" | Can't sign in | Wrong email or password | Yes: check credentials |
| `POST /auth/login` | 403 | "Account is disabled" | Can't sign in | Account deactivated | No: contact support |
| `POST /auth/signup` | 409 | "Email already registered" | Can't create account | Email already in use | Yes: use different email or sign in |
| `POST /auth/signup` | 422 | Validation error | Can't create account | Password too short (<8), invalid email | Yes: fix input |
| `POST /auth/refresh` | 401 | "Refresh token is invalid or revoked" | Session expired | Token revoked (password reset, etc.) | Yes: sign in again |
| `POST /auth/refresh` | 401 | "Refresh token has expired" | Session expired | Token older than 90 days | Yes: sign in again |
| `POST /auth/refresh` | 401 | "User account is inactive" | Can't refresh | Account disabled | No: contact support |
| Any authenticated | 401 | "Invalid or expired access token" | Feature fails | JWT expired or malformed | Auto: client should refresh |
| Any authenticated | 403 | (no bearer) | Feature fails | No auth header sent | Client bug |

## Device Errors

| Endpoint | HTTP | Error text | User symptom | Root cause | User-fixable |
|---|---|---|---|---|---|
| Login/signup/register | 409 | DEVICE_LIMIT_REACHED | Can't sign in on new device | 5 active devices already | Yes: remove a device first |
| `POST /me/devices/{id}/revoke` | 400 | "Device is already revoked." | Remove button seems broken | Device already removed | Informational: already done |
| `POST /me/devices/register` | 422 | "device_type must be one of: phone, tablet, tv" | Can't register device | Invalid device type | Client bug |
| Any device endpoint | 404 | "Device not found." | Device action fails | Wrong device ID or not owned | Check device list |

## Pairing Errors

| Endpoint | HTTP | Error text | User symptom | Root cause | User-fixable |
|---|---|---|---|---|---|
| `POST /pairing/code` | 404 | "Device not found or not active." | TV can't generate code | TV device not registered or revoked | Yes: re-sign-in on TV |
| `POST /pairing/claim` | 404 | "Pairing code not found, expired, or already used." | Phone can't pair | Code wrong, expired (>10 min), already used, or wrong account | Yes: generate new code |
| `POST /pairing/claim` | 400 | "A device cannot pair with itself." | Pairing fails | Same device ID for phone and TV | Client bug |
| `POST /me/pairings` | 422 | "Controller and target device must be different." | Pairing fails | Same device on both sides | Client bug |
| `POST /me/pairings` | 404 | "One or both devices not found or not active." | Pairing fails | Device revoked or wrong account | Check device list |
| `POST /pairing/code` | 503 | "Could not generate a unique pairing code. Please retry." | TV can't generate code | Code collision after 10 retries | Retry (extremely rare) |

## Integration Errors

| Endpoint | HTTP | Error text | User symptom | Root cause | User-fixable |
|---|---|---|---|---|---|
| `PUT /me/integrations/{type}` | 400 | "integration_type in body must match URL path." | Save fails | Client sending wrong type in body | Client bug |
| `PUT /me/integrations/{type}` | 400 | "credentials are required for account storage mode." | Save fails | Empty credentials for account mode | Yes: provide credentials |
| `PUT /me/integrations/{type}` | 422 | Validation error on `storage_mode` | Save fails | Invalid storage_mode value | Client bug |
| `GET /me/integrations/{type}` | 404 | "Integration '...' not found." | Integration missing | Not saved or wrong type key | Check integration list |
| `GET /.../credentials` | 500 | "Failed to decrypt credentials. Please re-save." | Restore fails | Encryption key changed or data corrupted | Yes: re-save credentials |
| `POST /.../test` | 200 | `success: false`, "Stored credentials could not be decrypted." | Test fails | Same as above | Yes: re-save |

## Playlist Errors

| Endpoint | HTTP | Error text | User symptom | Root cause | User-fixable |
|---|---|---|---|---|---|
| `PUT /me/playlists/{id}` | 400 | "playlist_id in body must match URL path." | Save fails | Client mismatch | Client bug |
| `PUT /me/playlists/{id}` | 400 | "url is required for m3u playlists." | Save fails | M3U without URL | Yes: provide URL |
| `PUT /me/playlists/{id}` | 400 | "server and username are required for xtream playlists." | Save fails | Xtream missing credentials | Yes: fill in server/username |
| `PUT /me/playlists/{id}` | 422 | Validation error on `playlist_type` | Save fails | Not `m3u` or `xtream` | Client bug |
| `GET /.../credentials` | 500 | "Failed to decrypt playlist credentials. Please re-save." | Restore fails | Encryption key changed | Yes: re-save playlist |
| `POST /me/playlists/validate-epg` | 400/422 | Invalid or missing EPG URL | EPG check fails immediately | Bad URL or empty form | Yes: fix URL |
| `POST /me/playlists/validate-epg` | 200 | `success: false` | EPG check says broken or not EPG | URL unreachable, blocked, or does not contain XMLTV data | Yes: verify with source provider |

## Addon / Extension Errors

| Endpoint | HTTP | Error text | User symptom | Root cause | User-fixable |
|---|---|---|---|---|---|
| `POST /me/addons` | 403 | `addon_blocked` or `addon_sensitive_locked` | Extension install blocked | Content policy or platform restriction | Sometimes: change policy/access settings |
| `POST /me/addons` | 422 | "Manifest URL is required." | Install fails | Empty manifest URL | Yes: paste manifest URL |
| `POST /me/addons` | 502 | Manifest fetch failed | Install by URL fails | Manifest unreachable/server error | Yes: verify URL/provider |

## Media Favorite Errors

| Endpoint | HTTP | Error text | User symptom | Root cause | User-fixable |
|---|---|---|---|---|---|
| `PUT /me/media-favorites/{media_key}` | 400 | "source_device_id must belong to the authenticated user." | Favorite save fails | Client sent another user's or stale device ID | Client bug or re-register device |
| `PUT /me/media-favorites/{media_key}` | 422 | Validation error | Favorite save fails | Missing title/media_type or invalid payload | Client bug |

## Account Deletion Errors

| Endpoint | HTTP | Error text | User symptom | Root cause | User-fixable |
|---|---|---|---|---|---|
| `DELETE /me/account` | 404 | "Account not found." | Delete fails | Already deleted or wrong token | Informational |

## Password Reset Errors

| Endpoint | HTTP | Error text | User symptom | Root cause | User-fixable |
|---|---|---|---|---|---|
| `POST /auth/password-reset/confirm` | 400 | "Reset token is invalid or has already been used." | Can't reset | Link reused or invalid | Yes: request new link |
| `POST /auth/password-reset/confirm` | 400 | "Reset token has expired." | Can't reset | Link older than 60 minutes | Yes: request new link |

## General Troubleshooting

| Symptom | Likely cause | Resolution |
|---|---|---|
| "0 of 5 devices" after sign-in | Login didn't include device info | Update app or re-sign-in |
| Integrations not restored | Client not calling restore endpoints | Update app |
| Trakt not restored | Only single token stored (needs access+refresh) | Re-authenticate Trakt |
| Playlist channels don't load after restore | Client-side channel import issue | Refresh playlist in app |
| Email verification not updating in app | No `GET /me` call after verify | Check SSE or press Check button |
| Favorites do not match across devices | Client not refetching `/me/media-favorites` after sync/event | Refetch favorites on login, resume, manual sync, and `MEDIA_FAVORITES_UPDATED` |
| EPG URL saved but guide missing on another device | Client restored playlist but ignored `epg_url` | Update client restore to apply M3U `epg_url`; use Check EPG |
| Sign-in slow (~60s) | Xtream channel import during restore | Normal for large playlists |
| `POST /auth/logout` returns 404 | No logout endpoint exists | Expected: logout is client-side |
