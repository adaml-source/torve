import logging
import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session

from app.deps import get_current_user_id, get_db
from app.models import Device, DevicePairing
from app.schemas import PairingCreateRequest, PairingOut

_log = logging.getLogger(__name__)

router = APIRouter(prefix="/me/pairings", tags=["pairings"])


@router.get("", response_model=list[PairingOut])
def list_pairings(
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> list[PairingOut]:
    pairings = (
        db.query(DevicePairing)
        .filter(
            DevicePairing.user_id == uuid.UUID(user_id),
            DevicePairing.status == "active",
        )
        .order_by(DevicePairing.created_at.desc())
        .all()
    )
    _log.info("PAIRING_LIST user=%s count=%d", user_id, len(pairings))
    return pairings


@router.post("", response_model=PairingOut, status_code=status.HTTP_201_CREATED)
def create_pairing(
    body: PairingCreateRequest,
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> PairingOut:
    uid = uuid.UUID(user_id)

    if body.controller_device_id == body.target_device_id:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Controller and target device must be different.",
        )

    # Both devices must belong to this user and be active
    controller = (
        db.query(Device)
        .filter(Device.id == body.controller_device_id, Device.user_id == uid, Device.is_active == True)  # noqa: E712
        .first()
    )
    target = (
        db.query(Device)
        .filter(Device.id == body.target_device_id, Device.user_id == uid, Device.is_active == True)  # noqa: E712
        .first()
    )

    if not controller or not target:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="One or both devices not found or not active.",
        )

    # Check for existing active pairing between these two devices
    existing = (
        db.query(DevicePairing)
        .filter(
            DevicePairing.user_id == uid,
            DevicePairing.controller_device_id == body.controller_device_id,
            DevicePairing.target_device_id == body.target_device_id,
            DevicePairing.status == "active",
        )
        .first()
    )
    if existing:
        return existing

    pairing = DevicePairing(
        user_id=uid,
        controller_device_id=body.controller_device_id,
        target_device_id=body.target_device_id,
    )
    db.add(pairing)
    db.commit()
    db.refresh(pairing)
    return pairing


@router.post("/{pairing_id}/revoke", response_model=PairingOut)
def revoke_pairing(
    pairing_id: uuid.UUID,
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> PairingOut:
    pairing = (
        db.query(DevicePairing)
        .filter(
            DevicePairing.id == pairing_id,
            DevicePairing.user_id == uuid.UUID(user_id),
        )
        .first()
    )
    if not pairing:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Pairing not found.",
        )
    if pairing.status == "revoked":
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Pairing is already revoked.",
        )
    pairing.status = "revoked"
    pairing.updated_at = datetime.now(timezone.utc)
    db.commit()
    db.refresh(pairing)
    _log.info("PAIRING_REVOKED user=%s pairing=%s", user_id, pairing_id)
    return pairing
