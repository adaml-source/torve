"""Stripe billing history service.

Torve access is free by default. Stripe helpers remain for historical refund
support and temporary client compatibility; they must not grant or revoke
product features.
"""
from __future__ import annotations

import hashlib
import hmac
import logging
import uuid
from datetime import datetime, timedelta, timezone
from typing import Any

import httpx
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.billing import (
    ENTITLEMENT_LIFETIME,
    ENTITLEMENT_SUBSCRIPTION,
    SOURCE_STRIPE,
)
from app.config import settings
from app.mail import send_refund_manual_review_email
from app.models import (
    StripeCheckoutSession,
    StripeCustomer,
    StripeLifetimePurchase,
    StripeRefundRequest,
    StripeSubscription,
    StripeWebhookEvent,
    User,
)
from app.stripe_config import require_stripe_checkout_configured, require_stripe_webhook_configured

_log = logging.getLogger(__name__)

STRIPE_API_BASE = "https://api.stripe.com/v1"


class StripeBillingError(RuntimeError):
    def __init__(self, error_code: str, message: str, status_code: int = 400):
        super().__init__(message)
        self.error_code = error_code
        self.message = message
        self.status_code = status_code


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _dt_from_unix(value: Any) -> datetime | None:
    if value in (None, ""):
        return None
    try:
        return datetime.fromtimestamp(int(value), tz=timezone.utc)
    except (TypeError, ValueError, OSError):
        return None


def _stripe_headers() -> dict[str, str]:
    headers = {"Authorization": f"Bearer {settings.STRIPE_SECRET_KEY.strip()}"}
    if settings.STRIPE_API_VERSION.strip():
        headers["Stripe-Version"] = settings.STRIPE_API_VERSION.strip()
    return headers


def _stripe_post(path: str, data: dict[str, Any], *, idempotency_key: str | None = None) -> dict[str, Any]:
    try:
        headers = _stripe_headers()
        if idempotency_key:
            headers["Idempotency-Key"] = idempotency_key
        with httpx.Client(timeout=20.0, trust_env=False) as client:
            resp = client.post(
                f"{STRIPE_API_BASE}{path}",
                headers=headers,
                data=data,
            )
    except httpx.HTTPError as exc:
        _log.warning("STRIPE_REQUEST_FAILED path=%s error=%s", path, type(exc).__name__)
        raise StripeBillingError("stripe_checkout_failed", "Stripe request failed.", 502)

    if resp.status_code >= 400:
        _log_stripe_api_error("STRIPE_API_ERROR", path, resp)
        raise StripeBillingError("stripe_checkout_failed", "Stripe request failed.", 502)
    try:
        return resp.json()
    except ValueError:
        _log.warning("STRIPE_API_INVALID_JSON path=%s", path)
        raise StripeBillingError("stripe_checkout_failed", "Stripe request failed.", 502)


def _stripe_get(path: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
    try:
        with httpx.Client(timeout=20.0, trust_env=False) as client:
            resp = client.get(
                f"{STRIPE_API_BASE}{path}",
                headers=_stripe_headers(),
                params=params or {},
            )
    except httpx.HTTPError as exc:
        _log.warning("STRIPE_REQUEST_FAILED path=%s error=%s", path, type(exc).__name__)
        raise StripeBillingError("stripe_webhook_processing_failed", "Stripe request failed.", 502)

    if resp.status_code >= 400:
        _log_stripe_api_error("STRIPE_API_ERROR", path, resp)
        raise StripeBillingError("stripe_webhook_processing_failed", "Stripe request failed.", 502)
    try:
        return resp.json()
    except ValueError:
        _log.warning("STRIPE_API_INVALID_JSON path=%s", path)
        raise StripeBillingError("stripe_webhook_processing_failed", "Stripe request failed.", 502)


def _log_stripe_api_error(prefix: str, path: str, resp: httpx.Response) -> None:
    error_type = None
    error_code = None
    error_param = None
    try:
        payload = resp.json()
        error = payload.get("error") if isinstance(payload, dict) else None
        if isinstance(error, dict):
            error_type = error.get("type")
            error_code = error.get("code")
            error_param = error.get("param")
    except ValueError:
        pass
    _log.warning(
        "%s path=%s status=%s request_id=%s type=%s code=%s param=%s",
        prefix,
        path,
        resp.status_code,
        resp.headers.get("request-id") or resp.headers.get("Request-Id"),
        error_type,
        error_code,
        error_param,
    )


def _public_metadata(user_id: uuid.UUID, purchase_type: str) -> dict[str, str]:
    return {
        "app": "torve",
        "user_id": str(user_id),
        "entitlement": "deprecated_free_software",
        "purchase_type": purchase_type,
        "platform": "web",
    }


def _customer_for_user(db: Session, user_id: uuid.UUID) -> StripeCustomer | None:
    return db.query(StripeCustomer).filter(StripeCustomer.user_id == user_id).first()


def _customer_by_stripe_id(db: Session, stripe_customer_id: str | None) -> StripeCustomer | None:
    if not stripe_customer_id:
        return None
    return (
        db.query(StripeCustomer)
        .filter(StripeCustomer.stripe_customer_id == stripe_customer_id)
        .first()
    )


def _upsert_customer_mapping(
    db: Session,
    *,
    user_id: uuid.UUID,
    stripe_customer_id: str,
    email_snapshot: str | None = None,
) -> StripeCustomer:
    row = _customer_by_stripe_id(db, stripe_customer_id) or _customer_for_user(db, user_id)
    if row:
        row.user_id = user_id
        row.stripe_customer_id = stripe_customer_id
        if email_snapshot:
            row.email_snapshot = email_snapshot
        row.updated_at = _now()
        db.flush()
        return row
    row = StripeCustomer(
        user_id=user_id,
        stripe_customer_id=stripe_customer_id,
        email_snapshot=email_snapshot,
    )
    db.add(row)
    db.flush()
    return row


def get_or_create_customer(db: Session, user_id: uuid.UUID) -> StripeCustomer:
    existing = _customer_for_user(db, user_id)
    if existing:
        return existing

    user = db.query(User).filter(User.id == user_id).first()
    if not user:
        raise StripeBillingError("stripe_checkout_failed", "Could not create checkout session.", 404)

    data = {
        "email": user.email,
        "metadata[app]": "torve",
        "metadata[user_id]": str(user_id),
    }
    customer = _stripe_post("/customers", data)
    stripe_customer_id = customer.get("id")
    if not stripe_customer_id:
        raise StripeBillingError("stripe_checkout_failed", "Could not create checkout session.", 502)
    return _upsert_customer_mapping(
        db,
        user_id=user_id,
        stripe_customer_id=stripe_customer_id,
        email_snapshot=user.email,
    )


def create_checkout_session(db: Session, user_id: uuid.UUID, purchase_type: str) -> dict[str, str]:
    return {
        "deprecated": True,
        "checkout_required": False,
        "checkout_url": None,
        "session_id": None,
        "access_tier": "free",
        "message": "Stripe checkout is no longer required; Torve access is free.",
    }


def create_portal_session(db: Session, user_id: uuid.UUID) -> dict[str, str]:
    return {
        "deprecated": True,
        "portal_required": False,
        "portal_url": None,
        "access_tier": "free",
        "message": "Stripe customer portal is no longer required for Torve access.",
    }


REFUND_WINDOW_DAYS = 14
REFUND_STATUS_APPROVED = "approved"
REFUND_STATUS_MANUAL_REVIEW = "manual_review"
REFUND_STATUS_DENIED = "denied"
REFUND_STATUS_FAILED = "failed"


def _refund_message(status: str, reason: str) -> str:
    if status == REFUND_STATUS_APPROVED:
        return "Your refund was approved and is being processed."
    if status == REFUND_STATUS_DENIED:
        return "This purchase is not eligible for an automatic refund."
    if reason == "prior_goodwill_refund":
        return "This request needs review because a goodwill refund was already used."
    if reason == "renewal_not_goodwill_refundable":
        return "Monthly renewals are reviewed manually and are not part of the first-purchase refund."
    return "Your refund request was received and will be reviewed."


def _hash_refund_signal(value: str | None) -> str | None:
    if not value:
        return None
    secret = settings.REFUND_ABUSE_HMAC_SECRET or settings.REBATE_CODE_HMAC_SECRET or settings.JWT_SECRET
    return hmac.new(secret.encode("utf-8"), value.encode("utf-8"), hashlib.sha256).hexdigest()


def _truncate_reason(value: str | None) -> str | None:
    if not value:
        return None
    return value.strip()[:500] or None


def _payment_identity(payment_intent_id: str | None, charge_id: str | None) -> dict[str, str | None]:
    resolved_payment_intent = payment_intent_id
    resolved_charge = charge_id
    method_type = None
    fingerprint_hash = None

    try:
        if resolved_payment_intent and not resolved_charge:
            intent = _stripe_get(f"/payment_intents/{resolved_payment_intent}")
            resolved_charge = _as_id(intent.get("latest_charge")) or intent.get("latest_charge")
        if resolved_charge:
            charge = _stripe_get(f"/charges/{resolved_charge}")
            details = charge.get("payment_method_details") or {}
            method_type = details.get("type")
            card = details.get("card") if isinstance(details.get("card"), dict) else {}
            fingerprint_hash = _hash_refund_signal(card.get("fingerprint"))
    except StripeBillingError:
        pass

    return {
        "payment_intent_id": resolved_payment_intent,
        "charge_id": resolved_charge,
        "payment_method_type": method_type,
        "payment_fingerprint_hash": fingerprint_hash,
    }


def _invoice_payment_identity(invoice_id: str | None) -> dict[str, str | None]:
    if not invoice_id:
        return {
            "payment_intent_id": None,
            "charge_id": None,
            "payment_method_type": None,
            "payment_fingerprint_hash": None,
        }
    try:
        invoice = _stripe_get(f"/invoices/{invoice_id}")
    except StripeBillingError:
        return {
            "payment_intent_id": None,
            "charge_id": None,
            "payment_method_type": None,
            "payment_fingerprint_hash": None,
        }
    payment_intent_id = _as_id(invoice.get("payment_intent")) or invoice.get("payment_intent")
    charge_id = _as_id(invoice.get("charge")) or invoice.get("charge")
    return _payment_identity(payment_intent_id, charge_id)


def _create_refund_request_row(
    db: Session,
    *,
    user_id: uuid.UUID,
    stripe_customer_id: str | None,
    purchase_type: str,
    target_kind: str | None,
    status: str,
    policy_reason: str,
    request_reason: str | None,
    stripe_payment_intent_id: str | None = None,
    stripe_charge_id: str | None = None,
    stripe_subscription_id: str | None = None,
    stripe_invoice_id: str | None = None,
    payment_fingerprint_hash: str | None = None,
    payment_method_type: str | None = None,
) -> StripeRefundRequest:
    row = StripeRefundRequest(
        user_id=user_id,
        stripe_customer_id=stripe_customer_id,
        purchase_type=purchase_type,
        target_kind=target_kind,
        status=status,
        policy_reason=policy_reason,
        request_reason=_truncate_reason(request_reason),
        stripe_payment_intent_id=stripe_payment_intent_id,
        stripe_charge_id=stripe_charge_id,
        stripe_subscription_id=stripe_subscription_id,
        stripe_invoice_id=stripe_invoice_id,
        payment_fingerprint_hash=payment_fingerprint_hash,
        payment_method_type=payment_method_type,
    )
    db.add(row)
    db.flush()
    return row


def _refund_response(row: StripeRefundRequest) -> dict[str, str]:
    return {
        "status": row.status,
        "request_id": str(row.id),
        "message": _refund_message(row.status, row.policy_reason),
    }


def _notify_manual_refund_review(db: Session, row: StripeRefundRequest) -> None:
    if row.status != REFUND_STATUS_MANUAL_REVIEW:
        return
    try:
        user = db.query(User).filter(User.id == row.user_id).first()
        sent = send_refund_manual_review_email(
            to=settings.REFUND_REVIEW_EMAIL,
            request_id=str(row.id),
            user_id=str(row.user_id),
            user_email=user.email if user else None,
            purchase_type=row.purchase_type,
            policy_reason=row.policy_reason,
            request_reason=row.request_reason,
            stripe_customer_id=row.stripe_customer_id,
        )
        if not sent:
            _log.warning("REFUND_REVIEW_EMAIL_NOT_SENT request_id=%s", row.id)
    except Exception as exc:  # noqa: BLE001
        _log.warning("REFUND_REVIEW_EMAIL_FAILED request_id=%s error=%s", row.id, type(exc).__name__)


def _manual_review_refund_response(db: Session, row: StripeRefundRequest) -> dict[str, str]:
    _notify_manual_refund_review(db, row)
    return _refund_response(row)


def _has_prior_goodwill_refund(
    db: Session,
    *,
    user_id: uuid.UUID,
    stripe_customer_id: str | None,
    payment_fingerprint_hash: str | None,
) -> bool:
    q = db.query(StripeRefundRequest).filter(
        StripeRefundRequest.status == REFUND_STATUS_APPROVED,
        StripeRefundRequest.policy_reason == "first_purchase_goodwill",
    )
    checks = [StripeRefundRequest.user_id == user_id]
    if stripe_customer_id:
        checks.append(StripeRefundRequest.stripe_customer_id == stripe_customer_id)
    if payment_fingerprint_hash:
        checks.append(StripeRefundRequest.payment_fingerprint_hash == payment_fingerprint_hash)
    from sqlalchemy import or_ as sa_or

    return q.filter(sa_or(*checks)).first() is not None


def _issue_stripe_refund(row: StripeRefundRequest) -> dict[str, Any]:
    data: dict[str, Any] = {
        "reason": "requested_by_customer",
        "metadata[app]": "torve",
        "metadata[refund_request_id]": str(row.id),
        "metadata[user_id]": str(row.user_id),
        "metadata[policy_reason]": row.policy_reason,
    }
    if row.stripe_payment_intent_id:
        data["payment_intent"] = row.stripe_payment_intent_id
    elif row.stripe_charge_id:
        data["charge"] = row.stripe_charge_id
    else:
        raise StripeBillingError("stripe_refund_failed", "Refund could not be processed.", 502)
    return _stripe_post("/refunds", data, idempotency_key=f"stripe-refund-request:{row.id}")


def _approve_refund(db: Session, row: StripeRefundRequest) -> None:
    refund = _issue_stripe_refund(row)
    refund_id = refund.get("id")
    if not refund_id:
        row.status = REFUND_STATUS_FAILED
        row.policy_reason = "stripe_refund_missing_id"
        row.updated_at = _now()
        db.flush()
        raise StripeBillingError("stripe_refund_failed", "Refund could not be processed.", 502)
    row.stripe_refund_id = refund_id
    row.status = REFUND_STATUS_APPROVED
    row.processed_at = _now()
    row.updated_at = _now()
    db.flush()


def _apply_refund_entitlement_effects(db: Session, row: StripeRefundRequest) -> None:
    now = _now()
    if row.purchase_type == "lifetime":
        purchase = None
        q = db.query(StripeLifetimePurchase).filter(StripeLifetimePurchase.user_id == row.user_id)
        if row.stripe_payment_intent_id:
            purchase = q.filter(StripeLifetimePurchase.stripe_payment_intent_id == row.stripe_payment_intent_id).first()
        if not purchase and row.stripe_charge_id:
            purchase = q.filter(StripeLifetimePurchase.stripe_charge_id == row.stripe_charge_id).first()
        if purchase:
            purchase.status = "refunded"
            purchase.refunded_at = row.processed_at or now
            purchase.updated_at = now
        return

    if row.purchase_type == "monthly" and row.stripe_subscription_id:
        subscription = (
            db.query(StripeSubscription)
            .filter(StripeSubscription.stripe_subscription_id == row.stripe_subscription_id)
            .first()
        )
        if subscription:
            subscription.status = "refunded"
            subscription.canceled_at = now
            subscription.cancel_at_period_end = False
            subscription.updated_at = now
        try:
            _stripe_post(f"/subscriptions/{row.stripe_subscription_id}/cancel", {})
            if subscription:
                subscription.status = "canceled"
                subscription.updated_at = now
        except StripeBillingError:
            _log.warning("STRIPE_REFUND_SUBSCRIPTION_CANCEL_FAILED subscription=%s", row.stripe_subscription_id)


def approve_manual_refund_request(db: Session, request_id: uuid.UUID) -> dict[str, str | None]:
    require_stripe_checkout_configured()
    row = db.query(StripeRefundRequest).filter(StripeRefundRequest.id == request_id).first()
    if not row:
        raise StripeBillingError("stripe_refund_request_not_found", "Refund request was not found.", 404)
    if row.status == REFUND_STATUS_APPROVED and row.stripe_refund_id:
        return {
            **_refund_response(row),
            "stripe_refund_id": row.stripe_refund_id,
        }
    if row.status != REFUND_STATUS_MANUAL_REVIEW:
        raise StripeBillingError("stripe_refund_not_reviewable", "Refund request cannot be approved.", 409)
    if not (row.stripe_payment_intent_id or row.stripe_charge_id):
        raise StripeBillingError("stripe_refund_missing_payment_reference", "Refund request needs payment details.", 409)

    _approve_refund(db, row)
    _apply_refund_entitlement_effects(db, row)
    row.updated_at = _now()
    db.flush()
    return {
        **_refund_response(row),
        "stripe_refund_id": row.stripe_refund_id,
    }


def _request_lifetime_refund(
    db: Session,
    *,
    user_id: uuid.UUID,
    customer: StripeCustomer,
    reason: str | None,
) -> dict[str, str]:
    purchase = (
        db.query(StripeLifetimePurchase)
        .filter(
            StripeLifetimePurchase.user_id == user_id,
            StripeLifetimePurchase.stripe_customer_id == customer.stripe_customer_id,
            StripeLifetimePurchase.price_id == settings.stripe_lifetime_price_id,
            StripeLifetimePurchase.status == "active",
        )
        .order_by(StripeLifetimePurchase.purchased_at.desc())
        .first()
    )
    if not purchase:
        row = _create_refund_request_row(
            db,
            user_id=user_id,
            stripe_customer_id=customer.stripe_customer_id,
            purchase_type="lifetime",
            target_kind="lifetime",
            status=REFUND_STATUS_MANUAL_REVIEW,
            policy_reason="purchase_not_found",
            request_reason=reason,
        )
        return _manual_review_refund_response(db, row)

    identity = _payment_identity(purchase.stripe_payment_intent_id, purchase.stripe_charge_id)
    within_window = purchase.purchased_at >= _now() - timedelta(days=REFUND_WINDOW_DAYS)
    prior_refund = _has_prior_goodwill_refund(
        db,
        user_id=user_id,
        stripe_customer_id=customer.stripe_customer_id,
        payment_fingerprint_hash=identity["payment_fingerprint_hash"],
    )
    has_payment_reference = bool(identity["payment_intent_id"] or identity["charge_id"])
    status_value = (
        REFUND_STATUS_APPROVED
        if within_window and not prior_refund and has_payment_reference
        else REFUND_STATUS_MANUAL_REVIEW
    )
    policy_reason = "first_purchase_goodwill"
    if not within_window:
        policy_reason = "outside_refund_window"
    elif prior_refund:
        policy_reason = "prior_goodwill_refund"
    elif not has_payment_reference:
        policy_reason = "payment_reference_missing"
    row = _create_refund_request_row(
        db,
        user_id=user_id,
        stripe_customer_id=customer.stripe_customer_id,
        purchase_type="lifetime",
        target_kind="lifetime",
        status=status_value,
        policy_reason=policy_reason,
        request_reason=reason,
        stripe_payment_intent_id=identity["payment_intent_id"],
        stripe_charge_id=identity["charge_id"],
        payment_fingerprint_hash=identity["payment_fingerprint_hash"],
        payment_method_type=identity["payment_method_type"],
    )
    if status_value != REFUND_STATUS_APPROVED:
        return _manual_review_refund_response(db, row)
    _approve_refund(db, row)
    _apply_refund_entitlement_effects(db, row)
    db.flush()
    return _refund_response(row)


def _request_monthly_refund(
    db: Session,
    *,
    user_id: uuid.UUID,
    customer: StripeCustomer,
    reason: str | None,
) -> dict[str, str]:
    session = (
        db.query(StripeCheckoutSession)
        .filter(
            StripeCheckoutSession.user_id == user_id,
            StripeCheckoutSession.stripe_customer_id == customer.stripe_customer_id,
            StripeCheckoutSession.purchase_type == "monthly",
            StripeCheckoutSession.price_id == settings.stripe_monthly_price_id,
            StripeCheckoutSession.stripe_subscription_id.isnot(None),
        )
        .order_by(StripeCheckoutSession.created_at.asc())
        .first()
    )
    subscription = None
    if session and session.stripe_subscription_id:
        subscription = (
            db.query(StripeSubscription)
            .filter(StripeSubscription.stripe_subscription_id == session.stripe_subscription_id)
            .first()
        )
    if not session or not subscription:
        row = _create_refund_request_row(
            db,
            user_id=user_id,
            stripe_customer_id=customer.stripe_customer_id,
            purchase_type="monthly",
            target_kind="subscription",
            status=REFUND_STATUS_MANUAL_REVIEW,
            policy_reason="purchase_not_found",
            request_reason=reason,
        )
        return _manual_review_refund_response(db, row)

    identity = _invoice_payment_identity(subscription.latest_invoice_id)
    within_window = session.created_at >= _now() - timedelta(days=REFUND_WINDOW_DAYS)
    same_initial_period = (
        subscription.current_period_start is None
        or session.created_at >= subscription.current_period_start - timedelta(hours=2)
    )
    prior_refund = _has_prior_goodwill_refund(
        db,
        user_id=user_id,
        stripe_customer_id=customer.stripe_customer_id,
        payment_fingerprint_hash=identity["payment_fingerprint_hash"],
    )
    can_auto_refund = (
        within_window
        and same_initial_period
        and not prior_refund
        and bool(identity["payment_intent_id"] or identity["charge_id"])
    )
    policy_reason = "first_purchase_goodwill"
    if not within_window or not same_initial_period:
        policy_reason = "renewal_not_goodwill_refundable"
    elif prior_refund:
        policy_reason = "prior_goodwill_refund"
    elif not (identity["payment_intent_id"] or identity["charge_id"]):
        policy_reason = "payment_reference_missing"
    row = _create_refund_request_row(
        db,
        user_id=user_id,
        stripe_customer_id=customer.stripe_customer_id,
        purchase_type="monthly",
        target_kind="subscription",
        status=REFUND_STATUS_APPROVED if can_auto_refund else REFUND_STATUS_MANUAL_REVIEW,
        policy_reason=policy_reason,
        request_reason=reason,
        stripe_payment_intent_id=identity["payment_intent_id"],
        stripe_charge_id=identity["charge_id"],
        stripe_subscription_id=subscription.stripe_subscription_id,
        stripe_invoice_id=subscription.latest_invoice_id,
        payment_fingerprint_hash=identity["payment_fingerprint_hash"],
        payment_method_type=identity["payment_method_type"],
    )
    if not can_auto_refund:
        return _manual_review_refund_response(db, row)

    _approve_refund(db, row)
    _apply_refund_entitlement_effects(db, row)
    db.flush()
    return _refund_response(row)


def create_refund_request(
    db: Session,
    user_id: uuid.UUID,
    purchase_type: str,
    reason: str | None = None,
) -> dict[str, str]:
    require_stripe_checkout_configured()
    if purchase_type not in ("monthly", "lifetime"):
        raise StripeBillingError("stripe_invalid_purchase_type", "Invalid purchase type.", 400)
    customer = _customer_for_user(db, user_id)
    if not customer:
        raise StripeBillingError(
            "stripe_customer_missing",
            "No Stripe billing profile was found for this account.",
            409,
        )
    if purchase_type == "lifetime":
        return _request_lifetime_refund(db, user_id=user_id, customer=customer, reason=reason)
    return _request_monthly_refund(db, user_id=user_id, customer=customer, reason=reason)


def verify_webhook_signature(raw_body: bytes, signature_header: str | None) -> bool:
    require_stripe_webhook_configured()
    if not signature_header:
        return False

    parts: dict[str, list[str]] = {}
    for part in signature_header.split(","):
        if "=" not in part:
            continue
        key, value = part.split("=", 1)
        parts.setdefault(key, []).append(value)

    timestamps = parts.get("t") or []
    signatures = parts.get("v1") or []
    if not timestamps or not signatures:
        return False

    signed_payload = timestamps[0].encode("utf-8") + b"." + raw_body
    expected = hmac.new(
        settings.STRIPE_WEBHOOK_SECRET.encode("utf-8"),
        signed_payload,
        hashlib.sha256,
    ).hexdigest()
    return any(hmac.compare_digest(expected, sig) for sig in signatures)


def _as_id(value: Any) -> str | None:
    if isinstance(value, str):
        return value
    if isinstance(value, dict):
        maybe = value.get("id")
        return maybe if isinstance(maybe, str) else None
    return None


def _metadata(obj: dict[str, Any]) -> dict[str, Any]:
    metadata = obj.get("metadata") or {}
    return metadata if isinstance(metadata, dict) else {}


def _uuid_from_str(value: Any) -> uuid.UUID | None:
    if not value:
        return None
    try:
        return uuid.UUID(str(value))
    except (TypeError, ValueError):
        return None


def _resolve_user_id(db: Session, obj: dict[str, Any]) -> uuid.UUID | None:
    uid = _uuid_from_str(_metadata(obj).get("user_id"))
    if uid:
        return uid
    uid = _uuid_from_str(obj.get("client_reference_id"))
    if uid:
        return uid
    customer = _customer_by_stripe_id(db, _as_id(obj.get("customer")) or obj.get("customer"))
    return customer.user_id if customer else None


def _session_price_id(session: dict[str, Any]) -> str | None:
    line_items = session.get("line_items") or {}
    if isinstance(line_items, dict):
        data = line_items.get("data") or []
        if data:
            price = data[0].get("price") or {}
            price_id = price.get("id")
            if price_id:
                return price_id
    session_id = session.get("id")
    if session_id:
        try:
            fetched = _stripe_get(f"/checkout/sessions/{session_id}/line_items", {"limit": 1})
            data = fetched.get("data") or []
            if data:
                return ((data[0].get("price") or {}).get("id"))
        except StripeBillingError:
            return None
    return None


def _subscription_price_id(subscription: dict[str, Any]) -> str | None:
    items = ((subscription.get("items") or {}).get("data") or [])
    if not items:
        return None
    return ((items[0].get("price") or {}).get("id"))


def _invoice_price_id(invoice: dict[str, Any]) -> str | None:
    lines = ((invoice.get("lines") or {}).get("data") or [])
    if not lines:
        return None
    line = lines[0]
    price = line.get("price") or {}
    if price.get("id"):
        return price.get("id")
    pricing = line.get("pricing") or {}
    price_details = pricing.get("price_details") or {}
    return price_details.get("price")


def _invoice_period_end(invoice: dict[str, Any]) -> datetime | None:
    lines = ((invoice.get("lines") or {}).get("data") or [])
    if not lines:
        return None
    return _dt_from_unix((lines[0].get("period") or {}).get("end"))


def _lifetime_source_ref(payment_intent_id: str | None, session_id: str | None) -> str | None:
    return payment_intent_id or session_id


def _upsert_checkout_session(
    db: Session,
    *,
    user_id: uuid.UUID,
    session: dict[str, Any],
    price_id: str,
    purchase_type: str,
) -> StripeCheckoutSession:
    session_id = session.get("id")
    row = (
        db.query(StripeCheckoutSession)
        .filter(StripeCheckoutSession.stripe_session_id == session_id)
        .first()
    )
    if not row:
        row = StripeCheckoutSession(
            user_id=user_id,
            stripe_session_id=session_id,
            price_id=price_id,
            purchase_type=purchase_type,
            mode=session.get("mode") or ("subscription" if purchase_type == "monthly" else "payment"),
            status=session.get("status") or "complete",
        )
        db.add(row)
    row.user_id = user_id
    row.stripe_customer_id = _as_id(session.get("customer")) or session.get("customer")
    row.price_id = price_id
    row.purchase_type = purchase_type
    row.mode = session.get("mode") or row.mode
    row.status = session.get("status") or row.status
    row.payment_status = session.get("payment_status")
    row.stripe_subscription_id = _as_id(session.get("subscription"))
    row.stripe_payment_intent_id = _as_id(session.get("payment_intent"))
    row.updated_at = _now()
    db.flush()
    return row


def _upsert_subscription(
    db: Session,
    *,
    user_id: uuid.UUID,
    subscription_id: str,
    stripe_customer_id: str,
    price_id: str,
    status: str,
    current_period_start: datetime | None = None,
    current_period_end: datetime | None = None,
    cancel_at_period_end: bool = False,
    canceled_at: datetime | None = None,
    latest_invoice_id: str | None = None,
) -> StripeSubscription:
    row = (
        db.query(StripeSubscription)
        .filter(StripeSubscription.stripe_subscription_id == subscription_id)
        .first()
    )
    if not row:
        row = StripeSubscription(
            user_id=user_id,
            stripe_customer_id=stripe_customer_id,
            stripe_subscription_id=subscription_id,
            stripe_price_id=price_id,
            status=status,
        )
        db.add(row)
    row.user_id = user_id
    row.stripe_customer_id = stripe_customer_id
    row.stripe_price_id = price_id
    row.status = status
    row.current_period_start = current_period_start
    row.current_period_end = current_period_end
    row.cancel_at_period_end = cancel_at_period_end
    row.canceled_at = canceled_at
    if latest_invoice_id:
        row.latest_invoice_id = latest_invoice_id
    row.updated_at = _now()
    db.flush()
    return row


def _upsert_lifetime_purchase(
    db: Session,
    *,
    user_id: uuid.UUID,
    stripe_customer_id: str,
    session_id: str | None,
    payment_intent_id: str | None,
    price_id: str,
    status: str,
    purchased_at: datetime | None = None,
    charge_id: str | None = None,
) -> StripeLifetimePurchase:
    q = db.query(StripeLifetimePurchase)
    row = None
    if payment_intent_id:
        row = q.filter(StripeLifetimePurchase.stripe_payment_intent_id == payment_intent_id).first()
    if not row and session_id:
        row = q.filter(StripeLifetimePurchase.stripe_checkout_session_id == session_id).first()
    if not row:
        row = StripeLifetimePurchase(
            user_id=user_id,
            stripe_customer_id=stripe_customer_id,
            stripe_checkout_session_id=session_id,
            stripe_payment_intent_id=payment_intent_id,
            price_id=price_id,
            status=status,
            purchased_at=purchased_at or _now(),
        )
        db.add(row)
    row.user_id = user_id
    row.stripe_customer_id = stripe_customer_id
    row.stripe_checkout_session_id = session_id or row.stripe_checkout_session_id
    row.stripe_payment_intent_id = payment_intent_id or row.stripe_payment_intent_id
    row.stripe_charge_id = charge_id or row.stripe_charge_id
    row.price_id = price_id
    row.status = status
    row.updated_at = _now()
    db.flush()
    return row


def _grant_lifetime_from_session(db: Session, session: dict[str, Any]) -> bool:
    price_id = _session_price_id(session)
    if price_id != settings.stripe_lifetime_price_id:
        _log.warning("STRIPE_PRICE_MISMATCH session=%s", session.get("id"))
        return False
    if session.get("mode") != "payment" or session.get("payment_status") != "paid":
        return False
    user_id = _resolve_user_id(db, session)
    customer_id = _as_id(session.get("customer")) or session.get("customer")
    if not user_id or not customer_id:
        _log.warning("STRIPE_LIFETIME_UNLINKED session=%s", session.get("id"))
        return False
    _upsert_customer_mapping(db, user_id=user_id, stripe_customer_id=customer_id)
    row = _upsert_checkout_session(
        db, user_id=user_id, session=session, price_id=price_id, purchase_type="lifetime"
    )
    source_ref = _lifetime_source_ref(row.stripe_payment_intent_id, row.stripe_session_id)
    if not source_ref:
        return False
    _upsert_lifetime_purchase(
        db,
        user_id=user_id,
        stripe_customer_id=customer_id,
        session_id=row.stripe_session_id,
        payment_intent_id=row.stripe_payment_intent_id,
        price_id=price_id,
        status="active",
        purchased_at=_now(),
    )
    return True


def _store_monthly_from_session(db: Session, session: dict[str, Any]) -> bool:
    price_id = _session_price_id(session)
    if price_id != settings.stripe_monthly_price_id:
        _log.warning("STRIPE_PRICE_MISMATCH session=%s", session.get("id"))
        return False
    user_id = _resolve_user_id(db, session)
    customer_id = _as_id(session.get("customer")) or session.get("customer")
    if not user_id or not customer_id:
        _log.warning("STRIPE_SUBSCRIPTION_UNLINKED session=%s", session.get("id"))
        return False
    _upsert_customer_mapping(db, user_id=user_id, stripe_customer_id=customer_id)
    row = _upsert_checkout_session(
        db, user_id=user_id, session=session, price_id=price_id, purchase_type="monthly"
    )
    subscription_id = row.stripe_subscription_id
    if not subscription_id:
        return True
    subscription = session.get("subscription") if isinstance(session.get("subscription"), dict) else None
    if subscription is None:
        try:
            subscription = _stripe_get(f"/subscriptions/{subscription_id}")
        except StripeBillingError:
            subscription = None
    if subscription:
        _handle_subscription_object(db, subscription)
    return True


def _handle_subscription_object(db: Session, subscription: dict[str, Any], *, latest_invoice_id: str | None = None) -> bool:
    subscription_id = subscription.get("id")
    price_id = _subscription_price_id(subscription)
    if not subscription_id or price_id != settings.stripe_monthly_price_id:
        return False
    user_id = _resolve_user_id(db, subscription)
    customer_id = _as_id(subscription.get("customer")) or subscription.get("customer")
    if not user_id and customer_id:
        customer = _customer_by_stripe_id(db, customer_id)
        user_id = customer.user_id if customer else None
    if not user_id or not customer_id:
        _log.warning("STRIPE_SUBSCRIPTION_UNLINKED subscription=%s", subscription_id)
        return False
    _upsert_customer_mapping(db, user_id=user_id, stripe_customer_id=customer_id)

    status = subscription.get("status") or "unknown"
    period_start = _dt_from_unix(subscription.get("current_period_start"))
    period_end = _dt_from_unix(subscription.get("current_period_end"))
    cancel_at_period_end = bool(subscription.get("cancel_at_period_end"))
    canceled_at = _dt_from_unix(subscription.get("canceled_at"))
    _upsert_subscription(
        db,
        user_id=user_id,
        subscription_id=subscription_id,
        stripe_customer_id=customer_id,
        price_id=price_id,
        status=status,
        current_period_start=period_start,
        current_period_end=period_end,
        cancel_at_period_end=cancel_at_period_end,
        canceled_at=canceled_at,
        latest_invoice_id=latest_invoice_id,
    )

    return True


def _handle_invoice_paid(db: Session, invoice: dict[str, Any]) -> bool:
    price_id = _invoice_price_id(invoice)
    if price_id != settings.stripe_monthly_price_id:
        return False
    subscription_id = _as_id(invoice.get("subscription")) or invoice.get("subscription")
    customer_id = _as_id(invoice.get("customer")) or invoice.get("customer")
    if not subscription_id:
        return False

    user_id = _resolve_user_id(db, invoice)
    if not user_id and customer_id:
        customer = _customer_by_stripe_id(db, customer_id)
        user_id = customer.user_id if customer else None
    if not user_id:
        _log.warning("STRIPE_INVOICE_UNLINKED invoice=%s", invoice.get("id"))
        return False
    if customer_id:
        _upsert_customer_mapping(db, user_id=user_id, stripe_customer_id=customer_id)

    period_end = _invoice_period_end(invoice)
    period_start = None
    lines = ((invoice.get("lines") or {}).get("data") or [])
    if lines:
        period_start = _dt_from_unix((lines[0].get("period") or {}).get("start"))
    if not period_end:
        try:
            sub = _stripe_get(f"/subscriptions/{subscription_id}")
            period_end = _dt_from_unix(sub.get("current_period_end"))
            period_start = _dt_from_unix(sub.get("current_period_start")) or period_start
        except StripeBillingError:
            pass
    if not period_end:
        return False

    _upsert_subscription(
        db,
        user_id=user_id,
        subscription_id=subscription_id,
        stripe_customer_id=customer_id or "",
        price_id=price_id,
        status="active",
        current_period_start=period_start,
        current_period_end=period_end,
        cancel_at_period_end=False,
        latest_invoice_id=invoice.get("id"),
    )
    return True


def _handle_invoice_payment_failed(db: Session, invoice: dict[str, Any]) -> bool:
    subscription_id = _as_id(invoice.get("subscription")) or invoice.get("subscription")
    if not subscription_id:
        return False
    row = (
        db.query(StripeSubscription)
        .filter(StripeSubscription.stripe_subscription_id == subscription_id)
        .first()
    )
    if row:
        row.status = "past_due"
        row.latest_invoice_id = invoice.get("id") or row.latest_invoice_id
        row.updated_at = _now()
        db.flush()
    return True


def _handle_subscription_refund_from_charge(db: Session, charge: dict[str, Any]) -> bool:
    refunded = int(charge.get("amount_refunded") or 0)
    if refunded <= 0:
        return False
    invoice_id = _as_id(charge.get("invoice")) or charge.get("invoice")
    subscription_id = None
    price_id = None
    if invoice_id:
        try:
            invoice = _stripe_get(f"/invoices/{invoice_id}")
            subscription_id = _as_id(invoice.get("subscription")) or invoice.get("subscription")
            price_id = _invoice_price_id(invoice)
        except StripeBillingError:
            invoice = None
    if price_id and price_id != settings.stripe_monthly_price_id:
        return False
    if not subscription_id and invoice_id:
        row = (
            db.query(StripeSubscription)
            .filter(StripeSubscription.latest_invoice_id == invoice_id)
            .first()
        )
        subscription_id = row.stripe_subscription_id if row else None
    if not subscription_id:
        return False

    row = (
        db.query(StripeSubscription)
        .filter(StripeSubscription.stripe_subscription_id == subscription_id)
        .first()
    )
    if row:
        row.status = "refunded"
        row.cancel_at_period_end = False
        row.canceled_at = row.canceled_at or _now()
        row.updated_at = _now()
        db.flush()
    try:
        _stripe_post(f"/subscriptions/{subscription_id}/cancel", {})
        if row:
            row.status = "canceled"
            row.updated_at = _now()
            db.flush()
    except StripeBillingError:
        _log.warning("STRIPE_REFUND_SUBSCRIPTION_CANCEL_FAILED subscription=%s", subscription_id)
    return True


def _handle_subscription_deleted(db: Session, subscription: dict[str, Any]) -> bool:
    subscription_id = subscription.get("id")
    if not subscription_id:
        return False
    _handle_subscription_object(db, {**subscription, "status": "canceled"})
    return True


def _handle_charge_refunded(db: Session, charge: dict[str, Any]) -> bool:
    payment_intent_id = _as_id(charge.get("payment_intent")) or charge.get("payment_intent")
    charge_id = charge.get("id")
    row = None
    if payment_intent_id:
        row = (
            db.query(StripeLifetimePurchase)
            .filter(StripeLifetimePurchase.stripe_payment_intent_id == payment_intent_id)
            .first()
        )
    if not row and charge_id:
        row = (
            db.query(StripeLifetimePurchase)
            .filter(StripeLifetimePurchase.stripe_charge_id == charge_id)
            .first()
        )
    if not row:
        return _handle_subscription_refund_from_charge(db, charge) or True
    row.stripe_charge_id = charge_id or row.stripe_charge_id
    amount = int(charge.get("amount") or 0)
    refunded = int(charge.get("amount_refunded") or 0)
    row.updated_at = _now()
    if amount and refunded and refunded < amount:
        row.status = "disputed"
        row.refunded_at = _now()
        db.flush()
        return True
    row.status = "refunded"
    row.refunded_at = _now()
    db.flush()
    return True


def _handle_charge_dispute_created(db: Session, dispute: dict[str, Any]) -> bool:
    charge_id = _as_id(dispute.get("charge")) or dispute.get("charge")
    if not charge_id:
        return True
    row = (
        db.query(StripeLifetimePurchase)
        .filter(StripeLifetimePurchase.stripe_charge_id == charge_id)
        .first()
    )
    if row:
        row.status = "disputed"
        row.updated_at = _now()
        db.flush()
    return True


def process_webhook_event(db: Session, event: dict[str, Any]) -> dict[str, Any]:
    event_id = event.get("id")
    event_type = event.get("type") or ""
    if not event_id:
        raise StripeBillingError("stripe_webhook_processing_failed", "Invalid Stripe event.", 400)

    existing = (
        db.query(StripeWebhookEvent)
        .filter(StripeWebhookEvent.stripe_event_id == event_id)
        .first()
    )
    if existing:
        return {"status": "already_processed", "event_type": existing.event_type}

    row = StripeWebhookEvent(
        stripe_event_id=event_id,
        event_type=event_type,
        processing_status="failed",
    )
    db.add(row)
    try:
        db.flush()
    except IntegrityError:
        db.rollback()
        return {"status": "already_processed", "event_type": event_type}

    try:
        row.processing_status = "ignored"
        row.processed_at = _now()
        db.commit()
        return {
            "status": "ignored",
            "event_type": event_type,
            "reason": "free_software_no_paid_access",
        }
    except StripeBillingError as exc:
        db.rollback()
        _mark_event_failed(db, event_id, event_type, exc.error_code)
        raise
    except Exception as exc:  # noqa: BLE001
        db.rollback()
        _log.warning("STRIPE_WEBHOOK_PROCESSING_FAILED event=%s type=%s error=%s",
                     event_id, event_type, type(exc).__name__)
        _mark_event_failed(db, event_id, event_type, "stripe_webhook_processing_failed")
        raise StripeBillingError(
            "stripe_webhook_processing_failed",
            "Could not process Stripe webhook.",
            500,
        )


def _mark_event_failed(db: Session, event_id: str, event_type: str, reason: str) -> None:
    row = (
        db.query(StripeWebhookEvent)
        .filter(StripeWebhookEvent.stripe_event_id == event_id)
        .first()
    )
    if not row:
        row = StripeWebhookEvent(
            stripe_event_id=event_id,
            event_type=event_type,
            processing_status="failed",
        )
        db.add(row)
    row.processing_status = "failed"
    row.failure_reason = reason[:500]
    db.commit()
