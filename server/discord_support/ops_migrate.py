"""One-off, owner-approved Torve server restructure. Run only on the Torve VPS.

Uses the existing bot credential in memory; never emits it or creates a user bot.
Preview is the default. A private rollback snapshot must already exist.
Historical one-off tool: completed migrations cannot be reapplied. Owner UI
permission synchronization and a separate final verification are required.
"""
import argparse
import copy
import datetime
import json
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

GUILD = "1507992418358919338"
FOLDER = Path("/var/backups/torve-discord/2026-10-25-restructure")
ENV = Path("/opt/torve-backend/.env")
START = "1508008547294904441"
NEWS = "1508057632550817792"
HELP = "1508057781297741884"
FORUM = "1508057928005845102"
SUPPORT_CAT = "1508057739874537482"
ARCHIVE_CAT = "1508058022759633028"
OLD_INTEGRATIONS = "1508058270433022022"
STAFF_CAT = "1508009636836675614"
MOD = "1508002604536434809"
TEAM = "1507992588798529546"
BOT_ROLE = "1509142051269378162"
ARCHIVE = ["1508048731117719695", "1508057663601115180", "1507992419138801716", "1508057830903517294", "1508057892094476458", "1508058056297021530", "1508058164270989472", "1508058191534096515", "1508058220563005551", "1508058298325143612", "1508058326980886590", "1508058351894921256", "1508058392336535585", "1508057695679152229"]


def read_env():
    cfg = {}
    for line in ENV.read_text().splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            cfg[key.strip()] = value.strip().strip("\"'")
    assert cfg.get("DISCORD_GUILD_ID") == GUILD
    return cfg


class API:
    def __init__(self, cfg):
        self.cfg = cfg
        self.after = 0

    def request(self, method, path, payload=None, *, webhook=None):
        for attempt in range(4):
            time.sleep(max(0, self.after - time.monotonic()))
            headers = {"User-Agent": "TorveApprovedMigration/1.0", "Content-Type": "application/json", "X-Audit-Log-Reason": "Owner-approved support restructure 2026-10-25"}
            if webhook is None:
                headers["Authorization"] = "Bot " + self.cfg["DISCORD_BOT_TOKEN"]
            url = webhook or "https://discord.com/api/v10" + path
            req = urllib.request.Request(url, data=json.dumps(payload).encode() if payload is not None else None, headers=headers, method=method)
            try:
                with urllib.request.urlopen(req, timeout=20) as response:
                    if response.headers.get("X-RateLimit-Remaining") == "0":
                        self.after = time.monotonic() + float(response.headers.get("X-RateLimit-Reset-After", "1"))
                    else:
                        self.after = time.monotonic() + 0.35
                    raw = response.read()
                    return json.loads(raw) if raw else None
            except urllib.error.HTTPError as error:
                raw = error.read()
                if error.code == 429:
                    self.after = time.monotonic() + float(json.loads(raw).get("retry_after", 1))
                    continue
                # Never include token-bearing URLs or full response/request data.
                try:
                    detail = json.loads(raw)
                except Exception:
                    detail = {}
                raise RuntimeError(f"Discord {error.code}: {detail.get('message', 'request failed')} / code {detail.get('code')}") from None
        raise RuntimeError("Discord rate limit retry budget exhausted")


def overwrite(role, *, allow=0, deny=0):
    return {"id": role, "type": 0, "allow": str(allow), "deny": str(deny)}


def build_plan(before):
    channels = {c["id"]: c for c in before["channels"]}
    assert all(c in channels for c in ARCHIVE)
    # Temporary bot view access permits verification; removed before completion.
    archived = [overwrite(GUILD, deny=1024 | 2048), overwrite(MOD, allow=1024 | 65536, deny=2048), overwrite(BOT_ROLE, allow=1024 | 2048 | 65536)]
    staff = [overwrite(GUILD, deny=1024), overwrite(MOD, allow=1024 | 2048 | 65536)]
    plan = []
    def patch(label, path, body):
        plan.append({"label": label, "method": "PATCH", "path": path, "body": body})
    patch("Rename GENERAL to SUPPORT", "/channels/" + SUPPORT_CAT, {"name": "SUPPORT"})
    patch("Rename rules to start-here", "/channels/" + START, {"name": "start-here", "topic": "Torve rules, official downloads, documentation and support. Never post credentials or private data."})
    help_overwrites = copy.deepcopy(channels[HELP]["permission_overwrites"])
    everyone = next((o for o in help_overwrites if o["id"] == GUILD), None)
    if everyone is None:
        everyone = overwrite(GUILD)
        help_overwrites.append(everyone)
    everyone["allow"] = str(int(everyone["allow"]) | 1024 | 2048 | 65536)
    everyone["deny"] = str(int(everyone["deny"]) & ~(1024 | 2048 | 65536))
    patch("Rename general to help", "/channels/" + HELP, {"name": "help", "parent_id": SUPPORT_CAT, "rate_limit_per_user": 10, "permission_overwrites": help_overwrites, "topic": "Ask a Torve setup or troubleshooting question. Include platform and app version. Human help: support@torve.app. The new support bot is not live yet; never post credentials, provider URLs or private data."})
    old_tags = {t["name"]: t for t in channels[FORUM].get("available_tags", [])}
    names = ["Android TV", "Fire TV", "Windows", "Android Mobile", "iOS / tvOS", "Setup", "Playback", "IPTV / EPG", "Sources", "Addons", "Libraries", "Account", "Feature", "New", "Needs Info", "Investigating", "Resolved", "Duplicate"]
    statuses = {"New", "Needs Info", "Investigating", "Resolved", "Duplicate"}
    tags = []
    for name in names:
        tag = {"name": name, "moderated": name in statuses}
        if name in old_tags:
            tag["id"] = old_tags[name]["id"]
        tags.append(tag)
    patch("Rename bugs to support forum", "/channels/" + FORUM, {"name": "support", "parent_id": SUPPORT_CAT, "available_tags": tags, "flags": int(channels[FORUM].get("flags", 0)) | 16, "topic": "One issue or feature request per post. Choose platform and topic tags. Include Torve version/distribution, device/OS, expected and actual result, exact error and safe steps to reproduce. Never post credentials, provider/playlist URLs, account identifiers or full logs. Private help: support@torve.app."})
    onboarding = before["onboarding"]
    prompts = [p for p in copy.deepcopy(onboarding["prompts"]) if p.get("in_onboarding")]
    patch("Simplify onboarding to four channels and rules acknowledgement", "/guilds/" + GUILD + "/onboarding", {"prompts": prompts, "default_channel_ids": [START, NEWS, HELP, FORUM], "enabled": True, "mode": onboarding.get("mode", 1)})
    plan[-1]["method"] = "PUT"
    patch("Stop join-message noise", "/guilds/" + GUILD, {"system_channel_id": HELP, "system_channel_flags": int(before["guild"].get("system_channel_flags", 0)) | 1})
    patch("Create archive category from SETUP HELP", "/channels/" + ARCHIVE_CAT, {"name": "ARCHIVE", "permission_overwrites": archived})
    for channel_id in ARCHIVE:
        # lock_permissions synchronizes a real parent change. A channel already
        # in this category still needs an explicit owner-session permission sync.
        patch("Archive " + channels[channel_id]["name"], "/guilds/" + GUILD + "/channels", [{"id": channel_id, "parent_id": ARCHIVE_CAT, "lock_permissions": True}])
        if channels[channel_id].get("parent_id") == ARCHIVE_CAT:
            plan[-1]["owner_permission_sync_required"] = True
        if channel_id == "1508057695679152229":
            # Existing private outreach channel excludes the beta bot. Keep that
            # boundary; the owner session performs this one move.
            plan[-1]["requires_owner_ui"] = True
    # Existing staff/beta overwrites are left alone. New queue is staff-only.
    plan.append({"label": "Create dedicated private support queue", "method": "POST", "path": "/guilds/" + GUILD + "/channels", "body": {"name": "support-queue", "type": 0, "parent_id": STAFF_CAT, "permission_overwrites": staff, "topic": "Private Torve support handoff queue. The new support bot is not deployed yet. Existing beta applications stay in staff-chat."}})
    plan[-1]["requires_owner_ui"] = True
    patch("Order categories and public channels", "/guilds/" + GUILD + "/channels", [{"id": "1508008646787862558", "position": 0}, {"id": SUPPORT_CAT, "position": 1}, {"id": "1508058426868236441", "position": 2}, {"id": STAFF_CAT, "position": 3}, {"id": ARCHIVE_CAT, "position": 4}, {"id": START, "position": 0}, {"id": NEWS, "position": 1}, {"id": HELP, "position": 0}, {"id": FORUM, "position": 1}])
    return plan


def main(apply=False):
    before = json.loads((FOLDER / "before.json").read_text())
    plan = build_plan(before)
    if not apply:
        print(json.dumps({"status": "preview", "operations": [{"label": o["label"], "method": o["method"], "path": o["path"]} for o in plan]}, indent=2))
        return
    if (FOLDER / "after-verified.json").exists():
        raise RuntimeError("This migration is complete. Use ops_verify.py; do not reapply temporary permissions.")
    api = API(read_env())
    roles = api.request("GET", "/guilds/" + GUILD + "/roles")
    role = next(r for r in roles if r["id"] == BOT_ROLE)
    assert int(role["permissions"]) & 16 and int(role["permissions"]) & 32, "Temporary owner-granted Manage Channels and Manage Server are required"
    assert not int(role["permissions"]) & 8, "Administrator is not permitted"
    ledger_path = FOLDER / "migration-ledger.json"
    ledger = json.loads(ledger_path.read_text()) if ledger_path.exists() else {"approved_by": "User: Deploy the restructure", "completed": []}
    if "Create archive category from SETUP HELP" in ledger["completed"]:
        # Permission edits are checked against the destination parent. Keep the
        # bot's normal send right during the move, then remove its archive grant.
        api.request("PATCH", "/channels/" + ARCHIVE_CAT, {"permission_overwrites": [overwrite(GUILD, deny=1024 | 2048), overwrite(MOD, allow=1024 | 65536, deny=2048), overwrite(BOT_ROLE, allow=1024 | 2048 | 65536)]})
    for operation in plan:
        if operation["label"] in ledger["completed"]:
            continue
        if operation.get("requires_owner_ui"):
            ledger.setdefault("owner_ui_pending", [])
            if operation["label"] not in ledger["owner_ui_pending"]:
                ledger["owner_ui_pending"].append(operation["label"])
            ledger_path.write_text(json.dumps(ledger, indent=2))
            print("Owner session required: " + operation["label"], flush=True)
            continue
        if operation["method"] == "POST":
            current = api.request("GET", "/guilds/" + GUILD + "/channels")
            existing = next((c for c in current if c["name"] == "support-queue" and c.get("parent_id") == STAFF_CAT), None)
            result = existing or api.request(operation["method"], operation["path"], operation["body"])
            ledger["support_queue_id"] = result["id"]
        else:
            api.request(operation["method"], operation["path"], operation["body"])
        ledger["completed"].append(operation["label"])
        ledger_path.write_text(json.dumps(ledger, indent=2))
        ledger_path.chmod(0o600)
        print(operation["label"], flush=True)
    current = api.request("GET", "/guilds/" + GUILD + "/channels")
    # Delete only the now-empty obsolete category, never a message-bearing channel.
    if any(c["id"] == OLD_INTEGRATIONS for c in current):
        assert not any(c.get("parent_id") == OLD_INTEGRATIONS for c in current)
        api.request("DELETE", "/channels/" + OLD_INTEGRATIONS)
        print("Removed empty INTEGRATIONS category", flush=True)
    # Remove temporary bot view grants from the archive; preserve staff-only history.
    # REST category edits did not propagate to existing children in this guild.
    # Each child must be checked; owner UI removes temporary bot exceptions or
    # explicitly synchronizes the final category policy before completion.
    api.request("PATCH", "/channels/" + ARCHIVE_CAT, {"permission_overwrites": [overwrite(GUILD, deny=1024 | 2048), overwrite(MOD, allow=1024 | 65536, deny=2048)]})
    current = api.request("GET", "/guilds/" + GUILD + "/channels")
    by_id = {c["id"]: c for c in current}
    normalize = lambda entries: sorted((o["id"], o["type"], str(o["allow"]), str(o["deny"])) for o in entries)
    final_policy = normalize(by_id[ARCHIVE_CAT]["permission_overwrites"])
    ledger["owner_permission_sync_pending"] = [c for c in ARCHIVE if normalize(by_id[c]["permission_overwrites"]) != final_policy]
    ledger["api_phase_finished_at"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    ledger["status"] = "requires_owner_cleanup_and_final_verification"
    ledger_path.write_text(json.dumps(ledger, indent=2))
    ledger_path.chmod(0o600)
    print("API phase applied. Finish owner UI cleanup, content, guide and role restoration; run ops_verify.py before recording completion.", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply", action="store_true")
    main(parser.parse_args().apply)
