"""Server-owned Family policy. Secrets and recovery proofs never enter account settings."""
import hashlib
import hmac
import json
import re
import secrets
import uuid
from datetime import datetime, timedelta, timezone

from fastapi import HTTPException, Request
from jose import JWTError, jwt
from pydantic import BaseModel, ConfigDict, Field, field_validator
from sqlalchemy import Boolean, DateTime, ForeignKey, Integer, JSON, String, Text
from sqlalchemy.orm import Mapped, Session, mapped_column

from app.config import settings
from app.database import Base
from app.models import User
from app.security import hash_password, verify_password


class FamilyAccountPolicy(Base):
    __tablename__ = "family_account_policies"
    user_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), primary_key=True)
    enabled: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    kids: Mapped[dict] = mapped_column(JSON, default=dict, nullable=False)
    version: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    pin_hash: Mapped[str | None] = mapped_column(Text)
    pin_revision: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    failures: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    locked_until: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    recovery_hash: Mapped[str | None] = mapped_column(String(64))
    recovery_expires: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    recovery_sent: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    recovery_failures: Mapped[int] = mapped_column(Integer, default=0, nullable=False)


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


PROFILE = re.compile(r"^[A-Za-z0-9_-]{1,40}$")


class KidsRules(StrictModel):
    maximumAge: int = Field(default=12, ge=0, le=17)
    ratingCountry: str = "US"
    hideUnrated: bool = True
    allowedSources: list[str] = Field(default_factory=list, max_length=200)
    blockedTitles: list[str] = Field(default_factory=list, max_length=1000)

    @field_validator("ratingCountry")
    @classmethod
    def country(cls, value):
        if value not in {"US", "DE", "GB", "FR", "AU"}: raise ValueError("Unsupported rating country")
        return value

    @field_validator("allowedSources", "blockedTitles")
    @classmethod
    def identifiers(cls, values, info):
        pattern = (r"^(catalogue|downloads|extension:[A-Za-z0-9_.-]+|jellyfin:[A-Za-z0-9_-]+|channels:[^\r\n]+)$"
                   if info.field_name == "allowedSources" else r"^(MOVIE|SERIES):(tt[0-9]+|[0-9]+)$")
        if any(len(v) > 250 or "://" in v or not re.fullmatch(pattern, v) for v in values): raise ValueError("Unsupported identifier")
        return sorted(set(values))


class PolicyConfiguration(StrictModel):
    enabled: bool = False
    accountId: str = ""
    kids: dict[str, KidsRules] = Field(default_factory=dict, max_length=8)
    version: int = Field(default=0, ge=0)

    @field_validator("kids")
    @classmethod
    def profiles(cls, value):
        if any(not PROFILE.fullmatch(key) for key in value): raise ValueError("Unsupported profile")
        return value


class PolicyUpdate(StrictModel):
    configuration: PolicyConfiguration
    expectedVersion: int = Field(ge=0)
    pin: str | None = Field(default=None, pattern=r"^[0-9]{4,6}$")


class PinProof(StrictModel):
    pin: str = Field(pattern=r"^[0-9]{4,6}$")


class RecoveryProof(PinProof):
    code: str = Field(pattern=r"^[0-9]{6}$")


def now(): return datetime.now(timezone.utc)
def aware(value): return value.replace(tzinfo=timezone.utc) if value and value.tzinfo is None else value


def locked_policy(db: Session, uid: uuid.UUID) -> FamilyAccountPolicy:
    # The owner lock serializes first creation as well as PIN attempts and CAS writes.
    user = db.query(User).filter(User.id == uid, User.is_active == True).with_for_update().first()
    if user is None: raise HTTPException(401, "Account unavailable")
    row = db.get(FamilyAccountPolicy, uid)
    if row is None:
        row = FamilyAccountPolicy(user_id=uid); db.add(row); db.flush()
    return row


def configuration(row):
    return {"enabled": row.enabled, "accountId": str(row.user_id), "kids": row.kids or {}, "version": row.version}


def snapshot(row):
    return {"configuration": configuration(row), "pinRevision": row.pin_revision}


def token_binding(request):
    return hashlib.sha256(request.headers.get("X-Torve-Installation-Id", request.headers.get("authorization", "")).encode()).hexdigest()


def parent_grant(row, request):
    expires = now() + timedelta(hours=12)
    token = jwt.encode({"sub": str(row.user_id), "aud": "torve-family-parent", "exp": expires,
                        "revision": row.pin_revision, "manageUntil": int((now() + timedelta(minutes=5)).timestamp()), "session": token_binding(request)}, settings.JWT_SECRET, algorithm=settings.JWT_ALGORITHM)
    return {**snapshot(row), "grant": token, "expiresAt": int(expires.timestamp() * 1000)}


def has_parent(row, request, manage=True):
    try:
        proof = jwt.decode(request.headers.get("X-Torve-Parent-Grant", ""), settings.JWT_SECRET,
                           algorithms=[settings.JWT_ALGORITHM], audience="torve-family-parent")
        return (proof.get("sub") == str(row.user_id) and proof.get("revision") == row.pin_revision
                and (not manage or proof.get("manageUntil", 0) > now().timestamp())
                and hmac.compare_digest(proof.get("session", ""), token_binding(request)))
    except (JWTError, TypeError, ValueError): return False


def require_parent(row, request):
    if not has_parent(row, request): raise HTTPException(403, "Verify the parent PIN to change profiles or connections")


def verify_pin(db, row, pin):
    if aware(row.locked_until) and aware(row.locked_until) > now(): raise HTTPException(429, "Too many attempts. Try again in a minute")
    if not row.pin_hash or not verify_password(pin, row.pin_hash):
        row.failures += 1
        if row.failures >= 5: row.locked_until = now() + timedelta(seconds=60); row.failures = 0
        db.commit()
        raise HTTPException(403, "Incorrect parent PIN")
    row.failures = 0; row.locked_until = None


def family_row(db, uid): return db.get(FamilyAccountPolicy, uuid.UUID(str(uid)))


def require_parent_when_enabled(db, uid, request):
    row = family_row(db, uid)
    if row and row.enabled: require_parent(row, request)


def require_adult_backend_access(request: Request, user_id: str, db: Session):
    # Server resolvers cannot prove the certification of arbitrary NZB/stream URLs.
    # Until a trusted title proof is available they require a parent grant.
    row = family_row(db, user_id)
    if row and row.enabled and not has_parent(row, request, manage=False): raise HTTPException(403, "Unlock an adult profile to use this resolver")


def guard_profile_settings(db, uid, request, incoming):
    row = family_row(db, uid)
    if row and row.enabled and any(k.startswith("household_v1:") and ":profile:" in k for k in incoming):
        require_parent(row, request)


def recovery_digest(uid, code, email):
    return hmac.new(settings.JWT_SECRET.encode(), f"family-recovery:{uid}:{email.strip().lower()}:{code}".encode(), hashlib.sha256).hexdigest()


def can_read_integration(db, uid, request, integration):
    row = family_row(db, uid)
    if not row or not row.enabled or has_parent(row, request, manage=False): return True
    profile = request.headers.get("X-Torve-Profile-Id", "")
    return (profile in row.kids and integration.integration_type.endswith("::p:" + profile)
            and (integration.config or {}).get("family_copy_profile") == profile)


def guard_integration_read(db, uid, request, integration):
    if not can_read_integration(db, uid, request, integration): raise HTTPException(403, "Parent verification required for this connection")


def canonical_connection(db, uid, row):
    from app.models import UserIntegration
    seen = set()
    while (row.config or {}).get("shared_connection_origin"):
        if row.integration_type in seen: raise HTTPException(409, "Reconnect this connection")
        seen.add(row.integration_type)
        target = (row.config or {})["shared_connection_origin"]
        row = db.query(UserIntegration).filter_by(user_id=uid, integration_type=target).first()
        if row is None: raise HTTPException(409, "The shared connection was removed. Copy it again")
    return row
