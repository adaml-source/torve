"""
Admin user-management endpoints. Powers the /app/admin-accounts.html UI.

One aggregate GET endpoint joins user + devices + entitlements + payments
+ lifetime-grant ledger so the UI gets everything it needs in a single
round-trip. Action endpoints are narrow and idempotent where possible.

All endpoints are gated by PADDLE_ADMIN_SECRET via _verify_admin (same
header as the other admin routers). Every authenticated call is logged
at warning level by _verify_admin → ADMIN_CALL audit line.
"""
import hmac as _hmac
import logging
import uuid
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, Header, HTTPException, Query, Request
from pydantic import BaseModel, EmailStr
from sqlalchemy import func, or_
from sqlalchemy.orm import Session

from app.config import settings
from app.deps import get_db
from app.models import (
    Device,
    LifetimeGrantRecord,
    User,
    UserEntitlement,
    WebPayment,
)

_log = logging.getLogger(__name__)

router = APIRouter(prefix="/admin/users", tags=["admin"])


def _verify_admin(request: Request, x_admin_secret: str = Header(None)):
    if not settings.PADDLE_ADMIN_SECRET:
        raise HTTPException(status_code=503, detail="Not configured")
    if not x_admin_secret:
        raise HTTPException(status_code=403, detail="Forbidden")
    if not _hmac.compare_digest(x_admin_secret, settings.PADDLE_ADMIN_SECRET):
        raise HTTPException(status_code=403, detail="Forbidden")
    client_ip = request.headers.get("x-real-ip") or (request.client.host if request.client else None)
    _log.warning("ADMIN_CALL ip=%s method=%s path=%s", client_ip, request.method, request.url.path)


# ── DTO helpers ──────────────────────────────────────────────────────


def _iso(dt: datetime | None) -> str | None:
    return dt.astimezone(timezone.utc).isoformat() if dt else None


def _user_summary(u: User, device_count: int, active_entitlements: int) -> dict:
    return {
        "id": str(u.id),
        "email": u.email,
        "display_name": u.display_name,
        "is_active": u.is_active,
        "is_verified": u.is_verified,
        "has_premium_access": u.has_premium_access,
        "has_lifetime_access": u.has_lifetime_access,
        "device_cap_override": u.device_cap_override,
        "created_at": _iso(u.created_at),
        "updated_at": _iso(u.updated_at),
        "device_count": device_count,
        "active_entitlements": active_entitlements,
    }


def _device_row(d: Device) -> dict:
    return {
        "id": str(d.id),
        "device_type": d.device_type,
        "platform": d.platform,
        "display_name": d.display_name,
        "installation_id": d.installation_id,
        "stable_device_id": d.stable_device_id,
        "app_version": d.app_version,
        "is_active": d.is_active,
        "last_seen_at": _iso(d.last_seen_at),
        "revoked_at": _iso(d.revoked_at),
        "created_at": _iso(d.created_at),
    }


def _entitlement_row(e: UserEntitlement) -> dict:
    return {
        "id": str(e.id),
        "entitlement_type": e.entitlement_type,
        "source": e.source,
        "source_ref": e.source_ref,
        "product_id": e.product_id,
        "status": e.status,
        "granted_at": _iso(e.granted_at),
        "expires_at": _iso(e.expires_at),
        "auto_renew": e.auto_renew,
        "originating_device_id": str(e.originating_device_id) if e.originating_device_id else None,
        "revoked_at": _iso(e.revoked_at),
        "last_verified_at": _iso(e.last_verified_at),
    }


def _payment_row(p: WebPayment) -> dict:
    return {
        "id": str(p.id),
        "paddle_transaction_id": p.paddle_transaction_id,
        "amount": p.amount,
        "currency": p.currency,
        "status": p.status,
        "discount_code": p.discount_code,
        "entitlement_granted": p.entitlement_granted,
        "refunded_at": _iso(p.refunded_at),
        "revoked_at": _iso(p.revoked_at),
        "last_event_type": p.last_event_type,
        "created_at": _iso(p.created_at),
    }


def _grant_row(g: LifetimeGrantRecord) -> dict:
    return {
        "id": str(g.id),
        "email": g.email,
        "source": g.source,
        "source_ref": g.source_ref,
        "product_id": g.product_id,
        "notes": g.notes,
        "granted_at": _iso(g.granted_at),
        "revoked_at": _iso(g.revoked_at),
        "revoke_reason": getattr(g, "revoke_reason", None),
    }


# ── List + search ────────────────────────────────────────────────────


@router.get("", dependencies=[Depends(_verify_admin)])
def list_users(
    q: str = Query(default="", description="Email substring or display-name substring"),
    is_verified: bool | None = Query(default=None),
    has_premium: bool | None = Query(default=None),
    is_active: bool | None = Query(default=None),
    limit: int = Query(default=50, le=200),
    offset: int = Query(default=0, ge=0),
    db: Session = Depends(get_db),
):
    """Paginated user search. Returns lightweight summary rows.

    The full per-user detail (devices/entitlements/payments) is on the
    `GET /{id}` aggregate endpoint, not here — keeps the search list fast.
    """
    base = db.query(User)
    if q:
        needle = f"%{q.lower().strip()}%"
        base = base.filter(or_(User.email.ilike(needle), User.display_name.ilike(needle)))
    if is_verified is not None:
        base = base.filter(User.is_verified == is_verified)
    if has_premium is not None:
        base = base.filter(User.has_premium_access == has_premium)
    if is_active is not None:
        base = base.filter(User.is_active == is_active)

    total = base.count()
    users = (
        base.order_by(User.created_at.desc())
        .offset(offset)
        .limit(limit)
        .all()
    )
    if not users:
        return {"total": total, "users": []}

    user_ids = [u.id for u in users]
    device_counts = dict(
        db.query(Device.user_id, func.count(Device.id))
        .filter(Device.user_id.in_(user_ids), Device.is_active == True)  # noqa: E712
        .group_by(Device.user_id)
        .all()
    )
    ent_counts = dict(
        db.query(UserEntitlement.user_id, func.count(UserEntitlement.id))
        .filter(
            UserEntitlement.user_id.in_(user_ids),
            UserEntitlement.status == "active",
        )
        .group_by(UserEntitlement.user_id)
        .all()
    )
    return {
        "total": total,
        "limit": limit,
        "offset": offset,
        "users": [
            _user_summary(u, device_counts.get(u.id, 0), ent_counts.get(u.id, 0))
            for u in users
        ],
    }


# ── Aggregate detail ─────────────────────────────────────────────────


@router.get("/{user_id}", dependencies=[Depends(_verify_admin)])
def get_user_detail(
    user_id: uuid.UUID,
    db: Session = Depends(get_db),
):
    """One-shot view: user + all devices + all entitlements + recent
    payments + matching lifetime-grant ledger rows. Powers the accounts
    UI's expand panel without a fan-out from the browser."""
    u = db.query(User).filter(User.id == user_id).first()
    if not u:
        raise HTTPException(status_code=404, detail="User not found")

    devices = (
        db.query(Device)
        .filter(Device.user_id == user_id)
        .order_by(Device.last_seen_at.desc())
        .all()
    )
    entitlements = (
        db.query(UserEntitlement)
        .filter(UserEntitlement.user_id == user_id)
        .order_by(UserEntitlement.granted_at.desc())
        .all()
    )
    payments = (
        db.query(WebPayment)
        .filter(WebPayment.user_id == user_id)
        .order_by(WebPayment.created_at.desc())
        .limit(50)
        .all()
    )
    grants = (
        db.query(LifetimeGrantRecord)
        .filter(LifetimeGrantRecord.email == u.email)
        .order_by(LifetimeGrantRecord.granted_at.desc())
        .all()
    )
    return {
        "user": _user_summary(
            u,
            sum(1 for d in devices if d.is_active),
            sum(1 for e in entitlements if e.status == "active"),
        ),
        "devices": [_device_row(d) for d in devices],
        "entitlements": [_entitlement_row(e) for e in entitlements],
        "payments": [_payment_row(p) for p in payments],
        "lifetime_grants": [_grant_row(g) for g in grants],
    }


# ── Mutations ────────────────────────────────────────────────────────


@router.post("/{user_id}/force-verify", dependencies=[Depends(_verify_admin)])
def force_verify(user_id: uuid.UUID, db: Session = Depends(get_db)):
    u = db.query(User).filter(User.id == user_id).first()
    if not u:
        raise HTTPException(status_code=404, detail="User not found")
    u.is_verified = True
    db.commit()
    db.refresh(u)
    return {"ok": True, "is_verified": u.is_verified}


@router.post("/{user_id}/lock", dependencies=[Depends(_verify_admin)])
def lock_user(user_id: uuid.UUID, db: Session = Depends(get_db)):
    """Lock the account (is_active=False). Blocks login (auth flow
    rejects inactive users) and effectively suspends access. Reversible
    via /unlock."""
    u = db.query(User).filter(User.id == user_id).first()
    if not u:
        raise HTTPException(status_code=404, detail="User not found")
    u.is_active = False
    db.commit()
    return {"ok": True, "is_active": False}


@router.post("/{user_id}/unlock", dependencies=[Depends(_verify_admin)])
def unlock_user(user_id: uuid.UUID, db: Session = Depends(get_db)):
    u = db.query(User).filter(User.id == user_id).first()
    if not u:
        raise HTTPException(status_code=404, detail="User not found")
    u.is_active = True
    db.commit()
    return {"ok": True, "is_active": True}


@router.post("/{user_id}/devices/{device_id}/revoke", dependencies=[Depends(_verify_admin)])
def revoke_device(
    user_id: uuid.UUID,
    device_id: uuid.UUID,
    db: Session = Depends(get_db),
):
    """Soft-revoke a single device (is_active=False, revoked_at=now).
    The device row stays for audit; future syncs from that install_id
    won't grant access until the device is recreated by re-login."""
    d = db.query(Device).filter(Device.id == device_id, Device.user_id == user_id).first()
    if not d:
        raise HTTPException(status_code=404, detail="Device not found")
    if d.is_active:
        d.is_active = False
        d.revoked_at = datetime.now(timezone.utc)
        db.commit()
    return {"ok": True, "device": _device_row(d)}


@router.post("/{user_id}/entitlements/{entitlement_id}/revoke", dependencies=[Depends(_verify_admin)])
def revoke_entitlement(
    user_id: uuid.UUID,
    entitlement_id: uuid.UUID,
    reason: str = Query(default="admin_revoked"),
    db: Session = Depends(get_db),
):
    """Revoke a specific entitlement. Sets status=revoked, revoked_at,
    and recomputes the cached has_premium_access / has_lifetime_access
    flags so the UI badges reflect reality without a manual follow-up."""
    from app.billing import recompute_user_premium

    e = (
        db.query(UserEntitlement)
        .filter(UserEntitlement.id == entitlement_id, UserEntitlement.user_id == user_id)
        .first()
    )
    if not e:
        raise HTTPException(status_code=404, detail="Entitlement not found")
    if e.status != "revoked":
        e.status = "revoked"
        e.revoked_at = datetime.now(timezone.utc)
        recompute_user_premium(db, user_id)
        db.commit()
    _log.warning("ADMIN_REVOKE_ENTITLEMENT user=%s ent=%s reason=%s", user_id, entitlement_id, reason)
    return {"ok": True, "entitlement": _entitlement_row(e)}


class GrantLifetimeBody(BaseModel):
    notes: str | None = None
    product_id: str | None = None


@router.post("/{user_id}/grant-lifetime", dependencies=[Depends(_verify_admin)])
def grant_lifetime(
    user_id: uuid.UUID,
    body: GrantLifetimeBody,
    db: Session = Depends(get_db),
):
    """Grant a lifetime entitlement to a user and write a ledger row so
    the grant survives account deletion + re-signup."""
    from app.billing import (
        ENTITLEMENT_LIFETIME,
        SOURCE_ADMIN,
        _record_lifetime_grant,
        grant_entitlement,
    )
    u = db.query(User).filter(User.id == user_id).first()
    if not u:
        raise HTTPException(status_code=404, detail="User not found")
    source_ref = f"admin-portal:{datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S')}"
    ent = grant_entitlement(
        db,
        user_id=u.id,
        source=SOURCE_ADMIN,
        source_ref=source_ref,
        entitlement_type=ENTITLEMENT_LIFETIME,
        product_id=body.product_id,
    )
    _record_lifetime_grant(
        db, email=u.email, source=SOURCE_ADMIN, source_ref=source_ref,
        product_id=body.product_id, notes=body.notes,
    )
    db.commit()
    return {"ok": True, "entitlement_id": str(ent.id) if ent else None}


class GrantTemporaryBody(BaseModel):
    duration_days: int | None = None
    expires_at: str | None = None  # ISO-8601, e.g. 2026-05-31T23:59:59Z
    notes: str | None = None
    product_id: str | None = None


@router.post("/{user_id}/grant-temporary", dependencies=[Depends(_verify_admin)])
def grant_temporary(
    user_id: uuid.UUID,
    body: GrantTemporaryBody,
    db: Session = Depends(get_db),
):
    """Grant a time-limited subscription entitlement. Use for trials,
    comped access, refunds-as-credit. auto_renew=False so the row simply
    expires at expires_at — no renewal pings, no Paddle/store coupling.

    Provide either duration_days OR expires_at, not both. Lifetime grants
    use the separate /grant-lifetime endpoint."""
    from app.billing import (
        ENTITLEMENT_SUBSCRIPTION,
        SOURCE_ADMIN,
        grant_entitlement,
    )
    if (body.duration_days is None) == (body.expires_at is None):
        raise HTTPException(
            status_code=400,
            detail="Provide exactly one of duration_days or expires_at.",
        )
    u = db.query(User).filter(User.id == user_id).first()
    if not u:
        raise HTTPException(status_code=404, detail="User not found")

    now = datetime.now(timezone.utc)
    if body.expires_at:
        try:
            expires = datetime.fromisoformat(body.expires_at.replace("Z", "+00:00"))
        except ValueError:
            raise HTTPException(status_code=400, detail="Invalid expires_at; use ISO-8601.")
        if expires.tzinfo is None:
            expires = expires.replace(tzinfo=timezone.utc)
    else:
        if body.duration_days <= 0:
            raise HTTPException(status_code=400, detail="duration_days must be positive.")
        expires = now + timedelta(days=body.duration_days)

    if expires <= now:
        raise HTTPException(status_code=400, detail="expires_at must be in the future.")

    source_ref = f"admin-portal-temp:{now.strftime('%Y%m%d%H%M%S%f')}"
    ent = grant_entitlement(
        db,
        user_id=u.id,
        source=SOURCE_ADMIN,
        source_ref=source_ref,
        entitlement_type=ENTITLEMENT_SUBSCRIPTION,
        expires_at=expires,
        auto_renew=False,
        product_id=body.product_id,
    )
    db.commit()
    _log.warning(
        "ADMIN_GRANT_TEMPORARY user=%s expires=%s notes=%s",
        user_id, expires.isoformat(), body.notes or "",
    )
    return {
        "ok": True,
        "entitlement_id": str(ent.id) if ent else None,
        "expires_at": expires.isoformat(),
    }


@router.post("/{user_id}/refresh-flags", dependencies=[Depends(_verify_admin)])
def refresh_premium_flags(user_id: uuid.UUID, db: Session = Depends(get_db)):
    """Recompute has_lifetime_access / has_premium_access from the
    canonical UserEntitlement table. Useful after a manual revoke or
    when the cached booleans drift.

    Calls recompute_user_premium directly (not check_premium_active)
    because the latter trusts cached has_lifetime_access=True without
    verifying — so if the cache was stale-True, it would stay True. The
    whole point of this endpoint is to force-rebuild the cache from
    entitlements."""
    from app.billing import recompute_user_premium

    u = db.query(User).filter(User.id == user_id).first()
    if not u:
        raise HTTPException(status_code=404, detail="User not found")
    recompute_user_premium(db, u.id)
    db.commit()
    db.refresh(u)
    return {
        "ok": True,
        "has_premium_access": u.has_premium_access,
        "has_lifetime_access": u.has_lifetime_access,
    }
