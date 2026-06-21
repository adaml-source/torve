import logging
import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy.orm import Session

from app.deps import get_current_user_id, get_db
from app.models import Device
from app.rate_limits import enforce_rate_limit

_log = logging.getLogger(__name__)
from app.schemas import (
    DeviceHeartbeatRequest,
    DeviceLimitError,
    DeviceOut,
    DeviceRegisterRequest,
    DeviceRenameRequest,
)

router = APIRouter(prefix="/me/devices", tags=["devices"])

# Alias router so clients calling /devices/... (without /me) still work
compat_router = APIRouter(prefix="/devices", tags=["devices"], include_in_schema=False)


def _active_devices_query(db: Session, user_id: uuid.UUID):
    return db.query(Device).filter(
        Device.user_id == user_id,
        Device.is_active == True,  # noqa: E712
    )


@router.get("", response_model=list[DeviceOut])
def list_devices(
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
    include_revoked: bool = False,
) -> list[DeviceOut]:
    q = db.query(Device).filter(Device.user_id == uuid.UUID(user_id))
    if not include_revoked:
        q = q.filter(Device.is_active == True)  # noqa: E712
    devices = q.order_by(Device.created_at.desc()).all()
    _log.info("DEVICE_LIST user=%s count=%d include_revoked=%s", user_id, len(devices), include_revoked)
    return devices


@router.post("/register", response_model=DeviceOut, status_code=status.HTTP_201_CREATED)
def register_device(
    body: DeviceRegisterRequest,
    request: Request,
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> DeviceOut:
    uid = uuid.UUID(user_id)
    enforce_rate_limit(
        category="device_activation",
        request=request,
        user_id=user_id,
        limit=120,
        window_seconds=300,
    )

    if body.device_type not in ("phone", "tablet", "tv", "desktop"):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="device_type must be one of: phone, tablet, tv, desktop",
        )

    # Dedup: stable_device_id first (survives reinstalls), then installation_id
    from app.routers.auth import _find_existing_device
    existing = _find_existing_device(db, uid, body.stable_device_id, body.installation_id)
    if existing:
        existing.last_seen_at = datetime.now(timezone.utc)
        existing.app_version = body.app_version or existing.app_version
        existing.platform = body.platform or existing.platform
        existing.display_name = body.display_name or existing.display_name
        if body.stable_device_id and not existing.stable_device_id:
            existing.stable_device_id = body.stable_device_id
        if body.installation_id and existing.installation_id != body.installation_id:
            existing.installation_id = body.installation_id
        if not existing.is_active:
            existing.is_active = True
            existing.revoked_at = None
        db.commit()
        db.refresh(existing)
        _log.info(
            "DEVICE_SLOT_DECISION user=%s device=%s reason=existing_reused "
            "entitlement_source=unchanged platform=%s",
            uid, existing.id, existing.platform,
        )
        return existing

    device = Device(
        user_id=uid,
        device_type=body.device_type,
        platform=body.platform,
        display_name=body.display_name,
        installation_id=body.installation_id,
        stable_device_id=body.stable_device_id,
        app_version=body.app_version,
    )
    db.add(device)
    db.commit()
    db.refresh(device)
    _log.info(
        "DEVICE_SLOT_DECISION user=%s device=%s reason=registered "
        "access_model=free_software type=%s platform=%s stable_id=%s",
        uid, device.id,
        device.device_type, device.platform, device.stable_device_id,
    )
    return device


@router.post("/{device_id}/heartbeat", response_model=DeviceOut)
def heartbeat(
    device_id: uuid.UUID,
    body: DeviceHeartbeatRequest,
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> DeviceOut:
    device = _get_own_device(db, user_id, device_id)
    device.last_seen_at = datetime.now(timezone.utc)
    if body.app_version:
        device.app_version = body.app_version
    db.commit()
    db.refresh(device)
    return device


@router.post("/{device_id}/revoke", response_model=DeviceOut)
@router.post("/{device_id}/remove", response_model=DeviceOut, include_in_schema=False)
def revoke_device(
    device_id: uuid.UUID,
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> DeviceOut:
    device = _get_own_device(db, user_id, device_id)
    if not device.is_active:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Device is already revoked.",
        )
    device.is_active = False
    device.revoked_at = datetime.now(timezone.utc)
    db.commit()
    db.refresh(device)
    _log.info("DEVICE_REVOKED user=%s device=%s type=%s platform=%s",
              user_id, device_id, device.device_type, device.platform)
    return device


@router.delete("/{device_id}", response_model=DeviceOut, include_in_schema=False)
def delete_device(
    device_id: uuid.UUID,
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> DeviceOut:
    return revoke_device(device_id, user_id, db)


@router.post("/{device_id}/rename", response_model=DeviceOut)
def rename_device(
    device_id: uuid.UUID,
    body: DeviceRenameRequest,
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> DeviceOut:
    device = _get_own_device(db, user_id, device_id)
    device.display_name = body.display_name
    db.commit()
    db.refresh(device)
    _log.info("DEVICE_RENAMED user=%s device=%s new_name=%s", user_id, device_id, body.display_name)
    return device


def _get_own_device(db: Session, user_id: str, device_id: uuid.UUID) -> Device:
    device = (
        db.query(Device)
        .filter(Device.id == device_id, Device.user_id == uuid.UUID(user_id))
        .first()
    )
    if not device:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Device not found.",
        )
    return device


# ── Compat aliases (/devices/...) ────────────────────────────────────────────

compat_router.get("", response_model=list[DeviceOut])(list_devices)
compat_router.post("/register", response_model=DeviceOut, status_code=status.HTTP_201_CREATED)(register_device)
compat_router.post("/{device_id}/heartbeat", response_model=DeviceOut)(heartbeat)
compat_router.post("/{device_id}/revoke", response_model=DeviceOut)(revoke_device)
compat_router.post("/{device_id}/remove", response_model=DeviceOut)(revoke_device)
compat_router.delete("/{device_id}", response_model=DeviceOut)(delete_device)
compat_router.post("/{device_id}/rename", response_model=DeviceOut)(rename_device)
