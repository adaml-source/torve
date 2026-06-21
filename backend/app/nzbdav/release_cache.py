"""In-process dual cache + provider health tracker for NzbDAV.

Three caches:
1. warm_success: hash_key -> ResolvedStream payload dict, short TTL
2. dead_release: hash_key -> failure_code string, long TTL
3. provider_health: reachability samples + latency

Plus a simple per-process circuit breaker. No Redis dependency — mirrors
the ttl_cache idiom from resolve_memory_service.

Follow-up TODO: persistent dead-release cache (currently in-process).
"""
from __future__ import annotations

import threading
import time
from collections import deque
from dataclasses import dataclass, field
from typing import Any

from app.config import settings


@dataclass
class _TTLEntry:
    value: Any
    expires_at: float


class _TTLCache:
    """Tiny TTL cache. Safe across threads; purges lazily on read."""

    def __init__(self, default_ttl: int) -> None:
        self._default_ttl = default_ttl
        self._data: dict[str, _TTLEntry] = {}
        self._lock = threading.Lock()

    def get(self, key: str) -> Any | None:
        now = time.time()
        with self._lock:
            entry = self._data.get(key)
            if entry is None:
                return None
            if entry.expires_at < now:
                self._data.pop(key, None)
                return None
            return entry.value

    def set(self, key: str, value: Any, *, ttl: int | None = None) -> None:
        exp = time.time() + (ttl if ttl is not None else self._default_ttl)
        with self._lock:
            self._data[key] = _TTLEntry(value=value, expires_at=exp)

    def delete(self, key: str) -> None:
        with self._lock:
            self._data.pop(key, None)

    def clear(self) -> None:
        with self._lock:
            self._data.clear()

    def __contains__(self, key: str) -> bool:
        return self.get(key) is not None


@dataclass
class _HealthState:
    # Rolling window of recent error timestamps (monotonic)
    errors: deque = field(default_factory=deque)
    last_latency_ms: float | None = None
    last_reachable: bool = True
    tripped_until: float = 0.0


class NzbdavReleaseCache:
    """Container for the three caches + circuit breaker state.

    A single process-wide instance is exposed via `release_cache`.
    """

    # Circuit breaker tuning
    CIRCUIT_ERROR_THRESHOLD = 5
    CIRCUIT_WINDOW_SECONDS = 60
    CIRCUIT_TRIP_SECONDS = 120

    def __init__(self) -> None:
        self.warm_success = _TTLCache(
            default_ttl=settings.NZBDAV_WARM_SUCCESS_TTL_SECONDS
        )
        self.dead_release = _TTLCache(
            default_ttl=settings.NZBDAV_DEAD_RELEASE_TTL_SECONDS
        )
        self._health: dict[str, _HealthState] = {}
        self._lock = threading.Lock()

    # ── warm-success cache ──────────────────────────────────────────────

    def get_warm_success(self, hash_key: str) -> dict | None:
        return self.warm_success.get(hash_key)

    def set_warm_success(self, hash_key: str, payload: dict) -> None:
        self.warm_success.set(hash_key, payload)

    # ── dead-release cache ──────────────────────────────────────────────

    def get_dead_release(self, hash_key: str) -> str | None:
        return self.dead_release.get(hash_key)

    def set_dead_release(self, hash_key: str, failure_code: str) -> None:
        self.dead_release.set(hash_key, failure_code)

    # ── provider health / circuit breaker ────────────────────────────────

    def _health_for(self, provider_key: str) -> _HealthState:
        with self._lock:
            state = self._health.get(provider_key)
            if state is None:
                state = _HealthState()
                self._health[provider_key] = state
            return state

    def record_success(self, provider_key: str, *, latency_ms: float) -> None:
        s = self._health_for(provider_key)
        with self._lock:
            s.last_latency_ms = latency_ms
            s.last_reachable = True

    def record_error(self, provider_key: str) -> bool:
        """Record an upstream error. Returns True if the circuit just tripped."""
        now = time.monotonic()
        s = self._health_for(provider_key)
        with self._lock:
            s.last_reachable = False
            s.errors.append(now)
            # Drop samples outside the window
            cutoff = now - self.CIRCUIT_WINDOW_SECONDS
            while s.errors and s.errors[0] < cutoff:
                s.errors.popleft()
            if (
                s.tripped_until < now
                and len(s.errors) >= self.CIRCUIT_ERROR_THRESHOLD
            ):
                s.tripped_until = now + self.CIRCUIT_TRIP_SECONDS
                return True
            return False

    def is_circuit_tripped(self, provider_key: str) -> bool:
        now = time.monotonic()
        s = self._health_for(provider_key)
        with self._lock:
            return s.tripped_until > now

    def circuit_until(self, provider_key: str) -> float:
        s = self._health_for(provider_key)
        with self._lock:
            return s.tripped_until

    def reset(self) -> None:
        """Clear all caches + health state (test helper)."""
        self.warm_success.clear()
        self.dead_release.clear()
        with self._lock:
            self._health.clear()


# Process-wide singleton. Tests reset via `release_cache.reset()`.
release_cache = NzbdavReleaseCache()
