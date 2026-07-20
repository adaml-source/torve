"""Anonymous TV-QR sign-in pairing.

Three endpoints under /pairing/signin/:

  POST /code     anonymous   TV creates a 6-char code with its
                             installation_id and form-factor info.
  POST /status   anonymous   TV polls every ~2s. Returns pending /
                             expired / claimed (with tokens on the
                             FIRST claimed-poll only; subsequent polls
                             return "expired" so a leaked code can't
                             be re-played).
  POST /claim    auth req'd  Phone (signed in) scans the QR
                             "torve-signin:<CODE>", calls this. Backend
                             registers the TV as a device of the phone's
                             user, mints a fresh access+refresh pair for
                             the TV (NOT reusing the phone's tokens),
                             encrypts and stores them on the row for the
                             TV's next status poll to pick up.

This is a separate router from `pairing_code` (the authenticated
remote-control flow) on purpose — different invariants, different
table, different security model. They share nothing but the URL prefix
ancestor (`/pairing/`) — and even there, sub-paths don't overlap.
"""
from __future__ import annotations

import logging
import secrets
import string
import uuid
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from app.config import settings
from app.crypto import decrypt_secret, encrypt_secret
from app.deps import get_current_user_id, get_db
from app.models import Device, PairingSigninCode, User
from app.security import create_access_token

_log = logging.getLogger(__name__)

router = APIRouter(prefix="/pairing/signin", tags=["pairing-signin"])

_CODE_LENGTH = 6
_CODE_TTL_MINUTES = 10
_CODE_ALPHABET = string.ascii_uppercase + string.digits
_MAX_CODE_RETRIES = 10
# Allowed device types match _register_device_on_auth's whitelist.
_ALLOWED_DEVICE_TYPES = ("phone", "tablet", "tv", "desktop")


# ── Request / response models ──────────────────────────────────────────


class CodeRequest(BaseModel):
    installation_id: str = Field(min_length=1, max_length=255)
    device_name: str | None = Field(default=None, max_length=200)
    device_type: str = Field(min_length=1, max_length=20)
    platform: str | None = Field(default=None, max_length=50)


class CodeResponse(BaseModel):
    code: str
    expires_at: datetime


class StatusRequest(BaseModel):
    code: str = Field(min_length=1, max_length=10)
    installation_id: str = Field(min_length=1, max_length=255)


class TokensOut(BaseModel):
    access_token: str
    refresh_token: str
    token_type: str = "bearer"
    expires_in: int


class UserOut(BaseModel):
    id: uuid.UUID
    email: str
    display_name: str | None = None
    is_verified: bool
    has_lifetime_access: bool = False
    has_premium_access: bool = False
    access_tier: str = "free"


class PairedDeviceOut(BaseModel):
    id: uuid.UUID
    name: str | None = None


class StatusResponse(BaseModel):
    status: str  # "pending" | "expired" | "claimed"
    tokens: TokensOut | None = None
    user: UserOut | None = None
    paired_device: PairedDeviceOut | None = None


class ClaimRequest(BaseModel):
    code: str = Field(min_length=1, max_length=10)


class ClaimResponse(BaseModel):
    status: str  # "claimed"
    paired_device: PairedDeviceOut


# ── Helpers ────────────────────────────────────────────────────────────


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _generate_code() -> str:
    return "".join(secrets.choice(_CODE_ALPHABET) for _ in range(_CODE_LENGTH))


def _normalize_code(raw: str) -> str:
    return raw.strip().upper()


def _ensure_aware(dt: datetime) -> datetime:
    """Coerce a naive datetime (legacy SQLite test rows) to UTC. Postgres
    rows come back already-aware so this is mostly a no-op in prod."""
    return dt.replace(tzinfo=timezone.utc) if dt.tzinfo is None else dt


# ── POST /pairing/signin/code ──────────────────────────────────────────


@router.post("/code", response_model=CodeResponse)
def create_signin_code(
    body: CodeRequest,
    db: Session = Depends(get_db),
) -> CodeResponse:
    """Anonymous. Creates a single-use code bound to the requesting
    installation_id. The TV polls /status with the same installation_id
    until a phone claims it."""
    if body.device_type not in _ALLOWED_DEVICE_TYPES:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"device_type must be one of: {', '.join(_ALLOWED_DEVICE_TYPES)}",
        )

    now = _now()
    expires_at = now + timedelta(minutes=_CODE_TTL_MINUTES)

    # Generate a unique code, retry on collision against unclaimed +
    # unexpired rows. Collisions are vanishingly rare (36^6 = 2.1B), but
    # we cover them rather than relying on exception bubbling.
    code = None
    for _ in range(_MAX_CODE_RETRIES):
        candidate = _generate_code()
        existing = (
            db.query(PairingSigninCode)
            .filter(
                PairingSigninCode.code == candidate,
                PairingSigninCode.consumed_at.is_(None),
                PairingSigninCode.expires_at > now,
            )
            .first()
        )
        if not existing:
            code = candidate
            break

    if code is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Could not generate a unique pairing code. Please retry.",
        )

    row = PairingSigninCode(
        code=code,
        device_installation_id=body.installation_id,
        device_name=body.device_name,
        device_type=body.device_type,
        platform=body.platform,
        expires_at=expires_at,
        created_at=now,
    )
    db.add(row)
    db.commit()
    # Audit metadata only — never log the code itself.
    _log.info(
        "PAIRING_SIGNIN_CODE_CREATED type=%s platform=%s ttl_min=%d",
        body.device_type, body.platform or "?", _CODE_TTL_MINUTES,
    )
    return CodeResponse(code=code, expires_at=expires_at)


# ── POST /pairing/signin/status ────────────────────────────────────────


@router.post("/status", response_model=StatusResponse)
def get_signin_status(
    body: StatusRequest,
    db: Session = Depends(get_db),
) -> StatusResponse:
    """Anonymous. Poll endpoint for the TV. Validates installation_id
    matches the code's bound installation — a different device polling
    with the same code gets 404, so a stolen code can't be used to
    intercept tokens."""
    code = _normalize_code(body.code)
    row = db.query(PairingSigninCode).filter(PairingSigninCode.code == code).first()
    if row is None or row.device_installation_id != body.installation_id:
        # 404 in both cases — never leak that the code exists but is
        # bound to a different installation.
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Pairing code not found.",
        )

    now = _now()
    expires_at = _ensure_aware(row.expires_at)

    if row.consumed_at is not None:
        # Tokens already handed out. Single-shot delivery — surface
        # "expired" so the client treats it as terminal and re-pairs.
        return StatusResponse(status="expired")

    if expires_at <= now:
        return StatusResponse(status="expired")

    if row.claimed_at is None:
        return StatusResponse(status="pending")

    # claimed but not yet consumed — first claimed-poll. Decrypt the
    # stored tokens, mark consumed, return everything to the TV.
    if not row.access_token_encrypted or not row.refresh_token_encrypted:
        # Defensive: claim should always populate both. If not, treat as
        # internal error rather than stranding the TV silently.
        _log.error("PAIRING_SIGNIN_CLAIMED_MISSING_TOKENS code_prefix=%s", code[:2])
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Claimed code has no tokens — please retry.",
        )
    try:
        access_token = decrypt_secret(row.access_token_encrypted)
        refresh_token = decrypt_secret(row.refresh_token_encrypted)
    except (ValueError, Exception):  # noqa: BLE001
        _log.error("PAIRING_SIGNIN_TOKEN_DECRYPT_FAILED code_prefix=%s", code[:2])
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Token unwrap failed. Please re-pair.",
        )

    user = db.query(User).filter(User.id == row.claimed_by_user_id).first()
    if user is None:
        # User was deleted between claim and poll. Treat as expired.
        row.consumed_at = now
        db.commit()
        return StatusResponse(status="expired")

    paired_device = (
        db.query(Device).filter(Device.id == row.claimed_device_id).first()
        if row.claimed_device_id else None
    )
    from app.billing import resolve_access_state
    access_state = resolve_access_state(db, user.id)

    # Single-shot delivery: clear the encrypted blobs and mark consumed
    # so a replay attempt sees expired. The Device + RefreshToken rows
    # already exist independently — clearing here only removes the
    # ciphertext envelope, not the actual session.
    row.access_token_encrypted = None
    row.refresh_token_encrypted = None
    row.consumed_at = now
    db.commit()

    _log.info("PAIRING_SIGNIN_DELIVERED user=%s", user.id)
    return StatusResponse(
        status="claimed",
        tokens=TokensOut(
            access_token=access_token,
            refresh_token=refresh_token,
            token_type="bearer",
            expires_in=row.access_token_expires_in_s or settings.ACCESS_TOKEN_EXPIRE_MINUTES * 60,
        ),
        user=UserOut(
            id=user.id, email=user.email,
            display_name=user.display_name, is_verified=user.is_verified,
            has_lifetime_access=False,
            has_premium_access=bool(access_state.get("has_premium_access")),
            access_tier=access_state.get("access_tier", "free"),
        ),
        paired_device=PairedDeviceOut(
            id=paired_device.id if paired_device else uuid.uuid4(),
            name=(paired_device.display_name if paired_device else None),
        ) if paired_device else None,
    )


# ── POST /pairing/signin/claim ─────────────────────────────────────────


@router.post("/claim", response_model=ClaimResponse)
def claim_signin_code(
    body: ClaimRequest,
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> ClaimResponse:
    """Authenticated. Phone scans the QR, calls this. Server registers
    the TV as a device of the phone's user, mints a NEW access+refresh
    pair for the TV (not reusing the phone's tokens), encrypts and
    stores them on the row for the TV's next status poll.

    Refuses 404 if the code doesn't exist, 410 if it's expired or
    already claimed."""
    from app.routers.auth import _create_refresh_token, _register_device_on_auth

    code = _normalize_code(body.code)
    row = db.query(PairingSigninCode).filter(PairingSigninCode.code == code).first()
    if row is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Pairing code not found.",
        )

    now = _now()
    expires_at = _ensure_aware(row.expires_at)
    if expires_at <= now:
        raise HTTPException(
            status_code=status.HTTP_410_GONE,
            detail="Pairing code has expired.",
        )
    if row.claimed_at is not None:
        raise HTTPException(
            status_code=status.HTTP_410_GONE,
            detail="Pairing code has already been claimed.",
        )

    uid = uuid.UUID(user_id)
    user = db.query(User).filter(User.id == uid).first()
    if user is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Calling user not found.",
        )

    # Register the TV as a device of this user. Reuses the same dedup +
    # device-cap enforcement as a fresh login. If the device already
    # exists for this user under that installation_id, we just refresh
    # last_seen_at; otherwise a new Device row is created.
    tv_device = _register_device_on_auth(
        db,
        user_id=uid,
        device_type=row.device_type,
        platform=row.platform,
        display_name=row.device_name,
        installation_id=row.device_installation_id,
        app_version=None,
        stable_device_id=None,
    )
    # Force the INSERT so tv_device.id is populated. SQLAlchemy's
    # default=uuid.uuid4 only fires at flush time; without this, id is
    # None when we assign it to claimed_device_id below.
    db.flush()

    # Mint a fresh access + refresh pair for the TV. NOT the phone's
    # tokens — the TV needs its own session/RefreshToken row. The
    # access token is just a JWT for the user; the refresh token row
    # is bound to the TV's device_name + platform so future audits show
    # who's signed in where.
    access_token = create_access_token(str(user.id))
    refresh_raw, _refresh_row = _create_refresh_token(
        db,
        user=user,
        device_name=row.device_name,
        platform=row.platform,
    )

    row.claimed_by_user_id = uid
    row.claimed_at = now
    row.claimed_device_id = tv_device.id
    row.access_token_encrypted = encrypt_secret(access_token)
    row.refresh_token_encrypted = encrypt_secret(refresh_raw)
    row.access_token_expires_in_s = settings.ACCESS_TOKEN_EXPIRE_MINUTES * 60
    db.commit()

    _log.info(
        "PAIRING_SIGNIN_CLAIMED user=%s tv_device=%s",
        user.id, tv_device.id,
    )
    return ClaimResponse(
        status="claimed",
        paired_device=PairedDeviceOut(id=tv_device.id, name=tv_device.display_name),
    )
