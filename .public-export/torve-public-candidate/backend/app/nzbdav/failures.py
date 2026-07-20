"""Normalized upstream failure codes.

UpstreamError raises inside the nzbdav package carry a code + optional
fallback hints. NEVER surface raw upstream detail to clients — the
`detail` field is internal-only and stays in logs.

The `code` values are the only strings allowed in API responses.
"""
from __future__ import annotations

from enum import Enum


class FailureCode(str, Enum):
    UPSTREAM_UNREACHABLE = "UPSTREAM_UNREACHABLE"
    AUTH_INVALID = "AUTH_INVALID"
    RATE_LIMITED = "RATE_LIMITED"
    RELEASE_UNAVAILABLE = "RELEASE_UNAVAILABLE"
    REPAIR_FAILED = "REPAIR_FAILED"
    EXTRACTION_FAILED = "EXTRACTION_FAILED"
    TIMEOUT = "TIMEOUT"
    STREAM_NOT_READY = "STREAM_NOT_READY"
    UNKNOWN_UPSTREAM_ERROR = "UNKNOWN_UPSTREAM_ERROR"


# Dead-release codes: persisted in the dead-release cache; do not retry
# for the cache's TTL.
DEAD_RELEASE_CODES = frozenset({
    FailureCode.RELEASE_UNAVAILABLE.value,
    FailureCode.REPAIR_FAILED.value,
    FailureCode.EXTRACTION_FAILED.value,
})


class UpstreamError(Exception):
    """Internal exception carrying a normalized failure code.

    `detail` is for logs only — never put it in an API response.
    """

    def __init__(
        self,
        code: FailureCode,
        *,
        detail: str | None = None,
        fallback_suggestions: list[str] | None = None,
    ) -> None:
        self.code = code
        self.detail = detail
        self.fallback_suggestions = list(fallback_suggestions or [])
        super().__init__(code.value)


# Substring markers used to classify free-form upstream error strings.
# Keep lowercase. Order matters: the first match wins.
_REPAIR_MARKERS = (
    "par2 repair failed",
    "repair failed",
    "unrepairable",
    "par2 failed",
)
_EXTRACTION_MARKERS = (
    "unrar failed",
    "unpack failed",
    "extraction failed",
    "7z extraction error",
)
_RELEASE_UNAVAILABLE_MARKERS = (
    "no articles",
    "article not found",
    "no such release",
    "release unavailable",
    "404 not found",
)


def classify_upstream_detail(detail: str | None) -> FailureCode:
    """Classify a raw upstream message string into a FailureCode.

    Used when no HTTP status or network signal disambiguates.
    """
    if not detail:
        return FailureCode.UNKNOWN_UPSTREAM_ERROR
    d = detail.lower()
    for marker in _REPAIR_MARKERS:
        if marker in d:
            return FailureCode.REPAIR_FAILED
    for marker in _EXTRACTION_MARKERS:
        if marker in d:
            return FailureCode.EXTRACTION_FAILED
    for marker in _RELEASE_UNAVAILABLE_MARKERS:
        if marker in d:
            return FailureCode.RELEASE_UNAVAILABLE
    return FailureCode.UNKNOWN_UPSTREAM_ERROR


def classify_http_status(status: int) -> FailureCode | None:
    """Map an HTTP status to a FailureCode. Returns None for 2xx/3xx."""
    if 200 <= status < 400:
        return None
    if status in (401, 403):
        return FailureCode.AUTH_INVALID
    if status == 404:
        return FailureCode.RELEASE_UNAVAILABLE
    if status == 429:
        return FailureCode.RATE_LIMITED
    if 500 <= status < 600:
        return FailureCode.UPSTREAM_UNREACHABLE
    return FailureCode.UNKNOWN_UPSTREAM_ERROR


def default_fallback_suggestions(code: FailureCode) -> list[str]:
    """Canonical fallback hints for a given failure code. Safe to return in
    API responses — these are normalized, non-leaky strings.
    """
    if code == FailureCode.RELEASE_UNAVAILABLE:
        return ["try_alternate_release", "try_easynews"]
    if code in (FailureCode.REPAIR_FAILED, FailureCode.EXTRACTION_FAILED):
        return ["try_alternate_release"]
    if code == FailureCode.AUTH_INVALID:
        return ["reconfigure_integration"]
    if code == FailureCode.RATE_LIMITED:
        return ["retry_in_60s"]
    if code == FailureCode.UPSTREAM_UNREACHABLE:
        return ["retry_in_60s", "try_alternate_provider"]
    if code == FailureCode.TIMEOUT:
        return ["retry_in_60s"]
    if code == FailureCode.STREAM_NOT_READY:
        return ["retry_in_60s"]
    return []
