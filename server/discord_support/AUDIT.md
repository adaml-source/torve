# Torve Discord support audit and proposed channel map

**25 October 2026 · Europe/Berlin · original pre-migration audit**

The owner subsequently approved the restructure. It has now been applied and
verified; see [DEPLOYMENT-RESULT.md](DEPLOYMENT-RESULT.md). The observations and
proposal below describe the state before that migration, not the current server.
The support bot remains undeployed. [WAKO-REFERENCE.md](WAKO-REFERENCE.md) records
the later inspection and the conversational functionality still to implement.

The connected Discord desktop session was available and used to inspect **Torve
Media Community**, server ID `1507992418358919338`. The server profile showed
**15 members**, six categories and 22 channels: 17 public channels, two restricted
beta channels and three staff channels. “Private” is a Discord permission label;
it does not mean only beta testers can see a channel.

At the time of this initial audit, no channel, category, role, permission,
onboarding answer, message, integration or bot deployment was changed.
Navigation and search fields were used for inspection.
No messages, reactions or approval buttons were sent/pressed. No user token or
Discord private API was extracted. The existing Torve Beta Bot was not modified.

## Findings that affect support

1. **Too many destinations for the observed traffic.** Four device-help channels
   have no messages in the inspected views; four integration-help channels contain
   the same pinned safety guidance with no member troubleshooting. The public
   bug, suggestions and setup-sharing forums show their empty-state screens.
   Actual member questions appear in `#general` and one beta feedback thread.
2. **Old beta terms are still presented as current.** `#beta-info` advertises a
   signup deadline of 1 July 2026 and the end of free beta Premium on 31 July 2026.
   Its application button still appears. Do not assume that button or its backend
   is live; it was not clicked. The public website now lists free Android/Fire TV/
   Windows access and Apple releases in preparation. Rewrite the notice against
   current product policy before inviting new testers.
3. **Support depends on the owner.** Historical access fixes were handled manually
   in conversation, without a request ID, clear status, reproducible intake or
   recorded staff handoff. The Moderator role has zero members. A bot needs an
   actual human owner/backup; it cannot promise a response time on its own.
4. **Repeated test applications obscure staff work.** The visible `#staff-chat`
   history contains repeated tester/e2e application review cards from 15 June,
   including Discord and Torve identifiers. Preserve their privacy. Do not use
   those cards as public examples, knowledge-base input or proof of real support
   demand. Create a separate queue for new support work.
5. **Permission exceptions need deliberate handling.** Staff chat, beta info and
   beta feedback all report that permissions are **not synced with their category**.
   Beta info grants access to Verified User as well as beta/staff roles; beta
   feedback grants access to Beta Tester and staff. Blind category sync would
   change access. Inventory the actual overwrites before any approved migration.
6. **Release information needs clearer platform labels.** Visible announcements
   contain duplicate Windows 1.1.7 entries, including a Fire TV OOM fix described
   on a Windows release card. The latest visible card is Windows 1.3.0 from
   29 August. Keep announcements, point downloads at the live manifest, and review
   platform-specific notes. Historical release notes are not evidence that a bug
   is still open or that a fix is verified on every platform.
7. **Onboarding is broad.** Advanced onboarding is enabled, with nine default
   channels (the whole START HERE and GENERAL categories), one chattable default,
   one rules acknowledgement and two post-join questions. All 17 public channels
   are assignable. The Server Guide already offers Rules, Downloads and FAQ
   resource cards; those can carry information without three separate reading
   channels. The mobile onboarding answer says “Mobile”, while the role inventory
   says “Google Mobile”; check that mapping when simplifying roles.

## Before → after, every existing channel

“Archive” below means preserve the existing channel ID/history, make it read-only,
and hide it from normal onboarding under a staff-only ARCHIVE category **after
approval**. A merge carries useful instructions into the destination and leaves
a redirect in the old channel before archiving. Discord history is not copied
into new messages or impersonated. No initial channel deletion is proposed.

| Current category/channel | Observed content | Exact proposed action |
|---|---|---|
| START HERE / announcements | Release cards, May–August | **Keep** in START HERE; staff/release integration may post; members read |
| START HERE / rules | Rules embed, pinned | **Keep + rename `start-here`**; retain rules and add official download, guide, help, privacy and human links |
| START HERE / downloads | One download/setup embed | **Merge into `start-here`; archive**; live website remains the version/download source |
| START HERE / faq | One FAQ embed | **Merge links and stable basics into `start-here`; archive**; bot owns reviewed FAQ lookup |
| START HERE / new-members | Join/wave system messages | **Archive**; use Server Guide for welcome; stop join-message noise only after checking system-channel configuration |
| GENERAL / general | Historical real support conversation | **Keep + rename `help`**, move to SUPPORT; bot commands and human routing; preserve all history |
| GENERAL / show-your-setup | Empty forum view | **Archive**; setup details belong in the relevant support post |
| GENERAL / suggestions | Empty forum view | **Merge into `support` as Feature tag; archive** |
| GENERAL / bugs | Empty forum view, existing platform/status tags | **Keep + rename `support`**, move to SUPPORT; one public issue per forum post |
| SETUP HELP / android-tv | Empty text channel view | **Merge into `support` with Android TV tag; archive** |
| SETUP HELP / fire-tv | Empty text channel view | **Merge into `support` with Fire TV tag; archive** |
| SETUP HELP / windows | Empty text channel view | **Merge into `support` with Windows tag; archive** |
| SETUP HELP / mobile | Empty text channel view | **Merge into `support` with Mobile tag; archive**; distinguish Android/iOS in intake |
| INTEGRATIONS / iptv-epg | Safety post + pin | **Merge into `support` with IPTV/EPG tag; archive** |
| INTEGRATIONS / stremio-addons | Same safety post + pin | **Merge into `support` with Addons tag; archive** |
| INTEGRATIONS / usenet-debrid | Same safety post + pin | **Merge into `support` with Sources tag; archive** |
| INTEGRATIONS / plex-jellyfin-kodi | Same safety post + pin | **Merge into `support` with Libraries tag; archive** |
| BETA / beta-info | Rules, old deadlines, application card | **Keep restricted; rewrite current policy**; keep Verified User recruitment visibility only if intentional |
| BETA / beta-feedback | One historical access thread with a confirmed resolution | **Keep restricted**, outside default onboarding; retain Beta Tester/staff access |
| STAFF / mod-log | AutoMod activity-alert and community notices | **Keep private**; preserve both system destinations |
| STAFF / staff-chat | Beta review cards and test noise | **Keep private**, separate from support; disable production test posting in its owning integration after review |
| STAFF / reddit-outreach | Empty view | **Archive** as outside support scope |
| New STAFF / support-queue | Does not exist yet | **Create private text channel only after approval**; opaque bot request IDs and authorised staff commands |

Result: **4 public active channels**, 2 beta channels, 3 staff channels = **9 active
channels**, plus 14 preserved archives. There would be 23 physical channels because
the new support queue is added; this reduces member-facing clutter without
discarding history. Remove the empty GENERAL, SETUP HELP and INTEGRATIONS categories
only after their channels have been moved and the owner approves the final list.
If no current beta program exists, archive its two channels as a separate decision;
do not silently retire it or remove roles/integrations.

```text
START HERE
  #start-here       rules, install/guide links, privacy, human help
  #announcements    releases and significant service notices
SUPPORT
  #help            /torve commands and routing
  #support         forum: one problem or feature request per post
BETA               restricted, excluded from normal onboarding
  #beta-info
  #beta-feedback
STAFF              restricted
  #mod-log
  #staff-chat
  #support-queue    new; support bot and authorised staff only
ARCHIVE            restricted, read-only history
  14 retired channels
```

## Roles and permissions

All 15 custom roles were inventoried. Counts are the UI values at inspection and
can overlap because members hold multiple roles.

| Current role | Members | Recommendation |
|---|---:|---|
| Torve Team | 1 | Keep; administrator access was visible on private-channel access lists; reserve for the owner/team |
| Moderator | 0 | Keep; appoint a real support backup with least privilege |
| Torve Beta Bot | 1 | Keep managed role/integration separate; Administrator switch was **off** |
| Beta Tester | 2 | Keep while the program exists; controls beta feedback access |
| Verified User | 13 | Keep pending verification-flow review; currently also sees beta info |
| Muted | 0 | Retire only after verifying no AutoMod/bot dependencies; prefer native timeout for future moderation |
| Android TV | 2 | Remove from required onboarding; platform belongs on support posts; retire later after dependency check |
| Fire TV | 1 | Same |
| Windows | 1 | Same |
| Google Mobile | 1 | Same; reconcile mobile answer label |
| IPTV / EPG | 3 | Same; topic belongs on support posts |
| Stremio Addons | 2 | Same |
| Usenet / Debrid | 1 | Same |
| Real-Debrid | 0 | Retire after integration/permission dependency check; avoid duplicating broader Sources topic |
| Plex / Jellyfin / Kodi | 1 | Same; topic belongs on support posts |

The `@everyone` role is additional to these 15. Its Administrator, Manage Channels
and Manage Roles switches were off; View Channels was on. This does not by itself
establish every member's effective permissions.

**Verified channel exceptions:** `staff-chat` is private and unsynced, with Torve
Team (administrator), Moderator, Torve Beta Bot and the server owner listed.
`beta-info` is private and unsynced, with Team, Moderator, Beta Bot, Beta Tester,
Verified User and owner. `beta-feedback` is private and unsynced, with Team,
Moderator, Beta Tester and owner. This distinction must survive migration.

**Target permission matrix:**

| Surface | Normal/verified members | Beta Tester | Team/Moderator | New support bot |
|---|---|---|---|---|
| start-here, announcements | Read; no member posting | Same | Publish/edit as assigned | No publishing access |
| help | Read/send/use commands; 10-second member slowmode | Same | Answer/moderate | Commands only; ephemeral replies |
| support forum | Create/reply, no manage privileges | Same | Triage/moderate | No ambient reads or autonomous posts |
| beta-info | Recruitment visibility only if retained intentionally | Read | Maintain | No access |
| beta-feedback | Hidden | Post/reply | Moderate | No access |
| staff-chat, mod-log | Hidden | Hidden | Assigned staff only | No access |
| support-queue | Hidden | Hidden | View/use staff commands | View/send only; no admin or role management |
| archives | Hidden by default | Hidden unless specific retained access required | Read history | No access |

Self-assigned topic/device roles must not grant staff, account, billing or beta
feedback access. Keep all staff/bot roles above vanity roles; the support bot does
not need to assign roles. Do not remove managed roles manually. The existing
Beta Bot's non-administrator permissions were not exhaustively exported.

## Support evidence and recurrence

| Theme | Evidence | Interpretation and bot behavior |
|---|---|---|
| Beta accepted but Premium still locked | General: 28 May; separate Shield Pro beta thread: 29 May | **Two independent historical reporters**. Owner manually fixed access; the beta thread confirms success. Route privately to a human; the support bot cannot grant entitlements. |
| M3U disappears after restart | General: 28 May | One historical report; subsequent Xtream suggestion did not resolve the reporter's issue. Collect platform/version, restart steps and persistence result; never request the playlist URL. |
| Xtream setup does not work | General: 28–29 May | One reporter in the same discussion; unresolved in the sampled conversation. Collect safe error/context, route to Sources troubleshooting, then staff. |
| Where to get beta link code | General: 4 June | One question; owner pointed to app settings. Because the program notice is expired, do not turn this old instruction into a current enrollment promise. |
| P2P support | General: 28 May | One historical capability question, answered no then. Verify current product documentation before giving a present-day capability answer. |
| Windows installer warnings and device transfer | FAQ/download embeds: 24 May; release cards | Existing documentation topics, **not counted as repeated member incidents**. Link current official download and guide pages; do not tell users to bypass OS protections. |
| Large IPTV playlist crashes | Announcement cards: 17 August | A historical claimed fix, not a fresh user report or independent validation. Playback/source intake should capture device and app version. |

There is insufficient message volume to rank current problems statistically or
claim that all of these issues still reproduce. Other-server conversations were
not used as evidence about Torve.

Useful evidence links: [general history](https://discord.com/channels/1507992418358919338/1508057781297741884),
[resolved beta thread](https://discord.com/channels/1507992418358919338/1509925242666225784),
[FAQ embed](https://discord.com/channels/1507992418358919338/1508057663601115180/1508160670993223720),
[beta info](https://discord.com/channels/1507992418358919338/1508058459797585981),
[release announcements](https://discord.com/channels/1507992418358919338/1508057632550817792).
Access to these links follows the server's existing permissions.

## Proposed onboarding and support flow

Show four default channels only. Keep one required acknowledgement covering
respect, lawful media, no credentials/private data and the public nature of forum
posts. Remove mandatory device/integration selection; put those choices in issue
intake. Preserve Verified User assignment until its dependency is understood.
Server Guide resources: **Install**, **Ask for help**, **Contact the team**. Show
beta enrollment only when a current program and terms are approved.

Member flow: start-here → `/torve ask` in help → guided troubleshooting → either a
sanitised public forum post or consented private `/torve issue` → staff response
retrieved with `/torve ticket`. Private account/billing/security requests always
have the email route. A public forum is not a confidential ticket system.

Require one platform and one topic on forum posts. Suggested platform tags:
Android TV, Fire TV, Windows, Android Mobile, iOS/tvOS. Topics: Setup, Playback,
IPTV/EPG, Sources, Addons, Libraries, Account, Feature. Limit status tags to New,
Needs Info, Investigating, Resolved and Duplicate; staff apply status. Use the
post template: app version/distribution, model/OS, expected/actual behavior, exact
error, safe reproduction steps, checks already tried. Keep credentials, URLs,
account identifiers and full diagnostics out of public posts.

## Approval and migration sequence

1. Review this exact map and the bot's [command preview](command-preview.json),
   [code](service.py), [deployment and tests](README.md). Confirm actual staff
   coverage and whether the beta program is active. No action is implied by reading
   the proposal.
2. Before migration, capture channel IDs, all role permission bitfields, channel
   overwrites, onboarding/system-channel references, webhooks and bot destinations
   using an authorised admin export or read-only bot preflight. Keep that export
   private. The audit's channel IDs are in [channel-map.json](channel-map.json).
3. After explicit approval, rename the three retained public channels, copy only
   reviewed resource text, create the private support queue, configure forum tags,
   then stage redirects. Existing beta/release integrations keep their channel IDs.
4. Test the member, Verified User, Beta Tester, Moderator, owner and support bot
   views in staging. Confirm queue isolation and the unsynced beta exceptions.
   Configure new onboarding only after its destinations exist.
5. Make the 14 retired channels read-only and move to the restricted archive.
   Remove their old onboarding references and empty categories. Verify invite,
   rules, AutoMod, release posts and beta application destinations still work.
6. Deploy the bot only after separate public-deployment approval and staging
   acceptance. Keep AI off initially. Roll back via the saved names/overwrites and
   stop the bot if necessary. Deleting any archived channel requires a later,
   channel-specific decision; this proposal contains no initial deletion step.

## Coverage and limitations

All 22 channel destinations were opened in the connected session, including the
beta forum post and staff channels. The audit covers the loaded message/forum
views, onboarding setup, Server Guide resources, all role names/counts and the
targeted permission checks above. It is not a full export of every historical
message, archived thread, member-specific overwrite, webhook secret, AutoMod
rule, audit log event or every permission bit across all roles/channels. No
ordinary-member account was impersonated. Unknown effective permissions must be
checked before applying the proposed matrix. Screens showing private identifiers
were inspected for the audit but not copied into the public FAQ or this report.

The live public support, download and Fire TV pages returned HTTP 200 and were
saved as source snapshots. The bot is implemented and tested offline. Live
Discord command transport, real model availability, hosting and full member-view
acceptance remain untested. The later server changes are recorded separately in
the deployment result; this command bot has not been deployed.
