"""Generic stream handoff boundary.

This is intentionally narrower than a full debrid resolver. It lets clients
exchange a backend-known successful stream reference for a short-lived Torve
handoff URL, while preserving existing direct-client playback paths until a
full provider-side resolver exists.
"""
from __future__ import annotations

import logging
import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, Request, Response, status
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from app.config import settings
from app.deps import AccountDeviceContext, get_db, require_account_device_access
from app.models import ResolveSuccessMemory, _hash_source_key
from app.nzbdav.failures import UpstreamError
from app.nzbdav.handoff import HandoffError, verify as verify_handoff
from app import resolve_memory_service as rsm_svc
from app.acceleration_coverage import anonymize_identifier
from app.content_policy import (
    AccessContext,
    AgeBand,
    PolicyDecision,
    classify_text,
    decide,
)
from app.content_policy_service import get_or_create_policy, get_override_sets, resolve_channel
from app.provider_resolution import (
    BackendResolveInput,
    ProviderResolveError,
    resolve_backend_stream,
)
from app.rate_limits import enforce_rate_limit
from app.routers.nzbdav import _proxy_stream
from app.stream_handoff import (
    get_handoff,
    is_safe_public_stream_url,
    put_handoff,
)
from app.stream_path_telemetry import record_stream_handoff_error

_log = logging.getLogger(__name__)

router = APIRouter(prefix="/resolver/stream", tags=["stream-handoff"])


class StreamHandoffRequest(BaseModel):
    content_id: str = Field(min_length=1, max_length=255)
    memory_id: str | None = Field(
        default=None,
        max_length=64,
        description="Opaque ResolveSuccessMemory id returned by acceleration APIs.",
    )
    provider_type: str | None = Field(default=None, max_length=50)
    source_key: str | None = Field(
        default=None,
        max_length=5000,
        description="Legacy exact source selector; never treated as proof.",
    )


class StreamResolveRequest(BaseModel):
    content_id: str = Field(min_length=1, max_length=255)
    provider_type: str = Field(min_length=1, max_length=50)
    source_url: str | None = Field(default=None, max_length=5000)
    addon_id: uuid.UUID | None = None
    stream_type: str | None = Field(default=None, max_length=40)
    stream_id: str | None = Field(default=None, max_length=500)
    season: int | None = None
    episode: int | None = None
    infohash: str | None = Field(default=None, max_length=64)
    file_name: str | None = Field(default=None, max_length=1000)
    quality: str | None = Field(default=None, max_length=20)
    audio_flags: str | None = Field(default=None, max_length=200)
    file_size: int | None = Field(default=None, ge=0)


class StreamHandoffResponse(BaseModel):
    url: str
    is_direct: bool = False
    supports_range: bool = True
    stream_id: str
    expires_in_seconds: int


class StreamResolveResponse(BaseModel):
    content_id: str
    memory_id: str
    provider_type: str
    is_direct: bool = False
    supports_handoff: bool = True
    expires_at: str


def _error(status_code: int, code: str, message: str) -> HTTPException:
    return HTTPException(
        status_code=status_code,
        detail={"code": code, "error_code": code, "message": message},
    )


def _record_handoff_error_safe(
    db: Session,
    *,
    error_code: str,
    user_id: object | None = None,
    device_id: object | None = None,
    provider_category: str | None = None,
    source_category: str = "generic_handoff",
) -> None:
    try:
        record_stream_handoff_error(
            db,
            error_code=error_code,
            user_hash=anonymize_identifier(user_id),
            device_hash=anonymize_identifier(device_id),
            provider_category=provider_category,
            source_category=source_category,
        )
        db.commit()
    except Exception as exc:  # noqa: BLE001
        db.rollback()
        _log.warning(
            "STREAM_HANDOFF_ERROR_TELEMETRY_FAILED code=%s error=%s",
            error_code,
            type(exc).__name__,
        )


def _uuid_or_error(value: str, *, code: str) -> uuid.UUID:
    try:
        return uuid.UUID(str(value))
    except (TypeError, ValueError):
        raise _error(status.HTTP_422_UNPROCESSABLE_ENTITY, code, "Invalid stream reference.")


def _lookup_stream_memory(
    db: Session,
    *,
    user_id: uuid.UUID,
    body: StreamHandoffRequest,
) -> ResolveSuccessMemory:
    now = datetime.now(timezone.utc)
    q = db.query(ResolveSuccessMemory).filter(
        ResolveSuccessMemory.user_id == user_id,
        ResolveSuccessMemory.content_id == body.content_id,
        ResolveSuccessMemory.expires_at > now,
    )
    if body.memory_id:
        q = q.filter(ResolveSuccessMemory.id == _uuid_or_error(
            body.memory_id, code="stream_reference_invalid"
        ))
        if body.provider_type:
            q = q.filter(ResolveSuccessMemory.provider_type == body.provider_type)
    elif body.provider_type and body.source_key:
        q = q.filter(
            ResolveSuccessMemory.provider_type == body.provider_type,
            ResolveSuccessMemory.source_key_hash == _hash_source_key(body.source_key),
            ResolveSuccessMemory.source_key == body.source_key,
        )
    else:
        raise _error(
            status.HTTP_422_UNPROCESSABLE_ENTITY,
            "stream_reference_required",
            "A backend stream reference is required.",
        )

    row = q.order_by(ResolveSuccessMemory.last_success_at.desc()).first()
    if row is None:
        raise _error(
            status.HTTP_404_NOT_FOUND,
            "stream_reference_not_found",
            "Stream reference is no longer available.",
        )
    return row


def _enforce_resolved_stream_policy(
    db: Session,
    *,
    user_id: uuid.UUID,
    request: Request,
    file_name: str | None,
) -> None:
    if not file_name:
        return
    policy = get_or_create_policy(db, user_id)
    age_band = AgeBand(policy.age_band)
    content_allow, content_deny, _, _ = get_override_sets(db)
    classification = classify_text(
        file_name,
        allowlist_ids=content_allow,
        denylist_ids=content_deny,
    )
    decision = decide(
        policy_mode=resolve_channel(request),
        classification=classification,
        age_band=age_band,
        sensitive_enabled=policy.sensitive_material_enabled,
        context=AccessContext.ACCELERATION_INVENTORY,
    )
    if decision in {PolicyDecision.BLOCK, PolicyDecision.HIDE}:
        raise _error(
            status.HTTP_403_FORBIDDEN,
            "stream_blocked",
            "This stream is not available on this platform.",
        )


@router.post("/resolve", response_model=StreamResolveResponse)
def resolve_stream_backend_native(
    body: StreamResolveRequest,
    request: Request,
    ctx: AccountDeviceContext = Depends(require_account_device_access),
    db: Session = Depends(get_db),
) -> StreamResolveResponse:
    """Resolve a supported provider/addon stream server-side.

    The client supplies only a provider/source selector. Provider credentials
    stay on the backend; the response is an opaque resolve-memory id which can
    be exchanged through /resolver/stream/handoff.
    """
    enforce_rate_limit(
        category="stream_backend_resolve",
        request=request,
        user_id=ctx.user_id,
        device_id=str(ctx.device_id),
        limit=30,
        window_seconds=60,
    )
    uid = uuid.UUID(ctx.user_id)
    try:
        resolved = resolve_backend_stream(
            db,
            user_id=uid,
            body=BackendResolveInput(
                content_id=body.content_id,
                provider_type=body.provider_type,
                source_url=body.source_url,
                addon_id=body.addon_id,
                stream_type=body.stream_type,
                stream_id=body.stream_id,
                file_name=body.file_name,
                infohash=body.infohash,
                quality=body.quality,
                audio_flags=body.audio_flags,
                file_size=body.file_size,
            ),
        )
    except ProviderResolveError as exc:
        raise _error(exc.status_code, exc.code, exc.message)

    _enforce_resolved_stream_policy(
        db,
        user_id=uid,
        request=request,
        file_name=resolved.file_name,
    )

    row = rsm_svc.record_success(
        db,
        user_id=uid,
        content_id=body.content_id,
        provider_type=resolved.provider_type,
        source_key=resolved.upstream_url,
        season=body.season,
        episode=body.episode,
        infohash=resolved.infohash,
        file_name=resolved.file_name,
        quality=resolved.quality,
        audio_flags=resolved.audio_flags,
        file_size=resolved.file_size,
        provenance_kind=resolved.provenance_kind,
    )
    db.commit()
    db.refresh(row)
    _log.info(
        "BACKEND_STREAM_RESOLVED user=%s device=%s content=%s provider=%s memory=%s provenance=%s",
        uid, ctx.device_id, body.content_id, resolved.provider_type, row.id, resolved.provenance_kind,
    )
    return StreamResolveResponse(
        content_id=body.content_id,
        memory_id=str(row.id),
        provider_type=resolved.provider_type,
        expires_at=row.expires_at.isoformat(),
    )


@router.post("/handoff", response_model=StreamHandoffResponse)
def create_stream_handoff(
    body: StreamHandoffRequest,
    request: Request,
    ctx: AccountDeviceContext = Depends(require_account_device_access),
    db: Session = Depends(get_db),
) -> StreamHandoffResponse:
    """Create a short-lived handoff URL for a backend-known non-Usenet stream."""
    enforce_rate_limit(
        category="stream_handoff_create",
        request=request,
        user_id=ctx.user_id,
        device_id=str(ctx.device_id),
        limit=60,
        window_seconds=60,
    )
    uid = uuid.UUID(ctx.user_id)
    row = _lookup_stream_memory(db, user_id=uid, body=body)

    if row.provenance_kind == "USENET_NZBDAV" or row.provider_type == "nzbdav":
        raise _error(
            status.HTTP_409_CONFLICT,
            "use_usenet_handoff",
            "Use the Usenet resolver handoff for this stream.",
        )
    if not is_safe_public_stream_url(row.source_key):
        _log.info(
            "STREAM_HANDOFF_UNAVAILABLE user=%s device=%s content=%s provider=%s reason=non_public_url",
            uid, ctx.device_id, body.content_id, row.provider_type,
        )
        _record_handoff_error_safe(
            db,
            error_code="stream_handoff_unavailable",
            user_id=uid,
            device_id=ctx.device_id,
            provider_category=row.provider_type,
            source_category="non_public_url",
        )
        raise _error(
            status.HTTP_409_CONFLICT,
            "stream_handoff_unavailable",
            "This stream must be refreshed.",
        )

    token, stream_id, ttl = put_handoff(
        db,
        upstream_url=row.source_key,
        user_id=ctx.user_id,
        device_id=str(ctx.device_id),
        content_id=body.content_id,
        provider_type=row.provider_type,
        source_ref=str(row.id),
    )
    db.commit()
    base = settings.APP_PUBLIC_API_URL.rstrip("/")
    _log.info(
        "STREAM_HANDOFF_CREATED user=%s device=%s content=%s provider=%s memory=%s",
        uid, ctx.device_id, body.content_id, row.provider_type, row.id,
    )
    return StreamHandoffResponse(
        url=f"{base}/resolver/stream/handoff/{token}",
        is_direct=False,
        supports_range=True,
        stream_id=stream_id,
        expires_in_seconds=ttl,
    )


@router.get("/handoff/{token}")
async def stream_handoff_endpoint(
    token: str,
    request: Request,
    db: Session = Depends(get_db),
) -> Response:
    """Proxy a signed generic stream handoff token.

    The player may not be able to attach auth headers, so authorization is the
    short-lived signed token created by the authenticated device endpoint.
    """
    try:
        claims = verify_handoff(token)
    except HandoffError as exc:
        reason = exc.args[0] if exc.args else "unknown"
        _log.info("STREAM_HANDOFF_REJECTED reason=%s", reason)
        if reason == "expired":
            _record_handoff_error_safe(db, error_code="stream_expired")
            raise _error(
                status.HTTP_410_GONE,
                "stream_expired",
                "Playback link expired.",
            )
        raise _error(
            status.HTTP_401_UNAUTHORIZED,
            "invalid_handoff",
            "Invalid playback link.",
        )

    enforce_rate_limit(
        category="stream_handoff_playback",
        request=request,
        user_id=claims.user_id,
        device_id=claims.device_id,
        limit=240,
        window_seconds=60,
    )

    payload = get_handoff(db, claims.stream_id)
    if payload is None or not isinstance(payload.get("upstream_url"), str):
        _record_handoff_error_safe(
            db,
            error_code="stream_reference_not_found",
            user_id=claims.user_id,
            device_id=claims.device_id,
            source_category="cache_miss",
        )
        raise _error(
            status.HTTP_404_NOT_FOUND,
            "stream_reference_not_found",
            "Stream reference is no longer available.",
        )
    if str(payload.get("user_id")) != claims.user_id:
        raise _error(status.HTTP_403_FORBIDDEN, "forbidden", "Playback link is not valid.")
    if claims.device_id and str(payload.get("device_id")) != claims.device_id:
        raise _error(status.HTTP_403_FORBIDDEN, "forbidden", "Playback link is not valid.")
    if claims.content_id and str(payload.get("content_id")) != claims.content_id:
        raise _error(status.HTTP_403_FORBIDDEN, "forbidden", "Playback link is not valid.")

    upstream_url = str(payload["upstream_url"])
    try:
        return await _proxy_stream(upstream_url, request, claims.user_id)
    except UpstreamError as exc:
        _log.warning(
            "STREAM_HANDOFF_PROXY_FAILED user=%s device=%s content=%s provider=%s code=%s",
            claims.user_id,
            claims.device_id,
            claims.content_id,
            payload.get("provider_type"),
            exc.code.value,
        )
        raise HTTPException(
            status_code=502,
            detail={
                "code": exc.code.value,
                "error_code": exc.code.value,
                "message": "Stream temporarily unavailable.",
            },
        )
