"""Sealed-envelope relay endpoints for intra-account device-to-device
secret transfer.

Sender and receiver are the same Torve user (sign-in is on both ends).
The receiver creates a session, the sender posts a client-encrypted
envelope, the receiver polls until delivered, decrypts client-side,
then consumes (envelope cleared on the server).

Server is a dumb relay: never decrypts the envelope, never inspects it,
never logs it. State is derived from timestamps; rows expire lazily on
read/write so we don't need a background sweeper for this surface.
"""
from __future__ import annotations

import base64
import logging
import uuid
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException, Request, Response, status
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from app.deps import get_current_user_id, get_db
from app.models import TransferSession

_log = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1/transfer", tags=["transfer"])

# Relay limits — tight on purpose. Sessions are meant to live for the
# few seconds a user spends on the "transfer to this device" screen.
_MAX_TTL_SECONDS = 10 * 60
_MAX_ENVELOPE_BYTES = 64 * 1024  # 64 KiB serialised JSON
_X25519_KEY_BYTES = 32


# ── Request / response models ──────────────────────────────────────────


class CreateSessionRequest(BaseModel):
    """Receiver creates a session with their ephemeral X25519 public key
    and a TTL. The TTL is enforced server-side (<= 10 minutes from now)."""
    receiver_public_key: str = Field(min_length=1, max_length=64)
    expires_at_epoch_ms: int = Field(ge=0)
    receiver_device_id: str = Field(min_length=1, max_length=128)
    receiver_device_name: str | None = Field(default=None, max_length=64)


class SessionStateResponse(BaseModel):
    session_id: uuid.UUID
    expires_at_epoch_ms: int
    state: str  # "pending" | "delivered" | "consumed" | "expired"
    envelope: dict | None = None


# ── Helpers ────────────────────────────────────────────────────────────


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _epoch_ms(dt: datetime) -> int:
    return int(dt.timestamp() * 1000)


def _validate_public_key(b64: str) -> None:
    """The public key arrives as base64url. Decode and confirm exactly 32
    raw bytes (X25519). We accept padding-optional input."""
    s = b64.strip()
    # base64.urlsafe_b64decode requires correct padding; pad up.
    pad = "=" * (-len(s) % 4)
    try:
        raw = base64.urlsafe_b64decode(s + pad)
    except (ValueError, TypeError):
        raise HTTPException(status_code=400, detail="receiver_public_key is not valid base64url.")
    if len(raw) != _X25519_KEY_BYTES:
        raise HTTPException(
            status_code=400,
            detail=f"receiver_public_key must decode to {_X25519_KEY_BYTES} bytes (got {len(raw)}).",
        )


def _derive_state(row: TransferSession, now: datetime) -> str:
    if row.consumed_at is not None:
        return "consumed"
    if row.expires_at <= now:
        return "expired"
    if row.envelope_json is not None:
        return "delivered"
    return "pending"


def _row_response(row: TransferSession, *, with_envelope: bool, now: datetime | None = None) -> SessionStateResponse:
    now = now or _now()
    state = _derive_state(row, now)
    return SessionStateResponse(
        session_id=row.id,
        expires_at_epoch_ms=_epoch_ms(row.expires_at),
        state=state,
        envelope=row.envelope_json if (with_envelope and state == "delivered") else None,
    )


def _load_owned_session(db: Session, session_id: uuid.UUID, user_id: uuid.UUID) -> TransferSession:
    """Return the row only if it exists AND belongs to the calling user.
    404 either way to avoid leaking session existence to other users."""
    row = db.query(TransferSession).filter(TransferSession.id == session_id).first()
    if row is None or row.user_id != user_id:
        raise HTTPException(status_code=404, detail="Session not found.")
    return row


# ── Endpoints ──────────────────────────────────────────────────────────


@router.post("/sessions", response_model=SessionStateResponse, status_code=status.HTTP_201_CREATED)
def create_session(
    body: CreateSessionRequest,
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> SessionStateResponse:
    """Create a relay session bound to (user_id, receiver_device_id)."""
    _validate_public_key(body.receiver_public_key)

    now = _now()
    expires_at = datetime.fromtimestamp(body.expires_at_epoch_ms / 1000.0, tz=timezone.utc)
    if expires_at <= now:
        raise HTTPException(status_code=400, detail="expires_at must be in the future.")
    if expires_at - now > timedelta(seconds=_MAX_TTL_SECONDS):
        raise HTTPException(
            status_code=400,
            detail=f"TTL exceeds {_MAX_TTL_SECONDS // 60}-minute maximum.",
        )

    uid = uuid.UUID(user_id)
    row = TransferSession(
        user_id=uid,
        receiver_device_id=body.receiver_device_id,
        receiver_device_name=body.receiver_device_name,
        receiver_public_key=body.receiver_public_key.strip(),
        expires_at=expires_at,
        created_at=now,
    )
    db.add(row)
    db.commit()
    db.refresh(row)

    _log.info(
        "TRANSFER_SESSION_CREATE user=%s session=%s receiver_device=%s ttl_s=%d",
        uid, row.id, row.receiver_device_id, int((expires_at - now).total_seconds()),
    )
    return _row_response(row, with_envelope=False, now=now)


@router.get("/sessions/{session_id}", response_model=SessionStateResponse)
def get_session(
    session_id: uuid.UUID,
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> SessionStateResponse:
    """Return current state. Envelope is included only when state=delivered."""
    uid = uuid.UUID(user_id)
    row = _load_owned_session(db, session_id, uid)
    return _row_response(row, with_envelope=True)


@router.post("/sessions/{session_id}/envelope", status_code=status.HTTP_204_NO_CONTENT)
async def post_envelope(
    session_id: uuid.UUID,
    request: Request,
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> Response:
    """Sender posts the SealedSecretsEnvelope JSON. Body is opaque to the
    server — we only validate shape/size and store for receiver pickup.
    Refuses on expired / consumed / already-delivered with 410."""
    uid = uuid.UUID(user_id)

    raw_body = await request.body()
    if len(raw_body) > _MAX_ENVELOPE_BYTES:
        raise HTTPException(
            status_code=413,
            detail=f"Envelope exceeds {_MAX_ENVELOPE_BYTES} byte limit.",
        )
    if not raw_body:
        raise HTTPException(status_code=400, detail="Envelope body is empty.")
    # Validate JSON shape — must be a top-level object — but otherwise
    # opaque. We never look at the keys.
    import json as _json
    try:
        parsed = _json.loads(raw_body)
    except _json.JSONDecodeError:
        raise HTTPException(status_code=400, detail="Envelope body must be valid JSON.")
    if not isinstance(parsed, dict):
        raise HTTPException(status_code=400, detail="Envelope body must be a JSON object.")

    row = _load_owned_session(db, session_id, uid)
    now = _now()
    state = _derive_state(row, now)
    if state == "consumed":
        raise HTTPException(status_code=410, detail="Session already consumed.")
    if state == "expired":
        raise HTTPException(status_code=410, detail="Session expired.")
    if state == "delivered":
        raise HTTPException(status_code=410, detail="Envelope already delivered.")

    row.envelope_json = parsed
    row.delivered_at = now
    db.commit()

    _log.info(
        "TRANSFER_SESSION_DELIVERED user=%s session=%s bytes=%d",
        uid, row.id, len(raw_body),
    )
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.post("/sessions/{session_id}/consume", response_model=SessionStateResponse)
def consume_session(
    session_id: uuid.UUID,
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> SessionStateResponse:
    """Mark session consumed and clear the envelope. Idempotent on
    already-consumed sessions (returns the same consumed state without
    error). Returns 410 only for expired sessions that were never
    delivered."""
    uid = uuid.UUID(user_id)
    row = _load_owned_session(db, session_id, uid)
    now = _now()
    state = _derive_state(row, now)

    if state == "expired":
        # Session lapsed without consume. Treat as ended.
        raise HTTPException(status_code=410, detail="Session expired.")

    if state != "consumed":
        row.envelope_json = None
        row.consumed_at = now
        db.commit()
        db.refresh(row)
        _log.info("TRANSFER_SESSION_CONSUMED user=%s session=%s", uid, row.id)

    return _row_response(row, with_envelope=False, now=now)
