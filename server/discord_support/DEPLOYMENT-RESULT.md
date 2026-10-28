# Torve Discord restructure — applied 25 October 2026

Later implementation update: conversational bot v2 is now available locally; see
[AUTONOMOUS.md](AUTONOMOUS.md). The new bot remains undeployed. The bot-gap section
below records the state immediately after the server restructure.

The owner approved deployment of the restructure. The existing Torve server was
updated through the connected owner session and its existing authorised bot on
the Torve VPS. The new support bot was **not deployed, enabled or registered**.

## Verified before / after

| Before | Applied result |
|---|---|
| 17 public channels | Four active public channels: start-here, announcements, help, support |
| START HERE / rules | Renamed start-here; existing message now combines rules, official installation links and support routes |
| START HERE / announcements | Kept, same channel ID and release history |
| GENERAL / general | Renamed help; same history, platform/version guidance and 10-second slow mode |
| GENERAL / bugs | Renamed support forum; same ID/history, platform/topic/status tags and tag requirement |
| downloads, faq | Official links folded into start-here; original channels preserved privately in ARCHIVE |
| new-members, show-your-setup, suggestions | Preserved privately in ARCHIVE; new questions/requests go to help/support |
| android-tv, fire-tv, windows, mobile | Preserved privately in ARCHIVE; support forum supplies platform tags |
| iptv-epg, stremio-addons, usenet-debrid, plex-jellyfin-kodi | Preserved privately in ARCHIVE; support forum supplies topic tags |
| reddit-outreach | Preserved privately in ARCHIVE |
| SETUP HELP category | Reused as ARCHIVE, with a staff-only policy |
| INTEGRATIONS category | Removed only after it contained no channels |
| BETA / beta-info, beta-feedback | Kept with their original, distinct permission exceptions |
| Expired July beta offer/application button | Historical notice replaces the old offer; button retired; accounts, roles and entitlements unchanged |
| STAFF / mod-log, staff-chat | Kept with original permissions and history |
| No dedicated support queue | Created private STAFF / support-queue, ID 1563813545911787580; staff access only until bot deployment |
| Nine default channels, device/integration questions | Four default channels; required rules acknowledgement and Verified User assignment retained |
| Guide tasks linked to new-members/downloads/faq | Tasks now visit help, announcements and support; Read the rules remains |
| Guide resources: Rules, Downloads, FAQ | One existing Community Rules resource, verified to open the updated start-here message |

There are now five categories and 23 physical channels, including the 14 private
archives and the new queue. No message-bearing channel was deleted. The role list,
role assignments and permission bitfields were preserved. Onboarding removed the
device/integration choices, which automatically cleared those roles' onboarding
flags; this did not change their permissions or remove them from members.

## Verification and recovery

The final Discord API check passed all seven assertions: all 14 archives have the
exact staff-only category policy; no temporary bot exception remains; all original
role permission bitfields are restored; original channel IDs remain except the
empty category; beta access is unchanged; the four onboarding defaults are exact;
and the new queue denies normal member access. The existing Beta Bot's original
permission value is restored to `268520448`, without Administrator, Manage
Channels or Manage Server.

The saved Server Guide was reopened in the owner session. It showed only the
Community Rules resource, and opening it displayed the updated start-here content.
The three corrected tasks saved successfully. A separate attempt to rename that
resource was rejected by Discord, so its original label is retained. The owner
view and API policy checks do not substitute for testing a fresh member join.

Private before/after inventories and edited-message snapshots are on the VPS in
`/var/backups/torve-discord/2026-10-25-restructure/`, restricted to root. Restore
names, parent IDs, ordering, overwrites and onboarding from `before.json` to roll
back. Restore edited cards from their separate content snapshots if needed. Do
not blindly rerun the migration: Discord did not propagate REST category edits
to all child permissions; owner cleanup was performed and independently verified.

## Bot status and member use

Members can currently ask humans in help, create a support forum post, or contact
support@torve.app privately. The new `/torve` command service is local code only.
It supplies reviewed FAQs, deterministic troubleshooting, consented encrypted
intake, ownership checks, staff replies, deletion and rate limits. Its 20 isolated
offline tests passed previously; that result is reused because service code,
dependencies, tests and test environment have not changed in this restructure.
All five recorded source hashes and all four pinned runtime dependency versions
were freshly checked against that passing result. The three operator scripts
passed fresh syntax checks; the channel map was checked for all 14 archive entries.

Wako's observed bot provides a richer conversational experience. The Torve target
is ordinary questions, targeted follow-ups, remembered case context, documented
troubleshooting, persistent issue status and human takeover. That conversation
layer is **not implemented yet**. See [WAKO-REFERENCE.md](WAKO-REFERENCE.md) for
the observed examples and exact gap, and [README.md](README.md) for the current
code, privacy controls, deployment guide and staging plan.

Before public bot deployment: test a fresh member join and member/staff views;
exercise signed Discord commands and a consented request/reply; verify queue
isolation, deletion and failure/rate-limit handling. The conversational upgrade
also needs multi-turn, cross-member isolation, opt-out and staff-takeover tests.
No live bot transport or AI deployment is claimed by these server changes.
