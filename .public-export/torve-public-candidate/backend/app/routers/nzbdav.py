"""NzbDAV upstream integration endpoints.

Two groups:
- /integrations/nzbdav/*  — config lifecycle (test, save, status, delete)
- /resolver/usenet/*      — warm, resolve, job poll, cancel, handoff

Write and resolver endpoints require authenticated active account access.
GET /status uses `get_current_user_id` because it is read-only. API responses
are strictly Torve-native; upstream semantics never leak to clients.
"""
from __future__ import annotations

import hashlib
import logging
import time
import uuid
from typing import Any

import httpx
from fastapi import APIRouter, Depends, HTTPException, Request, Response, status
from fastapi.responses import RedirectResponse, StreamingResponse
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from app.config import settings
from app.deps import (
    AccountDeviceContext,
    get_current_user_id,
    get_db,
    require_account_access,
    require_account_device_access,
)
from app.models import NzbdavConfig, WarmJob
from app.nzbdav import state as st
from app.nzbdav import telemetry
from app.nzbdav.account_store import account_store
from app.nzbdav.client import NzbdavClient, validate_base_url
from app.nzbdav.failures import (
    FailureCode,
    UpstreamError,
    default_fallback_suggestions,
)
from app.nzbdav.handoff import HandoffError, verify as verify_handoff
from app.nzbdav.release_cache import release_cache
from app.nzbdav.resolve_service import resolve_service
from app.nzbdav.warm_service import WarmCandidateInput, warm_service
from app.rate_limits import enforce_rate_limit

_log = logging.getLogger(__name__)

integrations_router = APIRouter(
    prefix="/integrations/nzbdav", tags=["nzbdav-integration"]
)
resolver_router = APIRouter(prefix="/resolver/usenet", tags=["nzbdav-resolver"])


# ── Pydantic types (public API surface) ─────────────────────────────────


class UsenetCandidate(BaseModel):
    """Torve-native candidate input.

    `candidate_id` is a client-stable identifier (any unique token).
    `hash_key` is the dedup / cache key — for usenet feeds this is
    typically a release infohash-equivalent. `nzb_url` is optional; when
    set it is forwarded to the upstream as the submission source.
    """
    candidate_id: str = Field(min_length=1, max_length=255)
    hash_key: str = Field(min_length=1, max_length=255)
    nzb_url: str | None = Field(default=None, max_length=2000)


class ResolvedStream(BaseModel):
    """Torve-native handoff payload. `url` is a Torve-side handoff URL,
    never the raw upstream URL."""
    url: str
    is_direct: bool = False
    supports_range: bool = True
    stream_id: str | None = None


class ConfigTestRequest(BaseModel):
    base_url: str = Field(min_length=1, max_length=2000)
    api_key: str = Field(min_length=1, max_length=500)


class ConfigTestResponse(BaseModel):
    ok: bool
    degraded: bool = False
    reason: str | None = None


class ConfigSaveRequest(BaseModel):
    base_url: str = Field(min_length=1, max_length=2000)
    api_key: str = Field(min_length=1, max_length=500)


class ConfigStatusResponse(BaseModel):
    configured: bool
    is_enabled: bool = False
    last_tested_at: str | None = None
    last_healthy_at: str | None = None
    degraded: bool = False
    reason: str | None = None


class WarmRequest(BaseModel):
    content_id: str = Field(min_length=1, max_length=255)
    candidates: list[UsenetCandidate] = Field(default_factory=list, max_length=50)
    top_n: int | None = Field(default=None, ge=1, le=10)


class WarmCandidateOut(BaseModel):
    candidate_id: str
    hash_key: str
    job_id: str | None = None
    state: str  # simplified: ready | warming | failed
    failure_code: str | None = None
    fallback_suggestions: list[str] = Field(default_factory=list)


class WarmResponse(BaseModel):
    content_id: str
    results: list[WarmCandidateOut]


class ResolveRequest(BaseModel):
    content_id: str = Field(min_length=1, max_length=255)
    candidate: UsenetCandidate


class ResolveResponse(BaseModel):
    state: str  # ready | warming | failed
    job_id: str | None = None
    failure_code: str | None = None
    fallback_suggestions: list[str] = Field(default_factory=list)
    stream: ResolvedStream | None = None


class JobOut(BaseModel):
    job_id: str
    content_id: str
    state: str  # simplified
    failure_code: str | None = None
    fallback_suggestions: list[str] = Field(default_factory=list)


class ResolveNzbRequest(BaseModel):
    nzb_url: str = Field(min_length=1, max_length=2000)
    title: str = Field(default="", max_length=500)


class CancelRequest(BaseModel):
    content_id: str | None = Field(default=None, max_length=255)
    candidate_id: str | None = Field(default=None, max_length=255)
    user_session: str | None = Field(default=None, max_length=255)


class CancelResponse(BaseModel):
    cancelled: int


# ── Shared helpers ──────────────────────────────────────────────────────


def _uuid_or_400(user_id: str) -> uuid.UUID:
    try:
        return uuid.UUID(user_id)
    except ValueError:
        raise HTTPException(status_code=400, detail="invalid_user_id")


def _simplified_state_for_job(job: WarmJob) -> str:
    return st.to_simplified(job.state)


def _assess_version(version_string: str | None) -> tuple[bool, str | None]:
    """Compare upstream version against NZBDAV_MIN_KNOWN_GOOD.

    Returns (degraded, reason). Uses simple dotted-int semver-ish ordering.
    Missing or unparseable versions are tolerated (not marked degraded).
    """
    min_ver = settings.NZBDAV_MIN_KNOWN_GOOD or "0.0.0"
    if not version_string:
        return False, None

    def _parse(v: str) -> tuple[int, ...]:
        parts: list[int] = []
        for piece in v.split("."):
            # Allow "1.2.3-rc1" — stop at first non-numeric prefix token.
            num = ""
            for ch in piece:
                if ch.isdigit():
                    num += ch
                else:
                    break
            parts.append(int(num) if num else 0)
        return tuple(parts)

    try:
        cur = _parse(version_string)
        minv = _parse(min_ver)
    except Exception:  # noqa: BLE001
        return False, None
    # Pad to equal length
    n = max(len(cur), len(minv))
    cur = cur + (0,) * (n - len(cur))
    minv = minv + (0,) * (n - len(minv))
    if cur < minv:
        return True, "upstream_version_below_min_known_good"
    return False, None


# ── Integrations: test / save / status / delete ─────────────────────────


@integrations_router.post("/test", response_model=ConfigTestResponse)
async def test_config(
    body: ConfigTestRequest,
    user_id: str = Depends(require_account_access),
) -> ConfigTestResponse:
    """Validate supplied credentials. Does NOT persist anything."""
    uid = _uuid_or_400(user_id)
    start = time.monotonic()
    try:
        validate_base_url(body.base_url)
    except UpstreamError as exc:
        telemetry.config_test(
            latency_ms=int((time.monotonic() - start) * 1000),
            outcome=exc.code.value, user_id=uid,
        )
        return ConfigTestResponse(
            ok=False, degraded=True, reason=exc.code.value,
        )
    async with NzbdavClient(
        base_url=body.base_url, api_key=body.api_key
    ) as client:
        try:
            result = await client.test_connection()
        except UpstreamError as exc:
            telemetry.config_test(
                latency_ms=int((time.monotonic() - start) * 1000),
                outcome=exc.code.value, user_id=uid,
            )
            return ConfigTestResponse(
                ok=False, degraded=True, reason=exc.code.value,
            )
    degraded, reason = _assess_version(result.version_string)
    telemetry.config_test(
        latency_ms=int((time.monotonic() - start) * 1000),
        outcome=("degraded" if degraded else "ok"), user_id=uid,
    )
    return ConfigTestResponse(ok=True, degraded=degraded, reason=reason)


@integrations_router.put("/config", response_model=ConfigStatusResponse)
async def save_config(
    body: ConfigSaveRequest,
    user_id: str = Depends(require_account_access),
    db: Session = Depends(get_db),
) -> ConfigStatusResponse:
    """Validate and persist NzbDAV credentials for the user."""
    uid = _uuid_or_400(user_id)
    try:
        validate_base_url(body.base_url)
    except UpstreamError:
        raise HTTPException(
            status_code=400,
            detail={"code": FailureCode.UPSTREAM_UNREACHABLE.value,
                    "message": "base_url is not acceptable"},
        )
    version_string: str | None = None
    capabilities: dict | None = None
    async with NzbdavClient(
        base_url=body.base_url, api_key=body.api_key
    ) as client:
        try:
            result = await client.test_connection()
            version_string = result.version_string
            capabilities = result.capabilities
        except UpstreamError as exc:
            raise HTTPException(
                status_code=400,
                detail={"code": exc.code.value,
                        "message": "failed_to_validate_upstream"},
            )
    cfg = account_store.save(
        db,
        user_id=uid,
        base_url=body.base_url,
        api_key_plaintext=body.api_key,
        version_string=version_string,
        capabilities=capabilities,
        is_enabled=True,
    )
    degraded, reason = _assess_version(cfg.version_string)
    return ConfigStatusResponse(
        configured=True,
        is_enabled=cfg.is_enabled,
        last_tested_at=cfg.last_tested_at.isoformat() if cfg.last_tested_at else None,
        last_healthy_at=cfg.last_healthy_at.isoformat() if cfg.last_healthy_at else None,
        degraded=degraded,
        reason=reason,
    )


@integrations_router.get("/status", response_model=ConfigStatusResponse)
def get_config_status(
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> ConfigStatusResponse:
    uid = _uuid_or_400(user_id)
    cfg = account_store.get_for_user(db, uid)
    if cfg is None:
        return ConfigStatusResponse(configured=False)
    degraded, reason = _assess_version(cfg.version_string)
    return ConfigStatusResponse(
        configured=True,
        is_enabled=cfg.is_enabled,
        last_tested_at=cfg.last_tested_at.isoformat() if cfg.last_tested_at else None,
        last_healthy_at=cfg.last_healthy_at.isoformat() if cfg.last_healthy_at else None,
        degraded=degraded,
        reason=reason,
    )


@integrations_router.delete("/config", status_code=204)
def delete_config(
    user_id: str = Depends(require_account_access),
    db: Session = Depends(get_db),
) -> Response:
    uid = _uuid_or_400(user_id)
    account_store.delete_for_user(db, uid)
    return Response(status_code=204)


# ── Resolver: warm / resolve / jobs / cancel / handoff ──────────────────


@resolver_router.post("/warm", response_model=WarmResponse)
async def warm_endpoint(
    body: WarmRequest,
    request: Request,
    caller: AccountDeviceContext = Depends(require_account_device_access),
    db: Session = Depends(get_db),
) -> WarmResponse:
    uid = _uuid_or_400(caller.user_id)
    enforce_rate_limit(
        category="resolver",
        request=request,
        user_id=caller.user_id,
        device_id=str(caller.device_id),
        limit=180,
        window_seconds=60,
    )
    inputs = [
        WarmCandidateInput(
            candidate_id=c.candidate_id,
            hash_key=c.hash_key,
            nzb_url=c.nzb_url,
        )
        for c in body.candidates
    ]
    results = await warm_service.start_warm(
        db,
        user_id=uid,
        content_id=body.content_id,
        candidates=inputs,
        top_n=body.top_n or 5,
    )
    out = [
        WarmCandidateOut(
            candidate_id=r.candidate_id,
            hash_key=r.hash_key,
            job_id=r.job_id,
            state=r.simplified_state,
            failure_code=r.failure_code,
            fallback_suggestions=r.fallback_suggestions,
        )
        for r in results
    ]
    return WarmResponse(content_id=body.content_id, results=out)


@resolver_router.post("/resolve", response_model=ResolveResponse)
async def resolve_endpoint(
    body: ResolveRequest,
    request: Request,
    caller: AccountDeviceContext = Depends(require_account_device_access),
    db: Session = Depends(get_db),
) -> ResolveResponse:
    uid = _uuid_or_400(caller.user_id)
    enforce_rate_limit(
        category="resolver",
        request=request,
        user_id=caller.user_id,
        device_id=str(caller.device_id),
        limit=180,
        window_seconds=60,
    )
    cand = WarmCandidateInput(
        candidate_id=body.candidate.candidate_id,
        hash_key=body.candidate.hash_key,
        nzb_url=body.candidate.nzb_url,
    )
    result = await resolve_service.resolve(
        db,
        user_id=uid,
        device_id=caller.device_id,
        content_id=body.content_id,
        candidate=cand,
    )
    stream: ResolvedStream | None = None
    if (
        result.simplified_state == st.SIMPLIFIED_READY
        and result.stream_url is not None
    ):
        stream = ResolvedStream(
            url=result.stream_url,
            is_direct=bool(result.is_direct),
            supports_range=bool(result.supports_range),
            stream_id=result.stream_id,
        )
    return ResolveResponse(
        state=result.simplified_state,
        job_id=result.job_id,
        failure_code=result.failure_code,
        fallback_suggestions=result.fallback_suggestions or [],
        stream=stream,
    )


@resolver_router.post("/resolve-nzb", response_model=ResolveResponse)
async def resolve_nzb_endpoint(
    body: ResolveNzbRequest,
    request: Request,
    caller: AccountDeviceContext = Depends(require_account_device_access),
    db: Session = Depends(get_db),
) -> ResolveResponse:
    """Resolve a bare NZB URL directly to a playable stream.

    Accepts an NZB URL and optional title from a browse surface, submits
    it to the user's registered NzbDAV integration, and returns the same
    shape as POST /resolve. The caller does not need to synthesize a
    UsenetCandidate — the server derives stable dedup keys from the URL.

    Returns 422 with error_code='nzbdav_not_configured' when the user has
    no active NzbDAV integration.
    """
    uid = _uuid_or_400(caller.user_id)
    enforce_rate_limit(
        category="resolver",
        request=request,
        user_id=caller.user_id,
        device_id=str(caller.device_id),
        limit=180,
        window_seconds=60,
    )

    cfg = account_store.get_for_user(db, uid)
    if cfg is None or not cfg.is_enabled:
        raise HTTPException(
            status_code=422,
            detail={"error_code": "nzbdav_not_configured",
                    "message": "No NzbDAV integration registered for this account"},
        )

    url_hash = hashlib.sha256(body.nzb_url.encode()).hexdigest()[:32]
    candidate = WarmCandidateInput(
        candidate_id=f"nzb-{url_hash}",
        hash_key=url_hash,
        nzb_url=body.nzb_url,
    )
    result = await resolve_service.resolve(
        db,
        user_id=uid,
        device_id=caller.device_id,
        content_id=f"bare-nzb-{url_hash}",
        candidate=candidate,
    )
    stream: ResolvedStream | None = None
    if result.simplified_state == st.SIMPLIFIED_READY and result.stream_url is not None:
        stream = ResolvedStream(
            url=result.stream_url,
            is_direct=bool(result.is_direct),
            supports_range=bool(result.supports_range),
            stream_id=result.stream_id,
        )
    return ResolveResponse(
        state=result.simplified_state,
        job_id=result.job_id,
        failure_code=result.failure_code,
        fallback_suggestions=result.fallback_suggestions or [],
        stream=stream,
    )


@resolver_router.get("/jobs/{job_id}", response_model=JobOut)
def get_job(
    job_id: str,
    request: Request,
    caller: AccountDeviceContext = Depends(require_account_device_access),
    db: Session = Depends(get_db),
) -> JobOut:
    uid = _uuid_or_400(caller.user_id)
    enforce_rate_limit(
        category="resolver",
        request=request,
        user_id=caller.user_id,
        device_id=str(caller.device_id),
        limit=240,
        window_seconds=60,
    )
    try:
        jid = uuid.UUID(job_id)
    except ValueError:
        raise HTTPException(status_code=400, detail="invalid_job_id")
    job = (
        db.query(WarmJob)
        .filter(WarmJob.job_id == jid, WarmJob.user_id == uid)
        .one_or_none()
    )
    if job is None:
        raise HTTPException(status_code=404, detail="not_found")
    simplified = _simplified_state_for_job(job)
    code = job.failure_code
    suggestions: list[str] = []
    if code:
        try:
            suggestions = default_fallback_suggestions(FailureCode(code))
        except ValueError:
            suggestions = []
    return JobOut(
        job_id=str(job.job_id),
        content_id=job.content_id,
        state=simplified,
        failure_code=code,
        fallback_suggestions=suggestions,
    )


@resolver_router.post("/cancel", response_model=CancelResponse)
def cancel_endpoint(
    body: CancelRequest,
    request: Request,
    caller: AccountDeviceContext = Depends(require_account_device_access),
    db: Session = Depends(get_db),
) -> CancelResponse:
    uid = _uuid_or_400(caller.user_id)
    enforce_rate_limit(
        category="resolver",
        request=request,
        user_id=caller.user_id,
        device_id=str(caller.device_id),
        limit=120,
        window_seconds=60,
    )
    q = db.query(WarmJob).filter(WarmJob.user_id == uid)
    if body.content_id:
        q = q.filter(WarmJob.content_id == body.content_id)
    if body.candidate_id:
        q = q.filter(WarmJob.candidate_id == body.candidate_id)
    # Only cancel jobs that are still "warming"
    rows = q.all()
    cancelled = 0
    for row in rows:
        if st.to_simplified(row.state) == st.SIMPLIFIED_WARMING:
            row.state = st.CANCELLED
            cancelled += 1
    db.commit()
    telemetry.cancel(reason="user_request", user_id=uid)
    return CancelResponse(cancelled=cancelled)


@resolver_router.get("/handoff/{token}")
async def handoff_endpoint(
    token: str,
    request: Request,
) -> Response:
    """Resolve a handoff token -> 302 redirect or inline proxy stream.

    This is an authenticated active-device flow on the caller side (the handoff
    token is only minted from resolve), but the endpoint
    itself accepts the token-bearing call directly so a client can hand
    the URL to its player without additional auth headers. The token's
    user_id claim is used for logging only — access is gated on the HMAC
    validity + expiration.
    """
    try:
        claims = verify_handoff(token)
    except HandoffError as exc:
        telemetry.handoff(
            kind="verify", outcome=f"reject_{exc.args[0] if exc.args else 'unknown'}",
            user_id=None,
        )
        raise HTTPException(status_code=401, detail="invalid_handoff")

    # Look up the cached upstream URL by matching stream_id against any
    # entry in warm_success. We scan because the cache is keyed on
    # hash_key, not stream_id — acceptable for this sprint's scale.
    found: dict | None = None
    with release_cache.warm_success._lock:  # type: ignore[attr-defined]
        for entry in list(release_cache.warm_success._data.values()):  # type: ignore[attr-defined]
            payload = entry.value
            if isinstance(payload, dict) and payload.get("stream_id") == claims.stream_id:
                found = payload
                break
    if found is None or not isinstance(found.get("upstream_url"), str):
        telemetry.handoff(
            kind="verify", outcome="not_found", user_id=claims.user_id,
        )
        raise HTTPException(status_code=404, detail="stream_expired")

    # Enforce user-scope: the cached entry's user must match the token's user.
    if str(found.get("user_id")) != claims.user_id:
        telemetry.handoff(
            kind="verify", outcome="user_mismatch", user_id=claims.user_id,
        )
        raise HTTPException(status_code=403, detail="forbidden")
    if claims.content_id and found.get("content_id") and str(found.get("content_id")) != claims.content_id:
        telemetry.handoff(
            kind="verify", outcome="content_mismatch", user_id=claims.user_id,
        )
        raise HTTPException(status_code=403, detail="forbidden")
    if claims.device_id and found.get("device_id") and str(found.get("device_id")) != claims.device_id:
        telemetry.handoff(
            kind="verify", outcome="device_mismatch", user_id=claims.user_id,
        )
        raise HTTPException(status_code=403, detail="forbidden")

    upstream_url: str = found["upstream_url"]

    if settings.NZBDAV_HANDOFF_DIRECT_REDIRECT:
        telemetry.handoff(kind="redirect", outcome="ok", user_id=claims.user_id)
        return RedirectResponse(url=upstream_url, status_code=302)

    # Proxy path: stream bytes through with Range forwarding.
    try:
        return await _proxy_stream(upstream_url, request, claims.user_id)
    except UpstreamError as exc:
        telemetry.handoff(
            kind="proxy", outcome=exc.code.value, user_id=claims.user_id
        )
        raise HTTPException(status_code=502, detail=exc.code.value)


async def _proxy_stream(
    upstream_url: str, request: Request, user_id: str
) -> StreamingResponse:
    """Stream the upstream URL through Torve with Range/If-Range forwarding."""
    # Forward only safe, range-related headers.
    forwarded: dict[str, str] = {}
    for name in ("range", "if-range"):
        val = request.headers.get(name)
        if val is not None:
            forwarded[name.capitalize()] = val

    timeout = httpx.Timeout(connect=10.0, read=30.0, write=10.0, pool=10.0)
    client = httpx.AsyncClient(trust_env=False, timeout=timeout, follow_redirects=True)
    try:
        req = client.build_request("GET", upstream_url, headers=forwarded)
        resp = await client.send(req, stream=True)
    except httpx.TimeoutException as exc:
        await client.aclose()
        raise UpstreamError(FailureCode.TIMEOUT, detail="proxy_timeout") from exc
    except httpx.HTTPError as exc:
        await client.aclose()
        raise UpstreamError(
            FailureCode.UPSTREAM_UNREACHABLE, detail=f"proxy_{type(exc).__name__}",
        ) from exc

    passthrough = {}
    for hname in (
        "content-type", "content-length", "content-range",
        "accept-ranges", "etag", "last-modified",
    ):
        v = resp.headers.get(hname)
        if v is not None:
            passthrough[hname] = v

    async def _iter():
        try:
            async for chunk in resp.aiter_raw():
                yield chunk
        finally:
            await resp.aclose()
            await client.aclose()

    telemetry.handoff(kind="proxy", outcome="ok", user_id=user_id)
    return StreamingResponse(
        _iter(),
        status_code=resp.status_code,
        headers=passthrough,
    )


# ── Router collection (for main.py registration) ───────────────────────


def include_in(app) -> None:
    """Convenience helper — idiom-consistent with other router files."""
    app.include_router(integrations_router)
    app.include_router(resolver_router)
