"""Symmetric wrapping for at-rest sensitive values (Prompt 12 hardening).

Concretely: LAN-hub `auth_secret` (and a couple of related fields) used to
sit in the database in plaintext. Production deployments need them
encrypted-at-rest so a DB dump alone does not surrender hub auth secrets.

The wrap key lives in `TORVE_LAN_SECRET_WRAP_KEY` (a urlsafe base64-encoded
Fernet key, 32 bytes pre-encoding). Generate one with:

    python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"

Behavior matrix:

  * `TORVE_LAN_SECRET_WRAP_KEY` set + valid → wrap/unwrap with Fernet.
    Wrapped values carry the `WRAP_PREFIX` so legacy plaintext rows are
    still recognizable on read (and migrated lazily on next write).

  * `TORVE_LAN_SECRET_WRAP_KEY` unset + `TORVE_ENV=prod` → refuse to wrap.
    `wrap()` raises so callers (the publish-LAN-hub endpoint) can return
    503 instead of accepting a plaintext write.

  * `TORVE_LAN_SECRET_WRAP_KEY` unset + non-prod → store plaintext, log
    a one-shot warning. Tests and local dev keep working without a key.

The unwrap path is permissive in every mode: a legacy plaintext value
(no prefix) round-trips unchanged so existing dev DBs keep working.
"""
from __future__ import annotations

import base64
import logging
import os
from typing import Optional

from cryptography.fernet import Fernet, InvalidToken

logger = logging.getLogger(__name__)

# Wrapped values are tagged so the read path can tell ciphertext from
# legacy plaintext without parsing every row twice. The prefix is fixed
# so a future migration can sweep for `WRAP_PREFIX` and re-encrypt.
WRAP_PREFIX = "fernet:v1:"

_ENV_KEY = "TORVE_LAN_SECRET_WRAP_KEY"
_ENV_MODE = "TORVE_ENV"
_PROD_VALUES = {"prod", "production", "release"}

_warned_no_key = False


def _is_production() -> bool:
    return os.environ.get(_ENV_MODE, "").strip().lower() in _PROD_VALUES


def _load_fernet() -> Optional[Fernet]:
    raw = os.environ.get(_ENV_KEY)
    if not raw:
        return None
    try:
        # Fernet rejects malformed keys at construction time, so a typo
        # in the env var fails loud rather than corrupting writes.
        return Fernet(raw.encode("ascii"))
    except (ValueError, base64.binascii.Error) as exc:
        # In production, refuse to come up with a broken key. In dev,
        # log+fall through so unit tests with a bogus key (e.g. CI not
        # priming the var) still run.
        if _is_production():
            raise RuntimeError(
                f"{_ENV_KEY} is set but invalid; refusing to start in production"
            ) from exc
        logger.warning("%s is set but invalid; falling back to plaintext: %s", _ENV_KEY, exc)
        return None


class WrapUnavailable(RuntimeError):
    """Raised in production when wrapping is required but unconfigured."""


def wrap(plaintext: str) -> str:
    """Encrypt [plaintext] for at-rest storage.

    Production mode without a wrap key raises [WrapUnavailable] so the
    caller surfaces a 503 to the client rather than silently downgrading.
    Dev/test without a key returns the plaintext unchanged with a single
    process-lifetime warning.
    """
    global _warned_no_key
    if not plaintext:
        return plaintext
    fernet = _load_fernet()
    if fernet is not None:
        token = fernet.encrypt(plaintext.encode("utf-8")).decode("ascii")
        return f"{WRAP_PREFIX}{token}"
    if _is_production():
        raise WrapUnavailable(
            f"{_ENV_KEY} is unset; production deployments must configure a wrap key"
        )
    if not _warned_no_key:
        logger.warning(
            "%s unset; storing LAN auth_secret in plaintext (acceptable only "
            "for dev/test). Set the env var to enable Fernet wrapping.",
            _ENV_KEY,
        )
        _warned_no_key = True
    return plaintext


def unwrap(value: Optional[str]) -> Optional[str]:
    """Decrypt [value] from at-rest storage.

    Tolerates three input shapes so we never dataloss-fail on a legacy DB:
      * `fernet:v1:<token>` → decrypt with Fernet.
      * Anything else → treat as plaintext (legacy / dev) and return as-is.
      * None → None.
    """
    if value is None or not value:
        return value
    if not value.startswith(WRAP_PREFIX):
        return value  # legacy plaintext row
    token = value[len(WRAP_PREFIX):]
    fernet = _load_fernet()
    if fernet is None:
        # We have a wrapped value but no key. This is a config bug — the
        # operator changed env state out from under existing rows. Fail
        # loud so they see the cause instead of getting a silent 401 later.
        raise WrapUnavailable(
            f"{_ENV_KEY} is unset but database contains wrapped values"
        )
    try:
        return fernet.decrypt(token.encode("ascii")).decode("utf-8")
    except InvalidToken as exc:
        # Wrong key for this row — same root cause as above (key rotation
        # without a re-wrap migration). Surface explicitly.
        raise WrapUnavailable(
            "wrap key does not match stored ciphertext (rotated without re-wrap?)"
        ) from exc


def is_wrap_configured() -> bool:
    """Used by health checks / startup gates to surface wrapping state."""
    return _load_fernet() is not None
