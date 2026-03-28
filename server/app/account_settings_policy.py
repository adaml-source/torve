"""
Server-side policy for account settings.

Defines which keys are FORBIDDEN from being stored in account settings.
This is the authoritative server-side denylist. Third-party API secrets,
OAuth tokens, and credential material must never be persisted in
account settings — even if a stale or malicious client tries to push them.

Enforcement behavior: SILENT STRIP.
- Forbidden keys are removed from incoming PATCH payloads before persistence.
- GET responses also strip forbidden keys from legacy data for defense in depth.
- Safe keys in a mixed payload are still persisted normally.
- This is chosen over 4xx rejection because:
  - Old clients may still push mixed payloads (graceful degradation).
  - Users should not lose safe settings because one forbidden key was included.
  - Silent stripping is a safe, non-breaking enforcement boundary.
"""

import logging
import re

logger = logging.getLogger(__name__)

# ── Explicit denylist of secret-bearing keys ──
# Any key in this set will be stripped from incoming PATCH payloads
# and from GET responses (for legacy rows).

FORBIDDEN_ACCOUNT_SETTINGS_KEYS: frozenset[str] = frozenset({
    # Debrid credentials
    "debrid_api_key",
    "debrid_rd_refresh_token",
    "debrid_rd_client_id",
    "debrid_rd_client_secret",
    "debrid_rd_expires_at",
    # Trakt credentials
    "trakt_client_id",
    "trakt_client_secret",
    "trakt_access_token",
    "trakt_refresh_token",
    # SIMKL credentials
    "simkl_client_id",
    "simkl_access_token",
    # AI provider API keys
    "claude_api_key",
    "chatgpt_api_key",
    "gemini_api_key",
    "perplexity_api_key",
    "deepseek_api_key",
    # Metadata provider credentials
    "omdb_api_key",
    "mdblist_api_key",
    # Media server credentials
    "jellyfin_api_key",
    "plex_access_token",
    "kodi_hosts_json",  # may contain auth credentials in host entries
    # Auth tokens (should never be in account settings)
    "auth_access_token",
    "auth_refresh_token",
    "auth_token_expires_at",
    # Legacy secret transport blob
    "_sync_payload",
})

# ── Pattern-based denylist for catch-all safety ──
# Matches keys that look like secrets even if not explicitly listed.
_SECRET_PATTERNS: list[re.Pattern] = [
    re.compile(r".*_api_key$", re.IGNORECASE),
    re.compile(r".*_access_token$", re.IGNORECASE),
    re.compile(r".*_refresh_token$", re.IGNORECASE),
    re.compile(r".*_client_secret$", re.IGNORECASE),
    re.compile(r".*_client_id$", re.IGNORECASE),
    re.compile(r".*_password$", re.IGNORECASE),
    re.compile(r".*_cookie$", re.IGNORECASE),
    re.compile(r".*_bearer$", re.IGNORECASE),
    re.compile(r".*_webhook_secret$", re.IGNORECASE),
    re.compile(r".*_auth_header$", re.IGNORECASE),
    re.compile(r"^_sync_payload$", re.IGNORECASE),
]


def is_forbidden_key(key: str) -> bool:
    """Check if a key is forbidden from account settings storage."""
    if key in FORBIDDEN_ACCOUNT_SETTINGS_KEYS:
        return True
    for pattern in _SECRET_PATTERNS:
        if pattern.match(key):
            return True
    return False


def strip_forbidden_keys(settings: dict[str, str | None]) -> dict[str, str | None]:
    """
    Remove forbidden keys from a settings dict.
    Returns a new dict with only safe keys.
    Logs stripped keys for audit trail.
    """
    safe = {}
    stripped = []
    for key, value in settings.items():
        if is_forbidden_key(key):
            stripped.append(key)
        else:
            safe[key] = value
    if stripped:
        logger.warning("Stripped forbidden keys from account settings: %s", stripped)
    return safe


def scrub_stored_settings(settings_json: dict) -> tuple[dict, list[str]]:
    """
    Remove forbidden keys from an existing stored settings blob.
    Returns (scrubbed_dict, list_of_removed_keys).
    """
    scrubbed = {}
    removed = []
    for key, value in settings_json.items():
        if is_forbidden_key(key):
            removed.append(key)
        else:
            scrubbed[key] = value
    return scrubbed, removed
