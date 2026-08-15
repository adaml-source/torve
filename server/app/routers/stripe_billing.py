import logging
import uuid
from datetime import datetime, timezone
from typing import Any

from fastapi import APIRouter, Depends, Header, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.billing import (
    ENTITLEMENT_LIFETIME,
    ENTITLEMENT_SUBSCRIPTION,
    SOURCE_STRIPE,
    evaluate_stripe_purchase_eligibility,
    expire_subscription,
    grant_entitlement,
)
from app.deps import get_current_user_id, get_db
from app.models import User, UserEntitlement
from app.stripe_service import (
    StripeBillingConfigError,
    StripeCheckoutError,
    StripeCustomerMissing,
    StripePortalError,
    cancel_stripe_subscription_at_period_end,
    construct_stripe_webhook_event,
    create_stripe_checkout_session,
    create_stripe_portal_session,
    retrieve_subscription_period_end,
)

_log = logging.getLogger(__name__)

router = APIRouter(prefix="/billing/stripe", tags=["stripe-billing"])
webhook_router = APIRouter(prefix="/webhooks", tags=["stripe-webhook"])


class StripeCheckoutRequest(BaseModel):
    purchase_type: str = ""


def _error(status_code: int, error_code: str, message: str) -> JSONResponse:
    return JSONResponse(
        status_code=status_code,
        content={"error_code": error_code, "message": message},
    )


def _obj_get(value: Any, key: str, default: Any = None) -> Any:
    if isinstance(value, dict):
        return value.get(key, default)
    return getattr(value, key, default)


def _metadata_get(value: Any, key: str) -> str | None:
    metadata = _obj_get(value, "metadata", {}) or {}
    if isinstance(metadata, dict):
        item = metadata.get(key)
    else:
        item = getattr(metadata, key, None)
    return item if isinstance(item, str) and item.strip() else None


@router.post("/checkout-session", response_model=None)
def create_checkout_session(
    body: StripeCheckoutRequest,
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> JSONResponse | dict:
    uid = uuid.UUID(user_id)
    purchase_type = (body.purchase_type or "").strip().lower()

    eligibility = evaluate_stripe_purchase_eligibility(db, uid, purchase_type)
    if not eligibility.allowed:
        return _error(
            400 if eligibility.error_code == "stripe_invalid_purchase_type" else 409,
            eligibility.error_code or "stripe_purchase_not_allowed",
            eligibility.message or "This Stripe purchase is not allowed.",
        )

    user = db.query(User).filter(User.id == uid).first()
    if not user:
        return _error(404, "user_not_found", "Account not found.")

    try:
        checkout_url = create_stripe_checkout_session(user, purchase_type)
    except StripeBillingConfigError:
        return _error(503, "stripe_not_configured", "Stripe billing is not configured.")
    except StripeCheckoutError:
        _log.warning("Stripe checkout session creation failed for user=%s", uid)
        return _error(502, "stripe_checkout_failed", "Stripe checkout could not be started.")

    return {"checkout_url": checkout_url}


@router.post("/portal-session", response_model=None)
def create_portal_session(
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> JSONResponse | dict:
    uid = uuid.UUID(user_id)
    user = db.query(User).filter(User.id == uid).first()
    if not user:
        return _error(404, "user_not_found", "Account not found.")
    try:
        portal_url = create_stripe_portal_session(user)
    except StripeBillingConfigError:
        return _error(503, "stripe_not_configured", "Stripe billing is not configured.")
    except StripeCustomerMissing:
        return _error(404, "stripe_customer_missing", "No Stripe customer exists for this account.")
    except StripePortalError:
        _log.warning("Stripe portal session creation failed for user=%s", uid)
        return _error(502, "stripe_portal_failed", "Stripe billing portal could not be started.")
    return {"portal_url": portal_url}


def _session_user_id(session: Any) -> uuid.UUID | None:
    raw = _obj_get(session, "client_reference_id") or _metadata_get(session, "torve_user_id")
    if not isinstance(raw, str) or not raw:
        return None
    try:
        return uuid.UUID(raw)
    except ValueError:
        return None


def _timestamp_to_datetime(value: int | None) -> datetime | None:
    if not value:
        return None
    return datetime.fromtimestamp(value, tz=timezone.utc)


def _handle_checkout_completed(db: Session, session: Any) -> bool:
    user_id = _session_user_id(session)
    if not user_id:
        return False
    purchase_type = (_metadata_get(session, "purchase_type") or "").strip().lower()
    session_id = _obj_get(session, "id")
    if not isinstance(session_id, str) or not session_id:
        return False

    if purchase_type == "monthly":
        subscription_id = _obj_get(session, "subscription")
        source_ref = subscription_id if isinstance(subscription_id, str) and subscription_id else session_id
        period_end = retrieve_subscription_period_end(source_ref) if source_ref != session_id else None
        expires_at = _timestamp_to_datetime(period_end)
        if expires_at is None:
            return False
        grant_entitlement(
            db,
            user_id,
            SOURCE_STRIPE,
            source_ref,
            entitlement_type=ENTITLEMENT_SUBSCRIPTION,
            product_id="stripe_monthly",
            expires_at=expires_at,
            auto_renew=True,
        )
        return True

    if purchase_type == "lifetime":
        grant_entitlement(
            db,
            user_id,
            SOURCE_STRIPE,
            session_id,
            entitlement_type=ENTITLEMENT_LIFETIME,
            product_id="stripe_lifetime",
        )
        _cancel_existing_stripe_monthly_renewals(db, user_id)
        return True

    return False


def _cancel_existing_stripe_monthly_renewals(db: Session, user_id: uuid.UUID) -> None:
    now = datetime.now(timezone.utc)
    subscriptions = db.query(UserEntitlement).filter(
        UserEntitlement.user_id == user_id,
        UserEntitlement.source == SOURCE_STRIPE,
        UserEntitlement.entitlement_type == ENTITLEMENT_SUBSCRIPTION,
        UserEntitlement.status == "active",
        UserEntitlement.expires_at > now,
    ).all()
    for ent in subscriptions:
        try:
            cancel_stripe_subscription_at_period_end(ent.source_ref)
            ent.auto_renew = False
        except StripePortalError:
            _log.warning("Stripe monthly cancellation after lifetime upgrade failed user=%s", user_id)


def _handle_subscription_changed(db: Session, subscription: Any) -> bool:
    subscription_id = _obj_get(subscription, "id")
    if not isinstance(subscription_id, str) or not subscription_id:
        return False
    status = (_obj_get(subscription, "status") or "").lower()
    period_end = _timestamp_to_datetime(_obj_get(subscription, "current_period_end"))

    if status in {"active", "trialing"} and period_end:
        user_id_raw = _metadata_get(subscription, "torve_user_id")
        try:
            user_id = uuid.UUID(user_id_raw) if user_id_raw else None
        except ValueError:
            user_id = None
        if not user_id:
            existing = db.query(UserEntitlement).filter(
                UserEntitlement.source == SOURCE_STRIPE,
                UserEntitlement.source_ref == subscription_id,
                UserEntitlement.entitlement_type == ENTITLEMENT_SUBSCRIPTION,
            ).first()
            user_id = existing.user_id if existing else None
        if not user_id:
            return False
        grant_entitlement(
            db,
            user_id,
            SOURCE_STRIPE,
            subscription_id,
            entitlement_type=ENTITLEMENT_SUBSCRIPTION,
            product_id="stripe_monthly",
            expires_at=period_end,
            auto_renew=True,
        )
        return True

    if status in {"canceled", "unpaid", "incomplete_expired"}:
        return expire_subscription(db, SOURCE_STRIPE, subscription_id) is not None

    return False


@webhook_router.post("/stripe", response_model=None)
async def stripe_webhook(
    request: Request,
    stripe_signature: str | None = Header(default=None, alias="Stripe-Signature"),
    db: Session = Depends(get_db),
) -> JSONResponse | dict:
    payload = await request.body()
    try:
        event = construct_stripe_webhook_event(payload, stripe_signature)
    except StripeBillingConfigError:
        return _error(503, "stripe_not_configured", "Stripe webhook is not configured.")
    except StripeCheckoutError:
        return _error(400, "stripe_webhook_invalid", "Stripe webhook could not be verified.")

    event_type = _obj_get(event, "type")
    obj = _obj_get(_obj_get(event, "data", {}), "object", {})

    handled = False
    try:
        if event_type == "checkout.session.completed":
            handled = _handle_checkout_completed(db, obj)
        elif event_type in {"customer.subscription.updated", "customer.subscription.deleted"}:
            handled = _handle_subscription_changed(db, obj)
        if handled:
            db.commit()
    except StripeCheckoutError:
        db.rollback()
        _log.warning("Stripe webhook processing deferred event_type=%s", event_type)
        return _error(503, "stripe_checkout_failed", "Stripe webhook processing was deferred.")
    except Exception:  # noqa: BLE001
        db.rollback()
        _log.exception("Stripe webhook processing failed event_type=%s", event_type)
        return _error(500, "stripe_checkout_failed", "Stripe webhook processing failed.")

    return {"received": True, "handled": handled}
