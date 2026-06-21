import logging
import uuid
from datetime import datetime, timedelta, timezone
from urllib.parse import urlencode

from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy.orm import Session

from app.config import settings
from app.deps import get_db
from fastapi.responses import RedirectResponse

from app.email_typo_guard import suspect_typo
from app.events import UserEvent, event_bus
from app.mail import send_password_reset_email, send_verification_email, send_welcome_email
from app.models import AccountSettings, Device, EmailVerificationToken, PasswordResetToken, RefreshToken, User
from app.rate_limits import enforce_rate_limit
from app.schemas import (
    AuthResponse,
    DeviceLimitError,
    DeviceOut,
    LoginRequest,
    MessageResponse,
    PasswordResetConfirm,
    PasswordResetRequest,
    RefreshRequest,
    RefreshResponse,
    RefreshTokensOut,
    ResendVerificationRequest,
    SignupRequest,
    TokensOut,
    UserOut,
)
from app.security import (
    create_access_token,
    generate_refresh_token,
    hash_password,
    hash_refresh_token,
    refresh_token_expiry,
    verify_password,
)

_log = logging.getLogger(__name__)

router = APIRouter(prefix="/auth", tags=["auth"])


@router.post("/signup", response_model=AuthResponse, status_code=status.HTTP_201_CREATED)
@router.post("/register", response_model=AuthResponse, status_code=status.HTTP_201_CREATED, include_in_schema=False)
def signup(
    body: SignupRequest,
    request: Request,
    db: Session = Depends(get_db),
) -> AuthResponse:
    enforce_rate_limit(category="auth_signup", request=request, limit=200, window_seconds=300)
    email = body.email.lower().strip()

    # Reject obvious typos (github.coml, user@gmial.com, etc.). Without
    # this, the verification email silently goes to nowhere and the
    # account sits unverified forever. Surface a correction suggestion
    # so the user can fix it in the same form.
    suggestion = suspect_typo(email)
    if suggestion is not None:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={
                "code": "email_typo_suspected",
                "message": f"That email address looks like it might have a typo. Did you mean {suggestion}?",
                "suggestion": suggestion,
            },
        )

    existing = db.query(User).filter(User.email == email).first()
    if existing:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Email already registered")

    user = User(
        email=email,
        password_hash=hash_password(body.password),
        display_name=body.display_name,
    )
    db.add(user)
    db.flush()  # get user.id before creating refresh token

    r_platform = body.resolved_platform()
    r_name = body.resolved_device_name()
    raw_token, token_row = _create_refresh_token(db, user, r_name, r_platform)

    # Register device if device_type provided or inferable from platform
    device_out = None
    resolved_type = _infer_device_type(body.resolved_device_type(), r_platform)
    if resolved_type:
        device_out = _register_device_on_auth(
            db, user.id, resolved_type, r_platform,
            r_name, body.resolved_installation_id(), body.resolved_app_version(),
            stable_device_id=body.resolved_stable_device_id(),
        )

    # Create default account settings
    acct_settings = AccountSettings(user_id=user.id, settings={
        "language": "en", "home_layout": "default", "ratings_provider": "imdb",
    })
    db.add(acct_settings)

    db.commit()
    db.refresh(user)
    if device_out:
        db.refresh(device_out)

    # Send verification email (non-blocking)
    _send_verification(db, user)

    return AuthResponse(
        tokens=TokensOut(
            access_token=create_access_token(str(user.id)),
            refresh_token=raw_token,
        ),
        user=_auth_user_out(db, user),
        device=device_out,
    )


@router.post("/login", response_model=AuthResponse)
def login(
    body: LoginRequest,
    request: Request,
    db: Session = Depends(get_db),
) -> AuthResponse:
    enforce_rate_limit(category="auth_login", request=request, limit=200, window_seconds=300)
    email = body.email.lower().strip()

    user = db.query(User).filter(User.email == email).first()
    if not user or not verify_password(body.password, user.password_hash):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid email or password",
        )

    if not user.is_active:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Account is disabled",
        )

    r_platform = body.resolved_platform()
    r_name = body.resolved_device_name()
    raw_token, _ = _create_refresh_token(db, user, r_name, r_platform)

    # Register device if device_type provided or inferable from platform
    device_out = None
    resolved_type = _infer_device_type(body.resolved_device_type(), r_platform)
    if resolved_type:
        device_out = _register_device_on_auth(
            db, user.id, resolved_type, r_platform,
            r_name, body.resolved_installation_id(), body.resolved_app_version(),
            stable_device_id=body.resolved_stable_device_id(),
        )

    db.commit()
    if device_out:
        db.refresh(device_out)

    return AuthResponse(
        tokens=TokensOut(
            access_token=create_access_token(str(user.id)),
            refresh_token=raw_token,
        ),
        user=_auth_user_out(db, user),
        device=device_out,
    )


@router.post("/refresh", response_model=RefreshResponse)
def refresh(body: RefreshRequest, db: Session = Depends(get_db)) -> RefreshResponse:
    token_hash = hash_refresh_token(body.refresh_token)

    token_row = (
        db.query(RefreshToken).filter(RefreshToken.token_hash == token_hash).first()
    )

    if not token_row:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Refresh token is invalid or revoked",
        )

    # Reuse detection: if the token was already revoked, someone is replaying it
    if token_row.is_revoked:
        _handle_refresh_token_reuse(db, token_row)
        db.commit()
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Refresh token is invalid or revoked",
        )

    if token_row.expires_at < datetime.now(timezone.utc):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Refresh token has expired",
        )

    user = db.query(User).filter(User.id == token_row.user_id).first()
    if not user or not user.is_active:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="User account is inactive",
        )

    # Rotate: revoke old token, issue new one in same family
    raw_new_refresh, new_token_row = _rotate_refresh_token(db, token_row)

    # Register/upsert device if device info is provided
    device_out = None
    resolved_type = _infer_device_type(body.resolved_device_type(), body.resolved_platform())
    if resolved_type:
        device_out = _register_device_on_auth(
            db, user.id, resolved_type, body.resolved_platform(),
            body.resolved_device_name(), body.resolved_installation_id(),
            body.resolved_app_version(),
            stable_device_id=body.resolved_stable_device_id(),
        )

    db.commit()
    if device_out:
        db.refresh(device_out)

    return RefreshResponse(
        tokens=RefreshTokensOut(
            access_token=create_access_token(str(user.id)),
            refresh_token=raw_new_refresh,
        ),
        user=_auth_user_out(db, user),
        device=device_out,
    )


# ── Password reset ────────────────────────────────────────────────────────────

_RESET_GENERIC_MSG = "If that email is registered, a reset link has been sent."


@router.post("/password-reset/request", response_model=MessageResponse)
def password_reset_request(
    body: PasswordResetRequest, db: Session = Depends(get_db)
) -> MessageResponse:
    email = body.email.lower().strip()
    user = db.query(User).filter(User.email == email).first()

    if user and user.is_active:
        now = datetime.now(timezone.utc)

        # Invalidate any prior unused password reset tokens for this user
        db.query(PasswordResetToken).filter(
            PasswordResetToken.user_id == user.id,
            PasswordResetToken.used_at == None,  # noqa: E711
        ).update({"used_at": now})

        raw_token = generate_refresh_token()
        token_hash = hash_refresh_token(raw_token)
        expires_at = now + timedelta(
            minutes=settings.PASSWORD_RESET_TOKEN_EXPIRE_MINUTES
        )

        reset_row = PasswordResetToken(
            user_id=user.id,
            token_hash=token_hash,
            expires_at=expires_at,
        )
        db.add(reset_row)
        db.commit()

        reset_url = f"{settings.APP_PUBLIC_WEB_URL}/reset-password?{urlencode({'token': raw_token})}"
        sent = send_password_reset_email(to=user.email, reset_url=reset_url)
        if not sent:
            _log.warning("Failed to send password reset email to %s", email)

    # Always return generic message — never reveal whether the email exists
    return MessageResponse(message=_RESET_GENERIC_MSG)


@router.post("/password-reset/confirm", response_model=MessageResponse)
def password_reset_confirm(
    body: PasswordResetConfirm, db: Session = Depends(get_db)
) -> MessageResponse:
    token_hash = hash_refresh_token(body.token)

    reset_row = (
        db.query(PasswordResetToken)
        .filter(PasswordResetToken.token_hash == token_hash)
        .first()
    )

    if not reset_row or reset_row.used_at is not None:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Reset token is invalid or has already been used.",
        )

    if reset_row.expires_at < datetime.now(timezone.utc):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Reset token has expired.",
        )

    user = db.query(User).filter(User.id == reset_row.user_id).first()
    if not user:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Reset token is invalid.",
        )

    # Update password
    user.password_hash = hash_password(body.new_password)
    user.updated_at = datetime.now(timezone.utc)

    # Mark token as used
    reset_row.used_at = datetime.now(timezone.utc)

    # Revoke all existing refresh tokens for this user
    db.query(RefreshToken).filter(
        RefreshToken.user_id == user.id,
        RefreshToken.is_revoked == False,  # noqa: E712
    ).update({"is_revoked": True})

    db.commit()

    return MessageResponse(message="Password has been reset successfully.")


# ── Email verification ────────────────────────────────────────────────────────


@router.get("/verify-email")
def verify_email(token: str, db: Session = Depends(get_db)) -> RedirectResponse:
    """Called when user clicks the link in their verification email."""
    token_hash = hash_refresh_token(token)
    base_url = settings.APP_PUBLIC_WEB_URL

    row = (
        db.query(EmailVerificationToken)
        .filter(EmailVerificationToken.token_hash == token_hash)
        .first()
    )

    if not row or row.used_at is not None:
        return RedirectResponse(f"{base_url}/verify-email?status=invalid")

    if row.expires_at < datetime.now(timezone.utc):
        return RedirectResponse(f"{base_url}/verify-email?status=expired")

    user = db.query(User).filter(User.id == row.user_id).first()
    if not user:
        return RedirectResponse(f"{base_url}/verify-email?status=invalid")

    user.is_verified = True
    user.updated_at = datetime.now(timezone.utc)
    row.used_at = datetime.now(timezone.utc)

    # Email is now proven to belong to this user — safe to auto-restore a
    # previously-purchased lifetime grant for the same address. No-op if
    # this email has no ledger entry (the common case).
    try:
        from app.billing import restore_lifetime_if_granted
        restore_lifetime_if_granted(db, user)
    except Exception:
        _log.exception("Lifetime restore check failed for user %s", user.id)

    db.commit()

    # Notify any connected SSE clients that email is now verified
    event_bus.emit(UserEvent(event_type="EMAIL_VERIFIED", user_id=user.id))

    # Send welcome email (non-blocking — verification succeeds even if mail fails)
    sent = send_welcome_email(to=user.email)
    if not sent:
        _log.warning("Failed to send welcome email to %s", user.email)

    return RedirectResponse(f"{base_url}/verify-email?status=success")


@router.post("/resend-verification", response_model=MessageResponse)
def resend_verification(
    body: ResendVerificationRequest, db: Session = Depends(get_db)
) -> MessageResponse:
    email = body.email.lower().strip()
    user = db.query(User).filter(User.email == email).first()

    if user and user.is_active and not user.is_verified:
        _send_verification(db, user)

    # Generic response — never reveal whether email exists
    return MessageResponse(
        message="If that email is registered and unverified, a verification link has been sent."
    )


# ── Internal helpers ──────────────────────────────────────────────────────────


def maybe_resend_verification(db: Session, user: User) -> bool:
    """Re-send the verification email IF the user is unverified AND
    they don't already have a live (unused, unexpired) token.

    Idempotent and safe to call from any engagement-moment hook —
    successful purchase, admin grant, etc. Never raises; returns
    True when an email was sent, False when skipped or failed.

    The natural throttle is the existing 48 h token lifetime: while
    a token is live we don't issue another, so this can be called
    on every retry / repeat trigger without spamming the user.
    """
    if user.is_verified:
        return False
    now = datetime.now(timezone.utc)
    has_active = db.query(EmailVerificationToken).filter(
        EmailVerificationToken.user_id == user.id,
        EmailVerificationToken.used_at.is_(None),
        EmailVerificationToken.expires_at > now,
    ).first() is not None
    if has_active:
        return False
    try:
        _send_verification(db, user)
        _log.info("VERIFICATION_RESENT_ON_ENGAGEMENT user=%s email=%s", user.id, user.email)
        return True
    except Exception:  # noqa: BLE001 — never let this break the calling flow
        _log.exception("maybe_resend_verification failed user=%s", user.id)
        return False


def _auth_user_out(db: Session, user: User) -> UserOut:
    """Serialize auth user state from the same resolver used by /me."""
    from app.billing import resolve_access_state

    access = resolve_access_state(db, user.id)
    return UserOut(
        id=user.id,
        email=user.email,
        display_name=user.display_name,
        is_active=user.is_active,
        is_verified=user.is_verified,
        has_lifetime_access=False,
        has_premium_access=bool(access.get("has_premium_access")),
        created_at=user.created_at,
    )


def _send_verification(db: Session, user: User) -> None:
    """Create a verification token row and send the email. Logs on failure."""
    # Invalidate any prior unused verification tokens for this user
    now = datetime.now(timezone.utc)
    db.query(EmailVerificationToken).filter(
        EmailVerificationToken.user_id == user.id,
        EmailVerificationToken.used_at == None,  # noqa: E711
    ).update({"used_at": now})

    raw_token = generate_refresh_token()
    token_hash = hash_refresh_token(raw_token)
    expires_at = now + timedelta(
        hours=settings.EMAIL_VERIFICATION_TOKEN_EXPIRE_HOURS
    )

    row = EmailVerificationToken(
        user_id=user.id,
        token_hash=token_hash,
        expires_at=expires_at,
    )
    db.add(row)
    db.commit()

    verify_url = (
        f"{settings.APP_PUBLIC_API_URL}/auth/verify-email"
        f"?{urlencode({'token': raw_token})}"
    )
    sent = send_verification_email(to=user.email, verify_url=verify_url)
    if not sent:
        _log.warning("Failed to send verification email to %s", user.email)

def _infer_device_type(device_type: str | None, platform: str | None) -> str | None:
    """Infer device_type from platform when client doesn't send it explicitly."""
    if device_type:
        return device_type
    if not platform:
        return None
    p = platform.lower()
    if p in ("firetv", "fire_tv", "androidtv", "android_tv", "googletv", "chromecast"):
        return "tv"
    if p in ("android", "ios"):
        return "phone"
    if p in ("ipad", "tablet"):
        return "tablet"
    if p in ("windows", "macos", "linux", "desktop"):
        return "desktop"
    # If platform is provided but we can't classify, default to phone
    if p:
        return "phone"
    return None


def _register_device_on_auth(
    db: Session,
    user_id: uuid.UUID,
    device_type: str,
    platform: str | None,
    display_name: str | None,
    installation_id: str | None,
    app_version: str | None,
    stable_device_id: str | None = None,
) -> Device:
    """Register or refresh a device during signup/login.

    Dedup priority: stable_device_id (hardware ID) > installation_id (app ID).
    Device registration is retained for account security, sync integrity, and
    privacy. It is not a paid capacity gate.
    """
    if device_type not in ("phone", "tablet", "tv", "desktop"):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="device_type must be one of: phone, tablet, tv, desktop",
        )

    existing = _find_existing_device(db, user_id, stable_device_id, installation_id)
    if existing:
        existing.last_seen_at = datetime.now(timezone.utc)
        existing.app_version = app_version or existing.app_version
        existing.platform = platform or existing.platform
        existing.display_name = display_name or existing.display_name
        # Backfill stable_device_id on legacy rows
        if stable_device_id and not existing.stable_device_id:
            existing.stable_device_id = stable_device_id
        # Update installation_id if it changed (reinstall)
        if installation_id and existing.installation_id != installation_id:
            existing.installation_id = installation_id
        # Reactivate if previously revoked
        if not existing.is_active:
            existing.is_active = True
            existing.revoked_at = None
        _log.info(
            "DEVICE_SLOT_DECISION user=%s device=%s reason=auth_existing_reused "
            "entitlement_source=unchanged platform=%s",
            user_id, existing.id, existing.platform,
        )
        return existing

    device = Device(
        user_id=user_id,
        device_type=device_type,
        platform=platform,
        display_name=display_name,
        installation_id=installation_id,
        stable_device_id=stable_device_id,
        app_version=app_version,
    )
    db.add(device)
    _log.info(
        "DEVICE_SLOT_DECISION user=%s device=pending reason=auth_registered "
        "access_model=free_software type=%s platform=%s stable_id=%s",
        user_id,
        device_type, platform, stable_device_id,
    )
    return device


def _find_existing_device(
    db: Session,
    user_id: uuid.UUID,
    stable_device_id: str | None,
    installation_id: str | None,
) -> Device | None:
    """Find an existing device row by stable_device_id first, then installation_id.

    stable_device_id (ANDROID_ID / IDFV) survives reinstalls, so it takes
    priority over installation_id which changes on reinstall/clear-data.
    """
    if stable_device_id:
        existing = (
            db.query(Device)
            .filter(
                Device.user_id == user_id,
                Device.stable_device_id == stable_device_id,
            )
            .first()
        )
        if existing:
            return existing

    if installation_id:
        existing = (
            db.query(Device)
            .filter(
                Device.user_id == user_id,
                Device.installation_id == installation_id,
            )
            .first()
        )
        if existing:
            return existing

    return None


def _create_refresh_token(
    db: Session,
    user: User,
    device_name: str | None,
    platform: str | None,
    family_id: uuid.UUID | None = None,
) -> tuple[str, RefreshToken]:
    raw = generate_refresh_token()
    token_row = RefreshToken(
        user_id=user.id,
        token_hash=hash_refresh_token(raw),
        device_name=device_name,
        platform=platform,
        expires_at=refresh_token_expiry(),
        family_id=family_id or uuid.uuid4(),
    )
    db.add(token_row)
    return raw, token_row


def _rotate_refresh_token(
    db: Session,
    old_token_row: RefreshToken,
) -> tuple[str, RefreshToken]:
    """Revoke the old refresh token and issue a new one in the same family.

    This implements refresh token rotation: each refresh token is single-use.
    The new token inherits the family_id so reuse detection works.
    """
    old_token_row.is_revoked = True
    user = db.query(User).filter(User.id == old_token_row.user_id).first()
    raw, new_row = _create_refresh_token(
        db, user,
        old_token_row.device_name,
        old_token_row.platform,
        family_id=old_token_row.family_id,
    )
    return raw, new_row


def _handle_refresh_token_reuse(db: Session, token_row: RefreshToken) -> None:
    """Handle reuse of a revoked refresh token (potential compromise).

    Revokes ALL tokens in the same family, forcing re-authentication
    on all sessions that share this token lineage.
    """
    if token_row.family_id:
        count = db.query(RefreshToken).filter(
            RefreshToken.family_id == token_row.family_id,
            RefreshToken.is_revoked == False,  # noqa: E712
        ).update({"is_revoked": True})
        _log.warning(
            "SECURITY: Refresh token reuse detected for user %s, family %s. "
            "Revoked %d tokens in family.",
            token_row.user_id, token_row.family_id, count,
        )
