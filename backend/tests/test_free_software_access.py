"""Free-software access contract.

These tests pin the new model: product access is available to authenticated
active accounts and does not depend on paid entitlements, purchases, trials,
rebates, admin grants, device slots, or donations.
"""
from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone

from app.models import (
    Device,
    LifetimeGrantRecord,
    RebateCode,
    RebateRedemption,
    StripeSubscription,
    User,
    UserEntitlement,
    WebPayment,
)
from app.rebate_service import create_rebate_codes
from app.security import create_access_token, hash_password


def _make_user(db) -> User:
    user = User(
        id=uuid.uuid4(),
        email=f"free-access-{uuid.uuid4().hex[:8]}@test.com",
        password_hash=hash_password("TestPass123!"),
        is_verified=True,
        has_lifetime_access=False,
        has_premium_access=False,
    )
    db.add(user)
    db.commit()
    db.refresh(user)
    return user


def _auth(user: User) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(str(user.id))}"}


def _assert_default_access(body: dict) -> None:
    assert body["has_premium_access"] is True
    assert body["access_tier"] == "free"
    assert body["entitlement_type"] is None
    assert body["source"] is None
    assert body["expires_at"] is None
    assert body["auto_renew"] is None
    assert body["needs_verification"] is False


def test_access_state_is_free_by_default_without_paid_records(client, db):
    user = _make_user(db)
    try:
        r = client.get("/me/access-state", headers=_auth(user))
        assert r.status_code == 200, r.text
        _assert_default_access(r.json())

        me = client.get("/me", headers=_auth(user)).json()
        assert me["has_premium_access"] is True
        assert me["has_lifetime_access"] is False
        assert me["access_tier"] == "free"
        assert me["entitlement_source"] is None
    finally:
        db.delete(user)
        db.commit()


def test_expired_canceled_missing_entitlements_do_not_reduce_access(client, db):
    user = _make_user(db)
    now = datetime.now(timezone.utc)
    db.add_all([
        UserEntitlement(
            user_id=user.id,
            entitlement_type="subscription_monthly",
            source="stripe",
            source_ref=f"expired-{user.id}",
            status="active",
            expires_at=now - timedelta(days=1),
            auto_renew=False,
        ),
        UserEntitlement(
            user_id=user.id,
            entitlement_type="subscription_monthly",
            source="paddle_web",
            source_ref=f"canceled-{user.id}",
            status="revoked",
            expires_at=now + timedelta(days=30),
            auto_renew=False,
        ),
    ])
    db.commit()
    try:
        r = client.get("/me/access-state", headers=_auth(user))
        assert r.status_code == 200
        _assert_default_access(r.json())
    finally:
        db.query(UserEntitlement).filter(UserEntitlement.user_id == user.id).delete()
        db.delete(user)
        db.commit()


def test_historical_lifetime_purchase_and_donation_absence_do_not_change_access(client, db):
    user = _make_user(db)
    db.add_all([
        UserEntitlement(
            user_id=user.id,
            entitlement_type="lifetime_access",
            source="admin_grant",
            source_ref=f"legacy-life-{user.id}",
            status="active",
        ),
        LifetimeGrantRecord(
            email=user.email,
            source="admin_grant",
            source_ref=f"ledger-{user.id}",
            product_id="legacy_lifetime",
        ),
        WebPayment(
            paddle_transaction_id=f"historical-{user.id}",
            user_id=user.id,
            product_id="legacy_lifetime",
            price_id="legacy_price",
            amount="0",
            currency="USD",
            status="completed",
            entitlement_granted=True,
        ),
    ])
    db.commit()
    try:
        r = client.get("/me/access-state", headers=_auth(user))
        assert r.status_code == 200
        _assert_default_access(r.json())
        assert "donation" not in r.text.lower()
    finally:
        db.query(WebPayment).filter(WebPayment.user_id == user.id).delete()
        db.query(LifetimeGrantRecord).filter(LifetimeGrantRecord.email == user.email).delete()
        db.query(UserEntitlement).filter(UserEntitlement.user_id == user.id).delete()
        db.delete(user)
        db.commit()


def test_device_registration_has_no_paid_cap(client, db, monkeypatch):
    monkeypatch.setattr("app.config.settings.MAX_DEVICES_PER_ACCOUNT", 1)
    user = _make_user(db)
    try:
        for i in range(3):
            r = client.post(
                "/me/devices/register",
                headers=_auth(user),
                json={
                    "device_type": "phone",
                    "platform": "android",
                    "installation_id": f"free-device-{i}-{user.id}",
                },
            )
            assert r.status_code == 201, r.text
    finally:
        db.query(Device).filter(Device.user_id == user.id).delete()
        db.delete(user)
        db.commit()


def test_purchase_verification_is_deprecated_and_non_entitling(client, db):
    user = _make_user(db)
    try:
        r = client.post(
            "/me/purchases/google-play/verify",
            headers=_auth(user),
            json={
                "purchase_token": "fake-token",
                "product_id": "com.torve.pro.lifetime",
                "order_id": f"GPA-free-{uuid.uuid4().hex}",
            },
        )
        assert r.status_code == 200, r.text
        assert r.json()["verified"] is True
        assert r.json()["entitlement_granted"] is False

        assert db.query(UserEntitlement).filter(UserEntitlement.user_id == user.id).count() == 0
        _assert_default_access(client.get("/me/access-state", headers=_auth(user)).json())
    finally:
        db.query(WebPayment).filter(WebPayment.user_id == user.id).delete()
        db.delete(user)
        db.commit()


def test_restore_and_stripe_state_do_not_control_access(client, db):
    user = _make_user(db)
    db.add(
        StripeSubscription(
            user_id=user.id,
            stripe_customer_id="cus_free_access",
            stripe_subscription_id=f"sub_{uuid.uuid4().hex}",
            stripe_price_id="price_legacy",
            status="canceled",
        )
    )
    db.commit()
    try:
        restore = client.post("/me/purchases/restore", headers=_auth(user))
        assert restore.status_code == 200
        assert restore.json()["has_premium_access"] is True
        assert restore.json()["has_lifetime_access"] is False

        checkout = client.post(
            "/billing/stripe/checkout-session",
            headers=_auth(user),
            json={"purchase_type": "monthly"},
        )
        assert checkout.status_code == 200
        assert checkout.json()["checkout_required"] is False
        _assert_default_access(client.get("/me/access-state", headers=_auth(user)).json())
    finally:
        db.query(StripeSubscription).filter(StripeSubscription.user_id == user.id).delete()
        db.delete(user)
        db.commit()


def test_rebate_redemption_is_record_only(client, db):
    user = _make_user(db)
    pairs = create_rebate_codes(db, count=1, campaign_name="free-software-test")
    raw_code, row = pairs[0]
    db.commit()
    try:
        r = client.post(
            "/me/rebate-codes/redeem",
            headers=_auth(user),
            json={"code": raw_code},
        )
        assert r.status_code == 200, r.text
        assert r.json()["success"] is True
        assert db.query(UserEntitlement).filter(UserEntitlement.user_id == user.id).count() == 0
        _assert_default_access(client.get("/me/access-state", headers=_auth(user)).json())
    finally:
        db.query(RebateRedemption).filter(RebateRedemption.user_id == user.id).delete()
        db.query(RebateCode).filter(RebateCode.id == row.id).delete()
        db.delete(user)
        db.commit()
