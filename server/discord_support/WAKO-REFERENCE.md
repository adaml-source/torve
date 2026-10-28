# Wako Issues Bot: observed behavior and Torve implementation gap

Read-only inspection of the connected Discord desktop session on 25 October 2026.
No messages, commands, reactions or issue changes were submitted to wako.

The original Torve implementation was command-based FAQ lookup and private intake.
Conversational v2 is now implemented locally, including acknowledgements, encrypted
case memory, diagnostic follow-up, live build checks, forum tags, human takeover
and a gated Codex repair pipeline. It is not deployed. See [AUTONOMOUS.md](AUTONOMOUS.md)
and the fresh [test record](TEST-RESULTS.json) for implemented scope and validation.
The owner approved the server restructure; this inspection does not grant access
to wako's code, internal issue system, model, private data or deployment settings.

| Observed in wako | Evidence | What this establishes |
|---|---|---|
| Replies to ordinary chat without a slash command | `#help`: MyTrakt/Simkl lists, 25 October | Members can describe a problem naturally and receive a relevant reply. |
| Asks a discriminating diagnostic question | Lists missing from Movies/TV/Anime tabs: asks whether they appear in For You | The bot narrows the symptom before explaining the documented catalog-type behavior. |
| Carries context across replies | Dolby Vision discussion in `#general`, 23–24 October | Retains device, profile, public/beta comparison and audio/video symptoms over several updates. |
| Adapts to new evidence | Same discussion: follows the report from stuttering to black video with audio | It responds to the evolving report instead of restarting a checklist each time. |
| Explains product behavior in detail | Dedupe sources forum post, 19 October | Distinguishes Best Match behavior from add-on-specific pills; answers the actual feature request. |
| Maintains a long issue conversation | “Anime doesn’t seem to sync properly”, 23–24 October | Keeps Simkl watched state, consolidated seasons, search, Library, Up Next and TV beta evidence in the same discussion. |
| Acknowledges an apparent resolution or discovered setting | MyTrakt list Catalog Type exchange | Confirms the member's workaround and explains why it maps to the desired tabs. Screenshots were present; its actual image-processing mechanism cannot be verified. |
| Uses public issue labels | Forum shows Open, Bug, Backlog and Suggestion | A shared issue board exists. This does not prove whether the bot itself assigns or changes the labels. |
| Says a report is being updated or a fix is coming | Anime thread and Dolby Vision replies | This is visible wording only. No access was available to verify an internal ticket, engineering work, release commitment or actual fix. |

Sources accessible to server members:

- [Wako help](https://discord.com/channels/571252273557143553/755437633278050374)
- [Wako general](https://discord.com/channels/571252273557143553/756885105758306406)
- [Wako issue forum](https://discord.com/channels/571252273557143553/1466949126792155318)
- [Source deduplication thread](https://discord.com/channels/571252273557143553/1561508924111323297)

## Revised Torve target

Members should be able to write an ordinary problem description in `#help` or a
support forum post. The bot should keep context within that member's conversation,
ask the next useful question, retrieve relevant reviewed Torve documentation,
suggest reversible checks, and incorporate follow-up results. Commands remain
shortcuts and the private reporting route.

The reference requirements, now addressed by the v2 implementation, are:

1. A channel-restricted message listener and explicit notice explaining AI use.
   Limit automatic behavior to help and support posts; keep beta, staff, archives,
   DMs and unrelated conversation out. Announcements and general server activity
   must not trigger replies. Support opt-out and immediate human handoff.
2. Encrypted, short-lived context per member/thread, including platform, device/OS,
   app version/distribution, expected/actual result, checks tried and their results.
   Keep independent members' cases separate and support deletion.
3. Reviewed Torve support knowledge, with source/revision references and
   an explicit publishing boundary for the authenticated guide. Six public FAQs
   cannot support arbitrary detailed playback, sync or integration claims on
   their own. V2 adds reviewed questions/checks and explicit abstention/handoff;
   broader product-specific content still needs a reviewed public export.
4. Structured AI decisions for answer, clarification, troubleshooting, suspected
   bug, feature request and human escalation. Missing knowledge must produce a
   question or handoff. Treat messages, screenshots and links as untrusted data.
5. Case IDs, confirmed duplicate links and persistent status. Create/escalate a report
   only after the member agrees, and confirm an actual stored record before saying
   it was filed. A staff response/status change must link to real staff activity.
6. A clear distinction between “this may be a bug”, “staff are investigating”,
   “a change was deployed”, and “the member confirmed resolution”. Never promise
   a fix or claim engineering ownership without verified evidence.
7. Per-member/thread cooldowns, reply deduplication, bot-loop prevention, bounded
   context and API budgets, safe attachment handling, staff takeover and monitored
   failures. Run multi-turn staging tests before enabling public replies.

## What members can use now

The restructured Discord channels provide the human support route. The new Torve
support bot is not live, so `/torve` commands from this implementation are not yet
available to server members. Existing Torve Beta Bot behavior is separate.

The local service now implements the conversational listener, structured multi-turn
diagnosis, consent, acknowledgements, forum tagging, staff updates and resolution.
The Codex worker can produce a narrow patch proposal only after build/source,
policy, executable reproduction and independent-review gates. It never deploys.
Live Discord/OpenAI acceptance and the isolated runner's real credentials/images
remain deployment work. Attachment interpretation and automatic cross-member
duplicate inference are outside this implementation; confirmed duplicate linking
is a staff action. No claim is made of access to Wako's internal implementation.
