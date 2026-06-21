# Unknowns and Gaps

## Naming Ambiguities

| Backend term | Possible user-facing terms | Risk |
|---|---|---|
| `integration_type` | "Integration", "Service", "Provider", "Addon", "Extension" | Users may not recognize "integration" if the UI says "addon" or "service" |
| `playlist_type: m3u` | "M3U playlist", "URL playlist", "URL import" | Users may call this "adding a URL" or "importing a playlist" |
| `playlist_type: xtream` | "Xtream playlist", "Xtream login", "Service login" | Users may call this "logging into a service" |
| `storage_mode: account` | "Cloud backup", "Synced", "Account-linked" | Need consistent label |
| `storage_mode: device_only` | "Local only", "This device only", "Not synced" | Need consistent label |
| `is_verified` | "Email verified", "Account verified" | Backend means email verified, user may think account is "approved" |
| `is_active` | "Active", "Enabled", "Not disabled" | Backend uses this for admin disablement, not user-controlled |
| `revoked` (device) | "Removed", "Deleted", "Deactivated" | UI may show "Remove" but backend stores as "revoked" |

## Routes That Exist but Lack Obvious User Affordances

| Route | Issue |
|---|---|
| `POST /me/integrations/{type}/test` | Currently validates stored credential presence/decryptability more than deep provider connectivity. UI should phrase this carefully. |
| `GET /me/integrations/{type}/credentials` | Internal restore path, hidden from API docs. Clients call it but users should never see it |
| `GET /me/playlists/{id}/credentials` | Same: internal restore path |
| `POST /me/playlists/validate-epg` | Needs UI affordance wherever users edit or inspect an M3U EPG URL |
| `GET/PUT/DELETE /me/media-favorites` | Clients must audit old local-only favorite flows and switch signed-in users to backend-backed sync |
| `POST /me/devices/{id}/heartbeat` | No obvious UI. Used by background keep-alive |
| `PATCH /me/account-settings` | Backend accepts any arbitrary key. No validation on what keys are meaningful |

## Frontend/Backend Wording Divergence Risks

| Area | Backend | Frontend likely says | Resolution needed |
|---|---|---|---|
| Device removal | `revoke` | "Remove" or "Delete" | Align UI to say "Remove" |
| Pairing | `controller_device_id` + `target_device_id` | "Phone" + "TV" | UI should translate |
| Integration save | `PUT .../integrations/{type}` | "Save" or "Connect" | UI maps to PUT |
| Password field naming | Playlist credentials return `"password"` | Xtream "Password" field | Should match |
| Credential dict structure | `{"value": "key"}` (normalized from string) | Client may expect `{"api_key": "..."}` | **Known issue**: Trakt fails to restore because of field name mismatch |

## Items Requiring Frontend Verification

1. **What does the app show when device limit is reached?** Backend returns structured error with active device list. Does the UI render it?

2. **Does the app handle `is_verified=false` gracefully?** Backend allows login without verification. Does the app gate any features on verification?

3. **What settings keys does the app actually use?** Backend only knows `language`, `home_layout`, `ratings_provider` from defaults. App may use more.

4. **What integration types does the app define?** Backend has seen `OMDB_API_KEY`, `DEBRID_API_KEY_REAL_DEBRID`, `TRAKT_TOKENS`, `SIMKL_ACCESS_TOKEN`. Are there more defined in client code?

5. **Does the app handle SSE reconnection?** Backend supports it, but does the client reconnect after network loss or app backgrounding?

6. **Does the app properly clear all local state on sign-out?** Backend has no logout endpoint. All cleanup is client-side. Incomplete cleanup has been observed (Xtream credentials surviving sign-out).

7. **Does the app call `GET /me/access-state` with `installation_id`?** The route now exists. Verify clients include the stable install ID so device activation is accurate.

8. **Does the app call `POST /auth/logout`?** This returns 404. Is the app treating the 404 as an error?

9. **What is the app's behavior when playlist restore takes >30 seconds?** Channel import from Xtream servers blocks the sign-in flow.

10. **How does the app display `has_credentials: true` but failed restore?** Needs a "Needs re-authentication" state rather than "Not connected".

## Data That the Backend Cannot Answer

- Which features require email verification to use (client-gated)
- Which integrations are required vs optional vs recommended (client-defined)
- What the home screen looks like with default vs custom layout settings
- What "ratings_provider" values are valid beyond "imdb"
- Whether the app has in-app purchase / entitlement checks (no billing backend code exists)
- What local database schema the app uses for channels and EPG caches
- Whether the app supports multiple playlists simultaneously
- What EPG sources the app supports and how they work

## Known Production Bugs (as of audit date)

1. **Trakt restore failure:** Client sends single OAuth token as string. Backend normalizes to `{"value": "..."}`. Trakt requires `access_token` + `refresh_token` in a dict. Fix needed: client must send proper dict structure.

2. **Missing `/auth/logout` endpoint:** Client calls it and gets 404. Not harmful but generates unnecessary log noise.

3. **Access-state route exists now:** Older clients may still treat access-state as missing or omit `installation_id`. Verify current clients use the returned entitlement/device fields correctly.

4. **Slow sign-in after restore:** Xtream channel import happens synchronously during restore, blocking UI for 30-60 seconds.

5. **Legacy favorite sync wording:** Some client/help surfaces may still describe favorites as local-only. Signed-in favorites are now backend-backed via `/me/media-favorites`.

6. **EPG URL restore/check coverage:** Clients must verify M3U `epg_url` is saved, restored, and used on desktop/mobile/TV, and expose the backend EPG check action.
