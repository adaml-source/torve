"""
Content policy API.

Exposes the policy contract for Google Play compliance:
- GET /me/content-policy — read current policy state (channel resolved per-request)
- PATCH /me/content-policy/dob — set date of birth
- POST /me/content-policy/enable-sensitive — enable sensitive material
- POST /me/content-policy/disable-sensitive — disable sensitive material

Policy mode is NOT user-writable. It is resolved from the X-Torve-Channel
request header on every call. This prevents cross-device leakage.
"""
import logging
import uuid
from datetime import date

from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from app.content_policy import CURRENT_POLICY_VERSION
from app.content_policy_service import (
    disable_sensitive_material,
    enable_sensitive_material,
    get_policy_state,
    resolve_channel,
    set_date_of_birth,
)
from app.deps import get_current_user_id, get_db

_log = logging.getLogger(__name__)

router = APIRouter(prefix="/me/content-policy", tags=["content-policy"])


class PolicyStateOut(BaseModel):
    content_policy_mode: str
    age_band: str
    adult_eligible: bool
    sensitive_material_enabled: bool
    sensitive_material_policy_version: str | None
    can_enable_sensitive_material: bool
    current_policy_version: str
    policy_state_version: int


class SetDobRequest(BaseModel):
    date_of_birth: date


class EnableSensitiveRequest(BaseModel):
    policy_version: str = Field(description="Client must send the policy version they showed to the user")


@router.get("", response_model=PolicyStateOut)
def get_content_policy(
    request: Request,
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> PolicyStateOut:
    """Return the authoritative content policy state for this user.

    Policy mode is resolved from the X-Torve-Channel header, not stored on the account.
    """
    uid = uuid.UUID(user_id)
    policy_mode = resolve_channel(request)
    state = get_policy_state(db, uid, policy_mode)
    db.commit()
    return PolicyStateOut(**state)


@router.patch("/dob", response_model=PolicyStateOut)
def update_dob(
    body: SetDobRequest,
    request: Request,
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> PolicyStateOut:
    """Set date of birth. Computes age band and adult eligibility."""
    uid = uuid.UUID(user_id)
    set_date_of_birth(db, uid, body.date_of_birth)
    db.commit()
    policy_mode = resolve_channel(request)
    state = get_policy_state(db, uid, policy_mode)
    return PolicyStateOut(**state)


@router.post("/enable-sensitive", response_model=PolicyStateOut)
def enable_sensitive(
    body: EnableSensitiveRequest,
    request: Request,
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> PolicyStateOut:
    """Enable sensitive material access. Requires adult eligibility."""
    uid = uuid.UUID(user_id)
    try:
        enable_sensitive_material(db, uid, body.policy_version)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail={
                "code": "not_adult_eligible",
                "message": "You must verify your age before enabling sensitive content.",
            },
        )
    db.commit()
    policy_mode = resolve_channel(request)
    state = get_policy_state(db, uid, policy_mode)
    return PolicyStateOut(**state)


@router.post("/disable-sensitive", response_model=PolicyStateOut)
def disable_sensitive(
    request: Request,
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> PolicyStateOut:
    """Disable sensitive material access."""
    uid = uuid.UUID(user_id)
    disable_sensitive_material(db, uid)
    db.commit()
    policy_mode = resolve_channel(request)
    state = get_policy_state(db, uid, policy_mode)
    return PolicyStateOut(**state)
