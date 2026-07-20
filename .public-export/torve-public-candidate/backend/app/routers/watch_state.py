"""
Cross-device watch-state sync.

POST /me/watch_state/report   — client reports (content_id, position_ms).
GET  /me/watch_state/latest    — query latest report for a given content_id.

Both endpoints are auth-only (no premium gate) because cross-device
resume is core UX. device_id is accepted optionally on write; when
provided it is validated to belong to the authenticated user. If
omitted, the server attempts to infer the calling device from
X-Torve-Installation-Id or ?installation_id=. If neither path yields
a user-owned active device, the row is recorded without device
attribution and the GET response returns device_id=null.

Schema lives in app/models.py::WatchStateReport. Append-only:
each report is a new row; the "latest" semantic is derived by
ORDER BY reported_at DESC LIMIT 1.
"""
from __future__ import annotations

import logging
import uuid
from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException, Query, status
from pydantic import BaseModel, Field
from sqlalchemy import desc
from sqlalchemy.orm import Session

from app.deps import get_calling_installation_id, get_current_user_id, get_db
from app.models import Device, WatchStateReport

_log = logging.getLogger(__name__)

router = APIRouter(prefix="/me/watch_state", tags=["watch-state"])


# ── DTOs ────────────────────────────────────────────────────────────────


class WatchStateReportRequest(BaseModel):
    """Payload sent by the client every ~30s during playback."""
    content_id: str = Field(min_length=1, max_length=255)
    provider: str = Field(min_length=1, max_length=80)
    position_ms: int = Field(ge=0)
    # Optional — client may attach a device UUID. Server validates ownership.
    device_id: str | None = Field(default=None, max_length=64)


class WatchStateReportResponse(BaseModel):
    status: str
    reported_at: datetime


class WatchStateLatestResponse(BaseModel):
    content_id: str
    provider: str
    position_ms: int
    reported_at: datetime
    device_id: str | None = None


# ── POST /me/watch_state/report ────────────────────────────────────────


@router.post("/report", response_model=WatchStateReportResponse)
def report_watch_state(
    body: WatchStateReportRequest,
    user_id: str = Depends(get_current_user_id),
    installation_id: str | None = Depends(get_calling_installation_id),
    db: Session = Depends(get_db),
) -> WatchStateReportResponse:
    uid = uuid.UUID(user_id)

    device_uuid: uuid.UUID | None = None
    if body.device_id:
        try:
            candidate = uuid.UUID(body.device_id)
        except ValueError:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="device_id must be a valid UUID.",
            )
        # Validate ownership. A client may not attribute reports to a
        # device it does not own. Failure silently drops the attribution
        # rather than 400'ing so the report still lands.
        device = (
            db.query(Device)
            .filter(Device.id == candidate, Device.user_id == uid)
            .one_or_none()
        )
        if device is not None:
            device_uuid = device.id
    elif installation_id:
        device = (
            db.query(Device)
            .filter(
                Device.user_id == uid,
                Device.installation_id == installation_id,
                Device.is_active == True,  # noqa: E712
            )
            .first()
        )
        if device is not None:
            device_uuid = device.id

    row = WatchStateReport(
        user_id=uid,
        device_id=device_uuid,
        content_id=body.content_id,
        provider=body.provider,
        position_ms=body.position_ms,
    )
    db.add(row)
    db.commit()
    db.refresh(row)
    return WatchStateReportResponse(status="ok", reported_at=row.reported_at)


# ── GET /me/watch_state/latest ─────────────────────────────────────────


@router.get("/latest", response_model=WatchStateLatestResponse)
def get_latest_watch_state(
    content_id: str = Query(min_length=1, max_length=255),
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> WatchStateLatestResponse:
    uid = uuid.UUID(user_id)
    row = (
        db.query(WatchStateReport)
        .filter(
            WatchStateReport.user_id == uid,
            WatchStateReport.content_id == content_id,
        )
        .order_by(desc(WatchStateReport.reported_at))
        .first()
    )
    if row is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="No watch state recorded for this content.",
        )
    return WatchStateLatestResponse(
        content_id=row.content_id,
        provider=row.provider,
        position_ms=row.position_ms,
        reported_at=row.reported_at,
        device_id=str(row.device_id) if row.device_id else None,
    )
