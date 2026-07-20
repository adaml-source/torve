# Discord Beta Flow

Torve beta access is server-authoritative. The Discord Beta Tester role is a community marker and must never be treated as app access by itself. Torve app access checks use `/me/access-state` and the `beta_access_grants` table.

Users must verify their Torve signup email address before applying for beta access. Discord community verification or a Discord Verified User role is not Torve account verification.

## Discord App Setup

1. Create or open the Torve Discord application in the Discord Developer Portal.
2. Add a bot and copy the bot token into the server environment as `DISCORD_BOT_TOKEN`.
3. Copy the application public key into `DISCORD_PUBLIC_KEY`.
4. Set the interactions endpoint URL to `https://api.torve.app/discord/interactions`.
5. Invite the bot to the Torve guild with the permissions below.

Store secrets only in `.env` or the real server environment. Do not paste the bot token, public key, webhook URLs, or private configuration into Discord.

## Required Bot Permissions

- View Channels
- Send Messages
- Read Message History
- Manage Roles
- Use Slash Commands is optional for future command flows

The bot role must be above the Beta Tester role in Discord role ordering, otherwise Discord will reject role assignment.

## Required IDs

Set these environment variables:

- `DISCORD_BOT_TOKEN`
- `DISCORD_PUBLIC_KEY`
- `DISCORD_GUILD_ID`
- `DISCORD_BETA_TESTER_ROLE_ID`
- `DISCORD_BETA_APPLICATION_CHANNEL_ID`
- `DISCORD_BETA_APPLICATION_MESSAGE_ID`
- `DISCORD_BETA_REVIEW_CHANNEL_ID`
- `DISCORD_BETA_AUTO_APPROVE=false`
- `TORVE_BETA_GRANT_DAYS=30`
- `BETA_SIGNUP_CLOSE_AT=2026-07-01T23:59:59+02:00`
- `BETA_FREE_ACCESS_END_AT=2026-07-31T23:59:59+02:00`

Optional reviewer allowlists:

- `DISCORD_BETA_REVIEWER_ROLE_IDS`
- `DISCORD_BETA_REVIEWER_USER_IDS`

Staff approval also accepts Discord members with moderation, manage guild, manage roles, or administrator permissions.

## Publish Apply Message

There are two beta-related Discord message IDs and they are intentionally separate:

- `DISCORD_BETA_INFO_MESSAGE_ID` is the static `#beta-info` page managed by `app.discord_static_messages` through a Discord webhook.
- `DISCORD_BETA_APPLICATION_MESSAGE_ID` is the bot-authored Apply for Beta button message managed by `app.discord_beta_admin`.

Do not reuse `DISCORD_BETA_INFO_MESSAGE_ID` for the Apply button. Discord bots cannot edit webhook-authored static messages.

Run:

```bash
python -m app.discord_beta_admin publish-application-message
```

If `DISCORD_BETA_APPLICATION_MESSAGE_ID` is blank, this creates a new bot-authored message in `DISCORD_BETA_APPLICATION_CHANNEL_ID` and prints the created message ID. Save that ID as `DISCORD_BETA_APPLICATION_MESSAGE_ID`.

To update an existing bot-authored message, either set `DISCORD_BETA_APPLICATION_MESSAGE_ID` or pass:

```bash
python -m app.discord_beta_admin publish-application-message --message-id <discord_message_id>
```

The message is posted in `DISCORD_BETA_APPLICATION_CHANNEL_ID` and includes an Apply for Beta button.

If editing returns 403 or 404, the configured message may not be bot-authored or may no longer exist. Clear `DISCORD_BETA_APPLICATION_MESSAGE_ID` and run the publish command again.

## Campaign Window

The Discord beta free-premium campaign has a fixed signup and free-access window:

- Last free-premium beta signup/application day: July 1, 2026 at 23:59:59 Europe/Berlin.
- Last beta free premium access day: July 31, 2026 at 23:59:59 Europe/Berlin.

Users can keep generating Torve beta link codes and submitting Discord beta applications after these dates so they can opt in to the Discord beta area and early-release communication. The July dates only control free premium app access:

- Applications submitted by the free-premium signup deadline may receive a temporary `discord_beta` premium grant.
- Every `discord_beta` premium grant is capped at July 31, 2026.
- Applications submitted after the free-premium signup deadline may still be approved, but approval is Discord-only and does not create a free premium grant.
- After July 31, no new `discord_beta` premium grants are created; cleanup expires active grants, while staff approval can still add the Discord Beta Tester role.

Beta/free premium access is separate from paid subscriptions, lifetime purchases, store claims, and rebate grants. Existing paid premium users may still apply for beta so they can receive early-release access; clients must not block beta application only because `has_premium_access=true`. The Discord Beta Tester role is not app access; `/me/access-state` and `beta_access_grants` remain authoritative.

## Application UX

Discord modals are used only for text inputs in this backend. Devices, features, and stability preference are collected with a reliable multi-step component flow before the final modal:

1. The user clicks Apply for Beta.
2. The user selects devices/platforms from controlled options.
3. The user selects beta areas from controlled options.
4. The user selects a stability preference.
5. The final modal asks for the Torve beta link code, optional testing notes, and `I UNDERSTAND` safety confirmation.

The user is never asked for an email address in Discord.

All user-specific application steps are ephemeral and visible only to the applicant. The backend edits the existing ephemeral interaction response for device and feature selection where Discord supports that interaction pattern, then opens the final modal for link code and notes. These messages use Discord's normal ephemeral dismissal behavior and are not posted to the public `#beta-info` channel.

The multi-step selection draft is stored in the database in `discord_beta_application_drafts`, not in process memory. This keeps the flow reliable when the backend runs multiple workers or restarts between Discord interactions. Drafts expire after a short TTL, currently 15 minutes. If a draft expires, the user should click Apply for Beta again and restart the flow.

## Controlled Values

Device selections are stored in `devices_json` as canonical values:

- `android_tv`: Android TV
- `fire_tv`: Fire TV
- `windows`: Windows
- `android_mobile`: Android Mobile
- `ios`: iPhone / iPad
- `google_tv`: Google TV
- `nvidia_shield`: NVIDIA Shield
- `other`: Other

Feature and integration selections are stored in `integrations_json` as canonical values:

- `playback`: Playback
- `search`: Search
- `library`: Library
- `watchlist_favorites`: Watchlist / Favorites
- `iptv_epg`: IPTV / EPG
- `recordings`: Recordings
- `stremio_addons`: Stremio Addons
- `usenet`: Usenet
- `debrid`: Debrid
- `trakt_calendar`: Trakt / Calendar
- `desktop_app`: Desktop App
- `billing_premium`: Billing / Premium
- `onboarding_login`: Onboarding / Login
- `ui_navigation`: UI / Navigation

Stability preference is stored in `stability_preference`:

- `unstable_ok`: I can test unstable builds
- `mostly_stable`: I prefer mostly stable beta builds
- `release_candidate`: I only want release-candidate builds

Unknown submitted values are rejected and are not stored as canonical beta stats. Free text is limited to optional motivation/testing notes and is sanitized before storage or display.

## Beta Stats

Run:

```bash
python -m app.discord_beta_admin stats
```

This prints JSON counts for applications by status, active beta grants, selected devices, selected features/integrations, and stability preference. It does not print secrets, tokens, webhook URLs, or user credentials.

## Adding Options Safely

To add a device, feature, or stability option:

1. Add a canonical lowercase key and display label in `app.discord_beta`.
2. Keep the old key stable forever if it has already been stored.
3. Add or update tests for validation, storage, staff review labels, and stats.
4. Update this document.

Do not accept arbitrary Discord text as a canonical device, feature, integration, or stability value.

## Client Link Code

Authenticated Torve clients create a one-time Discord beta link code with:

```http
POST /me/beta/discord-link-code
Authorization: Bearer <torve_access_token>
```

Response:

```json
{
  "code": "BETA-XXXXXX",
  "expires_at": "2026-05-27T00:00:00+00:00"
}
```

The code is short-lived, single-use, and stored hashed at rest. Users should paste only this code into Discord. Never ask users to post their Torve email in Discord.

The Torve account must be active and the signup email must be verified before this endpoint will create a code. If the email is not verified, the backend returns:

```json
{
  "error_code": "email_not_verified",
  "message": "Verify your email address before applying for beta access."
}
```

Users should generate the code inside Torve only after completing account email verification.

## Staff Approval

When a Discord user submits the modal:

1. The backend validates and consumes the Torve link code.
2. The backend creates or updates the Discord account link.
3. The backend stores the beta application.
4. With `DISCORD_BETA_AUTO_APPROVE=false`, the bot posts a review card in `DISCORD_BETA_REVIEW_CHANNEL_ID`.
5. Staff press Approve or Reject.

`#beta-info` should stay clean: it contains only the permanent bot-authored Apply for Beta message. Staff review cards are posted only in the configured staff review channel, and approval/rejection updates edit that staff review interaction in place instead of posting public approval or rejection messages.

Approve creates one active `discord_beta` grant for `TORVE_BETA_GRANT_DAYS`, capped at `BETA_FREE_ACCESS_END_AT`, marks the application approved, and attempts to assign the Discord Beta Tester role. Re-approval is idempotent and does not create duplicate active grants.

Reject marks the application rejected and does not grant app access.

## Expiry

Run:

```bash
python -m app.discord_beta_admin expire-grants
```

This marks due active beta grants expired, also expires any remaining active grants after July 31, 2026, and attempts to remove the Beta Tester role. A Discord failure for one user is logged in sanitized form and does not stop the rest of the cleanup.

The same cleanup path also marks expired Discord beta application drafts consumed so stale sessions do not linger. Expired drafts do not block users from starting a new application.

## Revoke Access

Run:

```bash
python -m app.discord_beta_admin revoke --torve-user-id <uuid> --reason "<brief reason>"
```

Revocation marks active beta grants revoked, updates related applications, and attempts to remove the Discord role.

## Status

Authenticated clients can read beta state with:

```http
GET /me/beta/status
GET /me/access-state
```

`/me/access-state` includes:

```json
{
  "has_premium_access": true,
  "access_tier": "beta",
  "entitlement_type": "beta_access",
  "source": "discord_beta",
  "beta_access": {
    "active": true,
    "source": "discord_beta",
    "expires_at": "2026-06-26T00:00:00+00:00",
    "status": "active"
  }
}
```

Paid entitlement records remain separate from beta access, but an active beta grant is promoted into the top-level access state with `access_tier=beta` so clients that gate on `/me/access-state` can unlock app access. Paid premium users are still eligible to apply for Discord beta if `/me/beta/status` reports `can_apply=true`; client UI should use the beta status response instead of treating premium access as a beta block.

`GET /me/beta/status` also returns `beta_signup_close_at`, `beta_free_access_end_at`, `can_apply`, and `blocked_reason` so clients can show closed-signup or ended-access states without inferring them from Discord roles.

## Safe Testing

- Use a test Discord server first.
- Use test Torve accounts only.
- Keep `DISCORD_BETA_AUTO_APPROVE=false` until staff review has been exercised.
- Verify the bot role is above the Beta Tester role.
- Confirm `/me/access-state` changes only after backend approval, not when a role is manually added in Discord.

## Security Warnings

- Do not leak bot tokens, webhook URLs, private keys, Torve secrets, user tokens, or `.env` contents.
- Do not ask users to post email addresses in Discord.
- Do not treat Discord roles or Discord community verification as Torve email verification.
- Do not accept credentials, provider names, playlist links, tokens, private file paths, signed URLs, or illegal sources in application answers.
- Discord interaction errors are sanitized for users and logs.
- Beta grants are not paid premium entitlements and must remain separate from subscriptions, lifetime purchases, store claims, and rebate grants.
