#!/usr/bin/env python3
"""
Daily Torve cleanup.

Historically this only cleaned expired/used auth tokens (hence the
script name, kept for systemd-unit backward compatibility). It now
also prunes expired rows from the acceleration memory tables and
stale NzbDAV warm-job rows — every table whose schema includes an
`expires_at` that nothing else cleans.

Run via the torve-cleanup.timer unit daily at 03:00 UTC.

Usage:
    /opt/torve-backend/venv/bin/python /opt/torve-backend/scripts/cleanup_tokens.py
"""
import logging
import sys
from datetime import datetime, timedelta, timezone

# Ensure the app package is importable
sys.path.insert(0, "/opt/torve-backend")

from sqlalchemy.orm import Session

from app.account_settings_policy import scrub_stored_settings
from app.database import engine
from app.hash_availability_service import cleanup_expired as cleanup_hash_availability
from app.inventory_service import cleanup_expired as cleanup_inventory
from app.models import AccountSettings, EmailVerificationToken, PasswordResetToken
from app.nzbdav.warm_service import cleanup_expired as cleanup_warm_jobs
from app.resolve_memory_service import cleanup_expired as cleanup_resolve_memory

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
_log = logging.getLogger("torve-cleanup")

# Delete tokens that are either used or expired more than 7 days ago.
_TOKEN_GRACE_DAYS = 7


def _cleanup_tokens(db: Session) -> tuple[int, int]:
    now = datetime.now(timezone.utc)
    cutoff = now - timedelta(days=_TOKEN_GRACE_DAYS)

    ev_count = db.query(EmailVerificationToken).filter(
        (EmailVerificationToken.used_at != None)  # noqa: E711
        | (EmailVerificationToken.expires_at < cutoff)
    ).delete(synchronize_session=False)

    pr_count = db.query(PasswordResetToken).filter(
        (PasswordResetToken.used_at != None)  # noqa: E711
        | (PasswordResetToken.expires_at < cutoff)
    ).delete(synchronize_session=False)

    db.commit()
    return ev_count, pr_count


def _run_and_commit(db: Session, label: str, fn) -> int:
    """Run one cleanup function, commit the transaction, return its count.

    A failure in one step logs and returns 0 rather than blocking the
    rest of the nightly run — individual service bugs shouldn't stop
    the other prunes.
    """
    try:
        n = fn(db)
        db.commit()
        _log.info("cleanup.%s deleted=%d", label, n)
        return n
    except Exception:  # noqa: BLE001
        db.rollback()
        _log.exception("cleanup.%s failed", label)
        return 0


def _scrub_account_settings_rows(db: Session) -> int:
    """Walk every AccountSettings row and strip forbidden secret-bearing
    keys from its JSON blob. Idempotent; first run backfills legacy rows
    written before the strip-on-write policy, subsequent runs are no-ops
    unless a new forbidden pattern was added to the denylist.

    Returns the number of rows that had at least one key removed.
    """
    changed = 0
    rows = db.query(AccountSettings).all()
    for row in rows:
        if not isinstance(row.settings, dict) or not row.settings:
            continue
        scrubbed, removed = scrub_stored_settings(
            row.settings, user_id=row.user_id, context="backfill",
        )
        if removed:
            row.settings = scrubbed
            row.version = (row.version or 0) + 1
            row.updated_at = datetime.now(timezone.utc)
            changed += 1
    return changed


def cleanup() -> None:
    with Session(engine) as db:
        ev, pr = _cleanup_tokens(db)
        _log.info(
            "cleanup.tokens email_verification=%d password_reset=%d", ev, pr,
        )
        rsm = _run_and_commit(db, "resolve_memory", cleanup_resolve_memory)
        ham = _run_and_commit(db, "hash_availability", cleanup_hash_availability)
        inv = _run_and_commit(db, "inventory", cleanup_inventory)
        wj = _run_and_commit(db, "nzbdav_warm_jobs", cleanup_warm_jobs)
        asc = _run_and_commit(db, "account_settings_scrub", _scrub_account_settings_rows)

    _log.info(
        "cleanup.summary email_verification=%d password_reset=%d "
        "resolve_memory=%d hash_availability=%d inventory=%d nzbdav_warm_jobs=%d "
        "account_settings_scrubbed=%d",
        ev, pr, rsm, ham, inv, wj, asc,
    )


if __name__ == "__main__":
    cleanup()
