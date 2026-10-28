# Torve conversational support and restricted Codex repair

Implemented locally on 25 October 2026. The server restructure is live; this new
bot, its commands, AI model and Codex worker have **not** been enabled publicly.

## Member experience

1. A new help message or public support post receives a receipt and an AI privacy
   notice. Before consent, its text is not stored or sent to the model. Agree and
   start retrieves only that member's original message, not channel history.
2. Accepted requests receive a separate **Received** acknowledgement before
   diagnosis. The receipt sender can run while an earlier model turn is working.
   Network failures remain possible and are reported through delivery counters.
3. The bot remembers the last 12 messages in that member's case, platform/device,
   exact version/build/distribution, expected/actual result and attempted checks.
   It asks at most two useful questions or suggests at most two reviewed checks.
4. It checks Torve's live stable release manifest and compares the member's full
   reported build. Published metadata does not prove a store rollout or a fix.
5. Safe questions/checks that stall, eight-turn conversations, bugs, feature
   requests, private account matters or explicit human requests become actual
   encrypted staff tickets. No report is claimed before it is saved. A fresh
   privacy check is required for the queue; failure gives the private email route.
6. Staff takeover pauses AI advice. New evidence updates the existing ticket.
   Staff responses are queued back to the original public conversation. Staff
   status updates and member-confirmed resolution are recorded separately in the
   audit trail. A member can reopen a case while their consent remains active.
7. Stop AI, human help, resolution and deletion have buttons. Slash-command
   controls are also available. Deletion acknowledges immediately, then removes
   stored cases, tickets, context, pending replies/tags and repair jobs; it keeps a
   pseudonymous opt-out and short replay tombstones. Public Discord messages and
   independently retained email/provider records cannot be erased by this store.

Members need no slash command for an ordinary support conversation after consent.
Useful shortcuts: `/torve conversation action:human`, `/torve build`,
`/torve ticket`, `/torve forget`, and `/torve privacy`.

## Tags and acknowledgements

The supplied [tag preview](tag-preview.json) fits Discord's 20-tag limit. The
existing server already has 18; **Question** and **Solution** must be added by the
owner during bot setup. The support bot never creates or deletes channels or
changes the forum's taxonomy itself.

| Dimension | Tags / behaviour |
|---|---|
| Platform | Android TV, Fire TV, Windows, Android Mobile, iOS / tvOS |
| Problem category | Setup, Playback, IPTV / EPG, Sources, Addons, Libraries, Account |
| Inquiry type | Question, Feature |
| Status | New, Needs Info, Investigating, Resolved, Duplicate |
| Solution | Solution, only with actual member/staff resolution |

The bot updates real forum tags for the **post author's** active case. A different
participant's separate case cannot relabel the original post. It preserves an
existing platform/topic choice while those facts are unknown and applies at most
five tags. In the help text channel, replies show the same labels as text.
Investigating requires actual staff action; merely queueing a request leaves it
New. Duplicate links require staff confirmation and never expose another member's
private case details. A solution tag does not mean a software fix was deployed.

Receipts are durable and duplicate events do not create duplicate case work.
Within request limits, receipt handling is independent of model latency. Floods
are bounded; request-limit notices explain when a message was not queued.
Tag writes retry five times with backoff, then remain visible as failed work.

## Current build verification

`builds.py` fetches only `https://torve.app/downloads/releases.json`, refuses
redirects and oversized/malformed responses, validates metadata and caches for
five minutes. It never opens a user-supplied URL. It reports the stable platform,
version, numeric build, source URL and observation time. A failed refresh produces
an unavailable result instead of presenting old cache data as current.

The live manifest inspected during implementation declared a base commit **and
uncommitted worktree changes**. A base commit alone therefore cannot reconstruct
that build. Automatic repairs hold for human provenance review in this situation.
This is an intentional application of the owner's condition that a fix be valid.

## Codex repair contract

`repairs.py` persists an encrypted handoff; the public bot has no Git or Codex
execution capability. `fix_runner.py` is a separate worker on an explicitly
isolated Linux host, with an external operator-owned policy.

The worker proceeds only when all gates pass:

1. A bug report supplies actual/expected behaviour, safe reproduction steps,
   platform, stable distribution and an exact current version/build. Features,
   account/payment/auth/licensing matters, piracy, bypasses and uncertain source
   provenance require human review.
2. The verified manifest digest and complete source commit match the runner's
   approved policy. It exports that immutable revision into a disposable source
   directory, excluding the shared checkout and all its uncommitted changes.
3. Read-only Codex assessment confirms an ordinary authorised, lawful small bug
   in an explicitly allowed category, then proposes a regression test under the
   approved test prefix. Uncertainty stops the job.
4. A trusted baseline check passes; the added regression must then fail. The
   check is an operator-supplied argument array, never a shell command supplied by
   a Discord member or model.
5. Codex proposes a separate code patch: at most three code files and 120 changed
   lines. Regression changes are limited to two test files and 100 lines. No
   renames, deletions, binaries, symlinks, dependencies, workflows, migrations,
   credentials, auth/billing/licensing or broad structural changes qualify.
6. The regression passes with the fix and remains unchanged. A separate read-only
   Codex review must approve scope, lawfulness and preserved behaviour. These
   checks reduce risk; model judgements and tests are not a proof of correctness
   or a legal certification. Uncertainty still requires a human.
7. The result is a private patch plus a receipt recording source, digest and test
   evidence. The member receives a factual **proposal ready, not deployed** update.
   The worker does not push, merge, publish, release or deploy code.

Codex itself runs in a separate digest-pinned container: only read-only source,
a temporary result folder and dedicated Codex authentication are mounted. Its
network must be a separately configured egress network restricted to the required
OpenAI authentication/API endpoints. No support-store key/database, bot token,
customer files, host home, deployment credential or Docker socket is mounted.
Codex still uses its read-only sandbox; an unsupported sandbox fails the job.

Test commands run in a preloaded digest-pinned container with no network, no
credentials/home or Docker socket, restricted resources and a read-only root
filesystem. Only the disposable source directory is writable; its Git metadata
is read-only. The Codex process itself must run in an isolated environment with
dedicated authentication and no production secrets. An environment flag is an
operator attestation, not an operating-system isolation boundary.

Apple-native fixes need the configured MacinCloud validation route. A Linux-only
check must not be presented as iOS/tvOS runtime verification, and the worker must
hold such a repair if its approved checks cannot establish the fix. No paid
GitHub-hosted Mac fallback is configured.

There are at most two repair jobs per rolling 24 hours. Each Codex invocation has
a four-minute timeout; checks have three minutes each. A stale assessing job is
held for human review after 30 minutes. Stop/delete cancels pending work and the
runner rechecks consent/job existence between stages. An already-running model
request cannot be unsent. Patch artifacts have no automatic public publication;
operators must expire them and their regression fixtures within the case window.

## Deployment

1. Review the exact command/tag previews, notice, FAQ/playbook content and this
   repair policy. Test in a private staging server before separate public-launch
   approval. Keep the existing Torve Beta Bot and its credentials separate.
2. Create the dedicated support application. Configure its Ed25519 public key,
   application ID, token, approved OpenAI model/key, Fernet key and the supplied
   channel/role IDs in a protected environment file. Never paste secrets in chat.
3. Enable Message Content Intent in the Discord portal. Grant View Channel,
   Send Messages and Read Message History only for help, support and the private
   queue. Grant **Send Messages in Threads** and **Manage Threads only on the
   support forum** for moderated status tags. Do not grant Administrator, Manage
   Server, Manage Channels, Manage Roles, Manage Webhooks, bans or mass mentions.
4. Add the Question and Solution tags from the preview; make Solution and the
   five status tags moderated. Publish the privacy notice in start-here and help.
   Set `DISCORD_SUPPORT_NOTICE_READY=true` only after doing so.
5. Build the supplied Dockerfile from `server/`. Its Dockerfile-specific ignore
   file and explicit COPY list exclude local environments, customer files,
   migration backups and the Codex worker. Run one ASGI worker with an encrypted
   `/data` volume, read-only root, dropped capabilities and no Docker socket.
   Expose only the interaction endpoint through the existing HTTPS proxy.
6. Set `DISCORD_SUPPORT_ENABLED=true`, `DISCORD_AI_ENABLED=true`,
   `DISCORD_AUTONOMOUS_ENABLED=true` and `DISCORD_SUPPORT_TAGGING_ENABLED=true`.
   Keep the Codex handoff off initially. Run command registration explicitly with
   `python -m discord_support.commands --register`; configure the dedicated
   application's interaction URL. Do not reset the existing beta webhook.
7. Verify `/ready` returns 200 only with a connected gateway and a verified private
   queue. Run the staging tests below and inspect controlled logs/metrics.
8. Provision the separate Codex runner with a clean release source mirror,
   dedicated authentication, preloaded digest-pinned Codex/test images, a restricted
   Codex egress network and narrow
   [external policy](fix-policy.example.json). The example deliberately fails
   validation until actual source/digest/component/check values are reviewed.
   Neither bot secrets nor its production account database belong in that runner.
9. To enable repair handoffs, set `DISCORD_CODEX_HANDOFF_ENABLED=true` after runner
   validation. A separate restricted worker uses the support store/encryption key
   to consume the queue, while those secrets are excluded from Codex and test
   child-process environments. Invoke `python -m discord_support.fix_runner` for
   a read-only queue preview; `--once --policy /protected/policy.json` processes at
   most one job, with `TORVE_FIX_RUNNER_ISOLATED=true` set only in that environment.
   The supplied [service](deploy/torve-support-repair.service) and
   [timer](deploy/torve-support-repair.timer) invoke it once per minute without
   overlapping jobs. Configure the dedicated user's Docker access and storage
   mounts deliberately; the example [worker environment](deploy/repair.env.example)
   requires only the support-store location/key and enable flags, not bot or
   OpenAI tokens. The [bot Compose file](deploy/compose.yaml) is separate.

## Limits, retention and operations

Conversation input is capped at 1,800 characters, six messages/member/minute,
60/guild/minute, 100 queued messages and three new cases/member/day. Gateway intake
has four bounded receive slots. The worker diagnoses sequentially, keeping SQLite
on one event loop; acknowledgements have their own sender. The model gets no
tools, routing IDs, other members' context, private guide or attachment content.
It selects allowlisted diagnostic steps and references; explanations require
reviewed source IDs and reject action/deployment claims and executable commands.
Generative answers still require evaluation; schema validation does not prove facts.

AI calls use Responses with `store:false`, a strict schema and 1,600 output-token
cap. Shared daily call limits and conservative byte-plus-output reservations bound
usage; unused reservations are not refunded. Configure a provider spending cap
and choose limits deliberately at deployment. No paid model was selected or called
during this implementation.

Cases, tickets and repair reports expire within 30 days of the case's creation.
Inbox, seen-event and reply records last at most 24 hours; command replay cache is
10 minutes. Positive consent expires after 30 days. Pseudonymous opt-out persists
so the bot does not silently resume. Keep backups within the same retention
window and apply deletion again after any restore. All detailed content and
routing metadata are encrypted; quotas and preferences contain HMAC identities.

`/health` exposes operational counts only: backlog age, pending/uncertain replies
and pending/failed tag writes. Controlled logs include gateway, AI, acknowledgement,
handoff, delivery and tagging failures without message bodies/identifiers. HTTP
and gateway SDK packet logs are suppressed. Configure operator alerts for a lost
gateway, failed readiness, backlog >60 seconds, uncertain replies, exhausted tag
retries, queue privacy failure, AI budget/failure spikes and repair jobs held for
review. External alert delivery has not been configured by this task.

An ambiguous Discord send is held as uncertain rather than blindly retried after
a crash. Discord nonce handling helps with short duplicate retries but is not an
exactly-once guarantee. After consent, already accepted messages persist across a
restart; messages received during an actual gateway outage are not backfilled.

## Concise staging acceptance

- Post as a fresh member in help and a support post: receipt/consent first, then
  diagnosis; verify a follow-up is acknowledged while another model call is slow.
- Follow a multi-turn playback/sync case, check retained facts and avoided repeat
  questions, then check human takeover, follow-up evidence, resolution and reopening.
- Inspect actual Question/Feature, topic, platform, Needs Info, Investigating,
  Resolved/Solution and Duplicate tags; try another member's post and a locked,
  archived or private thread. Missing tags/permissions must produce a visible failure.
- Compare stable, beta, older, equal-semver/different-build and unavailable build
  metadata; simulate stale/failed manifests and incomplete source provenance.
- Exercise ownership, stop/delete during pending work, restart, timeout, refusal,
  prompt injection, rate/budget exhaustion, failed queue privacy and ambiguous sends.
- In the isolated runner, verify baseline pass → regression fail → fixed pass,
  independent review, cancellation and hard rejection of sensitive/large changes.
  Verify the final patch receipt and factual member update; no deployment occurs.

Primary references: [Discord message creation](https://docs.discord.com/developers/resources/message#create-message),
[thread permissions](https://docs.discord.com/developers/topics/threads),
[gateway intents](https://discordpy.readthedocs.io/en/stable/intents.html),
[OpenAI structured outputs](https://developers.openai.com/api/docs/guides/structured-outputs),
[Codex non-interactive execution](https://developers.openai.com/codex/noninteractive).
