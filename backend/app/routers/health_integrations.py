"""
Per-user integration health endpoint.

One call that tells the user (and the operator) which of their configured
integrations are working right now — addons (manifest reachability) and
NzbDAV (if configured). Probes run concurrently with a tight budget so
the response is always fast; results are cached per user for 30 seconds
so the client can refresh freely without hammering upstreams.

No secrets are ever returned. Manifest URLs may contain embedded
tokens (e.g. panda.torve.app/u/<token>/manifest.json), so only the
addon name + host are exposed in responses.
"""
from __future__ import annotations

import asyncio
import logging
import time
import uuid
from datetime import datetime, timezone
from typing import Any
from urllib.parse import urlparse

import httpx
from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from app.deps import get_current_user_id, get_db
from app.google_play_readiness import assess_readiness as assess_gp_readiness
from app.models import NzbdavConfig, UserAddon
from app.nzbdav.client import NzbdavClient
from app.nzbdav.failures import UpstreamError
from app.stripe_config import get_stripe_readiness

_log = logging.getLogger(__name__)

router = APIRouter(prefix="/me/health", tags=["health"])


# ── Response models ────────────────────────────────────────────────────

class IntegrationStatus(BaseModel):
    """One probed integration's current health.

    `kind` is the discriminator. `status` is the normalised verdict:
      - healthy:        reachable + functioning
      - degraded:       reachable but sub-optimal (e.g. old NzbDAV version)
      - unreachable:    network / DNS / timeout / 5xx
      - unauthorised:   credentials rejected
      - not_configured: user has not set this up (still listed for UX clarity)
    """
    kind: str                                   # "addon" | "nzbdav"
    id: str | None = None                       # internal row UUID (opaque)
    name: str
    host: str | None = None                     # hostname only, never full URL
    status: str
    latency_ms: int | None = None
    detail: str | None = None                   # short human-readable reason
    version: str | None = None                  # when upstream exposes one


class StripeReadinessStatus(BaseModel):
    configured: bool
    secret_key_present: bool
    webhook_secret_present: bool
    monthly_price_present: bool
    lifetime_price_present: bool
    tax_enabled: bool


class HealthResponse(BaseModel):
    checked_at: str                             # ISO8601
    overall: str                                # "healthy" | "degraded" | "unhealthy"
    integrations: list[IntegrationStatus] = Field(default_factory=list)
    stripe_readiness: StripeReadinessStatus | None = None


# ── Per-user TTL cache (in-process) ─────────────────────────────────────

_CACHE: dict[str, tuple[float, HealthResponse]] = {}
_CACHE_TTL_SECONDS = 30.0


def _cache_get(user_id: uuid.UUID) -> HealthResponse | None:
    now = time.monotonic()
    entry = _CACHE.get(str(user_id))
    if entry is None:
        return None
    expires_at, value = entry
    if expires_at < now:
        _CACHE.pop(str(user_id), None)
        return None
    return value


def _cache_put(user_id: uuid.UUID, value: HealthResponse) -> None:
    _CACHE[str(user_id)] = (time.monotonic() + _CACHE_TTL_SECONDS, value)


# ── Probers ─────────────────────────────────────────────────────────────

_PROBE_CONNECT_TIMEOUT = 3.0
_PROBE_READ_TIMEOUT = 5.0


def _host_of(url: str) -> str | None:
    try:
        p = urlparse(url)
        return p.hostname
    except Exception:  # noqa: BLE001
        return None


async def _probe_addon(addon: UserAddon) -> IntegrationStatus:
    """Fetch the addon's manifest.json. Measures latency and surfaces
    a normalised status. Does NOT return the raw manifest URL — only
    the hostname — because tokenised addon URLs double as credentials.
    """
    host = _host_of(addon.manifest_url)
    name = addon.name or host or "Unnamed addon"
    start = time.monotonic()
    timeout = httpx.Timeout(
        connect=_PROBE_CONNECT_TIMEOUT, read=_PROBE_READ_TIMEOUT,
        write=_PROBE_READ_TIMEOUT, pool=_PROBE_READ_TIMEOUT,
    )
    try:
        async with httpx.AsyncClient(trust_env=False, timeout=timeout, follow_redirects=True) as client:
            resp = await client.get(addon.manifest_url)
        elapsed_ms = int((time.monotonic() - start) * 1000)
        if 200 <= resp.status_code < 300:
            return IntegrationStatus(
                kind="addon", id=str(addon.id), name=name, host=host,
                status="healthy", latency_ms=elapsed_ms, version=addon.version,
            )
        if resp.status_code in (401, 403):
            return IntegrationStatus(
                kind="addon", id=str(addon.id), name=name, host=host,
                status="unauthorised", latency_ms=elapsed_ms,
                detail=f"Manifest returned HTTP {resp.status_code}",
            )
        return IntegrationStatus(
            kind="addon", id=str(addon.id), name=name, host=host,
            status="unreachable", latency_ms=elapsed_ms,
            detail=f"Manifest returned HTTP {resp.status_code}",
        )
    except httpx.TimeoutException:
        return IntegrationStatus(
            kind="addon", id=str(addon.id), name=name, host=host,
            status="unreachable", detail="timeout",
        )
    except httpx.HTTPError as exc:
        return IntegrationStatus(
            kind="addon", id=str(addon.id), name=name, host=host,
            status="unreachable", detail=type(exc).__name__,
        )


async def _probe_nzbdav(cfg: NzbdavConfig, api_key: str) -> IntegrationStatus:
    """Use the existing NzbdavClient to validate credentials + capture version.

    Upstream errors come back as normalised UpstreamError codes; mapping
    those to public status values keeps the response surface narrow.
    """
    host = _host_of(cfg.base_url)
    name = "NzbDAV"
    start = time.monotonic()
    try:
        async with NzbdavClient(base_url=cfg.base_url, api_key=api_key) as client:
            res = await client.test_connection()
        elapsed_ms = int((time.monotonic() - start) * 1000)
        return IntegrationStatus(
            kind="nzbdav", id=str(cfg.id), name=name, host=host,
            status="healthy", latency_ms=elapsed_ms, version=res.version_string,
        )
    except UpstreamError as exc:
        code = exc.code.value
        mapped = "unreachable"
        if code == "AUTH_INVALID":
            mapped = "unauthorised"
        elif code == "UPSTREAM_UNREACHABLE":
            mapped = "unreachable"
        elif code == "TIMEOUT":
            mapped = "unreachable"
        elif code in ("RATE_LIMITED",):
            mapped = "degraded"
        return IntegrationStatus(
            kind="nzbdav", id=str(cfg.id), name=name, host=host,
            status=mapped, detail=code,
        )


def _probe_google_play() -> IntegrationStatus:
    """Redacted readiness probe for Google Play IAP config.

    Runs assess_readiness() — no file contents, no client_email, no
    private keys ever surface in the returned IntegrationStatus. Only
    status + an opaque reason token.

    Global config (not per-user): one entry per server, same value for
    every caller. Probe is cheap (one os.path.isfile + one json.load on
    the SA file) so calling it synchronously in the main handler is fine.
    """
    r = assess_gp_readiness()
    # Shared/global readiness — no `id` or `host` field since there is
    # no server/endpoint to address; the SA file path is a secret.
    if r.status == "ready":
        return IntegrationStatus(
            kind="google_play", name="Google Play IAP",
            status="healthy",
        )
    if r.status == "not_configured":
        return IntegrationStatus(
            kind="google_play", name="Google Play IAP",
            status="not_configured", detail=r.reason,
        )
    # status == "misconfigured"
    return IntegrationStatus(
        kind="google_play", name="Google Play IAP",
        status="degraded", detail=r.reason,
    )


def _probe_stripe() -> tuple[IntegrationStatus, StripeReadinessStatus]:
    """Redacted Stripe billing readiness.

    Returns booleans only; never returns keys, webhook secrets, prices, or
    customer/payment data.
    """
    r = get_stripe_readiness()
    public = StripeReadinessStatus(**r.as_public_dict())
    if r.status == "ready":
        status_value = "healthy"
    elif r.status == "not_configured":
        status_value = "not_configured"
    else:
        status_value = "degraded"
    return (
        IntegrationStatus(
            kind="stripe",
            name="Stripe Billing",
            status=status_value,
            detail=r.reason,
        ),
        public,
    )


# ── Endpoint ────────────────────────────────────────────────────────────


def _compute_overall(integrations: list[IntegrationStatus]) -> str:
    if not integrations:
        return "healthy"
    statuses = {i.status for i in integrations}
    if statuses <= {"healthy", "not_configured"}:
        return "healthy"
    if "unreachable" in statuses or "unauthorised" in statuses:
        return "unhealthy"
    return "degraded"


@router.get("/integrations", response_model=HealthResponse)
async def get_integrations_health(
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> HealthResponse:
    """Return a compact health snapshot of the user's integrations.

    Cached in-process for 30 seconds per user; concurrent probes share
    a single cached result so a rapid refresh on the client doesn't
    multiply upstream traffic.
    """
    uid = uuid.UUID(user_id)

    cached = _cache_get(uid)
    if cached is not None:
        return cached

    # Load everything we're going to probe in a single DB round-trip.
    addons = (
        db.query(UserAddon)
        .filter(UserAddon.user_id == uid, UserAddon.is_enabled == True)  # noqa: E712
        .order_by(UserAddon.sort_order.asc(), UserAddon.created_at.asc())
        .all()
    )
    nzbdav_cfg = (
        db.query(NzbdavConfig)
        .filter(NzbdavConfig.user_id == uid, NzbdavConfig.is_enabled == True)  # noqa: E712
        .one_or_none()
    )

    tasks: list[asyncio.Task[IntegrationStatus]] = []
    for addon in addons:
        tasks.append(asyncio.create_task(_probe_addon(addon)))

    if nzbdav_cfg is not None:
        # Decrypt the API key for the probe only. Never returned in response.
        from app.crypto import decrypt_secret
        try:
            api_key = decrypt_secret(nzbdav_cfg.api_key_encrypted)
            tasks.append(asyncio.create_task(_probe_nzbdav(nzbdav_cfg, api_key)))
        except Exception:  # noqa: BLE001
            tasks.append(asyncio.create_task(_not_ready_nzbdav(
                cfg=nzbdav_cfg, reason="DECRYPT_FAILED",
            )))

    results: list[IntegrationStatus] = []
    if tasks:
        results = list(await asyncio.gather(*tasks, return_exceptions=False))

    # If no NzbDAV config, surface "not_configured" so the UI can
    # unambiguously show the state instead of silently omitting it.
    if nzbdav_cfg is None:
        results.append(IntegrationStatus(
            kind="nzbdav", name="NzbDAV", status="not_configured",
        ))

    # Global (non-user) readiness signal for Google Play IAP. Same value
    # for every authenticated caller; kept in this per-user endpoint so
    # the client has one place to render integration health.
    results.append(_probe_google_play())
    stripe_status, stripe_readiness = _probe_stripe()
    results.append(stripe_status)

    response = HealthResponse(
        checked_at=datetime.now(timezone.utc).isoformat(),
        overall=_compute_overall(results),
        integrations=results,
        stripe_readiness=stripe_readiness,
    )
    _cache_put(uid, response)
    _log.info(
        "HEALTH_INTEGRATIONS user=%s addons=%d nzbdav=%s overall=%s",
        uid, len(addons),
        "configured" if nzbdav_cfg is not None else "none",
        response.overall,
    )
    return response


async def _not_ready_nzbdav(*, cfg: NzbdavConfig, reason: str) -> IntegrationStatus:
    return IntegrationStatus(
        kind="nzbdav", id=str(cfg.id), name="NzbDAV",
        host=_host_of(cfg.base_url), status="unreachable", detail=reason,
    )
