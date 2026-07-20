"""
FastAPI dependency injectors.

Provides:
- get_db: database session
- get_current_user_id: JWT auth (returns user ID string)
- require_account_access: confirms the account has default/free product access
- get_calling_installation_id: extract X-Torve-Installation-Id header or
  ?installation_id= query param
"""
import logging
import uuid
from collections.abc import Generator
from dataclasses import dataclass

from fastapi import Depends, HTTPException, Request, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from jose import JWTError
from sqlalchemy.orm import Session

from app.database import SessionLocal
from app.security import decode_access_token

_log = logging.getLogger(__name__)
_bearer = HTTPBearer()


@dataclass(frozen=True)
class AccountDeviceContext:
    """Authenticated caller bound to an active registered device."""

    user_id: str
    device_id: uuid.UUID
    installation_id: str


# Legacy type name retained for imports that have not migrated yet.
PremiumDeviceContext = AccountDeviceContext


@dataclass(frozen=True)
class AuthenticatedDeviceContext:
    """Authenticated caller bound to an active registered device."""

    user_id: str
    device_id: uuid.UUID
    installation_id: str


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


def require_account_access(
    user_id: str = Depends(get_current_user_id),
    installation_id: str | None = Depends(get_calling_installation_id),
    db: Session = Depends(get_db),
) -> str:
    """Require an authenticated account with free/default product access.

    Torve product access is now free for authenticated active accounts. This
    dependency intentionally preserves authentication/account checks while
    removing paid-entitlement enforcement.
    """
    from app.billing import check_account_access_active
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

    if not check_account_access_active(db, uid, requesting_device_id=requesting_device_id):
        _log.info(
            "ACCOUNT_ACCESS_DENIED user=%s requesting_device=%s",
            uid, requesting_device_id,
        )
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail={
                "code": "account_access_required",
                "message": "An active account is required for this feature.",
            },
        )
    _log.debug("ACCOUNT_ACCESS_GRANTED user=%s requesting_device=%s",
               uid, requesting_device_id)
    return user_id


def require_active_device(
    user_id: str = Depends(get_current_user_id),
    installation_id: str | None = Depends(get_calling_installation_id),
    db: Session = Depends(get_db),
) -> AuthenticatedDeviceContext:
    """Require the caller to be an active device owned by the JWT user.

    This is intentionally not a premium gate. It is for sensitive account
    surfaces such as credential restore where the user is authenticated, but
    the backend still needs to know which registered device is receiving
    secrets.
    """
    from app.models import Device

    uid = uuid.UUID(user_id)
    if not installation_id:
        _log.warning("DEVICE_REQUIRED user=%s category=account_secret_restore", uid)
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail={
                "code": "device_required",
                "message": "A registered device is required for this feature.",
            },
        )

    device = (
        db.query(Device)
        .filter(
            Device.user_id == uid,
            Device.installation_id == installation_id,
            Device.is_active == True,  # noqa: E712
        )
        .first()
    )
    if device is None:
        _log.warning(
            "DEVICE_NOT_AUTHORIZED user=%s installation=%s category=account_secret_restore",
            uid, installation_id[:12],
        )
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail={
                "code": "device_not_authorized",
                "message": "This device is not authorized for this feature.",
            },
        )

    return AuthenticatedDeviceContext(
        user_id=user_id,
        device_id=device.id,
        installation_id=installation_id,
    )


def require_account_device_access(
    user_id: str = Depends(get_current_user_id),
    installation_id: str | None = Depends(get_calling_installation_id),
    db: Session = Depends(get_db),
) -> AccountDeviceContext:
    """Require an authenticated active registered device.

    Resolver/playback-sensitive endpoints still bind requests to a device for
    account security, privacy, and abuse prevention. They no longer require a
    paid entitlement.
    """
    from app.billing import check_account_access_active
    from app.models import Device

    uid = uuid.UUID(user_id)
    if not installation_id:
        _log.warning("DEVICE_REQUIRED user=%s category=resolver", uid)
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail={
                "code": "device_required",
                "message": "A registered device is required for this feature.",
            },
        )

    device = (
        db.query(Device)
        .filter(
            Device.user_id == uid,
            Device.installation_id == installation_id,
            Device.is_active == True,  # noqa: E712
        )
        .first()
    )
    if device is None:
        _log.warning(
            "DEVICE_NOT_AUTHORIZED user=%s installation=%s category=resolver",
            uid, installation_id[:12],
        )
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail={
                "code": "device_not_authorized",
                "message": "This device is not authorized for this feature.",
            },
        )

    if not check_account_access_active(db, uid, requesting_device_id=device.id):
        _log.info(
            "ACCOUNT_ACCESS_DENIED user=%s requesting_device=%s category=resolver",
            uid, device.id,
        )
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail={
                "code": "account_access_required",
                "message": "An active account is required for this feature.",
            },
        )

    _log.info(
        "ACCOUNT_DEVICE_GRANTED user=%s device=%s installation=%s category=resolver",
        uid, device.id, installation_id[:12],
    )
    return AccountDeviceContext(
        user_id=user_id,
        device_id=device.id,
        installation_id=installation_id,
    )


# Legacy aliases retained for compatibility only. New routes should depend on
# the account-access names above so paid-gate greps stay honest.
require_premium = require_account_access
require_premium_device = require_account_device_access
require_lifetime_access = require_account_access
