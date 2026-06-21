"""Account management endpoints (profile, password, deletion)."""
import logging
import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session

from app.billing import resolve_access_state
from app.deps import get_calling_installation_id, get_current_user_id, get_db
from app.models import RefreshToken, User
from app.schemas import (
    AccessStateOut,
    MeResponse,
    MessageResponse,
    PasswordChangeRequest,
    ProfileUpdateRequest,
    UserOut,
)
from app.security import create_access_token, hash_password, verify_password

_log = logging.getLogger(__name__)

router = APIRouter(prefix="/me", tags=["account"])

# Compat alias: Android client sends DELETE /auth/account
compat_router = APIRouter(prefix="/auth", tags=["account"], include_in_schema=False)


@router.get("", response_model=MeResponse)
def get_me(
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> MeResponse:
    """Return the authenticated user's profile with free/default access state."""
    uid = uuid.UUID(user_id)
    user = db.query(User).filter(User.id == uid).first()
    if not user:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="User not found.",
        )

    access = resolve_access_state(db, uid)
    tier = access.get("access_tier", "free")

    _log.info(
        "ME_RESOLVE user=%s access_tier=%s has_access=%s source=%s",
        uid, tier, access.get("has_premium_access"), access.get("source"),
    )

    return MeResponse(
        id=user.id,
        email=user.email,
        display_name=user.display_name,
        is_active=user.is_active,
        is_verified=user.is_verified,
        created_at=user.created_at,
        has_lifetime_access=False,
        has_premium_access=access.get("has_premium_access", False),
        access_tier=tier,
        entitlement_source=access.get("source"),
        entitlement_expires_at=access.get("expires_at"),
        auto_renew=access.get("auto_renew"),
    )


@router.get("/access-state", response_model=AccessStateOut)
def get_access_state(
    user_id: str = Depends(get_current_user_id),
    installation_id: str | None = Depends(get_calling_installation_id),
    db: Session = Depends(get_db),
) -> AccessStateOut:
    """Return the authenticated user's current free/default access state.

    Optional installation_id query param includes device activation status.
    """
    uid = uuid.UUID(user_id)
    state = resolve_access_state(db, uid, installation_id=installation_id)
    return AccessStateOut(**state)


@router.patch("/profile", response_model=UserOut)
def update_profile(
    body: ProfileUpdateRequest,
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> UserOut:
    """Update the authenticated user's profile (display name)."""
    uid = uuid.UUID(user_id)
    user = db.query(User).filter(User.id == uid).first()
    if not user:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="User not found.",
        )

    if body.display_name is not None:
        cleaned = body.display_name.strip() if body.display_name else None
        if cleaned and len(cleaned) > 100:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Display name must be 100 characters or fewer.",
            )
        user.display_name = cleaned or None

    user.updated_at = datetime.now(timezone.utc)
    db.commit()
    db.refresh(user)
    _log.info("Profile updated for user %s", uid)
    return user


@router.post("/change-password", response_model=MessageResponse)
def change_password(
    body: PasswordChangeRequest,
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> MessageResponse:
    """Change password for the authenticated user.

    Requires the current password. On success, all existing refresh tokens
    are revoked, requiring re-authentication on all devices.
    """
    uid = uuid.UUID(user_id)
    user = db.query(User).filter(User.id == uid).first()
    if not user:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="User not found.",
        )

    if not verify_password(body.current_password, user.password_hash):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Current password is incorrect.",
        )

    if body.current_password == body.new_password:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="New password must be different from current password.",
        )

    user.password_hash = hash_password(body.new_password)
    user.updated_at = datetime.now(timezone.utc)

    # Revoke all refresh tokens (force re-auth on all devices)
    db.query(RefreshToken).filter(
        RefreshToken.user_id == uid,
        RefreshToken.is_revoked == False,  # noqa: E712
    ).update({"is_revoked": True})

    db.commit()
    _log.info("Password changed for user %s, all refresh tokens revoked", uid)

    return MessageResponse(message="Password changed successfully. Please sign in again on all devices.")


@router.delete("/account", response_model=MessageResponse)
def delete_account(
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> MessageResponse:
    """Delete the authenticated user's account and all associated data."""
    uid = uuid.UUID(user_id)
    user = db.query(User).filter(User.id == uid).first()

    if not user:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Account not found.",
        )

    email_for_log = user.email
    db.delete(user)
    db.commit()

    _log.info("Account deleted: user_id=%s email=%s", uid, email_for_log)

    return MessageResponse(message="Your account and associated data have been deleted.")


@router.get("/panda/configure-url")
def panda_configure_url(
    user_id: str = Depends(get_current_user_id),
) -> dict:
    """Mint a short-lived JWT and return the Panda configure URL with it
    appended as ?torve_token=<jwt>. The Panda configure page reads the
    query param, forwards it as a Bearer header on /api/configs, and the
    resulting Panda config is bound to the calling user's Torve account
    (no management-token UX required).

    Same JWT shape as the regular access token — Panda verifies it with
    the shared TORVE_JWT_SECRET. Lifetime matches ACCESS_TOKEN_EXPIRE_MINUTES;
    if the user takes longer than that to fill in the form, the page falls
    back to anonymous (management-token) mode.
    """
    token = create_access_token(user_id)
    return {
        "configure_url": f"https://panda.torve.app/configure?torve_token={token}",
    }


# ── Compat alias (/auth/account) ──────────────────────────────────────────

compat_router.delete("/account", response_model=MessageResponse)(delete_account)
