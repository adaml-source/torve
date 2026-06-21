"""Tests for Stripe checkout, webhooks, and entitlement convergence."""
from __future__ import annotations

import hashlib
import hmac
import json
import uuid
from datetime import datetime, timedelta, timezone

from app.models import (
    StripeCheckoutSession,
    StripeCustomer,
    StripeLifetimePurchase,
    StripeRefundRequest,
    StripeSubscription,
    StripeWebhookEvent,
    User,
    UserEntitlement,
)
from app.billing import (
    ENTITLEMENT_LIFETIME,
    ENTITLEMENT_SUBSCRIPTION,
    SOURCE_STRIPE,
    grant_entitlement,
)
from app.security import create_access_token, hash_password
from app.stripe_config import get_stripe_readiness


MONTHLY = "price_monthly_test"
LIFETIME = "price_lifetime_test"
WEBHOOK_SECRET = "whsec_test_secret"
ADMIN_SECRET = "test-admin-secret"
ADMIN_HEADERS = {"X-Admin-Secret": ADMIN_SECRET}


def _set_stripe_config(monkeypatch, *, tax=True):
    monkeypatch.setattr("app.config.settings.STRIPE_SECRET_KEY", "sk_test_123")
    monkeypatch.setattr("app.config.settings.STRIPE_WEBHOOK_SECRET", WEBHOOK_SECRET)
    monkeypatch.setattr("app.config.settings.STRIPE_PRICE_MONTHLY", MONTHLY)
    monkeypatch.setattr("app.config.settings.STRIPE_PRICE_LIFETIME", LIFETIME)
    monkeypatch.setattr("app.config.settings.STRIPE_MONTHLY_PRICE_ID", "")
    monkeypatch.setattr("app.config.settings.STRIPE_LIFETIME_PRICE_ID", "")
    monkeypatch.setattr("app.config.settings.STRIPE_TAX_ENABLED", tax)
    monkeypatch.setattr("app.config.settings.STRIPE_SUCCESS_URL", "https://torve.app/billing/success")
    monkeypatch.setattr("app.config.settings.STRIPE_CANCEL_URL", "https://torve.app/billing/cancel")
    monkeypatch.setattr("app.config.settings.STRIPE_PORTAL_RETURN_URL", "https://torve.app/account/billing")


def _make_user(db):
    user = User(
        email=f"stripe-{uuid.uuid4().hex[:8]}@test.com",
        password_hash=hash_password("testpass123"),
        is_verified=True,
    )
    db.add(user)
    db.commit()
    db.refresh(user)
    return user


def _auth(user):
    return {"Authorization": f"Bearer {create_access_token(str(user.id))}"}


def test_refund_manual_review_email_has_clear_subject_and_text(monkeypatch):
    captured = {}

    def fake_send_email(**kwargs):
        captured.update(kwargs)
        return True

    monkeypatch.setattr("app.mail.send_email", fake_send_email)
    from app.mail import send_refund_manual_review_email

    assert send_refund_manual_review_email(
        to="support@torve.app",
        request_id="refund-123",
        user_id="user-123",
        user_email="customer@example.com",
        purchase_type="monthly",
        policy_reason="renewal_not_goodwill_refundable",
        request_reason="Please refund this renewal.",
        stripe_customer_id="cus_123",
    )
    assert captured["to"] == "support@torve.app"
    assert captured["subject"] == (
        "Manual review needed: refund refund-123 (monthly, renewal_not_goodwill_refundable)"
    )
    assert "Refund request refund-123 requires manual review" in captured["text"]
    assert "renewal_not_goodwill_refundable" in captured["text"]
    assert "Please refund this renewal." in captured["text"]
    assert "payment fingerprint" in captured["text"].lower()
    assert "raw Stripe payload" in captured["text"]


def _signed_event(payload: dict) -> tuple[bytes, dict[str, str]]:
    raw = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    ts = "123"
    sig = hmac.new(WEBHOOK_SECRET.encode(), ts.encode() + b"." + raw, hashlib.sha256).hexdigest()
    return raw, {"Stripe-Signature": f"t={ts},v1={sig}", "Content-Type": "application/json"}


def _checkout_session_event(event_id, user_id, *, price_id=LIFETIME, mode="payment", paid=True):
    return {
        "id": event_id,
        "type": "checkout.session.completed",
        "data": {
            "object": {
                "id": f"cs_{event_id}",
                "mode": mode,
                "status": "complete",
                "payment_status": "paid" if paid else "unpaid",
                "customer": "cus_test",
                "payment_intent": f"pi_{event_id}" if mode == "payment" else None,
                "subscription": f"sub_{event_id}" if mode == "subscription" else None,
                "client_reference_id": str(user_id),
                "metadata": {
                    "app": "torve",
                    "user_id": str(user_id),
                    "entitlement": "premium",
                    "purchase_type": "lifetime" if mode == "payment" else "monthly",
                    "platform": "web",
                },
                "line_items": {"data": [{"price": {"id": price_id}}]},
            }
        },
    }


def _invoice_paid_event(event_id, user_id, *, subscription_id="sub_monthly"):
    end = int((datetime.now(timezone.utc) + timedelta(days=31)).timestamp())
    start = int(datetime.now(timezone.utc).timestamp())
    return {
        "id": event_id,
        "type": "invoice.paid",
        "data": {
            "object": {
                "id": f"in_{event_id}",
                "customer": "cus_test",
                "subscription": subscription_id,
                "metadata": {"user_id": str(user_id)},
                "lines": {
                    "data": [
                        {
                            "price": {"id": MONTHLY},
                            "period": {"start": start, "end": end},
                        }
                    ]
                },
            }
        },
    }


def _event_id(prefix: str) -> str:
    return f"{prefix}_{uuid.uuid4().hex}"


def test_stripe_readiness_is_redacted(monkeypatch):
    monkeypatch.setattr("app.config.settings.STRIPE_SECRET_KEY", "")
    monkeypatch.setattr("app.config.settings.STRIPE_WEBHOOK_SECRET", "")
    monkeypatch.setattr("app.config.settings.STRIPE_PRICE_MONTHLY", "")
    monkeypatch.setattr("app.config.settings.STRIPE_PRICE_LIFETIME", "")
    monkeypatch.setattr("app.config.settings.STRIPE_MONTHLY_PRICE_ID", "")
    monkeypatch.setattr("app.config.settings.STRIPE_LIFETIME_PRICE_ID", "")
    monkeypatch.setattr("app.config.settings.STRIPE_PREMIUM_MONTHLY_PRICE_ID", "")
    monkeypatch.setattr("app.config.settings.STRIPE_PREMIUM_LIFETIME_PRICE_ID", "")
    monkeypatch.setattr("app.config.settings.STRIPE_TAX_ENABLED", False)

    readiness = get_stripe_readiness().as_public_dict()
    assert readiness == {
        "configured": False,
        "secret_key_present": False,
        "webhook_secret_present": False,
        "monthly_price_present": False,
        "lifetime_price_present": False,
        "tax_enabled": False,
    }
    assert "sk_" not in str(readiness)
    assert "whsec_" not in str(readiness)


def test_monthly_checkout_session_is_deprecated_free_response(client, db, monkeypatch):
    _set_stripe_config(monkeypatch)
    user = _make_user(db)
    calls = []

    def fake_post(path, data):
        calls.append((path, data))
        if path == "/customers":
            return {"id": "cus_monthly"}
        return {"id": "cs_monthly", "url": "https://checkout.stripe.test/monthly", "status": "open"}

    monkeypatch.setattr("app.stripe_service._stripe_post", fake_post)

    r = client.post(
        "/billing/stripe/checkout-session",
        json={"purchase_type": "monthly", "price_id": "price_attacker"},
        headers=_auth(user),
    )
    assert r.status_code == 200, r.text
    assert r.json()["deprecated"] is True
    assert r.json()["checkout_required"] is False
    assert r.json()["checkout_url"] is None
    assert r.json()["session_id"] is None
    assert r.json()["access_tier"] == "free"
    assert calls == []

    db.expire_all()
    db.refresh(user)
    assert user.has_premium_access is False


def test_checkout_deprecation_ignores_client_rollout_price_id_aliases(client, db, monkeypatch):
    monkeypatch.setattr("app.config.settings.STRIPE_SECRET_KEY", "sk_test_123")
    monkeypatch.setattr("app.config.settings.STRIPE_WEBHOOK_SECRET", WEBHOOK_SECRET)
    monkeypatch.setattr("app.config.settings.STRIPE_PRICE_MONTHLY", "")
    monkeypatch.setattr("app.config.settings.STRIPE_PRICE_LIFETIME", "")
    monkeypatch.setattr("app.config.settings.STRIPE_MONTHLY_PRICE_ID", MONTHLY)
    monkeypatch.setattr("app.config.settings.STRIPE_LIFETIME_PRICE_ID", LIFETIME)
    monkeypatch.setattr("app.config.settings.STRIPE_PREMIUM_MONTHLY_PRICE_ID", "")
    monkeypatch.setattr("app.config.settings.STRIPE_PREMIUM_LIFETIME_PRICE_ID", "")
    monkeypatch.setattr("app.config.settings.STRIPE_TAX_ENABLED", True)
    user = _make_user(db)
    calls = []

    def fake_post(path, data):
        calls.append((path, data))
        if path == "/customers":
            return {"id": "cus_alias"}
        return {"id": "cs_alias", "url": "https://checkout.stripe.test/alias", "status": "open"}

    monkeypatch.setattr("app.stripe_service._stripe_post", fake_post)

    r = client.post(
        "/billing/stripe/checkout-session",
        json={"purchase_type": "monthly"},
        headers=_auth(user),
    )
    assert r.status_code == 200, r.text
    assert r.json()["checkout_required"] is False
    assert calls == []


def test_lifetime_checkout_session_is_deprecated_free_response(client, db, monkeypatch):
    _set_stripe_config(monkeypatch)
    user = _make_user(db)
    calls = []

    def fake_post(path, data):
        calls.append((path, data))
        if path == "/customers":
            return {"id": "cus_lifetime"}
        return {"id": "cs_lifetime", "url": "https://checkout.stripe.test/lifetime", "status": "open"}

    monkeypatch.setattr("app.stripe_service._stripe_post", fake_post)

    r = client.post(
        "/billing/stripe/checkout-session",
        json={"purchase_type": "lifetime"},
        headers=_auth(user),
    )
    assert r.status_code == 200, r.text
    assert r.json()["deprecated"] is True
    assert r.json()["checkout_required"] is False
    assert r.json()["checkout_url"] is None
    assert r.json()["session_id"] is None
    assert calls == []
    db.expire_all()
    db.refresh(user)
    assert user.has_premium_access is False


def test_checkout_not_configured_is_sanitized(client, db, monkeypatch):
    monkeypatch.setattr("app.config.settings.STRIPE_SECRET_KEY", "")
    monkeypatch.setattr("app.config.settings.STRIPE_WEBHOOK_SECRET", "")
    monkeypatch.setattr("app.config.settings.STRIPE_PRICE_MONTHLY", "")
    monkeypatch.setattr("app.config.settings.STRIPE_PRICE_LIFETIME", "")
    monkeypatch.setattr("app.config.settings.STRIPE_MONTHLY_PRICE_ID", "")
    monkeypatch.setattr("app.config.settings.STRIPE_LIFETIME_PRICE_ID", "")
    monkeypatch.setattr("app.config.settings.STRIPE_PREMIUM_MONTHLY_PRICE_ID", "")
    monkeypatch.setattr("app.config.settings.STRIPE_PREMIUM_LIFETIME_PRICE_ID", "")
    user = _make_user(db)

    r = client.post(
        "/billing/stripe/checkout-session",
        json={"purchase_type": "monthly"},
        headers=_auth(user),
    )
    assert r.status_code == 200
    assert r.json()["checkout_required"] is False
    assert "sk_" not in r.text
    assert "whsec_" not in r.text


def test_webhook_signature_no_longer_required_for_ignored_events(client, monkeypatch):
    _set_stripe_config(monkeypatch)
    event_id = _event_id("evt_bad")
    r = client.post(
        "/billing/stripe/webhook",
        content=json.dumps({"id": event_id, "type": "checkout.session.completed"}).encode(),
        headers={"Stripe-Signature": "t=123,v1=bad"},
    )
    assert r.status_code == 200
    assert r.json()["status"] == "ignored"
    assert r.json()["reason"] == "free_software_no_paid_access"


def test_lifetime_checkout_webhook_records_ignored_event_without_entitlement(client, db, monkeypatch):
    _set_stripe_config(monkeypatch)
    user = _make_user(db)
    event_id = _event_id("evt_life")
    payload = _checkout_session_event(event_id, user.id)
    raw, headers = _signed_event(payload)

    r = client.post("/billing/stripe/webhook", content=raw, headers=headers)
    assert r.status_code == 200, r.text

    db.expire_all()
    db.refresh(user)
    assert user.has_lifetime_access is False
    assert user.has_premium_access is False
    ent = db.query(UserEntitlement).filter(
        UserEntitlement.source == "stripe",
        UserEntitlement.source_ref == f"pi_{event_id}",
    ).first()
    assert ent is None


def test_duplicate_stripe_event_is_idempotent(client, db, monkeypatch):
    _set_stripe_config(monkeypatch)
    user = _make_user(db)
    event_id = _event_id("evt_dup")
    payload = _checkout_session_event(event_id, user.id)
    raw, headers = _signed_event(payload)

    r1 = client.post("/billing/stripe/webhook", content=raw, headers=headers)
    r2 = client.post("/billing/stripe/webhook", content=raw, headers=headers)
    assert r1.status_code == 200
    assert r2.status_code == 200
    assert r2.json()["status"] == "already_processed"
    assert db.query(StripeWebhookEvent).filter(StripeWebhookEvent.stripe_event_id == event_id).count() == 1


def test_wrong_lifetime_price_does_not_grant(client, db, monkeypatch):
    _set_stripe_config(monkeypatch)
    user = _make_user(db)
    payload = _checkout_session_event(_event_id("evt_wrong"), user.id, price_id="price_other")
    raw, headers = _signed_event(payload)

    r = client.post("/billing/stripe/webhook", content=raw, headers=headers)
    assert r.status_code == 200
    db.expire_all()
    db.refresh(user)
    assert user.has_premium_access is False


def test_invoice_paid_webhook_does_not_grant_monthly_subscription(client, db, monkeypatch):
    _set_stripe_config(monkeypatch)
    user = _make_user(db)
    subscription_id = f"sub_invoice_{uuid.uuid4().hex}"
    payload = _invoice_paid_event(_event_id("evt_invoice"), user.id, subscription_id=subscription_id)
    raw, headers = _signed_event(payload)

    r = client.post("/billing/stripe/webhook", content=raw, headers=headers)
    assert r.status_code == 200, r.text
    db.expire_all()
    db.refresh(user)
    assert user.has_premium_access is False
    assert user.has_lifetime_access is False
    ent = db.query(UserEntitlement).filter(
        UserEntitlement.source == "stripe",
        UserEntitlement.source_ref == subscription_id,
    ).first()
    assert ent is None


def test_subscription_deleted_does_not_remove_admin_lifetime(client, db, monkeypatch, test_user):
    _set_stripe_config(monkeypatch)
    subscription_id = f"sub_delete_{uuid.uuid4().hex}"
    grant = UserEntitlement(
        user_id=test_user.id,
        entitlement_type="subscription_monthly",
        source="stripe",
        source_ref=subscription_id,
        product_id=MONTHLY,
        status="active",
        expires_at=datetime.now(timezone.utc) + timedelta(days=10),
    )
    db.add(grant)
    db.add(StripeCustomer(user_id=test_user.id, stripe_customer_id="cus_delete", email_snapshot=test_user.email))
    db.commit()

    payload = {
        "id": _event_id("evt_sub_delete"),
        "type": "customer.subscription.deleted",
        "data": {
            "object": {
                "id": subscription_id,
                "customer": "cus_delete",
                "status": "canceled",
                "metadata": {"user_id": str(test_user.id)},
                "items": {"data": [{"price": {"id": MONTHLY}}]},
            }
        },
    }
    raw, headers = _signed_event(payload)
    r = client.post("/billing/stripe/webhook", content=raw, headers=headers)
    assert r.status_code == 200, r.text

    db.expire_all()
    db.refresh(test_user)
    assert test_user.has_premium_access is True
    assert test_user.has_lifetime_access is True
    db.refresh(grant)
    assert grant.status == "active"


def test_lifetime_refund_webhook_is_ignored_for_access_and_history(client, db, monkeypatch):
    _set_stripe_config(monkeypatch)
    user = _make_user(db)
    event_id = _event_id("evt_refundable")
    raw, headers = _signed_event(_checkout_session_event(event_id, user.id))
    client.post("/billing/stripe/webhook", content=raw, headers=headers)

    refund = {
        "id": _event_id("evt_refund"),
        "type": "charge.refunded",
        "data": {
            "object": {
                "id": "ch_refund",
                "payment_intent": f"pi_{event_id}",
                "amount": 2399,
                "amount_refunded": 2399,
            }
        },
    }
    raw2, headers2 = _signed_event(refund)
    r = client.post("/billing/stripe/webhook", content=raw2, headers=headers2)
    assert r.status_code == 200, r.text

    db.expire_all()
    db.refresh(user)
    assert user.has_premium_access is False
    purchase = db.query(StripeLifetimePurchase).filter(
        StripeLifetimePurchase.stripe_payment_intent_id == f"pi_{event_id}"
    ).first()
    assert purchase is None


def test_portal_missing_customer_is_sanitized(client, db, monkeypatch):
    _set_stripe_config(monkeypatch)
    user = _make_user(db)
    r = client.post("/billing/stripe/portal-session", headers=_auth(user))
    assert r.status_code == 200
    assert r.json()["deprecated"] is True
    assert r.json()["portal_required"] is False
    assert r.json()["portal_url"] is None


def test_portal_existing_customer_returns_deprecated_free_response(client, db, monkeypatch):
    _set_stripe_config(monkeypatch)
    user = _make_user(db)
    db.add(StripeCustomer(user_id=user.id, stripe_customer_id="cus_portal", email_snapshot=user.email))
    db.commit()

    def fake_post(path, data):
        assert path == "/billing_portal/sessions"
        assert data["customer"] == "cus_portal"
        return {"url": "https://billing.stripe.test/session"}

    monkeypatch.setattr("app.stripe_service._stripe_post", fake_post)
    r = client.post("/billing/stripe/portal-session", headers=_auth(user))
    assert r.status_code == 200
    assert r.json()["deprecated"] is True
    assert r.json()["portal_required"] is False
    assert r.json()["portal_url"] is None


def test_lifetime_refund_request_auto_approves_first_purchase(client, db, monkeypatch):
    _set_stripe_config(monkeypatch)
    user = _make_user(db)
    db.add(StripeCustomer(user_id=user.id, stripe_customer_id="cus_refund", email_snapshot=user.email))
    db.add(
        StripeLifetimePurchase(
            user_id=user.id,
            stripe_customer_id="cus_refund",
            stripe_checkout_session_id="cs_life_refund",
            stripe_payment_intent_id="pi_life_refund",
            price_id=LIFETIME,
            status="active",
            purchased_at=datetime.now(timezone.utc),
        )
    )
    grant_entitlement(
        db,
        user_id=user.id,
        source=SOURCE_STRIPE,
        source_ref="pi_life_refund",
        entitlement_type=ENTITLEMENT_LIFETIME,
        product_id=LIFETIME,
    )
    db.commit()
    calls = []

    def fake_get(path, params=None):
        if path == "/payment_intents/pi_life_refund":
            return {"id": "pi_life_refund", "latest_charge": "ch_life_refund"}
        if path == "/charges/ch_life_refund":
            return {
                "id": "ch_life_refund",
                "payment_method_details": {
                    "type": "card",
                    "card": {"fingerprint": "raw-card-fingerprint"},
                },
            }
        raise AssertionError(path)

    def fake_post(path, data, idempotency_key=None):
        calls.append((path, data, idempotency_key))
        assert path == "/refunds"
        assert data["payment_intent"] == "pi_life_refund"
        assert data["reason"] == "requested_by_customer"
        assert idempotency_key
        return {"id": "re_life_refund"}

    monkeypatch.setattr("app.stripe_service._stripe_get", fake_get)
    monkeypatch.setattr("app.stripe_service._stripe_post", fake_post)

    r = client.post(
        "/billing/stripe/refund-request",
        json={"purchase_type": "lifetime", "reason": "Changed my mind"},
        headers=_auth(user),
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["status"] == "approved"
    assert calls and calls[0][0] == "/refunds"

    db.expire_all()
    request = db.query(StripeRefundRequest).filter(StripeRefundRequest.id == uuid.UUID(body["request_id"])).one()
    assert request.stripe_refund_id == "re_life_refund"
    assert request.payment_fingerprint_hash
    assert request.payment_fingerprint_hash != "raw-card-fingerprint"
    purchase = db.query(StripeLifetimePurchase).filter(
        StripeLifetimePurchase.stripe_payment_intent_id == "pi_life_refund"
    ).one()
    assert purchase.status == "refunded"
    entitlement = db.query(UserEntitlement).filter(
        UserEntitlement.source == SOURCE_STRIPE,
        UserEntitlement.source_ref == "pi_life_refund",
        UserEntitlement.entitlement_type == ENTITLEMENT_LIFETIME,
    ).one()
    assert entitlement.status == "active"


def test_lifetime_refund_request_repeated_goodwill_goes_to_review(client, db, monkeypatch):
    _set_stripe_config(monkeypatch)
    monkeypatch.setattr("app.config.settings.REFUND_REVIEW_EMAIL", "support@torve.app")
    user = _make_user(db)
    db.add(StripeCustomer(user_id=user.id, stripe_customer_id="cus_repeat", email_snapshot=user.email))
    db.add(
        StripeLifetimePurchase(
            user_id=user.id,
            stripe_customer_id="cus_repeat",
            stripe_checkout_session_id="cs_life_repeat",
            stripe_payment_intent_id="pi_life_repeat",
            price_id=LIFETIME,
            status="active",
            purchased_at=datetime.now(timezone.utc),
        )
    )
    db.add(
        StripeRefundRequest(
            user_id=user.id,
            stripe_customer_id="cus_repeat",
            purchase_type="monthly",
            target_kind="subscription",
            status="approved",
            policy_reason="first_purchase_goodwill",
        )
    )
    db.commit()

    monkeypatch.setattr("app.stripe_service._stripe_get", lambda path, params=None: {})

    def fail_post(path, data, idempotency_key=None):
        raise AssertionError("refund should not be sent to Stripe")

    monkeypatch.setattr("app.stripe_service._stripe_post", fail_post)
    emails = []
    monkeypatch.setattr(
        "app.stripe_service.send_refund_manual_review_email",
        lambda **kwargs: emails.append(kwargs) or True,
    )
    r = client.post(
        "/billing/stripe/refund-request",
        json={"purchase_type": "lifetime"},
        headers=_auth(user),
    )
    assert r.status_code == 200, r.text
    assert r.json()["status"] == "manual_review"
    row = db.query(StripeRefundRequest).filter(StripeRefundRequest.id == uuid.UUID(r.json()["request_id"])).one()
    assert row.policy_reason == "prior_goodwill_refund"
    assert len(emails) == 1
    assert emails[0]["to"] == "support@torve.app"
    assert emails[0]["request_id"] == str(row.id)
    assert emails[0]["user_id"] == str(user.id)
    assert emails[0]["user_email"] == user.email
    assert emails[0]["purchase_type"] == "lifetime"
    assert emails[0]["policy_reason"] == "prior_goodwill_refund"
    assert "payment_fingerprint" not in str(emails[0])


def test_monthly_refund_request_auto_approves_initial_payment(client, db, monkeypatch):
    _set_stripe_config(monkeypatch)
    user = _make_user(db)
    now = datetime.now(timezone.utc)
    period_end = now + timedelta(days=30)
    db.add(StripeCustomer(user_id=user.id, stripe_customer_id="cus_month_refund", email_snapshot=user.email))
    db.add(
        StripeCheckoutSession(
            user_id=user.id,
            stripe_session_id="cs_month_refund",
            stripe_customer_id="cus_month_refund",
            price_id=MONTHLY,
            purchase_type="monthly",
            mode="subscription",
            status="complete",
            payment_status="paid",
            stripe_subscription_id="sub_month_refund",
            created_at=now,
            updated_at=now,
        )
    )
    db.add(
        StripeSubscription(
            user_id=user.id,
            stripe_customer_id="cus_month_refund",
            stripe_subscription_id="sub_month_refund",
            stripe_price_id=MONTHLY,
            status="active",
            current_period_start=now,
            current_period_end=period_end,
            cancel_at_period_end=False,
            latest_invoice_id="in_month_refund",
        )
    )
    grant_entitlement(
        db,
        user_id=user.id,
        source=SOURCE_STRIPE,
        source_ref="sub_month_refund",
        entitlement_type=ENTITLEMENT_SUBSCRIPTION,
        product_id=MONTHLY,
        expires_at=period_end,
        auto_renew=True,
    )
    db.commit()
    calls = []

    def fake_get(path, params=None):
        if path == "/invoices/in_month_refund":
            return {"id": "in_month_refund", "payment_intent": "pi_month_refund", "charge": "ch_month_refund"}
        if path == "/charges/ch_month_refund":
            return {
                "id": "ch_month_refund",
                "payment_method_details": {"type": "card", "card": {"fingerprint": "fp-month"}},
            }
        raise AssertionError(path)

    def fake_post(path, data, idempotency_key=None):
        calls.append((path, data, idempotency_key))
        if path == "/subscriptions/sub_month_refund/cancel":
            return {"id": "sub_month_refund", "status": "canceled"}
        if path == "/refunds":
            assert data["payment_intent"] == "pi_month_refund"
            return {"id": "re_month_refund"}
        raise AssertionError(path)

    monkeypatch.setattr("app.stripe_service._stripe_get", fake_get)
    monkeypatch.setattr("app.stripe_service._stripe_post", fake_post)
    r = client.post(
        "/billing/stripe/refund-request",
        json={"purchase_type": "monthly"},
        headers=_auth(user),
    )
    assert r.status_code == 200, r.text
    assert r.json()["status"] == "approved"
    assert [call[0] for call in calls] == ["/refunds", "/subscriptions/sub_month_refund/cancel"]

    db.expire_all()
    sub = db.query(StripeSubscription).filter(
        StripeSubscription.stripe_subscription_id == "sub_month_refund"
    ).one()
    assert sub.status == "canceled"
    entitlement = db.query(UserEntitlement).filter(
        UserEntitlement.source == SOURCE_STRIPE,
        UserEntitlement.source_ref == "sub_month_refund",
        UserEntitlement.entitlement_type == ENTITLEMENT_SUBSCRIPTION,
    ).one()
    assert entitlement.status == "active"


def test_monthly_refund_request_renewal_goes_to_manual_review(client, db, monkeypatch):
    _set_stripe_config(monkeypatch)
    user = _make_user(db)
    now = datetime.now(timezone.utc)
    db.add(StripeCustomer(user_id=user.id, stripe_customer_id="cus_renewal", email_snapshot=user.email))
    db.add(
        StripeCheckoutSession(
            user_id=user.id,
            stripe_session_id="cs_month_old",
            stripe_customer_id="cus_renewal",
            price_id=MONTHLY,
            purchase_type="monthly",
            mode="subscription",
            status="complete",
            payment_status="paid",
            stripe_subscription_id="sub_renewal",
            created_at=now - timedelta(days=35),
            updated_at=now - timedelta(days=35),
        )
    )
    db.add(
        StripeSubscription(
            user_id=user.id,
            stripe_customer_id="cus_renewal",
            stripe_subscription_id="sub_renewal",
            stripe_price_id=MONTHLY,
            status="active",
            current_period_start=now,
            current_period_end=now + timedelta(days=30),
            cancel_at_period_end=False,
            latest_invoice_id="in_renewal",
        )
    )
    db.commit()

    def fake_get(path, params=None):
        if path == "/invoices/in_renewal":
            return {"id": "in_renewal", "payment_intent": "pi_renewal", "charge": "ch_renewal"}
        if path == "/charges/ch_renewal":
            return {"id": "ch_renewal", "payment_method_details": {"type": "card", "card": {"fingerprint": "fp-renewal"}}}
        raise AssertionError(path)

    monkeypatch.setattr("app.stripe_service._stripe_get", fake_get)

    def fail_post(path, data, idempotency_key=None):
        raise AssertionError("renewal should not auto refund")

    monkeypatch.setattr("app.stripe_service._stripe_post", fail_post)
    r = client.post(
        "/billing/stripe/refund-request",
        json={"purchase_type": "monthly"},
        headers=_auth(user),
    )
    assert r.status_code == 200, r.text
    assert r.json()["status"] == "manual_review"
    row = db.query(StripeRefundRequest).filter(StripeRefundRequest.id == uuid.UUID(r.json()["request_id"])).one()
    assert row.policy_reason == "renewal_not_goodwill_refundable"


def test_manual_refund_review_email_failure_does_not_block_request(client, db, monkeypatch):
    _set_stripe_config(monkeypatch)
    user = _make_user(db)
    db.add(StripeCustomer(user_id=user.id, stripe_customer_id="cus_mail_fail", email_snapshot=user.email))
    db.commit()

    def fail_email(**kwargs):
        raise RuntimeError("mail offline")

    monkeypatch.setattr("app.stripe_service.send_refund_manual_review_email", fail_email)
    r = client.post(
        "/billing/stripe/refund-request",
        json={"purchase_type": "lifetime"},
        headers=_auth(user),
    )
    assert r.status_code == 200, r.text
    assert r.json()["status"] == "manual_review"
    row = db.query(StripeRefundRequest).filter(StripeRefundRequest.id == uuid.UUID(r.json()["request_id"])).one()
    assert row.policy_reason == "purchase_not_found"


def test_admin_approve_lifetime_manual_refund_revoke_access(client, db, monkeypatch):
    _set_stripe_config(monkeypatch)
    monkeypatch.setattr("app.config.settings.PADDLE_ADMIN_SECRET", ADMIN_SECRET)
    user = _make_user(db)
    db.add(StripeCustomer(user_id=user.id, stripe_customer_id="cus_admin_life", email_snapshot=user.email))
    db.add(
        StripeLifetimePurchase(
            user_id=user.id,
            stripe_customer_id="cus_admin_life",
            stripe_checkout_session_id="cs_admin_life",
            stripe_payment_intent_id="pi_admin_life",
            price_id=LIFETIME,
            status="active",
            purchased_at=datetime.now(timezone.utc),
        )
    )
    grant_entitlement(
        db,
        user_id=user.id,
        source=SOURCE_STRIPE,
        source_ref="pi_admin_life",
        entitlement_type=ENTITLEMENT_LIFETIME,
        product_id=LIFETIME,
    )
    row = StripeRefundRequest(
        user_id=user.id,
        stripe_customer_id="cus_admin_life",
        purchase_type="lifetime",
        target_kind="lifetime",
        status="manual_review",
        policy_reason="prior_goodwill_refund",
        stripe_payment_intent_id="pi_admin_life",
        stripe_charge_id="ch_admin_life",
    )
    db.add(row)
    db.commit()
    request_id = row.id
    calls = []

    def fake_post(path, data, idempotency_key=None):
        calls.append((path, data, idempotency_key))
        assert path == "/refunds"
        assert data["payment_intent"] == "pi_admin_life"
        return {"id": "re_admin_life"}

    monkeypatch.setattr("app.stripe_service._stripe_post", fake_post)
    r = client.post(
        f"/admin/billing/stripe-refund-requests/{request_id}/approve",
        headers=ADMIN_HEADERS,
    )
    assert r.status_code == 200, r.text
    assert r.json()["status"] == "approved"
    assert r.json()["stripe_refund_id"] == "re_admin_life"
    assert len(calls) == 1

    db.expire_all()
    row = db.query(StripeRefundRequest).filter(StripeRefundRequest.id == request_id).one()
    assert row.status == "approved"
    assert row.stripe_refund_id == "re_admin_life"
    purchase = db.query(StripeLifetimePurchase).filter(
        StripeLifetimePurchase.stripe_payment_intent_id == "pi_admin_life"
    ).one()
    assert purchase.status == "refunded"
    entitlement = db.query(UserEntitlement).filter(
        UserEntitlement.source == SOURCE_STRIPE,
        UserEntitlement.source_ref == "pi_admin_life",
        UserEntitlement.entitlement_type == ENTITLEMENT_LIFETIME,
    ).one()
    assert entitlement.status == "active"

    r = client.post(
        f"/admin/billing/stripe-refund-requests/{request_id}/approve",
        headers=ADMIN_HEADERS,
    )
    assert r.status_code == 200, r.text
    assert r.json()["stripe_refund_id"] == "re_admin_life"
    assert len(calls) == 1


def test_admin_approve_monthly_manual_refund_expires_subscription(client, db, monkeypatch):
    _set_stripe_config(monkeypatch)
    monkeypatch.setattr("app.config.settings.PADDLE_ADMIN_SECRET", ADMIN_SECRET)
    user = _make_user(db)
    now = datetime.now(timezone.utc)
    period_end = now + timedelta(days=30)
    db.add(StripeCustomer(user_id=user.id, stripe_customer_id="cus_admin_month", email_snapshot=user.email))
    db.add(
        StripeSubscription(
            user_id=user.id,
            stripe_customer_id="cus_admin_month",
            stripe_subscription_id="sub_admin_month",
            stripe_price_id=MONTHLY,
            status="active",
            current_period_start=now,
            current_period_end=period_end,
            cancel_at_period_end=False,
            latest_invoice_id="in_admin_month",
        )
    )
    grant_entitlement(
        db,
        user_id=user.id,
        source=SOURCE_STRIPE,
        source_ref="sub_admin_month",
        entitlement_type=ENTITLEMENT_SUBSCRIPTION,
        product_id=MONTHLY,
        expires_at=period_end,
        auto_renew=True,
    )
    row = StripeRefundRequest(
        user_id=user.id,
        stripe_customer_id="cus_admin_month",
        purchase_type="monthly",
        target_kind="subscription",
        status="manual_review",
        policy_reason="renewal_not_goodwill_refundable",
        stripe_payment_intent_id="pi_admin_month",
        stripe_charge_id="ch_admin_month",
        stripe_subscription_id="sub_admin_month",
        stripe_invoice_id="in_admin_month",
    )
    db.add(row)
    db.commit()
    request_id = row.id
    calls = []

    def fake_post(path, data, idempotency_key=None):
        calls.append((path, data, idempotency_key))
        if path == "/refunds":
            assert data["payment_intent"] == "pi_admin_month"
            return {"id": "re_admin_month"}
        if path == "/subscriptions/sub_admin_month/cancel":
            return {"id": "sub_admin_month", "status": "canceled"}
        raise AssertionError(path)

    monkeypatch.setattr("app.stripe_service._stripe_post", fake_post)
    r = client.post(
        f"/admin/billing/stripe-refund-requests/{request_id}/approve",
        headers=ADMIN_HEADERS,
    )
    assert r.status_code == 200, r.text
    assert r.json()["status"] == "approved"
    assert r.json()["stripe_refund_id"] == "re_admin_month"
    assert [call[0] for call in calls] == ["/refunds", "/subscriptions/sub_admin_month/cancel"]

    db.expire_all()
    sub = db.query(StripeSubscription).filter(
        StripeSubscription.stripe_subscription_id == "sub_admin_month"
    ).one()
    assert sub.status == "canceled"
    entitlement = db.query(UserEntitlement).filter(
        UserEntitlement.source == SOURCE_STRIPE,
        UserEntitlement.source_ref == "sub_admin_month",
        UserEntitlement.entitlement_type == ENTITLEMENT_SUBSCRIPTION,
    ).one()
    assert entitlement.status == "active"


def test_admin_approve_manual_refund_requires_admin_secret(client, db, monkeypatch):
    _set_stripe_config(monkeypatch)
    monkeypatch.setattr("app.config.settings.PADDLE_ADMIN_SECRET", ADMIN_SECRET)
    request_id = uuid.uuid4()
    assert client.post(f"/admin/billing/stripe-refund-requests/{request_id}/approve").status_code == 403
    assert client.post(
        f"/admin/billing/stripe-refund-requests/{request_id}/approve",
        headers={"X-Admin-Secret": "wrong"},
    ).status_code == 403


def test_admin_approve_manual_refund_without_payment_reference_fails(client, db, monkeypatch):
    _set_stripe_config(monkeypatch)
    monkeypatch.setattr("app.config.settings.PADDLE_ADMIN_SECRET", ADMIN_SECRET)
    user = _make_user(db)
    row = StripeRefundRequest(
        user_id=user.id,
        stripe_customer_id="cus_no_ref",
        purchase_type="lifetime",
        target_kind="lifetime",
        status="manual_review",
        policy_reason="payment_reference_missing",
    )
    db.add(row)
    db.commit()

    def fail_post(path, data, idempotency_key=None):
        raise AssertionError("Stripe refund should not be attempted")

    monkeypatch.setattr("app.stripe_service._stripe_post", fail_post)
    r = client.post(
        f"/admin/billing/stripe-refund-requests/{row.id}/approve",
        headers=ADMIN_HEADERS,
    )
    assert r.status_code == 409, r.text
    assert r.json()["detail"]["error_code"] == "stripe_refund_missing_payment_reference"


def test_subscription_refund_webhook_is_ignored_for_access(client, db, monkeypatch):
    _set_stripe_config(monkeypatch)
    user = _make_user(db)
    now = datetime.now(timezone.utc)
    period_end = now + timedelta(days=30)
    db.add(StripeCustomer(user_id=user.id, stripe_customer_id="cus_sub_refund", email_snapshot=user.email))
    db.add(
        StripeSubscription(
            user_id=user.id,
            stripe_customer_id="cus_sub_refund",
            stripe_subscription_id="sub_refund_webhook",
            stripe_price_id=MONTHLY,
            status="active",
            current_period_start=now,
            current_period_end=period_end,
            cancel_at_period_end=False,
            latest_invoice_id="in_refund_webhook",
        )
    )
    grant_entitlement(
        db,
        user_id=user.id,
        source=SOURCE_STRIPE,
        source_ref="sub_refund_webhook",
        entitlement_type=ENTITLEMENT_SUBSCRIPTION,
        product_id=MONTHLY,
        expires_at=period_end,
        auto_renew=True,
    )
    db.commit()

    def fake_get(path, params=None):
        assert path == "/invoices/in_refund_webhook"
        return {
            "id": "in_refund_webhook",
            "subscription": "sub_refund_webhook",
            "lines": {"data": [{"price": {"id": MONTHLY}, "period": {"end": int(period_end.timestamp())}}]},
        }

    calls = []

    def fake_post(path, data, idempotency_key=None):
        calls.append(path)
        assert path == "/subscriptions/sub_refund_webhook/cancel"
        return {"id": "sub_refund_webhook", "status": "canceled"}

    monkeypatch.setattr("app.stripe_service._stripe_get", fake_get)
    monkeypatch.setattr("app.stripe_service._stripe_post", fake_post)
    raw, headers = _signed_event(
        {
            "id": _event_id("evt_sub_refund"),
            "type": "charge.refunded",
            "data": {
                "object": {
                    "id": "ch_sub_refund",
                    "invoice": "in_refund_webhook",
                    "amount": 199,
                    "amount_refunded": 199,
                }
            },
        }
    )
    r = client.post("/billing/stripe/webhook", content=raw, headers=headers)
    assert r.status_code == 200, r.text
    assert calls == []

    db.expire_all()
    entitlement = db.query(UserEntitlement).filter(
        UserEntitlement.source == SOURCE_STRIPE,
        UserEntitlement.source_ref == "sub_refund_webhook",
        UserEntitlement.entitlement_type == ENTITLEMENT_SUBSCRIPTION,
    ).one()
    assert entitlement.status == "active"
    db.refresh(user)
    assert user.has_premium_access is True


def test_subscription_cancel_at_period_end_keeps_access_until_period_end(client, db, monkeypatch):
    _set_stripe_config(monkeypatch)
    user = _make_user(db)
    period_end = datetime.now(timezone.utc) + timedelta(days=30)
    db.add(StripeCustomer(user_id=user.id, stripe_customer_id="cus_cancel_period", email_snapshot=user.email))
    grant_entitlement(
        db,
        user_id=user.id,
        source=SOURCE_STRIPE,
        source_ref="sub_cancel_period",
        entitlement_type=ENTITLEMENT_SUBSCRIPTION,
        product_id=MONTHLY,
        expires_at=period_end,
        auto_renew=True,
    )
    db.commit()
    raw, headers = _signed_event(
        {
            "id": _event_id("evt_cancel_period"),
            "type": "customer.subscription.updated",
            "data": {
                "object": {
                    "id": "sub_cancel_period",
                    "customer": "cus_cancel_period",
                    "status": "active",
                    "current_period_end": int(period_end.timestamp()),
                    "cancel_at_period_end": True,
                    "metadata": {"user_id": str(user.id)},
                    "items": {"data": [{"price": {"id": MONTHLY}}]},
                }
            },
        }
    )
    r = client.post("/billing/stripe/webhook", content=raw, headers=headers)
    assert r.status_code == 200, r.text

    db.expire_all()
    entitlement = db.query(UserEntitlement).filter(
        UserEntitlement.source == SOURCE_STRIPE,
        UserEntitlement.source_ref == "sub_cancel_period",
        UserEntitlement.entitlement_type == ENTITLEMENT_SUBSCRIPTION,
    ).one()
    assert entitlement.status == "active"
    assert entitlement.auto_renew is True
    db.refresh(user)
    assert user.has_premium_access is True
