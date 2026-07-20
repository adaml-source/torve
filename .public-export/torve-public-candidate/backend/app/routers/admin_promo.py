"""
Admin endpoints for managing Paddle promo/rebate codes.

Protected by a shared admin secret (PADDLE_ADMIN_SECRET).
These create Paddle discount codes via the API and store
internal audit records.
"""
import logging
import secrets
import string
import uuid
from datetime import datetime, timezone

import httpx
from fastapi import APIRouter, Depends, HTTPException, Header, Request, status
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from app.config import settings
from app.deps import get_db
from app.models import PromoCodeAudit

_log = logging.getLogger(__name__)

router = APIRouter(prefix="/admin/promo", tags=["admin"])

PADDLE_API_BASE = "https://api.paddle.com" if settings.PADDLE_ENVIRONMENT == "production" else "https://sandbox-api.paddle.com"


def _verify_admin(request: Request, x_admin_secret: str = Header(None)):
    """Verify admin secret header using constant-time comparison."""
    if not settings.PADDLE_ADMIN_SECRET:
        raise HTTPException(status_code=503, detail="Not configured")
    if not x_admin_secret:
        raise HTTPException(status_code=403, detail="Forbidden")
    import hmac as _hmac
    if not _hmac.compare_digest(x_admin_secret, settings.PADDLE_ADMIN_SECRET):
        raise HTTPException(status_code=403, detail="Forbidden")
    client_ip = request.headers.get("x-real-ip") or (request.client.host if request.client else None)
    _log.warning("ADMIN_CALL ip=%s method=%s path=%s", client_ip, request.method, request.url.path)


def _generate_code(length=8):
    chars = string.ascii_uppercase + string.digits
    return "".join(secrets.choice(chars) for _ in range(length))


class CreatePromoRequest(BaseModel):
    discount_percent: int = Field(default=100, ge=1, le=100)
    intended_for: str | None = None
    internal_note: str | None = None
    expires_at: str | None = None  # ISO datetime
    code: str | None = None  # Custom code, or auto-generate


class BatchPromoRequest(BaseModel):
    count: int = Field(ge=1, le=50)
    discount_percent: int = Field(default=100, ge=1, le=100)
    internal_note: str | None = None
    expires_at: str | None = None


@router.post("/create", dependencies=[Depends(_verify_admin)])
def create_promo_code(body: CreatePromoRequest, db: Session = Depends(get_db)):
    """Create a single Paddle discount code and store audit record."""
    code = body.code or _generate_code()
    discount_id = None

    # Create on Paddle if API key is configured
    if settings.PADDLE_API_KEY:
        discount_id = _create_paddle_discount(
            code=code,
            percent=body.discount_percent,
            expires_at=body.expires_at,
        )

    # Parse expiry
    expires = None
    if body.expires_at:
        try:
            expires = datetime.fromisoformat(body.expires_at.replace("Z", "+00:00"))
        except ValueError:
            pass

    audit = PromoCodeAudit(
        paddle_discount_id=discount_id,
        code=code,
        discount_type="percentage",
        discount_amount=str(body.discount_percent),
        usage_limit=1,
        intended_for=body.intended_for,
        internal_note=body.internal_note,
        expires_at=expires,
    )
    db.add(audit)
    db.commit()
    db.refresh(audit)

    _log.info("Promo code created: %s (paddle_id=%s, for=%s)", code, discount_id, body.intended_for)

    return {
        "code": code,
        "discount_percent": body.discount_percent,
        "paddle_discount_id": discount_id,
        "intended_for": body.intended_for,
        "expires_at": body.expires_at,
    }


@router.post("/batch", dependencies=[Depends(_verify_admin)])
def create_batch_codes(body: BatchPromoRequest, db: Session = Depends(get_db)):
    """Generate N single-use promo codes."""
    codes = []
    for i in range(body.count):
        code = _generate_code()
        discount_id = None

        if settings.PADDLE_API_KEY:
            try:
                discount_id = _create_paddle_discount(
                    code=code,
                    percent=body.discount_percent,
                    expires_at=body.expires_at,
                )
            except Exception as e:
                _log.error("Failed to create Paddle discount for code %s: %s", code, e)
                continue

        expires = None
        if body.expires_at:
            try:
                expires = datetime.fromisoformat(body.expires_at.replace("Z", "+00:00"))
            except ValueError:
                pass

        audit = PromoCodeAudit(
            paddle_discount_id=discount_id,
            code=code,
            discount_type="percentage",
            discount_amount=str(body.discount_percent),
            usage_limit=1,
            internal_note=body.internal_note or f"Batch {i+1}/{body.count}",
            expires_at=expires,
        )
        db.add(audit)
        codes.append({"code": code, "paddle_discount_id": discount_id})

    db.commit()
    _log.info("Batch created: %d codes at %d%% discount", len(codes), body.discount_percent)
    return {"created": len(codes), "codes": codes}


@router.get("/list", dependencies=[Depends(_verify_admin)])
def list_codes(db: Session = Depends(get_db)):
    """List all issued promo codes."""
    rows = db.query(PromoCodeAudit).order_by(PromoCodeAudit.created_at.desc()).all()
    return [
        {
            "id": str(r.id),
            "code": r.code,
            "discount_type": r.discount_type,
            "discount_amount": r.discount_amount,
            "intended_for": r.intended_for,
            "internal_note": r.internal_note,
            "is_active": r.is_active,
            "paddle_discount_id": r.paddle_discount_id,
            "expires_at": r.expires_at.isoformat() if r.expires_at else None,
            "created_at": r.created_at.isoformat(),
        }
        for r in rows
    ]


@router.post("/{code_id}/disable", dependencies=[Depends(_verify_admin)])
def disable_code(code_id: str, db: Session = Depends(get_db)):
    """Disable a promo code."""
    row = db.query(PromoCodeAudit).filter(PromoCodeAudit.id == uuid.UUID(code_id)).first()
    if not row:
        raise HTTPException(status_code=404, detail="Code not found")
    row.is_active = False
    db.commit()
    return {"status": "disabled", "code": row.code}


def _create_paddle_discount(code: str, percent: int, expires_at: str | None) -> str | None:
    """Create a discount on Paddle via API. Returns discount ID."""
    if not settings.PADDLE_API_KEY:
        return None

    payload = {
        "description": f"Torve rebate code {code}",
        "type": "percentage",
        "amount": str(percent),
        "recur": False,
        "code": code,
        "usage_limit": 1,
        "restrict_to": [settings.PADDLE_PRICE_ID] if settings.PADDLE_PRICE_ID else [],
        "enabled_for_checkout": True,
        "currency_code": None,
    }

    if expires_at:
        payload["expires_at"] = expires_at

    try:
        resp = httpx.post(
            f"{PADDLE_API_BASE}/discounts",
            json=payload,
            headers={
                "Authorization": f"Bearer {settings.PADDLE_API_KEY}",
                "Content-Type": "application/json",
            },
            timeout=15.0,
        )
        if resp.status_code in (200, 201):
            data = resp.json().get("data", {})
            return data.get("id")
        _log.error("Paddle discount creation failed: %s %s", resp.status_code, resp.text[:200])
        return None
    except httpx.HTTPError as e:
        _log.error("Paddle API error: %s", e)
        return None
