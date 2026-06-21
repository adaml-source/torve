"""Rebate code endpoints: user redemption and admin management."""
import hmac as _hmac
import logging
import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, Header, HTTPException, Query, Request, status
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from app.config import settings
from app.deps import get_current_user_id, get_db
from app.models import RebateCode, RebateRedemption
from app.rebate_service import check_admin_ip, create_rebate_codes, redeem_code, revoke_code

_log = logging.getLogger(__name__)

# ── Schemas ───────────────────────────────────────────────────────────────

class RedeemRequest(BaseModel):
    code: str = Field(min_length=10, max_length=50)


class RedeemResponse(BaseModel):
    success: bool
    error_code: str | None = None
    message: str


class AdminCreateRequest(BaseModel):
    count: int = Field(default=1, ge=1, le=500)
    campaign_name: str | None = Field(default=None, max_length=255)
    expires_at: str | None = None  # ISO8601
    allowed_email: str | None = Field(default=None, max_length=255)
    allowed_email_domain: str | None = Field(default=None, max_length=255)
    note: str | None = Field(default=None, max_length=500)
    # Historical campaign metadata only. Redemption no longer grants access.
    grant_duration_days: int | None = Field(default=None, ge=1, le=3650)


class AdminRevokeRequest(BaseModel):
    reason: str | None = Field(default=None, max_length=500)


# ── User endpoint ─────────────────────────────────────────────────────────

user_router = APIRouter(prefix="/me/rebate-codes", tags=["rebate"])


@user_router.post("/redeem", response_model=RedeemResponse)
def redeem_rebate_code(
    body: RedeemRequest,
    request: Request,
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> RedeemResponse:
    """Record a Torve rebate code redemption without changing access."""
    uid = uuid.UUID(user_id)
    source_ip = request.headers.get("x-real-ip") or (request.client.host if request.client else None)
    user_agent = request.headers.get("user-agent")

    result = redeem_code(db, uid, body.code, source_ip=source_ip, user_agent=user_agent)

    if not result.success:
        return RedeemResponse(
            success=False,
            error_code=result.error_code,
            message=result.message,
        )

    return RedeemResponse(
        success=True,
        error_code=result.error_code,
        message=result.message,
    )


# ── Admin endpoints ───────────────────────────────────────────────────────

admin_router = APIRouter(prefix="/admin/rebate-codes", tags=["admin-rebate"])


def _verify_admin(request: Request, x_admin_secret: str = Header(None)):
    """Verify admin secret + optional IP allowlist."""
    if not settings.PADDLE_ADMIN_SECRET:
        raise HTTPException(status_code=503, detail="Not configured")
    if not x_admin_secret:
        raise HTTPException(status_code=403, detail="Forbidden")
    if not _hmac.compare_digest(x_admin_secret, settings.PADDLE_ADMIN_SECRET):
        raise HTTPException(status_code=403, detail="Forbidden")
    # Optional IP allowlist (reads X-Real-IP from trusted proxy, falls back to client host)
    client_ip = request.headers.get("x-real-ip") or (request.client.host if request.client else None)
    if not check_admin_ip(client_ip):
        _log.warning("ADMIN_IP_DENIED ip=%s path=%s", client_ip, request.url.path)
        raise HTTPException(status_code=403, detail="Forbidden")
    _log.warning("ADMIN_CALL ip=%s method=%s path=%s", client_ip, request.method, request.url.path)


@admin_router.post("", dependencies=[Depends(_verify_admin)])
def admin_create_codes(
    body: AdminCreateRequest,
    db: Session = Depends(get_db),
):
    """Create rebate codes. Raw codes are returned only in this response."""
    expires_at = None
    if body.expires_at:
        try:
            expires_at = datetime.fromisoformat(body.expires_at.replace("Z", "+00:00"))
        except ValueError:
            raise HTTPException(status_code=400, detail="Invalid expires_at format")

    pairs = create_rebate_codes(
        db,
        count=body.count,
        campaign_name=body.campaign_name,
        expires_at=expires_at,
        allowed_email=body.allowed_email,
        allowed_email_domain=body.allowed_email_domain,
        note=body.note,
        grant_duration_days=body.grant_duration_days,
        created_by="admin",
    )
    db.commit()

    return {
        "created": len(pairs),
        "codes": [
            {
                "id": str(row.id),
                "raw_code": raw,
                "code_prefix": row.code_prefix,
                "campaign_name": row.campaign_name,
                "expires_at": row.expires_at.isoformat() if row.expires_at else None,
                "allowed_email": row.allowed_email,
                "allowed_email_domain": row.allowed_email_domain,
                "grant_duration_days": row.grant_duration_days,
            }
            for raw, row in pairs
        ],
    }


@admin_router.get("", dependencies=[Depends(_verify_admin)])
def admin_list_codes(
    db: Session = Depends(get_db),
    campaign: str = Query(default=None),
    status_filter: str = Query(default=None, alias="status"),
    limit: int = Query(default=50, ge=1, le=200),
    offset: int = Query(default=0, ge=0),
):
    """List rebate codes. Never returns raw code values."""
    q = db.query(RebateCode).order_by(RebateCode.created_at.desc())

    if campaign:
        q = q.filter(RebateCode.campaign_name == campaign)
    if status_filter == "redeemed":
        q = q.filter(RebateCode.redeemed_count > 0)
    elif status_filter == "unredeemed":
        q = q.filter(RebateCode.redeemed_count == 0, RebateCode.revoked_at == None)  # noqa: E711
    elif status_filter == "revoked":
        q = q.filter(RebateCode.revoked_at != None)  # noqa: E711

    total = q.count()
    rows = q.offset(offset).limit(limit).all()

    return {
        "total": total,
        "codes": [
            {
                "id": str(r.id),
                "code_prefix": r.code_prefix,
                "campaign_name": r.campaign_name,
                "note": r.note,
                "redeemed_count": r.redeemed_count,
                "max_redemptions": r.max_redemptions,
                "allowed_email": r.allowed_email,
                "allowed_email_domain": r.allowed_email_domain,
                "expires_at": r.expires_at.isoformat() if r.expires_at else None,
                "revoked_at": r.revoked_at.isoformat() if r.revoked_at else None,
                "revoked_reason": r.revoked_reason,
                "grant_duration_days": r.grant_duration_days,
                "created_at": r.created_at.isoformat(),
            }
            for r in rows
        ],
    }


@admin_router.get("/{code_id}", dependencies=[Depends(_verify_admin)])
def admin_code_detail(
    code_id: uuid.UUID,
    db: Session = Depends(get_db),
):
    """Get detail for a specific rebate code including redemption info."""
    code_row = db.query(RebateCode).filter(RebateCode.id == code_id).first()
    if not code_row:
        raise HTTPException(status_code=404, detail="Not found")

    redemptions = db.query(RebateRedemption).filter(
        RebateRedemption.rebate_code_id == code_id
    ).order_by(RebateRedemption.redeemed_at.desc()).all()

    return {
        "id": str(code_row.id),
        "code_prefix": code_row.code_prefix,
        "campaign_name": code_row.campaign_name,
        "note": code_row.note,
        "created_by": code_row.created_by,
        "redeemed_count": code_row.redeemed_count,
        "max_redemptions": code_row.max_redemptions,
        "allowed_email": code_row.allowed_email,
        "allowed_email_domain": code_row.allowed_email_domain,
        "expires_at": code_row.expires_at.isoformat() if code_row.expires_at else None,
        "revoked_at": code_row.revoked_at.isoformat() if code_row.revoked_at else None,
        "revoked_reason": code_row.revoked_reason,
        "grant_duration_days": code_row.grant_duration_days,
        "created_at": code_row.created_at.isoformat(),
        "redemptions": [
            {
                "id": str(r.id),
                "user_id": str(r.user_id),
                "redeemed_at": r.redeemed_at.isoformat(),
                "result_status": r.result_status,
            }
            for r in redemptions
        ],
    }


@admin_router.post("/{code_id}/revoke", dependencies=[Depends(_verify_admin)])
def admin_revoke_code(
    code_id: uuid.UUID,
    body: AdminRevokeRequest,
    db: Session = Depends(get_db),
):
    """Revoke a rebate code."""
    result = revoke_code(db, code_id, body.reason)
    if not result:
        raise HTTPException(status_code=404, detail="Not found")
    return {
        "id": str(result.id),
        "code_prefix": result.code_prefix,
        "revoked_at": result.revoked_at.isoformat(),
        "revoked_reason": result.revoked_reason,
    }
