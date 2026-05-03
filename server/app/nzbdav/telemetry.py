"""Structured telemetry helpers for the NzbDAV integration.

Events (one helper per event):
    NZBDAV_CONFIG_TEST         latency_ms, outcome, user
    NZBDAV_WARM_START          latency_ms, content, user, candidates
    NZBDAV_WARM_TO_READY       duration_ms, hash_key, user
    NZBDAV_RESOLVE             latency_ms, user, outcome, simplified_state
    NZBDAV_CACHE_HIT           cache, hash_key
    NZBDAV_FAILURE             code, user, phase
    NZBDAV_HANDOFF             kind, outcome, user
    NZBDAV_CIRCUIT_TRIPPED     until
    NZBDAV_CANCEL              reason, user

Never log decrypted secrets or raw upstream URLs. User IDs are logged as
the UUID string. Hash keys are opaque candidate identifiers (safe to log).
"""
from __future__ import annotations

import logging
import uuid

_log = logging.getLogger(__name__)


def _uid(user_id: str | uuid.UUID | None) -> str:
    if user_id is None:
        return "-"
    return str(user_id)


def config_test(*, latency_ms: int, outcome: str, user_id) -> None:
    _log.info(
        "NZBDAV_CONFIG_TEST latency_ms=%d outcome=%s user=%s",
        latency_ms, outcome, _uid(user_id),
    )


def warm_start(*, latency_ms: int, content_id: str, user_id, candidates: int) -> None:
    _log.info(
        "NZBDAV_WARM_START latency_ms=%d content=%s user=%s candidates=%d",
        latency_ms, content_id, _uid(user_id), candidates,
    )


def warm_to_ready(*, duration_ms: int, hash_key: str, user_id) -> None:
    _log.info(
        "NZBDAV_WARM_TO_READY duration_ms=%d hash_key=%s user=%s",
        duration_ms, hash_key, _uid(user_id),
    )


def resolve(*, latency_ms: int, user_id, outcome: str, simplified_state: str) -> None:
    _log.info(
        "NZBDAV_RESOLVE latency_ms=%d user=%s outcome=%s simplified_state=%s",
        latency_ms, _uid(user_id), outcome, simplified_state,
    )


def cache_hit(*, cache: str, hash_key: str) -> None:
    _log.info("NZBDAV_CACHE_HIT cache=%s hash_key=%s", cache, hash_key)


def failure(*, code: str, user_id, phase: str) -> None:
    _log.info(
        "NZBDAV_FAILURE code=%s user=%s phase=%s",
        code, _uid(user_id), phase,
    )


def handoff(*, kind: str, outcome: str, user_id) -> None:
    _log.info(
        "NZBDAV_HANDOFF kind=%s outcome=%s user=%s",
        kind, outcome, _uid(user_id),
    )


def circuit_tripped(*, until_ts: float) -> None:
    _log.warning("NZBDAV_CIRCUIT_TRIPPED until=%s", until_ts)


def cancel(*, reason: str, user_id) -> None:
    _log.info("NZBDAV_CANCEL reason=%s user=%s", reason, _uid(user_id))
