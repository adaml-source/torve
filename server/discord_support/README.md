# Torve Discord support bot

Conversational v2 implemented locally on 25 October 2026. **Not deployed, invited,
registered or enabled.** It adds consented automatic replies, case memory, a
durable inbox/outbox, live build checks and a restricted Codex repair pipeline.
Read [AUTONOMOUS.md](AUTONOMOUS.md) for the complete experience, repair gates and
current deployment requirements. The sections below also cover the command service.
The approved **server restructure is deployed**; see [DEPLOYMENT-RESULT.md](DEPLOYMENT-RESULT.md)
for the verified result and [AUDIT.md](AUDIT.md) for the original audit.
The command service below is still offline. Read [WAKO-REFERENCE.md](WAKO-REFERENCE.md)
for the observed conversational behavior and the implemented comparison.

This is a separate FastAPI service in Torve's canonical backend repository. It does
not import the account API, customer database, private guide, billing system or old
beta approval implementation. Existing application changes in the shared checkout
were left alone. The existing Torve Beta Bot remains a separate integration.

## Member experience

| Command | Result |
|---|---|
| `/torve help` | Command list and human contact |
| `/torve ask question:...` | Local public FAQ matching, exact reviewed answer, source link |
| `/torve ask question:... ai:true` | Explicit opt-in to AI FAQ selection if enabled by the operator |
| `/torve troubleshoot topic:...` | Playback, sources, account or Fire TV steps |
| `/torve issue ... consent:true` | Encrypted private request, after staff queue privacy preflight |
| `/torve ticket id:...` | Only the request owner can read its status and staff reply |
| `/torve forget` | Deletes the member's requests, replies and cached responses |
| `/torve privacy` | Consent, retention, data transfer and privacy explanation |
| `/torve conversation action:...` | Start/stop AI, human help, status, resolution and reopening |
| `/torve build platform:... [version:...]` | Live stable release metadata and version comparison |
| `/torve staff id:... [reply:...]` | Explicit staff role **and** private queue required; reads or answers a request |

Slash-command responses are ephemeral. With automatic mode enabled, the restricted
gateway receives new messages in configured help channels and public support posts;
case processing starts only after member consent, and replies there are public.
No DMs or attachment downloads are accepted. General commands stay in configured
text channels; conversation controls also validate support posts by their parent.
No claim is made that this code has fixed a member's device,
account, provider or app. Troubleshooting gives reversible checks and intake.

## Grounding and AI

`knowledge.json` contains six small, reviewed public FAQs. Their source pages were
fetched from the live Torve website on 25 October 2026; hashes and text snapshots
are in `evidence/`. The source-controlled guide at `app/guide/catalog.json` requires
authentication and is **not** used, copied or sent to OpenAI.

AI is off by default. The command FAQ route additionally requires `ai:true` on
each question; automatic conversations use the separate one-time consent gate
described in [AUTONOMOUS.md](AUTONOMOUS.md). The command FAQ route uses
the Responses API, `store:false`, a strict article-ID schema, no tools, and a
100-output-token cap. It selects a public FAQ ID; the service renders the actual
reviewed answer itself. Unrecognized IDs, incomplete/refused output and failures
fall back to abstention or a labelled local result. This limits invented advice,
but does not guarantee perfect relevance. Only a redacted question and public
article metadata are sent; Discord identities, message history and issue reports
are excluded. Redaction cannot detect every possible secret. OpenAI abuse
monitoring retention may still apply even with `store:false`.

Do not choose or enable a paid API model without the owner's deployment decision.
Set an explicit structured-output-capable model after checking availability in the
operator's API project. No OpenAI API credential was requested, created or used here.

## Offline validation

Run **only these isolated tests**. The repository's `server/tests/conftest.py`
connects to its configured account database and performs cleanup; this service's
tests deliberately avoid it.

From `server/` with Python 3.13:

```powershell
python -m venv --system-site-packages discord_support/.test-env
discord_support/.test-env/Scripts/python.exe -m pip install -r discord_support/requirements.txt
$env:PYTHONPATH=(Get-Location).Path
New-Item -ItemType Directory -Path discord_support/.test-runs -Force | Out-Null
$torveTestBase=Join-Path (Get-Location) ('discord_support/.test-runs/' + [guid]::NewGuid().ToString('N'))
discord_support/.test-env/Scripts/python.exe -m pytest discord_support/tests --confcutdir=discord_support --basetemp=$torveTestBase -q -p no:cacheprovider
```

Use a fresh virtual environment and install `pytest` on a clean build agent; the
local test environment above inherits the already-installed pytest package. On
Linux, use the equivalent `.test-env/bin/python` interpreter and `PYTHONPATH=.`.
`python -m discord_support.commands` prints a command preview without contacting
Discord. `--register` is a separate external write; do not use before approval.

## Deployment guide — requires approval

1. Review [AUDIT.md](AUDIT.md), channel map, command preview, data handling and
   public FAQ content with the owner. Test in a separate private staging server
   first. Do not repurpose or reset the existing Beta Bot's token or webhook URL.
2. In Discord's Developer Portal, create a dedicated Torve Support application or
   choose an explicitly approved unused application. The owner should create the
   token and store it in a server secret file/secret manager; never paste it into
   chat or source control. Save the application public key and application ID.
3. Install only to the chosen guild with `bot` and `applications.commands` scopes.
   Command-only mode needs no privileged gateway intent. Complete conversational
   mode requires **Message Content Intent**, enabled in both the portal and code.
   Members and Presence intents stay off. Grant View Channel and Send Messages
   only where needed. Add Read Message History only if Discord requires it for the
   chosen queue operations; grant Send Messages in Threads for the support forum.
   Do **not** grant Administrator, Manage Roles, Manage
   Channels, Manage Webhooks, mass mentions, ban/kick or account access.
4. The restructure created private **text** channel `#support-queue`
   (`1563813545911787580`), currently accessible to staff only. Explicitly deny
   View Channel to `@everyone`. Explicitly allow only Torve Team, Moderator and
   this support bot when its deployment is approved. Keep other bots, Beta Tester and self-assigned platform roles
   out. Put every administrator role in `DISCORD_STAFF_ROLE_IDS`, since admins
   bypass channel denies. Per-member view grants are intentionally rejected by
   the bot's conservative preflight; use approved roles. Keep this queue separate
   from `#staff-chat`, which has old beta approval cards and different overwrites.
5. Copy `.env.example` to a protected location outside the repository. Configure
   application/guild/public key, **exact `#help` channel ID**, private queue ID,
   staff role IDs and bot token. Generate `DISCORD_SUPPORT_DATA_KEY` with
   `python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"`
   in a private terminal. Do not print real deployment secrets in logs or share
   them. Use a persistent local disk path for `DISCORD_SUPPORT_DB`.
6. Build the dedicated image from `server/`:

   ```sh
   docker build -f discord_support/Dockerfile -t torve-discord-support .
   ```

   Run it as one replica and **one worker** with the secret environment file and a
   writable `/data` volume owned by UID 10001. Bind port 8012 to loopback, put it
   behind the existing HTTPS proxy, allow only POST `/interactions`, cap request
   bodies at 32 KiB and add ingress request limits. Do not expose the main customer
   API or mount its environment/DB into this service. The existing root backend
   Dockerfile runs two workers and must not be used for this service.
7. Set `DISCORD_SUPPORT_ENABLED=true`. Check `/health`; keep it available only to
   local monitoring. Configure Discord's Interactions Endpoint URL to the approved
   HTTPS `/interactions` address. Discord must accept the signed PING and reject
   invalid signatures. The service never uses a Discord user-session token.
8. After approval, register the single guild-scoped command from `server/` using
   `python -m discord_support.commands --register` with the protected environment
   already loaded. This upserts only `/torve`; it does not overwrite other apps'
   commands. Configure Discord command visibility for `#help` and the private queue.
9. Run the staging acceptance checks below. Then invite/configure in the real
   server only after the owner approves the exact public behavior. Start with AI
   disabled. Enable it only after the privacy notice, API budget and model pass
   review and staging tests. Deploy through the normal repository review process;
   do not run the main backend's broad deployment script just for this service.

## Operations, limits and privacy

- Discord Ed25519 signature verification uses the raw body plus timestamp, with a
  five-minute freshness window. Signed interaction IDs are deduplicated for ten
  minutes. Issue IDs are also deterministic per interaction, preventing duplicate
  reports if the service restarts between intake and receipt caching.
- Per member: 6 commands/minute and 3 issues/day. Per guild: 60 commands/minute.
  AI: at most 4 active background jobs and 100 requests per UTC day by default.
  Set a provider-side spending limit as well. Application limits are separate from
  Discord's dynamic HTTP quotas.
- Network work is deferred with an immediate ephemeral acknowledgement. FAQ and
  staff reply jobs are intentionally ephemeral: a process crash may lose an
  in-flight reply. A member can retry. Tickets and human replies persist in SQLite.
- The worker rechecks queue privacy every minute. Only opaque request IDs are
  posted in the staff queue. Full issue bodies stay encrypted and are displayed
  to a staff member only after a fresh privacy preflight. Notification retries
  stop after five attempts; use controlled failure events to trigger an operator
  alert. Discord nonce deduplication mitigates duplicate notifications; delivery
  is not guaranteed exactly once. A restart retries pending notifications.
- The worker honors Discord's `retry_after` and rate-limit headers, serializes
  calls, and does not retry ambiguous message sends on 5xx/network failure within
  one attempt. The conversation outbox publishes replies only in validated support
  destinations after consent. No DMs, reactions or entitlement changes exist.
- Identity is an HMAC of Discord's member ID. Issue text, staff replies and cached
  responses are encrypted with Fernet. Minimize before encrypting; exclude logs,
  media attachments, account emails, provider URLs and credentials. Protect the
  filesystem/volume and secret file; encryption does not replace access controls.
- Requests expire 30 days after creation. Cached responses expire after ten
  minutes. `/torve forget` removes their contents immediately while retaining a
  generic, encrypted replay tombstone for the remainder of that ten-minute window;
  retries cannot recreate a deleted report. Pseudonymous quota keys last at most
  24 hours. Expiry runs on commands and once a minute while the service is running.
  Run the service's purge path before enabling traffic after a prolonged outage.
  Avoid long-lived database snapshots; if backups are required, expire them within
  the same 30-day maximum and make deletion requests effective on restore. Do not
  promise deletion of independently retained Discord/email records.
- Private command tickets are polled with `/torve ticket`. Consented conversation
  cases also receive staff updates in their original public support conversation.
  The bot sends no DMs and promises no response time. Assign actual staff coverage.
  `support@torve.app` remains available during every failure mode.
- Controlled logs contain event names only (`signature_rejected`, `rate_limited`,
  `ai_failed`, `handoff_failed`, `handoff_delivered`, `staff_denied`, etc.). Disable
  proxy request-body logs, HTTP client debug logs and exception collectors that
  capture local variables. Webhook paths contain interaction credentials; do not
  record them. Uvicorn access logs are disabled in the supplied image.

Alert on sustained `handoff_failed`, `interaction_reply_failed`, repeated invalid
signatures, oldest unnotified request >5 minutes, retries exhausted, disk space,
unexpected queue privacy changes and elevated AI errors. Review unresolved requests
daily through the encrypted store and staff workflow. Do not export bodies into a
public dashboard. Counters in `app.state.counters` support in-process tests; use
the controlled event logs for production aggregation. No external monitoring or
alerts have been configured by this task.

Rollback: disable the command in Discord, stop this service, and direct members to
support@torve.app. Keep the encrypted volume only for the stated retention period.
Restore channel names/overwrites from the approved migration inventory if needed.
Rotate a compromised bot/API credential through the owner's secret management
process. Losing the encryption key makes stored requests unreadable; keep it
securely until their retention window ends.

## Concise staging acceptance plan

1. Valid signed PING succeeds; invalid, modified and stale signatures fail. A member
   in another guild, DM, beta channel or arbitrary thread is refused.
2. Exercise every FAQ and all four troubleshooting flows. Unsupported questions
   get a human route. Test prompt injection and credential-looking input.
3. With AI off, verify no OpenAI call. With explicit opt-in, test a real configured
   model, refusal, timeout and exhausted budget; verify source links and fallback.
4. Submit a consented issue, retry the same event, restart, receive the opaque staff
   notification, inspect/reply as staff, and read it as its owner. A second member
   must not retrieve it. A staff role outside the queue must be refused.
5. Temporarily misconfigure the staging queue's privacy; intake must refuse and
   staff reads must fail closed. Restore it and verify the worker recovers. Exercise
   429, 5xx, forbidden token, disk-full and notification retry exhaustion.
6. Test 6/minute, 3/day and AI caps. Inspect data at rest, logs, `/torve forget`,
   30-day expiry and restore/purge behavior. Verify ordinary members cannot see
   staff/beta archives. Measure initial acknowledgement below 3 seconds.

Live Discord transport, a real OpenAI model, container/proxy deployment and
member-role acceptance are **pending approval and credentials**; mocks do not
prove those integrations. See `TEST-RESULTS.json` for fresh local validation.

## Primary implementation references

- [Discord interactions and acknowledgements](https://docs.discord.com/developers/interactions/receiving-and-responding)
- [Discord rate limits](https://docs.discord.com/developers/topics/rate-limits)
- [OpenAI structured outputs](https://developers.openai.com/api/docs/guides/structured-outputs)
- [OpenAI data controls](https://developers.openai.com/api/docs/guides/your-data)
