"""Scrub forbidden secret-bearing keys from existing account_settings rows.

Revision ID: 0006_scrub_secrets
Revises: 0005_account_settings
Create Date: 2026-03-18

This migration removes any secret-bearing keys (API keys, tokens, credentials,
_sync_payload blobs) that were persisted by old clients before server-side
enforcement was added. Safe settings (language, ratings, layout) are preserved.
"""
import json
import logging
from alembic import op
import sqlalchemy as sa

revision = "0006_scrub_secrets"
down_revision = "0005_account_settings"
branch_labels = None
depends_on = None

logger = logging.getLogger(__name__)

# Must match the authoritative denylist in account_settings_policy.py.
FORBIDDEN_KEYS = {
    "debrid_api_key", "debrid_rd_refresh_token", "debrid_rd_client_id",
    "debrid_rd_client_secret", "debrid_rd_expires_at",
    "trakt_client_id", "trakt_client_secret", "trakt_access_token", "trakt_refresh_token",
    "simkl_client_id", "simkl_access_token",
    "claude_api_key", "chatgpt_api_key", "gemini_api_key", "perplexity_api_key", "deepseek_api_key",
    "omdb_api_key", "mdblist_api_key",
    "jellyfin_api_key", "plex_access_token", "kodi_hosts_json",
    "auth_access_token", "auth_refresh_token", "auth_token_expires_at",
    "_sync_payload",
}

# Pattern suffixes that indicate secret-like keys.
SECRET_SUFFIXES = (
    "_api_key", "_access_token", "_refresh_token",
    "_client_secret", "_client_id", "_password",
    "_cookie", "_bearer", "_webhook_secret", "_auth_header",
)


def _is_forbidden(key: str) -> bool:
    if key in FORBIDDEN_KEYS:
        return True
    lower = key.lower()
    return any(lower.endswith(suffix) for suffix in SECRET_SUFFIXES)


def upgrade() -> None:
    conn = op.get_bind()
    rows = conn.execute(sa.text("SELECT id, settings_json FROM account_settings")).fetchall()
    scrubbed_count = 0
    for row_id, settings_blob in rows:
        if not settings_blob:
            continue
        settings = json.loads(settings_blob) if isinstance(settings_blob, str) else settings_blob
        if not isinstance(settings, dict):
            continue
        clean = {k: v for k, v in settings.items() if not _is_forbidden(k)}
        removed = [k for k in settings if _is_forbidden(k)]
        if removed:
            logger.info("Scrubbing account_settings row %s: removing keys %s", row_id, removed)
            conn.execute(
                sa.text("UPDATE account_settings SET settings_json = :json WHERE id = :id"),
                {"json": json.dumps(clean), "id": row_id},
            )
            scrubbed_count += 1
    if scrubbed_count:
        logger.info("Scrubbed %d account_settings rows", scrubbed_count)


def downgrade() -> None:
    # Cannot restore scrubbed secrets — this is a one-way cleanup.
    pass
