"""Safe stream path telemetry ingestion and internal metrics."""
from __future__ import annotations

import hmac
import logging
import uuid
from datetime import datetime, timedelta, timezone
from typing import Literal

from fastapi import APIRouter, Depends, Header, HTTPException, Query, Request, status
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy.orm import Session

from app.acceleration_coverage import anonymize_identifier
from app.config import settings
from app.deps import get_calling_installation_id, get_current_user_id, get_db
from app.rate_limits import enforce_rate_limit
from app.stream_path_telemetry import (
    SENSITIVE_FIELD_NAMES,
    StreamPathEventInput,
    aggregate_stream_protection_metrics,
    generated_at_from_epoch_millis,
    record_stream_path_event,
)

_log = logging.getLogger(__name__)

router = APIRouter(tags=["stream-telemetry"])

PathType = Literal[
    "usenet_handoff",
    "generic_handoff_memory_id",
    "legacy_direct_no_memory_id",
    "iptv_direct",
    "direct_free",
]


class StreamPathTelemetryRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    path_type: PathType = Field(alias="pathType")
    platform: str | None = Field(default=None, max_length=50)
    app_version: str | None = Field(default=None, alias="appVersion", max_length=50)
    distribution_channel: str | None = Field(
        default=None,
        alias="distributionChannel",
        max_length=80,
    )
    content_type: str | None = Field(default=None, alias="contentType", max_length=40)
    provider_category: str | None = Field(default=None, alias="providerCategory", max_length=80)
    source_category: str | None = Field(default=None, alias="sourceCategory", max_length=80)
    device_category: str | None = Field(default=None, alias="deviceCategory", max_length=40)
    generated_at_epoch_millis: int | None = Field(
        default=None,
        alias="generatedAtEpochMillis",
        ge=0,
    )


class StreamPathTelemetryResponse(BaseModel):
    accepted: bool
    reason: str | None = None


def _verify_admin(request: Request, x_admin_secret: str | None = Header(default=None)) -> None:
    if not settings.PADDLE_ADMIN_SECRET:
        raise HTTPException(status_code=503, detail={"error_code": "admin_not_configured"})
    if not x_admin_secret or not hmac.compare_digest(x_admin_secret, settings.PADDLE_ADMIN_SECRET):
        raise HTTPException(status_code=403, detail={"error_code": "forbidden"})
    ip = request.client.host if request.client else None
    _log.warning("ADMIN_CALL ip=%s method=%s path=%s", ip, request.method, request.url.path)


@router.post("/telemetry/stream-path", response_model=StreamPathTelemetryResponse)
def record_stream_path_telemetry(
    body: StreamPathTelemetryRequest,
    request: Request,
    user_id: str = Depends(get_current_user_id),
    installation_id: str | None = Depends(get_calling_installation_id),
    db: Session = Depends(get_db),
) -> StreamPathTelemetryResponse:
    """Record coarse playback path adoption without sensitive playback data."""
    raw_keys = set()
    try:
        payload = body.model_dump(by_alias=False)
        raw_keys = {str(k).lower() for k in payload.keys()}
    except Exception:  # noqa: BLE001
        raw_keys = set()
    if raw_keys & SENSITIVE_FIELD_NAMES:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail={"error_code": "telemetry_sensitive_field_rejected"},
        )

    enforce_rate_limit(
        category="stream_path_telemetry",
        request=request,
        user_id=user_id,
        device_id=installation_id,
        limit=240,
        window_seconds=300,
    )

    user_hash = anonymize_identifier(user_id)
    device_hash = anonymize_identifier(installation_id)
    accepted, reason = record_stream_path_event(
        db,
        StreamPathEventInput(
            user_hash=user_hash,
            device_hash=device_hash,
            path_type=body.path_type,
            platform=body.platform,
            app_version=body.app_version,
            distribution_channel=body.distribution_channel,
            content_type=body.content_type,
            provider_category=body.provider_category,
            source_category=body.source_category,
            device_category=body.device_category,
            generated_at=generated_at_from_epoch_millis(body.generated_at_epoch_millis),
        ),
    )
    if accepted:
        db.commit()
    else:
        db.rollback()
    _log.info(
        "STREAM_PATH_TELEMETRY accepted=%s reason=%s user_hash=%s device_hash=%s path_type=%s platform=%s provider_category=%s",
        accepted,
        reason or "-",
        user_hash,
        device_hash,
        body.path_type,
        body.platform or "unknown",
        body.provider_category or "unknown",
    )
    return StreamPathTelemetryResponse(accepted=accepted, reason=reason)


@router.get(
    "/admin/metrics/stream-protection",
    dependencies=[Depends(_verify_admin)],
)
def get_stream_protection_metrics(
    window_hours: int = Query(default=24, ge=1, le=24 * 30),
    db: Session = Depends(get_db),
) -> dict:
    since = datetime.now(timezone.utc) - timedelta(hours=window_hours)
    metrics = aggregate_stream_protection_metrics(db, since=since)
    return {
        "window_hours": window_hours,
        **metrics,
    }
