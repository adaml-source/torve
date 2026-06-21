"""Thin upstream HTTP adapter for NzbDAV.

This module is the ONLY place that knows about NzbDAV-shaped URLs, error
payloads, and version strings. Nothing downstream of this module may leak
those semantics out.

Security:
- SSRF protection before any HTTP call (rejects private/loopback/link-local
  unless settings.NZBDAV_ALLOW_PRIVATE_HOSTS=True).
- Only http/https schemes.
- URL length cap of 2000 chars.
- 5s connect / 15s read for test/submit; 10s connect / 30s read for poll.
- Max 2 retries with jittered backoff.
- API key passed as X-Api-Key header, never logged.
- Connection errors, timeouts, and upstream 4xx/5xx are translated to
  UpstreamError before leaving the module.
"""
from __future__ import annotations

import asyncio
import ipaddress
import logging
import random
import socket
from dataclasses import dataclass
from typing import Any
from urllib.parse import urlparse

import httpx

from app.config import settings
from app.nzbdav.failures import (
    FailureCode,
    UpstreamError,
    classify_http_status,
    classify_upstream_detail,
)

_log = logging.getLogger(__name__)

MAX_URL_LEN = 2000
MAX_RETRIES = 2


# ── SSRF guard ──────────────────────────────────────────────────────────


def validate_base_url(base_url: str) -> str:
    """Validate a base URL for use with the NzbDAV upstream.

    Raises UpstreamError(UPSTREAM_UNREACHABLE) if the URL is unsafe.
    Returns the normalized URL (trailing slash stripped).
    """
    if not isinstance(base_url, str) or not base_url:
        raise UpstreamError(
            FailureCode.UPSTREAM_UNREACHABLE, detail="empty_base_url"
        )
    if len(base_url) > MAX_URL_LEN:
        raise UpstreamError(
            FailureCode.UPSTREAM_UNREACHABLE, detail="base_url_too_long"
        )
    parsed = urlparse(base_url)
    if parsed.scheme not in ("http", "https"):
        raise UpstreamError(
            FailureCode.UPSTREAM_UNREACHABLE, detail="invalid_scheme"
        )
    host = parsed.hostname
    if not host:
        raise UpstreamError(
            FailureCode.UPSTREAM_UNREACHABLE, detail="no_host"
        )
    if not settings.NZBDAV_ALLOW_PRIVATE_HOSTS:
        for addr in _resolve_addresses(host):
            if _is_private_address(addr):
                raise UpstreamError(
                    FailureCode.UPSTREAM_UNREACHABLE,
                    detail="private_host_rejected",
                )
    return base_url.rstrip("/")


def _resolve_addresses(host: str) -> list[str]:
    """Resolve a hostname to a list of IP address strings. If the host is
    already an IP literal, returns just that literal."""
    try:
        ip = ipaddress.ip_address(host)
        return [str(ip)]
    except ValueError:
        pass
    try:
        info = socket.getaddrinfo(host, None)
    except socket.gaierror:
        # DNS failure — treat as unreachable so the SSRF guard doesn't
        # accidentally allow a private range on retry.
        raise UpstreamError(
            FailureCode.UPSTREAM_UNREACHABLE, detail="dns_failure"
        )
    return [sockaddr[0] for _, _, _, _, sockaddr in info]


def _is_private_address(addr: str) -> bool:
    try:
        ip = ipaddress.ip_address(addr)
    except ValueError:
        return True  # Unknown — fail closed
    return (
        ip.is_private
        or ip.is_loopback
        or ip.is_link_local
        or ip.is_unspecified  # 0.0.0.0/::
        or ip.is_multicast
        or ip.is_reserved
    )


# ── Data types ──────────────────────────────────────────────────────────


@dataclass(frozen=True)
class ConnectionTestResult:
    ok: bool
    version_string: str | None
    capabilities: dict | None


@dataclass(frozen=True)
class SubmitJobResult:
    upstream_job_id: str


@dataclass(frozen=True)
class PollJobResult:
    upstream_state: str  # e.g. "queued", "downloading", "repairing", "ready", "failed"
    phase: str | None
    stream_url: str | None
    detail: str | None


# ── Client ──────────────────────────────────────────────────────────────


class NzbdavClient:
    """Thin HTTP adapter to a single NzbDAV upstream.

    Not shared across users — instantiate per (user, config) pair.
    """

    def __init__(
        self,
        *,
        base_url: str,
        api_key: str,
        http_client: httpx.AsyncClient | None = None,
    ) -> None:
        # SSRF check on construction so no code path can bypass it.
        self.base_url = validate_base_url(base_url)
        self._api_key = api_key
        self._owns_client = http_client is None
        self._client = http_client

    async def _get_client(self) -> httpx.AsyncClient:
        if self._client is None:
            self._client = httpx.AsyncClient(trust_env=False)
        return self._client

    async def aclose(self) -> None:
        if self._client is not None and self._owns_client:
            await self._client.aclose()
            self._client = None

    async def __aenter__(self) -> "NzbdavClient":
        return self

    async def __aexit__(self, *exc_info) -> None:
        await self.aclose()

    def _headers(self) -> dict[str, str]:
        return {
            "X-Api-Key": self._api_key,
            "Accept": "application/json",
        }

    async def _request(
        self,
        method: str,
        path: str,
        *,
        connect_timeout: float,
        read_timeout: float,
        json_body: Any = None,
    ) -> httpx.Response:
        url = f"{self.base_url}{path}"
        timeout = httpx.Timeout(
            connect=connect_timeout, read=read_timeout,
            write=connect_timeout, pool=connect_timeout,
        )
        client = await self._get_client()
        last_exc: Exception | None = None
        for attempt in range(MAX_RETRIES + 1):
            try:
                return await client.request(
                    method, url,
                    headers=self._headers(),
                    json=json_body,
                    timeout=timeout,
                )
            except httpx.TimeoutException as exc:
                last_exc = exc
                if attempt >= MAX_RETRIES:
                    raise UpstreamError(
                        FailureCode.TIMEOUT, detail="upstream_timeout"
                    ) from exc
            except (httpx.ConnectError, httpx.NetworkError) as exc:
                last_exc = exc
                if attempt >= MAX_RETRIES:
                    raise UpstreamError(
                        FailureCode.UPSTREAM_UNREACHABLE,
                        detail="upstream_connect_error",
                    ) from exc
            except httpx.HTTPError as exc:
                last_exc = exc
                if attempt >= MAX_RETRIES:
                    raise UpstreamError(
                        FailureCode.UNKNOWN_UPSTREAM_ERROR,
                        detail=f"http_error:{type(exc).__name__}",
                    ) from exc
            await asyncio.sleep(0.1 * (2 ** attempt) + random.random() * 0.05)
        # Unreachable, but satisfies type-checker.
        raise UpstreamError(
            FailureCode.UNKNOWN_UPSTREAM_ERROR,
            detail=f"retry_exhausted:{type(last_exc).__name__ if last_exc else 'unknown'}",
        )

    # ── Public methods ──────────────────────────────────────────────────

    async def test_connection(self) -> ConnectionTestResult:
        """Verify reachability + auth. Returns version + capabilities on success."""
        resp = await self._request(
            "GET", "/api/health",
            connect_timeout=5.0, read_timeout=15.0,
        )
        code = classify_http_status(resp.status_code)
        if code is not None:
            raise UpstreamError(code, detail=f"health_http_{resp.status_code}")
        data = _safe_json(resp)
        version = None
        capabilities = None
        if isinstance(data, dict):
            version = (
                data.get("version")
                or data.get("nzbdav_version")
                or data.get("app_version")
            )
            if not isinstance(version, str):
                version = None
            capabilities = data.get("capabilities")
            if not isinstance(capabilities, dict):
                capabilities = None
        return ConnectionTestResult(
            ok=True, version_string=version, capabilities=capabilities
        )

    async def list_version(self) -> str | None:
        """Return the upstream version string if known."""
        result = await self.test_connection()
        return result.version_string

    async def submit_nzb(
        self, *, nzb_url: str | None = None, hash_key: str | None = None
    ) -> SubmitJobResult:
        """Submit an NZB / release pointer for fetch.

        NzbDAV semantics: the upstream accepts a URL to an NZB file or a
        release identifier. Torve callers pass whichever is appropriate
        for the feed; we do not synthesize or scrape.
        """
        body: dict[str, Any] = {}
        if nzb_url:
            body["nzb_url"] = nzb_url
        if hash_key:
            body["hash_key"] = hash_key
        resp = await self._request(
            "POST", "/api/jobs",
            connect_timeout=5.0, read_timeout=15.0,
            json_body=body,
        )
        code = classify_http_status(resp.status_code)
        if code is not None:
            raise UpstreamError(code, detail=f"submit_http_{resp.status_code}")
        data = _safe_json(resp)
        job_id = None
        if isinstance(data, dict):
            job_id = data.get("job_id") or data.get("id")
        if not isinstance(job_id, str):
            raise UpstreamError(
                FailureCode.UNKNOWN_UPSTREAM_ERROR, detail="submit_missing_job_id"
            )
        return SubmitJobResult(upstream_job_id=job_id)

    async def poll_job(self, *, upstream_job_id: str) -> PollJobResult:
        resp = await self._request(
            "GET", f"/api/jobs/{upstream_job_id}",
            connect_timeout=10.0, read_timeout=30.0,
        )
        code = classify_http_status(resp.status_code)
        if code is not None:
            raise UpstreamError(code, detail=f"poll_http_{resp.status_code}")
        data = _safe_json(resp) or {}
        if not isinstance(data, dict):
            raise UpstreamError(
                FailureCode.UNKNOWN_UPSTREAM_ERROR, detail="poll_bad_json"
            )
        upstream_state = str(data.get("state") or data.get("status") or "unknown")
        phase = data.get("phase")
        stream_url = data.get("stream_url") or data.get("url")
        detail = data.get("detail") or data.get("message")
        if upstream_state.lower() in ("failed", "error") and detail:
            classified = classify_upstream_detail(str(detail))
            raise UpstreamError(classified, detail=str(detail))
        return PollJobResult(
            upstream_state=upstream_state,
            phase=str(phase) if phase else None,
            stream_url=str(stream_url) if stream_url else None,
            detail=str(detail) if detail else None,
        )

    async def fetch_stream_url(self, *, upstream_job_id: str) -> str:
        result = await self.poll_job(upstream_job_id=upstream_job_id)
        if not result.stream_url:
            raise UpstreamError(
                FailureCode.STREAM_NOT_READY, detail="poll_no_stream_url"
            )
        return result.stream_url


def _safe_json(resp: httpx.Response) -> Any:
    try:
        return resp.json()
    except Exception:  # noqa: BLE001
        return None
