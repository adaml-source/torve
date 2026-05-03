"""
Paddle webhook handler. Production-hardened.

Handles:
  - transaction.completed  -> grant entitlement
  - transaction.updated (status=refunded/reversed) -> revoke entitlement

All entitlement logic is delegated to app.billing.
Signature verification uses raw body bytes.
Idempotency enforced at DB constraint level.
Account binding via server-created purchase intents.
"""
import hashlib
import hmac
import json
import logging
import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy.orm import Session

from app.billing import (
    ENTITLEMENT_LIFETIME,
    ENTITLEMENT_SUBSCRIPTION,
    SOURCE_PADDLE,
    classify_paddle_product,
    grant_entitlement,
    resolve_purchase_intent,
    revoke_entitlement,
)
from app.config import settings
from app.deps import get_db
from app.models import WebPayment

_log = logging.getLogger(__name__)

router = APIRouter(prefix="/webhooks", tags=["webhooks"])


def _verify_signature(raw_body: bytes, signature_header: str | None) -> bool:
    """Verify Paddle webhook HMAC-SHA256 signature against raw body bytes."""
    if not settings.PADDLE_WEBHOOK_SECRET:
        _log.warning("PADDLE_WEBHOOK_SECRET not set, skipping verification in dev")
        return True

    if not signature_header:
        return False

    parts = {}
    for part in signature_header.split(";"):
        if "=" in part:
            k, v = part.split("=", 1)
            parts[k] = v

    ts = parts.get("ts", "")
    h1 = parts.get("h1", "")
    if not ts or not h1:
        return False

    signed_payload = ts + ":" + raw_body.decode("utf-8")
    expected = hmac.new(
        settings.PADDLE_WEBHOOK_SECRET.encode(),
        signed_payload.encode(),
        hashlib.sha256,
    ).hexdigest()

    return hmac.compare_digest(expected, h1)


@router.post("/paddle")
async def paddle_webhook(request: Request, db: Session = Depends(get_db)):
    """Handle Paddle webhook events. Signature verified against raw body."""
    raw_body = await request.body()
    signature = request.headers.get("paddle-signature")

    if not _verify_signature(raw_body, signature):
        _log.warning("Paddle webhook signature verification failed")
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid signature")

    try:
        payload = json.loads(raw_body)
    except json.JSONDecodeError:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid JSON")

    event_type = payload.get("event_type", "")
    event_id = payload.get("event_id", "")
    data = payload.get("data", {})

    _log.info("Paddle webhook: event_type=%s event_id=%s", event_type, event_id)

    if event_type == "transaction.completed":
        return _handle_completed(db, data, event_type, event_id, payload)
    elif event_type == "transaction.updated":
        return _handle_updated(db, data, event_type, event_id, payload)
    elif event_type == "transaction.payment_failed":
        _log.info("Payment failed: transaction=%s", data.get("id"))
        return {"status": "noted"}

    return {"status": "ignored", "event_type": event_type}


def _extract_payment_fields(data: dict) -> dict:
    """Extract normalized payment fields from Paddle transaction data."""
    items = data.get("items", [])
    product_id = ""
    price_id = ""
    for item in items:
        price = item.get("price", {})
        product_id = price.get("product_id", item.get("product_id", ""))
        price_id = price.get("id", item.get("price_id", ""))
        break

    details = data.get("details", {})
    totals = details.get("totals", {})
    amount = totals.get("total", totals.get("grand_total", "0"))
    currency = data.get("currency_code", "USD")

    discount_data = data.get("discount")
    discount_code = discount_data.get("code") if discount_data else None

    return {
        "transaction_id": data.get("id", ""),
        "customer_id": data.get("customer_id", ""),
        "product_id": product_id,
        "price_id": price_id,
        "amount": str(amount),
        "currency": currency,
        "discount_code": discount_code,
    }


def _resolve_user_id(data: dict, db: Session) -> tuple[uuid.UUID | None, uuid.UUID | None]:
    """Resolve Torve user_id from purchase intent (preferred) or custom_data (fallback).
    Returns (user_id, purchase_intent_id)."""
    custom_data = data.get("custom_data") or {}

    # Preferred: purchase intent
    intent_id_str = custom_data.get("torve_purchase_intent")
    if intent_id_str:
        intent = resolve_purchase_intent(db, intent_id_str)
        if intent:
            return intent.user_id, intent.id
        _log.warning("Invalid/expired purchase intent: %s", intent_id_str)

    # Fallback: raw user_id (less secure, for backward compatibility)
    user_id_str = custom_data.get("torve_user_id", "")
    if user_id_str:
        try:
            return uuid.UUID(user_id_str), None
        except ValueError:
            pass

    return None, None


def _handle_completed(db: Session, data: dict, event_type: str, event_id: str, payload: dict):
    """Process completed transaction: record payment, grant entitlement."""
    fields = _extract_payment_fields(data)
    txn_id = fields["transaction_id"]

    if not txn_id:
        _log.warning("Completed event missing transaction ID")
        return {"status": "error", "reason": "missing_transaction_id"}

    # Idempotency
    existing = db.query(WebPayment).filter(WebPayment.paddle_transaction_id == txn_id).first()
    if existing:
        _log.info("Transaction %s already processed", txn_id)
        return {"status": "already_processed"}

    user_id, intent_id = _resolve_user_id(data, db)
    product_class = classify_paddle_product(fields["price_id"])
    # Fallback for unconfigured envs (dev/test): treat any product as
    # lifetime so local dev doesn't need real Paddle config. Only fires
    # when Paddle is *genuinely* unconfigured (no PADDLE_PRICE_ID and no
    # PADDLE_SUBSCRIPTION_PRICE_ID), not when it's configured but the
    # webhook references a different price. Without this tightening,
    # any non-prod env (APP_ENV=test/staging/dev) would silently grant
    # lifetime to wrong-product webhooks. Caught by Backend CI 2026-05-03
    # via tests/test_payments.py::test_wrong_product_no_entitlement.
    paddle_unconfigured = (
        not settings.PADDLE_PRICE_ID
        and not settings.PADDLE_SUBSCRIPTION_PRICE_ID
    )
    if product_class is None and settings.APP_ENV != "production" and paddle_unconfigured:
        product_class = "lifetime"

    # Record payment
    payment = WebPayment(
        paddle_transaction_id=txn_id,
        paddle_customer_id=fields["customer_id"],
        user_id=user_id,
        product_id=fields["product_id"],
        price_id=fields["price_id"],
        amount=fields["amount"],
        currency=fields["currency"],
        discount_code=fields["discount_code"],
        status="completed",
        entitlement_granted=False,
        paddle_event_type=event_type,
        paddle_event_id=event_id,
        purchase_intent_id=intent_id,
        raw_payload=payload,
    )
    db.add(payment)

    # Grant entitlement based on product classification
    if product_class and user_id:
        if product_class == "subscription":
            ent_type = ENTITLEMENT_SUBSCRIPTION
        else:
            ent_type = ENTITLEMENT_LIFETIME
        ent = grant_entitlement(db, user_id, SOURCE_PADDLE, txn_id, ent_type,
                                product_id=fields["product_id"])
        if ent:
            payment.entitlement_granted = True
            # Mark intent as completed
            if intent_id:
                from app.models import PurchaseIntent
                intent = db.query(PurchaseIntent).filter(PurchaseIntent.id == intent_id).first()
                if intent:
                    intent.status = "completed"
    elif product_class:
        _log.warning("Premium payment %s (%s) has no linked user", txn_id, product_class)
    else:
        _log.info("Unrecognized product in transaction %s", txn_id)

    db.commit()
    return {"status": "processed", "entitlement_granted": payment.entitlement_granted}


def _handle_updated(db: Session, data: dict, event_type: str, event_id: str, payload: dict):
    """Handle transaction updates including refunds and reversals."""
    txn_id = data.get("id", "")
    txn_status = data.get("status", "")

    if not txn_id:
        return {"status": "ignored", "reason": "no_transaction_id"}

    # Only process refund/reversal statuses
    if txn_status not in ("refunded", "partially_refunded", "reversed"):
        _log.info("Transaction %s updated to %s (not a reversal)", txn_id, txn_status)
        return {"status": "noted", "transaction_status": txn_status}

    now = datetime.now(timezone.utc)
    payment = db.query(WebPayment).filter(WebPayment.paddle_transaction_id == txn_id).first()

    if payment:
        payment.status = txn_status
        payment.last_event_type = event_type
        payment.last_event_at = now
        if txn_status == "refunded":
            payment.refunded_at = now
        payment.revoked_at = now
        payment.updated_at = now

        # Revoke entitlement (try both types)
        ent = revoke_entitlement(db, SOURCE_PADDLE, txn_id, ENTITLEMENT_LIFETIME)
        if not ent:
            ent = revoke_entitlement(db, SOURCE_PADDLE, txn_id, ENTITLEMENT_SUBSCRIPTION)
        if ent:
            payment.entitlement_granted = False
            _log.info("Entitlement revoked for refunded transaction %s", txn_id)
    else:
        _log.warning("Refund/reversal for unknown transaction %s, recording for reconciliation", txn_id)
        # Store the event even if we don't have the original payment
        fields = _extract_payment_fields(data)
        orphan = WebPayment(
            paddle_transaction_id=txn_id,
            paddle_customer_id=fields["customer_id"],
            product_id=fields.get("product_id", "unknown"),
            price_id=fields.get("price_id", "unknown"),
            amount=fields.get("amount", "0"),
            currency=fields.get("currency", "USD"),
            status=txn_status,
            last_event_type=event_type,
            last_event_at=now,
            refunded_at=now if txn_status == "refunded" else None,
            paddle_event_id=event_id,
            raw_payload=payload,
        )
        db.add(orphan)

    db.commit()
    return {"status": "refund_processed", "transaction_id": txn_id}
