"""
Server-side policy for account settings: silent-strip of secret-bearing keys.

Threat model
------------
The /me/account-settings endpoint is a cross-device sync store. A stale,
buggy, or malicious client could push credentials (debrid API keys, Trakt /
SIMKL OAuth tokens, AI-provider API keys, Jellyfin / Plex credentials) into
it. Those would then:
  - sit in the `account_settings.settings` JSON blob at rest
  - sync to every signed-in device
  - leak through any future DB dump / backup / read-only share

The right place to enforce "never persist secrets here" is server-side, not
client-side, because clients can't be trusted to all be current and the
sync store itself is the attack surface.

Enforcement
-----------
Silent strip. Forbidden keys are removed from incoming PATCH payloads
before persistence AND from stored rows on GET (defense in depth for
legacy rows written before this policy existed). Reasons for strip-not-
reject:
  - Old clients may send mixed payloads (safe keys + a stray secret);
    we don't want to break their safe-key persistence.
  - User-visible 4xx would be confusing for a client-side bug.
  - Silent strip + structured log line gives the operator a trail
    without breaking the user.

Membership
----------
Two tiers:
  1. Explicit denylist (FORBIDDEN_ACCOUNT_SETTINGS_KEYS) — known
     secret-bearing keys the Android client currently ships. Verified
     against the client source at port time.
  2. Pattern denylist (_SECRET_PATTERNS) — catch-all for future keys
     the client may add without remembering to update the explicit list.
     Covers the standard shapes (_api_key, _access_token, _refresh_token,
     _client_secret, _password, _cookie, _bearer, _webhook_secret,
     _auth_header).

Adding to the explicit list is cheap — a non-secret key added by mistake
just loses user convenience. Missing a secret key leaks credentials.
Err generous.
"""
from __future__ import annotations

import logging
import re
import uuid

_log = logging.getLogger(__name__)


# Explicit denylist of known secret-bearing keys. Verified against the
# Android client source at port time; any key matching a client-shipped
# credential field should be listed here.
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
    # Auth tokens (should never be synced via account settings)
    "auth_access_token",
    "auth_refresh_token",
    "auth_token_expires_at",
    # Legacy secret-transport blob
    "_sync_payload",
})

# Allowlist of keys that match a pattern below but are NOT secrets.
# Think carefully before adding here — a mistaken allowlist entry leaks.
_ALLOWED_OVERRIDE_KEYS: frozenset[str] = frozenset({
    "has_password",   # boolean flag — whether the user has a password set.
                      # Matches .*_password$ but is not the password itself.
})

# Pattern denylist — catches new keys the client may add without
# remembering to update the explicit list above. Ordered by likelihood.
_SECRET_PATTERNS: list[re.Pattern[str]] = [
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
    """True when `key` must not be persisted in account settings."""
    if key in _ALLOWED_OVERRIDE_KEYS:
        return False
    if key in FORBIDDEN_ACCOUNT_SETTINGS_KEYS:
        return True
    return any(p.match(key) for p in _SECRET_PATTERNS)


def strip_forbidden_keys(
    settings: dict[str, object],
    *,
    user_id: uuid.UUID | None = None,
    context: str = "patch",
) -> dict[str, object]:
    """Return a new dict with forbidden keys removed. Logs stripped keys.

    `context` = "patch" (write path), "get" (read-side scrub of legacy
    rows), or "backfill" (one-shot cleanup script). Used in the audit
    log line so an operator can tell where a strip happened.
    """
    safe: dict[str, object] = {}
    stripped: list[str] = []
    for key, value in settings.items():
        if is_forbidden_key(key):
            stripped.append(key)
        else:
            safe[key] = value
    if stripped:
        _log.warning(
            "ACCOUNT_SETTINGS_SECRET_STRIPPED user=%s context=%s count=%d keys=%s",
            user_id, context, len(stripped), sorted(stripped),
        )
    return safe


def scrub_stored_settings(
    settings_json: dict[str, object],
    *,
    user_id: uuid.UUID | None = None,
    context: str = "get",
) -> tuple[dict[str, object], list[str]]:
    """Scrub an existing stored-settings blob.

    Returns (scrubbed_dict, list_of_removed_keys). The caller decides
    whether to persist the scrubbed dict back to the DB or just hide the
    forbidden keys from the current response; both are valid uses.
    """
    scrubbed: dict[str, object] = {}
    removed: list[str] = []
    for key, value in settings_json.items():
        if is_forbidden_key(key):
            removed.append(key)
        else:
            scrubbed[key] = value
    if removed:
        _log.warning(
            "ACCOUNT_SETTINGS_LEGACY_SCRUB user=%s context=%s count=%d keys=%s",
            user_id, context, len(removed), sorted(removed),
        )
    return scrubbed, removed
