"""
One-shot: bind existing Panda configs to their Torve owners.

Background. From 2026-04-26 Panda's `configs` table has a nullable
`owner_torve_user_id` column. New configs created via the Torve-authed
flow get it set at creation; existing configs predate the column and
need a backfill.

Authoritative mapping: torve-backend's `user_integrations` table holds
one row per (Torve user, integration type). For `PANDA_TOKEN` rows,
the encrypted `credentials` JSON contains `{token: <manifest_token>}`.
The manifest token's payload (base64url-decoded header) carries the
`configId` of the Panda config the user installed. Because the only way
a user obtains a manifest token is by creating the config (or having
its URL shared with them), the user_id on the integration row is the
authoritative owner for backfill purposes.

Idempotent: re-running this script does nothing on rows that already
have an owner. Refuses to overwrite an existing non-null owner unless
--force is given.

Usage:
    python -m scripts.backfill_panda_owner [--dry-run] [--force]
"""
from __future__ import annotations

import argparse
import base64
import json
import sqlite3
import sys
import uuid
from pathlib import Path

# Python-side imports for torve-backend
ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from app.crypto import decrypt_secret  # noqa: E402
from app.database import SessionLocal  # noqa: E402
from app.models import UserIntegration  # noqa: E402

PANDA_DB_PATH = "/opt/panda/.data/panda.db"


def _extract_config_id(manifest_token: str) -> str | None:
    """Decode the manifest token's header to extract configId.

    Token shape: <base64url(header_json)>.<signature>. We don't verify the
    signature for backfill — the integration row already vouches for the
    user's ownership. We just need configId from the payload.
    """
    if not manifest_token or "." not in manifest_token:
        return None
    header_b64 = manifest_token.split(".", 1)[0]
    # Pad if needed
    pad = "=" * (-len(header_b64) % 4)
    try:
        header_json = base64.urlsafe_b64decode(header_b64 + pad).decode("utf-8")
        payload = json.loads(header_json)
        return payload.get("configId")
    except (ValueError, json.JSONDecodeError, UnicodeDecodeError):
        return None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true", help="Print what would change without writing.")
    parser.add_argument("--force", action="store_true", help="Overwrite existing non-null owner_torve_user_id.")
    args = parser.parse_args()

    db = SessionLocal()
    rows = db.query(UserIntegration).filter(
        UserIntegration.integration_type == "PANDA_TOKEN"
    ).all()
    print(f"[backfill] {len(rows)} PANDA_TOKEN integration rows to process")

    if not Path(PANDA_DB_PATH).exists():
        print(f"[backfill] Panda DB not found at {PANDA_DB_PATH}", file=sys.stderr)
        return 1

    panda_db = sqlite3.connect(PANDA_DB_PATH)
    try:
        cur = panda_db.cursor()
        # Sanity-check the schema is migrated.
        cols = [r[1] for r in cur.execute("PRAGMA table_info(configs)").fetchall()]
        if "owner_torve_user_id" not in cols:
            print("[backfill] Panda configs table is missing owner_torve_user_id column. "
                  "Restart Panda once (or hit any endpoint that touches the DB) so the "
                  "auto-migration runs, then re-run this script.", file=sys.stderr)
            return 1

        bound = 0
        skipped_already_owned = 0
        skipped_no_token = 0
        skipped_bad_token = 0
        skipped_config_missing = 0
        for row in rows:
            user_id = str(row.user_id)
            try:
                creds = json.loads(decrypt_secret(row.encrypted_credentials)) if row.encrypted_credentials else {}
            except (ValueError, json.JSONDecodeError):
                print(f"[backfill] decrypt failed for user={user_id} — skipping")
                continue
            manifest_token = creds.get("token")
            if not manifest_token:
                skipped_no_token += 1
                continue
            config_id = _extract_config_id(manifest_token)
            if not config_id:
                skipped_bad_token += 1
                print(f"[backfill] could not extract config_id for user={user_id} — skipping")
                continue

            current = cur.execute(
                "SELECT owner_torve_user_id FROM configs WHERE id = ?", (config_id,)
            ).fetchone()
            if current is None:
                skipped_config_missing += 1
                print(f"[backfill] config {config_id} not found in Panda DB (user={user_id}) — skipping")
                continue
            current_owner = current[0]
            if current_owner == user_id:
                skipped_already_owned += 1
                continue
            if current_owner and not args.force:
                print(f"[backfill] config {config_id} already owned by {current_owner}, "
                      f"would set to {user_id} (--force to overwrite) — skipping")
                continue

            print(f"[backfill] {config_id} → owner_torve_user_id={user_id}"
                  f"{' (overwriting ' + current_owner + ')' if current_owner else ''}")
            if not args.dry_run:
                cur.execute(
                    "UPDATE configs SET owner_torve_user_id = ?, updated_at = datetime('now') "
                    "WHERE id = ?",
                    (user_id, config_id),
                )
            bound += 1

        if not args.dry_run:
            panda_db.commit()
        print(
            f"[backfill] done. bound={bound} already_owned={skipped_already_owned} "
            f"no_token={skipped_no_token} bad_token={skipped_bad_token} "
            f"config_missing={skipped_config_missing} dry_run={args.dry_run}"
        )
        return 0
    finally:
        panda_db.close()
        db.close()


if __name__ == "__main__":
    sys.exit(main())
