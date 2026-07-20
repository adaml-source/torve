"""Small in-process rate limiter for abuse resistance.

This is deliberately simple and dependency-free. It is not a substitute for
edge/CDN limits, but it gives sensitive endpoints a backend-side guardrail in
single-process and low-scale deployments.
"""
from __future__ import annotations

import logging
import threading
import time

from fastapi import HTTPException, Request, status

_log = logging.getLogger(__name__)
_lock = threading.Lock()
_buckets: dict[tuple[str, str], list[float]] = {}


def enforce_rate_limit(
    *,
    category: str,
    request: Request | None = None,
    user_id: str | None = None,
    device_id: str | None = None,
    limit: int,
    window_seconds: int,
) -> None:
    identity = _identity(request=request, user_id=user_id, device_id=device_id)
    now = time.monotonic()
    cutoff = now - window_seconds
    key = (category, identity)
    with _lock:
        recent = [ts for ts in _buckets.get(key, []) if ts >= cutoff]
        if len(recent) >= limit:
            _buckets[key] = recent
            _log.warning(
                "RATE_LIMITED category=%s identity=%s limit=%d window=%d",
                category, identity, limit, window_seconds,
            )
            raise HTTPException(
                status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                detail={
                    "error_code": "rate_limited",
                    "message": "Too many requests. Please try again later.",
                },
            )
        recent.append(now)
        _buckets[key] = recent


def _identity(
    *,
    request: Request | None,
    user_id: str | None,
    device_id: str | None,
) -> str:
    parts: list[str] = []
    if user_id:
        parts.append(f"u:{user_id}")
    if device_id:
        parts.append(f"d:{device_id}")
    if request is not None and request.client is not None:
        parts.append(f"ip:{request.client.host}")
    return "|".join(parts) or "anonymous"


def reset_rate_limits_for_tests() -> None:
    with _lock:
        _buckets.clear()
