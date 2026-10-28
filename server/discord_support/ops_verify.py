"""Read-only Discord restructure verification; optionally record private evidence.

Run on the Torve VPS. --record writes only the private migration evidence folder.
--guide-verified is an operator attestation after reopening the saved native UI;
the bot API does not verify Server Guide content.
"""
import argparse
import datetime
import json

try:
    from .ops_migrate import API, ARCHIVE, ARCHIVE_CAT, BOT_ROLE, FOLDER, GUILD, read_env
except ImportError:
    from ops_migrate import API, ARCHIVE, ARCHIVE_CAT, BOT_ROLE, FOLDER, GUILD, read_env


def normalize(entries):
    return sorted((o["id"], o["type"], str(o["allow"]), str(o["deny"])) for o in entries)


def verify(record=False, guide_verified=False):
    api = API(read_env())
    before = json.loads((FOLDER / "before.json").read_text())
    channels = api.request("GET", "/guilds/" + GUILD + "/channels")
    roles = api.request("GET", "/guilds/" + GUILD + "/roles")
    onboarding = api.request("GET", "/guilds/" + GUILD + "/onboarding")
    guild = api.request("GET", "/guilds/" + GUILD)
    by_id = {c["id"]: c for c in channels}
    expected_policy = sorted([
        (GUILD, 0, "0", "3072"),
        ("1508002604536434809", 0, "66560", "2048"),
    ])
    old_roles = {r["id"]: r for r in before["roles"]}
    queue = by_id.get("1563813545911787580", {})
    checks = {
        "all_14_archives_private_and_exactly_synced":
            all(c in by_id and by_id[c].get("parent_id") == ARCHIVE_CAT and
                normalize(by_id[c]["permission_overwrites"]) == expected_policy for c in ARCHIVE)
            and normalize(by_id[ARCHIVE_CAT]["permission_overwrites"]) == expected_policy,
        "archive_has_no_bot_exception":
            all(o["id"] != BOT_ROLE for c in [ARCHIVE_CAT] + ARCHIVE
                for o in by_id.get(c, {}).get("permission_overwrites", [])),
        "all_role_permissions_restored":
            set(old_roles) == {r["id"] for r in roles} and
            all(r["permissions"] == old_roles[r["id"]]["permissions"] for r in roles),
        "all_original_channels_preserved_except_empty_category":
            {c["id"] for c in before["channels"]} - set(by_id) == {"1508058270433022022"},
        "beta_permissions_unchanged":
            all(normalize(c["permission_overwrites"]) == normalize(by_id[c["id"]]["permission_overwrites"])
                for c in before["channels"] if c["id"] in ["1508058459797585981", "1508060137045233695"]),
        "four_onboarding_defaults":
            set(onboarding["default_channel_ids"]) == {
                "1508008547294904441", "1508057632550817792",
                "1508057781297741884", "1508057928005845102"},
        "private_staff_queue":
            queue.get("parent_id") == "1508009636836675614" and
            normalize(queue.get("permission_overwrites", [])) == sorted([
                (GUILD, 0, "0", "1024"),
                ("1508002604536434809", 0, "1024", "0"),
            ]),
    }
    print(json.dumps({"checks": checks, "guide_owner_ui_verified": guide_verified}, indent=2))
    if not all(checks.values()):
        raise SystemExit("Verification failed; do not mark the restructure complete.")
    if record:
        now = datetime.datetime.now(datetime.timezone.utc).isoformat()
        snapshot = {"verified_at": now, "checks": checks, "guide_owner_ui_verified": guide_verified,
                    "guild": guild, "channels": channels, "roles": roles, "onboarding": onboarding}
        path = FOLDER / "after-verified.json"
        path.write_text(json.dumps(snapshot, indent=2)); path.chmod(0o600)
        if guide_verified:
            ledger_path = FOLDER / "migration-ledger.json"
            ledger = json.loads(ledger_path.read_text())
            ledger.update(status="complete", completed_at=now, final_checks=checks,
                          guide_owner_ui_verified=True, owner_ui_pending=[],
                          owner_permission_sync_pending=[], support_queue_id=queue["id"])
            ledger_path.write_text(json.dumps(ledger, indent=2)); ledger_path.chmod(0o600)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--record", action="store_true")
    parser.add_argument("--guide-verified", action="store_true")
    args = parser.parse_args()
    verify(args.record, args.guide_verified)
