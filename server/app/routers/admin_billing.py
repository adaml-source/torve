"""
Admin billing reconciliation endpoints.

Protected by the same admin secret as promo endpoints.
Provides visibility into payments, entitlements, and unlinked transactions.
"""
import logging

from fastapi import APIRouter, Depends, Header, HTTPException, Query, Request
from sqlalchemy.orm import Session

from app.config import settings
from app.deps import get_db
from app.models import LifetimeGrantRecord, User, UserEntitlement, WebPayment
from datetime import datetime, timezone
from pydantic import BaseModel, EmailStr
import uuid

_log = logging.getLogger(__name__)

router = APIRouter(prefix="/admin/billing", tags=["admin"])


def _verify_admin(request: Request, x_admin_secret: str = Header(None)):
    if not settings.PADDLE_ADMIN_SECRET:
        raise HTTPException(status_code=503, detail="Not configured")
    if not x_admin_secret:
        raise HTTPException(status_code=403, detail="Forbidden")
    import hmac
    if not hmac.compare_digest(x_admin_secret, settings.PADDLE_ADMIN_SECRET):
        raise HTTPException(status_code=403, detail="Forbidden")
    client_ip = request.headers.get("x-real-ip") or (request.client.host if request.client else None)
    _log.warning("ADMIN_CALL ip=%s method=%s path=%s", client_ip, request.method, request.url.path)


@router.get("/payments", dependencies=[Depends(_verify_admin)])
def list_payments(
    status: str = Query(default=None),
    unlinked: bool = Query(default=False),
    limit: int = Query(default=50, le=200),
    db: Session = Depends(get_db),
):
    """List recent web payments with filters for reconciliation."""
    q = db.query(WebPayment).order_by(WebPayment.created_at.desc())

    if status:
        q = q.filter(WebPayment.status == status)
    if unlinked:
        q = q.filter(WebPayment.user_id == None)  # noqa: E711

    rows = q.limit(limit).all()
    return [
        {
            "id": str(r.id),
            "paddle_transaction_id": r.paddle_transaction_id,
            "user_id": str(r.user_id) if r.user_id else None,
            "product_id": r.product_id,
            "price_id": r.price_id,
            "amount": r.amount,
            "currency": r.currency,
            "discount_code": r.discount_code,
            "status": r.status,
            "entitlement_granted": r.entitlement_granted,
            "refunded_at": r.refunded_at.isoformat() if r.refunded_at else None,
            "created_at": r.created_at.isoformat(),
            "updated_at": r.updated_at.isoformat(),
        }
        for r in rows
    ]


@router.get("/entitlements", dependencies=[Depends(_verify_admin)])
def list_entitlements(
    status: str = Query(default=None),
    limit: int = Query(default=50, le=200),
    db: Session = Depends(get_db),
):
    """List entitlement records for reconciliation."""
    q = db.query(UserEntitlement).order_by(UserEntitlement.created_at.desc())
    if status:
        q = q.filter(UserEntitlement.status == status)
    rows = q.limit(limit).all()
    return [
        {
            "id": str(r.id),
            "user_id": str(r.user_id),
            "entitlement_type": r.entitlement_type,
            "source": r.source,
            "source_ref": r.source_ref,
            "status": r.status,
            "granted_at": r.granted_at.isoformat(),
            "revoked_at": r.revoked_at.isoformat() if r.revoked_at else None,
        }
        for r in rows
    ]


@router.get("/reconcile", dependencies=[Depends(_verify_admin)])
def reconciliation_report(db: Session = Depends(get_db)):
    """Quick reconciliation summary."""
    total_payments = db.query(WebPayment).count()
    completed = db.query(WebPayment).filter(WebPayment.status == "completed").count()
    refunded = db.query(WebPayment).filter(WebPayment.status.in_(["refunded", "reversed"])).count()
    unlinked = db.query(WebPayment).filter(WebPayment.user_id == None).count()  # noqa: E711
    granted = db.query(WebPayment).filter(WebPayment.entitlement_granted == True).count()  # noqa: E712

    active_ents = db.query(UserEntitlement).filter(UserEntitlement.status == "active").count()
    revoked_ents = db.query(UserEntitlement).filter(UserEntitlement.status == "revoked").count()

    # Mismatch detection: payments completed but no entitlement granted
    from app.models import User
    paid_no_entitlement = db.query(WebPayment).filter(
        WebPayment.status == "completed",
        WebPayment.entitlement_granted == False,  # noqa: E712
        WebPayment.user_id != None,  # noqa: E711
    ).count()

    # Users with has_lifetime_access=True but no active entitlement record
    users_with_flag = db.query(User).filter(User.has_lifetime_access == True).count()  # noqa: E712
    boolean_entitlement_mismatch = abs(users_with_flag - active_ents)

    # Failed verification attempts (abuse signal)
    failed_verify = db.query(WebPayment).filter(
        WebPayment.status.in_(["verification_failed", "rejected_product", "product_mismatch_rvs"])
    ).count()

    return {
        "payments": {
            "total": total_payments,
            "completed": completed,
            "refunded": refunded,
            "unlinked": unlinked,
            "with_entitlement": granted,
            "paid_no_entitlement": paid_no_entitlement,
            "failed_verification_attempts": failed_verify,
        },
        "entitlements": {
            "active": active_ents,
            "revoked": revoked_ents,
        },
        "anomalies": {
            "users_with_access_flag": users_with_flag,
            "boolean_entitlement_mismatch": boolean_entitlement_mismatch,
            "paid_but_not_unlocked": paid_no_entitlement,
        },
    }


@router.post("/prune-stale-devices", dependencies=[Depends(_verify_admin)])
def prune_stale_devices_endpoint(
    stale_days: int = Query(default=90, ge=30),
    dry_run: bool = Query(default=True),
    db: Session = Depends(get_db),
):
    """Revoke devices not seen in stale_days. Use dry_run=true to preview."""
    from app.billing import prune_stale_devices
    results = prune_stale_devices(db, stale_days=stale_days, dry_run=dry_run)
    return {
        "dry_run": dry_run,
        "stale_days": stale_days,
        "affected": len(results),
        "devices": results,
    }


# ── Lifetime grant ledger (persistent, survives account delete) ────────

class LifetimeGrantAddRequest(BaseModel):
    email: EmailStr
    source_ref: str
    product_id: str | None = None
    notes: str | None = None


def _grant_row_out(r: LifetimeGrantRecord) -> dict:
    return {
        "id": str(r.id),
        "email": r.email,
        "source": r.source,
        "source_ref": r.source_ref,
        "product_id": r.product_id,
        "granted_at": r.granted_at.isoformat() if r.granted_at else None,
        "revoked_at": r.revoked_at.isoformat() if r.revoked_at else None,
        "revoke_reason": r.revoke_reason,
        "notes": r.notes,
    }


@router.get("/lifetime-grants", dependencies=[Depends(_verify_admin)])
def list_lifetime_grants(
    email: str = Query(default=None),
    include_revoked: bool = Query(default=True),
    limit: int = Query(default=100, le=500),
    db: Session = Depends(get_db),
):
    """Browse the persistent lifetime-grant ledger.

    Use email=foo@bar to check whether a specific address has a past
    grant (e.g. a support request from a deleted user who wants their
    lifetime restored).
    """
    q = db.query(LifetimeGrantRecord).order_by(LifetimeGrantRecord.granted_at.desc())
    if email:
        q = q.filter(LifetimeGrantRecord.email == email.lower().strip())
    if not include_revoked:
        q = q.filter(LifetimeGrantRecord.revoked_at.is_(None))
    rows = q.limit(limit).all()
    return {"count": len(rows), "grants": [_grant_row_out(r) for r in rows]}


@router.post("/lifetime-grants", dependencies=[Depends(_verify_admin)])
def add_lifetime_grant(
    body: LifetimeGrantAddRequest,
    db: Session = Depends(get_db),
):
    """Manually record a lifetime grant for an email — used when restoring
    a customer's lost account or honoring an external purchase proof.

    If a user with that email currently exists, they also get the
    entitlement applied immediately. Otherwise the ledger row alone is
    enough: on verified re-signup, restore_lifetime_if_granted picks it up.
    """
    from app.billing import (
        ENTITLEMENT_LIFETIME,
        SOURCE_ADMIN,
        grant_entitlement,
        _record_lifetime_grant,
    )
    email = body.email.lower().strip()
    source_ref = body.source_ref or f"admin-manual:{datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S')}"

    user = db.query(User).filter(User.email == email).first()
    if user:
        ent = grant_entitlement(
            db,
            user_id=user.id,
            source=SOURCE_ADMIN,
            source_ref=source_ref,
            entitlement_type=ENTITLEMENT_LIFETIME,
            product_id=body.product_id,
        )
        db.commit()
        return {"ok": True, "user_existed": True, "entitlement_id": str(ent.id) if ent else None}

    # No current user — just write the ledger row so re-signup restores.
    row = _record_lifetime_grant(
        db, email=email, source=SOURCE_ADMIN, source_ref=source_ref,
        product_id=body.product_id, notes=body.notes,
    )
    db.commit()
    return {"ok": True, "user_existed": False, "grant_id": str(row.id) if row else None}


@router.delete("/lifetime-grants/{grant_id}", dependencies=[Depends(_verify_admin)])
def revoke_lifetime_grant(
    grant_id: uuid.UUID,
    reason: str = Query(default="admin_revoked"),
    db: Session = Depends(get_db),
):
    """Soft-revoke a ledger row. Doesn't delete it (so the history stays
    visible); just marks it revoked so restore logic skips it."""
    row = db.query(LifetimeGrantRecord).filter(LifetimeGrantRecord.id == grant_id).first()
    if not row:
        raise HTTPException(status_code=404, detail="Grant not found")
    if row.revoked_at is None:
        row.revoked_at = datetime.now(timezone.utc)
        row.revoke_reason = reason
        db.commit()
    return _grant_row_out(row)


@router.post("/lifetime-grants/purge", dependencies=[Depends(_verify_admin)])
def purge_lifetime_grants_for_email(
    email: str = Query(...),
    db: Session = Depends(get_db),
):
    """HARD-DELETE every ledger row for an email — for GDPR right-to-be-
    forgotten requests only. Normal account deletion does NOT touch these
    rows; this endpoint is the only way to erase them.
    """
    email_norm = email.lower().strip()
    deleted = db.query(LifetimeGrantRecord).filter(
        LifetimeGrantRecord.email == email_norm,
    ).delete(synchronize_session=False)
    db.commit()
    _log.warning("LIFETIME_GRANT_PURGE email=%s deleted=%d", email_norm, deleted)
    return {"ok": True, "email": email_norm, "deleted": deleted}
