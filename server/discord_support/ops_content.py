"""Owner-approved content update for the restructured server; no bot deployment."""
import json
import sys
from pathlib import Path

try:
    from .ops_migrate import API, read_env, FOLDER
except ImportError:  # Standalone operator copy on the VPS.
    from ops_migrate import API, read_env, FOLDER


def update():
    cfg = read_env()
    api = API(cfg)
    pages = {
        "RULES": {"embeds": [{"title": "Start here — Torve support", "description": "Welcome to Torve.\n\n**Install and learn**\nOfficial downloads: https://torve.app/download.html\nFire TV installation: https://torve.app/firetv.html\nTorve guide: https://torve.app/app/help.html (sign-in may be required)\n\n**Get help**\nAsk a setup or troubleshooting question in <#1508057781297741884>. Include your platform, Torve version and what happened.\nFor a reproducible problem or feature request, create one post in <#1508057928005845102> and choose the relevant tags.\nPrivate account, billing or security help: support@torve.app\n\n**Community rules**\n• Use only media and services you are authorised to access.\n• No provider sourcing, sales, piracy links, illegal streams or access-control bypasses.\n• Never share credentials, API keys, tokens, account emails, provider/playlist URLs or private files.\n• Be respectful. No harassment, hate, spam or disruptive behaviour.\n• For bug reports, include device/OS, app version, expected/actual behaviour and safe reproduction steps. Remove private information from screenshots.\n\nPrivacy: https://torve.app/privacy.html\nAccount deletion: https://torve.app/account-deletion.html\n\n**Support bot status:** the new conversational support bot is being prepared and is not live yet. Human support remains available.", "color": 15844367}], "allowed_mentions": {"parse": []}},
        "BETA_INFO": {"embeds": [{"title": "Beta testing — current notice", "description": "The previous 2026 beta notice listed deadlines of 1 July and 31 July. Those dates have passed. The old offer is historical and must not be treated as a current Premium entitlement or enrollment promise.\n\nTorve Team can confirm current testing opportunities and access. Existing Beta Tester roles and private feedback history have been preserved.\n\nFor normal public downloads and current platform availability, use https://torve.app/download.html. For account or access questions, contact support@torve.app.\n\nIf you are testing an approved beta build, use <#1508060137045233695> for beta feedback. State the platform, full version/build and safe reproduction steps. Never share credentials, account identifiers, provider/playlist URLs or private logs.", "color": 15844367}], "allowed_mentions": {"parse": []}},
    }
    ledger_path = FOLDER / "content-ledger.json"
    ledger = json.loads(ledger_path.read_text()) if ledger_path.exists() else {}
    for name, payload in pages.items():
        url = cfg["DISCORD_" + name + "_WEBHOOK_URL"].split("?")[0].rstrip("/") + "/messages/" + cfg["DISCORD_" + name + "_MESSAGE_ID"]
        snapshot = FOLDER / ("content-before-" + name.lower() + ".json")
        if not snapshot.exists():
            old = api.request("GET", "", webhook=url)
            snapshot.write_text(json.dumps(old, indent=2));snapshot.chmod(0o600)
        result = api.request("PATCH", "", payload, webhook=url)
        ledger[name] = {"message_id": result["id"], "updated": True}
        ledger_path.write_text(json.dumps(ledger, indent=2));ledger_path.chmod(0o600)
        print("Updated existing " + name.lower() + " information card", flush=True)
    # Disable only the expired application message's button, not the backend or
    # any account access. Existing beta entitlements are never changed here.
    message_id = cfg.get("DISCORD_BETA_APPLICATION_MESSAGE_ID")
    channel_id = cfg.get("DISCORD_BETA_APPLICATION_CHANNEL_ID")
    if message_id and channel_id:
        old = api.request("GET", "/channels/" + channel_id + "/messages/" + message_id)
        snapshot = FOLDER / "expired-beta-application-before.json"
        if not snapshot.exists():
            snapshot.write_text(json.dumps(old, indent=2));snapshot.chmod(0o600)
        api.request("PATCH", "/channels/" + channel_id + "/messages/" + message_id, {"content": "This application card belongs to the previous beta program. Its published July 2026 deadlines have passed. Contact Torve Team for current testing opportunities; no new Premium access is promised here.", "embeds": [], "components": [], "allowed_mentions": {"parse": []}})
        print("Retired the expired beta application button; existing accounts and roles unchanged", flush=True)


if __name__ == "__main__":
    if "--apply" not in sys.argv:
        print("Preview: revise start-here and beta notice, disable the expired application card button.")
    else:
        update()
