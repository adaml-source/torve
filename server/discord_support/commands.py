"""Print a preview by default. Guild command registration is an explicit action."""
import argparse
import asyncio
import json
from pathlib import Path

import httpx

from discord_support.service import Config, DiscordHTTP


def option(name, description, *, kind=3, required=True, **extra):
    return {"type": kind, "name": name, "description": description, "required": required, **extra}


def sub(name, description, *options):
    return {"type": 1, "name": name, "description": description, "options": list(options)}


COMMAND = {
    "name": "torve", "description": "Torve support, public FAQs and private human help",
    "type": 1, "default_member_permissions": None,
    "options": [
        sub("help", "How to get help from Torve"),
        sub("ask", "Search public Torve FAQs; never enter private data", option("question", "Question without account details, keys or URLs", max_length=1000), option("ai", "Opt in to sending a redacted question to OpenAI; default false", kind=5, required=False)),
        sub("troubleshoot", "Guided checks for a Torve problem", option("topic", "Choose a troubleshooting flow", choices=[{"name": x, "value": x} for x in ("playback", "sources", "account", "firetv")])),
        sub("issue", "Save a private request for staff; no credentials or logs", option("summary", "What happened, expected result, steps to reproduce; exclude private data", max_length=1200), option("platform", "Platform, device model and OS; no device identifiers", max_length=80), option("version", "Torve version and distribution channel", max_length=80), option("consent", "Allow authorised Torve staff to read this report, stored up to 30 days", kind=5)),
        sub("ticket", "Read the status and staff reply to your request", option("id", "Your request ID", min_length=16, max_length=16)),
        sub("forget", "Delete your stored requests and replies"),
        sub("privacy", "How the bot handles your data"),
        sub("conversation", "Consent to AI support, stop it, check status or request a human", option("action", "Choose an action; start agrees to the privacy notice", choices=[{"name":x,"value":x} for x in ("start","stop","human","status","resolve","reopen")])),
        sub("build", "Check Torve's live published stable release", option("platform", "Platform", choices=[{"name":x,"value":x} for x in ("windows","android_tv","fire_tv","android_mobile","ios","tvos")]), option("version", "Your installed semantic version; never an account identifier",required=False,max_length=60)),
        sub("staff", "Staff only: inspect or answer a request in the private queue", option("id", "Request ID", min_length=16, max_length=16), option("reply", "Answer the member; public for conversation cases", required=False, max_length=1000), option("status", "Case status; requires actual staff review", required=False,choices=[{"name":x,"value":x} for x in ("human","investigating","needs_info","resolved","duplicate","resume")]),option("duplicate_of","Existing conversation case ID, only for duplicate status",required=False,min_length=16,max_length=16)),
    ],
}


async def run(register=False):
    if not register:
        print(json.dumps(COMMAND, indent=2))
        return
    cfg = Config.from_env()
    cfg.validate()
    if not cfg.enabled or not cfg.bot_token:
        raise SystemExit("Enable and configure the approved bot before registering")
    async with httpx.AsyncClient(timeout=12) as client:
        api = DiscordHTTP(client, cfg.bot_token)
        # POST upserts only this named command. It does not overwrite other commands.
        result = await api.request("POST", f"/applications/{cfg.application_id}/guilds/{cfg.guild_id}/commands", json=COMMAND)
        print(json.dumps({"registered_command_id": result["id"], "guild_id": cfg.guild_id}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--register", action="store_true", help="Writes a guild-scoped command; requires owner approval")
    args = parser.parse_args()
    asyncio.run(run(args.register))
