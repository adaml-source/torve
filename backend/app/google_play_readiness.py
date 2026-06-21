"""
Google Play IAP configuration readiness.

Single source of truth for "is the backend correctly configured to verify
Google Play purchases right now?" Called from three places:

  1. App startup — logs a redacted one-liner so operators can confirm
     readiness state on every restart.
  2. /me/health/integrations — surfaces readiness to the client's
     diagnostics screen.
  3. purchase_verify._verify_google_play_token — legacy helper returning
     stable error codes without leaking exception text.

Security invariants
-------------------
- No file paths returned to the client.
- No service account JSON contents returned.
- No client_email / private_key / token_uri in any response or log line.
- Reasons are opaque string tokens (e.g. "service_account_unreadable"),
  never free text containing secrets.
"""
from __future__ import annotations

import json
import logging
import os
from dataclasses import dataclass
from typing import Literal

from app.config import settings

_log = logging.getLogger(__name__)


# ── Public types ───────────────────────────────────────────────────────

Status = Literal["ready", "not_configured", "misconfigured"]


@dataclass(frozen=True)
class GooglePlayReadiness:
    """Current Google Play IAP config state. Safe to return to clients."""
    ready: bool
    status: Status
    # One of: None, "service_account_path_empty",
    #         "service_account_unreadable", "service_account_malformed",
    #         "service_account_wrong_shape", "package_name_missing",
    #         "product_ids_missing".
    reason: str | None


# ── Reason constants (stable operator-facing tokens) ──────────────────

R_PATH_EMPTY = "service_account_path_empty"
R_UNREADABLE = "service_account_unreadable"
R_MALFORMED = "service_account_malformed"
R_WRONG_SHAPE = "service_account_wrong_shape"
R_PACKAGE_MISSING = "package_name_missing"
R_PRODUCT_IDS_MISSING = "product_ids_missing"


# ── Readiness check ───────────────────────────────────────────────────


def assess_readiness() -> GooglePlayReadiness:
    """Run the full readiness check against current settings + filesystem.

    Never raises. Never logs secrets. Cheap — acceptable to call on every
    legacy purchase-verification diagnostics.
    """
    path = (settings.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON or "").strip()
    package = (getattr(settings, "GOOGLE_PLAY_PACKAGE_NAME", "") or "").strip()
    product_ids = settings.all_google_play_product_ids

    # "not_configured" means the operator has not wired Google Play IAP
    # at all — intentional opt-out. Everything else is "misconfigured".
    nothing_set = not path and not package and not product_ids
    if nothing_set:
        return GooglePlayReadiness(
            ready=False, status="not_configured", reason=R_PATH_EMPTY,
        )

    if not path:
        return GooglePlayReadiness(
            ready=False, status="misconfigured", reason=R_PATH_EMPTY,
        )

    # Existence + readability check. Does NOT open or parse the file yet.
    if not os.path.isfile(path) or not os.access(path, os.R_OK):
        return GooglePlayReadiness(
            ready=False, status="misconfigured", reason=R_UNREADABLE,
        )

    # Parse + shape check. We DO NOT log the contents on failure.
    try:
        with open(path, "r", encoding="utf-8") as f:
            sa = json.load(f)
    except (OSError, json.JSONDecodeError):
        return GooglePlayReadiness(
            ready=False, status="misconfigured", reason=R_MALFORMED,
        )
    if not isinstance(sa, dict) or sa.get("type") != "service_account":
        return GooglePlayReadiness(
            ready=False, status="misconfigured", reason=R_WRONG_SHAPE,
        )
    # Minimum required fields for the OAuth JWT signing flow.
    for required in ("private_key", "client_email", "token_uri"):
        if not sa.get(required):
            return GooglePlayReadiness(
                ready=False, status="misconfigured", reason=R_WRONG_SHAPE,
            )

    if not package:
        return GooglePlayReadiness(
            ready=False, status="misconfigured", reason=R_PACKAGE_MISSING,
        )

    if not product_ids:
        return GooglePlayReadiness(
            ready=False, status="misconfigured", reason=R_PRODUCT_IDS_MISSING,
        )

    return GooglePlayReadiness(ready=True, status="ready", reason=None)


def log_startup_readiness() -> None:
    """Emit a single stdout line on app boot with the current state.

    Goes to stdout (print, not logger) so it appears in journalctl even
    before uvicorn sets up the root logger — matches the pattern used by
    the Sentry init message.
    """
    r = assess_readiness()
    print(
        f"GOOGLE_PLAY_READINESS status={r.status} reason={r.reason or '-'}",
        flush=True,
    )
