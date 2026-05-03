"""
FastAPI dependency injectors.

Provides:
- get_db: database session
- get_current_user_id: JWT auth (returns user ID string)
- require_premium: entitlement gate for premium features (subscription or lifetime)
- get_calling_installation_id: extract X-Torve-Installation-Id header or
  ?installation_id= query param (used for Pattern B device-aware gating)
"""
import logging
import uuid
from collections.abc import Generator

from fastapi import Depends, HTTPException, Request, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from jose import JWTError
from sqlalchemy.orm import Session

from app.database import SessionLocal
from app.security import decode_access_token

_log = logging.getLogger(__name__)
_bearer = HTTPBearer()


def get_calling_installation_id(request: Request) -> str | None:
    """Pull the calling device's installation_id from either the
    `X-Torve-Installation-Id` header (preferred for new clients) or the
    `?installation_id=` query param (existing /me/access-state shape).
    Returns None when neither is present — the device-aware gate then
    no-ops, preserving existing behavior.
    """
    header_val = request.headers.get("X-Torve-Installation-Id")
    if header_val:
        return header_val.strip()
    query_val = request.query_params.get("installation_id")
    if query_val:
        return query_val.strip()
    return None


def get_db() -> Generator[Session, None, None]:
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


def get_current_user_id(
    credentials: HTTPAuthorizationCredentials = Depends(_bearer),
) -> str:
    """Returns the user ID string from a valid Bearer JWT.

    Raises 401 if the token is missing, expired, or invalid.
    """
    try:
        return decode_access_token(credentials.credentials)
    except JWTError:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or expired access token",
            headers={"WWW-Authenticate": "Bearer"},
        )


def require_premium(
    user_id: str = Depends(get_current_user_id),
    installation_id: str | None = Depends(get_calling_installation_id),
    db: Session = Depends(get_db),
) -> str:
    """Dependency that enforces active Torve Premium access.

    Uses the centralized check_premium_active() which queries real
    entitlements and fixes stale cached booleans automatically.

    Pattern B device-aware gate: when the request carries an
    installation_id (X-Torve-Installation-Id header or ?installation_id=
    query param), the calling device is looked up and passed through.
    Unverified users see premium ONLY on the device that originated
    each entitlement (or all devices for grandfathered NULL-origin
    rows). Verified users are unaffected. Requests with no
    installation_id at all preserve the pre-Pattern-B global behavior.
    """
    from app.billing import check_premium_active
    from app.models import Device

    uid = uuid.UUID(user_id)

    requesting_device_id: uuid.UUID | None = None
    if installation_id:
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
            requesting_device_id = device.id

    if not check_premium_active(db, uid, requesting_device_id=requesting_device_id):
        _log.info(
            "PREMIUM_DENIED user=%s requesting_device=%s",
            uid, requesting_device_id,
        )
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail={
                "code": "premium_required",
                "message": "Torve Premium is required for this feature.",
            },
        )
    _log.debug("PREMIUM_GRANTED user=%s requesting_device=%s",
               uid, requesting_device_id)
    return user_id


# Legacy alias — require_premium already handles both lifetime and subscription.
require_lifetime_access = require_premium
