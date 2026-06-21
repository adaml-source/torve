"""Google Play voided-purchase refund reconciliation tests."""
from __future__ import annotations

import uuid
from datetime import datetime, timezone

import pytest

from app.billing import (
    ENTITLEMENT_LIFETIME,
    ENTITLEMENT_SUBSCRIPTION,
    SOURCE_GOOGLE_PLAY,
    grant_entitlement,
)
from app.google_play_voided import apply_google_play_voided_purchase
from app.models import LifetimeGrantRecord, User, UserEntitlement, WebPayment
from app.security import hash_password


@pytest.fixture(autouse=True)
def cleanup_google_play_voided_test_rows(db):
    yield
    try:
        db.query(WebPayment).filter(
            WebPayment.paddle_event_type == "google_play_voided_test"
        ).delete(synchronize_session=False)
        db.query(LifetimeGrantRecord).filter(
            LifetimeGrantRecord.email.like("gp-voided-%@test.com")
        ).delete(synchronize_session=False)
        db.query(User).filter(
            User.email.like("gp-voided-%@test.com")
        ).delete(synchronize_session=False)
        db.commit()
    except Exception:
        db.rollback()


def _make_user(db):
    user = User(
        email=f"gp-voided-{uuid.uuid4().hex[:8]}@test.com",
        password_hash=hash_password("testpass"),
        is_verified=True,
    )
    db.add(user)
    db.flush()
    return user


def _add_payment(db, user, ref, product_id="com.torve.pro.lifetime"):
    payment = WebPayment(
        paddle_transaction_id=ref,
        user_id=user.id,
        product_id=product_id,
        price_id=SOURCE_GOOGLE_PLAY,
        amount="0",
        currency="USD",
        status="completed",
        entitlement_granted=True,
        paddle_event_type="google_play_voided_test",
    )
    db.add(payment)
    db.flush()
    return payment


def _voided_time_ms() -> str:
    return str(int(datetime(2026, 5, 30, tzinfo=timezone.utc).timestamp() * 1000))


def test_voided_lifetime_purchase_revokes_by_purchase_token_prefix(db):
    user = _make_user(db)
    purchase_token = f"test-token-{uuid.uuid4().hex}-AO-J1OwvzmQfPLqAWXIcHpjmvbx-k9B79yjpDmb"
    ref = f"gp_{purchase_token[:64]}"
    ent = grant_entitlement(
        db,
        user.id,
        SOURCE_GOOGLE_PLAY,
        ref,
        ENTITLEMENT_LIFETIME,
        product_id="com.torve.pro.lifetime",
    )
    payment = _add_payment(db, user, ref)
    db.commit()

    result = apply_google_play_voided_purchase(
        db,
        {
            "purchaseToken": purchase_token,
            "orderId": "GPA.3388-0000-0000-00000",
            "voidedTimeMillis": _voided_time_ms(),
            "voidedSource": 0,
            "voidedReason": 1,
        },
        dry_run=False,
    )
    db.commit()

    assert result["matched_refs"] == [ref]
    assert result["revoked_entitlement_ids"] == [str(ent.id)]
    assert result["updated_payment_ids"] == [str(payment.id)]

    db.refresh(user)
    db.refresh(ent)
    db.refresh(payment)
    assert ent.status == "revoked"
    assert ent.revoked_at is not None
    assert user.has_lifetime_access is False
    assert user.has_premium_access is True
    assert payment.status == "refunded"
    assert payment.refunded_at is not None
    assert payment.entitlement_granted is False

    grant = db.query(LifetimeGrantRecord).filter(
        LifetimeGrantRecord.source == SOURCE_GOOGLE_PLAY,
        LifetimeGrantRecord.source_ref == ref,
    ).one()
    assert grant.revoked_at is not None
    assert grant.revoke_reason == "entitlement_revoked"


def test_voided_purchase_dry_run_does_not_change_access(db):
    user = _make_user(db)
    order_id = f"GPA.{uuid.uuid4().hex}"
    ref = f"gp_{order_id}"
    ent = grant_entitlement(
        db,
        user.id,
        SOURCE_GOOGLE_PLAY,
        ref,
        ENTITLEMENT_LIFETIME,
        product_id="com.torve.pro.lifetime",
    )
    payment = _add_payment(db, user, ref)
    db.commit()

    result = apply_google_play_voided_purchase(
        db,
        {
            "purchaseToken": "different-token",
            "orderId": order_id,
            "voidedTimeMillis": _voided_time_ms(),
        },
        dry_run=True,
    )
    db.commit()

    assert result["matched_refs"] == [ref]
    assert result["revoked_entitlement_ids"] == []
    assert result["updated_payment_ids"] == []
    db.refresh(user)
    db.refresh(ent)
    db.refresh(payment)
    assert ent.status == "active"
    assert user.has_lifetime_access is False
    assert user.has_premium_access is True
    assert payment.status == "completed"
    assert payment.entitlement_granted is True


def test_voided_subscription_purchase_expires_matching_entitlement(db):
    user = _make_user(db)
    order_id = f"GPA.{uuid.uuid4().hex}"
    ref = f"gp_{order_id}"
    ent = grant_entitlement(
        db,
        user.id,
        SOURCE_GOOGLE_PLAY,
        ref,
        ENTITLEMENT_SUBSCRIPTION,
        product_id="com.torve.pro.subscription",
        expires_at=datetime(2026, 6, 30, tzinfo=timezone.utc),
        auto_renew=True,
    )
    payment = _add_payment(db, user, ref, product_id="com.torve.pro.subscription")
    db.commit()

    result = apply_google_play_voided_purchase(
        db,
        {
            "purchaseToken": "sub-token",
            "orderId": order_id,
            "voidedTimeMillis": _voided_time_ms(),
        },
        dry_run=False,
    )
    db.commit()

    assert result["expired_entitlement_ids"] == [str(ent.id)]
    assert result["updated_payment_ids"] == [str(payment.id)]
    db.refresh(user)
    db.refresh(ent)
    assert ent.status == "expired"
    assert ent.auto_renew is False
    assert user.has_premium_access is True


def test_voided_purchase_without_local_match_is_reported_only(db):
    missing_ref = f"gp_GPA.{uuid.uuid4().hex}"
    result = apply_google_play_voided_purchase(
        db,
        {
            "purchaseToken": "unknown-token",
            "orderId": missing_ref.removeprefix("gp_"),
            "voidedTimeMillis": _voided_time_ms(),
        },
        dry_run=False,
    )

    assert result["matched_refs"] == []
    assert db.query(UserEntitlement).filter(
        UserEntitlement.source == SOURCE_GOOGLE_PLAY,
        UserEntitlement.source_ref == missing_ref,
    ).count() == 0


def test_admin_voided_sync_endpoint_is_dry_run_by_default(client, monkeypatch):
    monkeypatch.setattr("app.config.settings.PADDLE_ADMIN_SECRET", "test-admin-secret")
    calls = {}

    def fake_sync(db, *, lookback_days, dry_run):
        calls["lookback_days"] = lookback_days
        calls["dry_run"] = dry_run
        return {
            "ok": True,
            "dry_run": dry_run,
            "lookback_days": lookback_days,
            "fetched": 0,
            "matched": 0,
            "revoked_entitlements": 0,
            "expired_entitlements": 0,
            "updated_payments": 0,
            "items": [],
        }

    monkeypatch.setattr(
        "app.routers.admin_billing.sync_google_play_voided_purchases",
        fake_sync,
    )

    response = client.post(
        "/admin/billing/google-play/voided-purchases/sync?lookback_days=5",
        headers={"X-Admin-Secret": "test-admin-secret"},
    )

    assert response.status_code == 200
    assert response.json()["dry_run"] is True
    assert calls == {"lookback_days": 5, "dry_run": True}
