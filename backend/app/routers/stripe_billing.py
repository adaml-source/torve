"""Stripe billing endpoints for web purchases."""
from __future__ import annotations

import json
import logging
import uuid

from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.deps import get_current_user_id, get_db
from app.stripe_config import StripeConfigError
from app.stripe_service import (
    StripeBillingError,
    create_checkout_session,
    create_portal_session,
    create_refund_request,
    process_webhook_event,
)

_log = logging.getLogger(__name__)

router = APIRouter(prefix="/billing/stripe", tags=["stripe-billing"])


class CheckoutSessionRequest(BaseModel):
    purchase_type: str


class RefundRequest(BaseModel):
    purchase_type: str
    reason: str | None = None


def _public_error(error_code: str, message: str, status_code: int) -> HTTPException:
    return HTTPException(
        status_code=status_code,
        detail={"error_code": error_code, "message": message},
    )


@router.post("/checkout-session", response_model=None)
def create_stripe_checkout_session(
    body: CheckoutSessionRequest,
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> dict:
    _log.info("STRIPE_CHECKOUT_DEPRECATED_FREE_SOFTWARE user=%s purchase_type=%s", user_id, body.purchase_type)
    return create_checkout_session(db, uuid.UUID(user_id), body.purchase_type)


@router.post("/portal-session")
def create_stripe_portal_session(
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> dict:
    _log.info("STRIPE_PORTAL_DEPRECATED_FREE_SOFTWARE user=%s", user_id)
    return create_portal_session(db, uuid.UUID(user_id))


@router.post("/refund-request")
def create_stripe_refund_request(
    body: RefundRequest,
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> dict:
    try:
        result = create_refund_request(db, uuid.UUID(user_id), body.purchase_type, body.reason)
        db.commit()
        return result
    except StripeConfigError as exc:
        db.rollback()
        raise _public_error(exc.error_code, exc.message, status.HTTP_503_SERVICE_UNAVAILABLE)
    except StripeBillingError as exc:
        db.rollback()
        raise _public_error(exc.error_code, exc.message, exc.status_code)
    except Exception as exc:  # noqa: BLE001
        db.rollback()
        _log.warning("STRIPE_REFUND_REQUEST_FAILED user=%s error=%s", user_id, type(exc).__name__)
        raise _public_error(
            "stripe_refund_failed",
            "Could not process refund request.",
            status.HTTP_502_BAD_GATEWAY,
        )


@router.post("/webhook")
async def stripe_webhook(request: Request, db: Session = Depends(get_db)) -> dict:
    raw_body = await request.body()

    try:
        event = json.loads(raw_body)
    except json.JSONDecodeError:
        raise _public_error(
            "stripe_webhook_processing_failed",
            "Invalid Stripe webhook payload.",
            status.HTTP_400_BAD_REQUEST,
        )

    try:
        return process_webhook_event(db, event)
    except StripeBillingError as exc:
        raise _public_error(exc.error_code, exc.message, exc.status_code)
