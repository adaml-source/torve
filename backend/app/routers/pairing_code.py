import secrets
import string
import uuid
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session

from app.deps import get_current_user_id, get_db, require_account_access
from app.models import Device, DevicePairing, PairingCode
from app.schemas import PairingClaimRequest, PairingCodeOut, PairingCodeRequest, PairingOut

_CODE_LENGTH = 6
_CODE_TTL_MINUTES = 10
_CODE_ALPHABET = string.ascii_uppercase + string.digits
_MAX_CODE_RETRIES = 10

router = APIRouter(prefix="/pairing", tags=["pairing"])


def _generate_code() -> str:
    return "".join(secrets.choice(_CODE_ALPHABET) for _ in range(_CODE_LENGTH))


def _get_own_active_device(
    db: Session, user_id: uuid.UUID, device_id: uuid.UUID
) -> Device:
    device = (
        db.query(Device)
        .filter(
            Device.id == device_id,
            Device.user_id == user_id,
            Device.is_active == True,  # noqa: E712
        )
        .first()
    )
    if not device:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Device not found or not active.",
        )
    return device


@router.post("/code", response_model=PairingCodeOut, status_code=status.HTTP_201_CREATED)
def create_pairing_code(
    body: PairingCodeRequest,
    user_id: str = Depends(require_account_access),
    db: Session = Depends(get_db),
) -> PairingCodeOut:
    uid = uuid.UUID(user_id)
    now = datetime.now(timezone.utc)

    # Verify device belongs to user and is active
    target_device = _get_own_active_device(db, uid, body.device_id)

    # Invalidate any previous unclaimed codes for the same target device
    db.query(PairingCode).filter(
        PairingCode.target_device_id == target_device.id,
        PairingCode.claimed_at == None,  # noqa: E711
        PairingCode.expires_at > now,
    ).update({"expires_at": now})

    # Generate unique code, retry on collision
    expires_at = now + timedelta(minutes=_CODE_TTL_MINUTES)
    code = None
    for _ in range(_MAX_CODE_RETRIES):
        candidate = _generate_code()
        exists = (
            db.query(PairingCode)
            .filter(
                PairingCode.code == candidate,
                PairingCode.claimed_at == None,  # noqa: E711
                PairingCode.expires_at > now,
            )
            .first()
        )
        if not exists:
            code = candidate
            break

    if code is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Could not generate a unique pairing code. Please retry.",
        )

    pairing_code = PairingCode(
        user_id=uid,
        target_device_id=target_device.id,
        code=code,
        expires_at=expires_at,
    )
    db.add(pairing_code)
    db.commit()
    db.refresh(pairing_code)

    return PairingCodeOut(
        code=pairing_code.code,
        expires_at=pairing_code.expires_at,
        target_device_id=pairing_code.target_device_id,
    )


@router.post("/claim", response_model=PairingOut)
def claim_pairing_code(
    body: PairingClaimRequest,
    user_id: str = Depends(require_account_access),
    db: Session = Depends(get_db),
) -> PairingOut:
    uid = uuid.UUID(user_id)
    now = datetime.now(timezone.utc)

    # Verify controller device belongs to user and is active
    controller_device = _get_own_active_device(db, uid, body.device_id)

    # Normalize code
    code = body.code.strip().upper()

    # Look up active pairing code
    pairing_code = (
        db.query(PairingCode)
        .filter(
            PairingCode.code == code,
            PairingCode.claimed_at == None,  # noqa: E711
            PairingCode.expires_at > now,
        )
        .first()
    )

    if not pairing_code:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Pairing code not found, expired, or already used.",
        )

    # User mismatch: don't leak existence
    if pairing_code.user_id != uid:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Pairing code not found, expired, or already used.",
        )

    # Self-pairing check
    if controller_device.id == pairing_code.target_device_id:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="A device cannot pair with itself.",
        )

    # Check for existing active pairing between these two devices
    existing = (
        db.query(DevicePairing)
        .filter(
            DevicePairing.user_id == uid,
            DevicePairing.controller_device_id == controller_device.id,
            DevicePairing.target_device_id == pairing_code.target_device_id,
            DevicePairing.status == "active",
        )
        .first()
    )
    if existing:
        # Mark code as claimed but return existing pairing
        pairing_code.claimed_at = now
        pairing_code.claimed_by_device_id = controller_device.id
        db.commit()
        return existing

    # Create new pairing
    pairing = DevicePairing(
        user_id=uid,
        controller_device_id=controller_device.id,
        target_device_id=pairing_code.target_device_id,
    )
    db.add(pairing)

    # Mark code as claimed
    pairing_code.claimed_at = now
    pairing_code.claimed_by_device_id = controller_device.id

    db.commit()
    db.refresh(pairing)
    return pairing
