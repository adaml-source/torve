"""Tests for deprecated Paddle webhook/payment compatibility."""
import uuid as _uuid

from app.models import User, UserEntitlement, WebPayment
from app.security import create_access_token, hash_password

# Generate unique IDs per test run to avoid cross-run conflicts
_RUN = _uuid.uuid4().hex[:6]


def _txn(suffix):
    return f"txn_{_RUN}_{suffix}"


def _make_user(db):
    user = User(email=f"pay_{_uuid.uuid4().hex[:8]}@test.com", password_hash=hash_password("testpass123"))
    db.add(user)
    db.flush()
    return user


def _completed_payload(txn_id, user_id=None, intent_id=None, product_id="pro_test", price_id="pri_test"):
    custom = {}
    if intent_id:
        custom["torve_purchase_intent"] = str(intent_id)
    elif user_id:
        custom["torve_user_id"] = str(user_id)
    return {
        "event_type": "transaction.completed",
        "event_id": f"evt_{txn_id}",
        "data": {
            "id": txn_id,
            "customer_id": "ctm_test",
            "items": [{"price": {"id": price_id, "product_id": product_id}}],
            "details": {"totals": {"total": "999"}},
            "currency_code": "USD",
            "custom_data": custom,
            "discount": None,
        },
    }


def _refund_payload(txn_id):
    return {
        "event_type": "transaction.updated",
        "event_id": f"evt_ref_{txn_id}",
        "data": {
            "id": txn_id,
            "status": "refunded",
            "customer_id": "ctm_test",
            "items": [],
            "details": {"totals": {"total": "0"}},
            "currency_code": "USD",
        },
    }


def test_purchase_records_without_entitlement(client, db, monkeypatch):
    monkeypatch.setattr("app.config.settings.PADDLE_WEBHOOK_SECRET", "")
    monkeypatch.setattr("app.config.settings.PADDLE_PRODUCT_ID", "pro_test")
    monkeypatch.setattr("app.config.settings.PADDLE_PRICE_ID", "pri_test")

    user = _make_user(db)
    db.commit()
    txn = _txn("create")

    r = client.post("/webhooks/paddle", json=_completed_payload(txn, user_id=user.id))
    assert r.status_code == 200
    assert r.json()["entitlement_granted"] is False

    db.expire_all()
    db.refresh(user)
    assert user.has_lifetime_access is False

    ent = db.query(UserEntitlement).filter(UserEntitlement.source_ref == txn).first()
    assert ent is None
    payment = db.query(WebPayment).filter(WebPayment.paddle_transaction_id == txn).first()
    assert payment is not None
    assert payment.status == "deprecated_free_software"


def test_duplicate_idempotent(client, db, monkeypatch):
    monkeypatch.setattr("app.config.settings.PADDLE_WEBHOOK_SECRET", "")
    monkeypatch.setattr("app.config.settings.PADDLE_PRODUCT_ID", "pro_test")
    monkeypatch.setattr("app.config.settings.PADDLE_PRICE_ID", "pri_test")

    user = _make_user(db)
    db.commit()
    txn = _txn("dup")

    r1 = client.post("/webhooks/paddle", json=_completed_payload(txn, user_id=user.id))
    r2 = client.post("/webhooks/paddle", json=_completed_payload(txn, user_id=user.id))
    assert r1.json()["entitlement_granted"] is False
    assert r2.json()["status"] == "already_processed"

    assert db.query(WebPayment).filter(WebPayment.paddle_transaction_id == txn).count() == 1


def test_wrong_product_no_entitlement(client, db, monkeypatch):
    monkeypatch.setattr("app.config.settings.PADDLE_WEBHOOK_SECRET", "")
    monkeypatch.setattr("app.config.settings.PADDLE_PRODUCT_ID", "pro_lifetime")
    monkeypatch.setattr("app.config.settings.PADDLE_PRICE_ID", "pri_lifetime")

    user = _make_user(db)
    db.commit()
    txn = _txn("wrong")

    client.post("/webhooks/paddle", json=_completed_payload(txn, user_id=user.id, product_id="pro_other", price_id="pri_other"))

    db.expire_all()
    db.refresh(user)
    assert user.has_lifetime_access is False


def test_refund_updates_history_without_revoking_access(client, db, monkeypatch):
    monkeypatch.setattr("app.config.settings.PADDLE_WEBHOOK_SECRET", "")
    monkeypatch.setattr("app.config.settings.PADDLE_PRODUCT_ID", "pro_test")
    monkeypatch.setattr("app.config.settings.PADDLE_PRICE_ID", "pri_test")

    user = _make_user(db)
    db.commit()
    txn = _txn("refund")

    client.post("/webhooks/paddle", json=_completed_payload(txn, user_id=user.id))

    # Verify entitlement via API (uses app's own session)
    token = create_access_token(str(user.id))
    r = client.get("/me", headers={"Authorization": f"Bearer {token}"})
    assert r.json()["has_lifetime_access"] is False
    assert r.json()["has_premium_access"] is True

    # Refund
    client.post("/webhooks/paddle", json=_refund_payload(txn))

    r2 = client.get("/me", headers={"Authorization": f"Bearer {token}"})
    assert r2.json()["has_lifetime_access"] is False
    assert r2.json()["has_premium_access"] is True


def test_second_entitlement_survives_refund(client, db, monkeypatch):
    monkeypatch.setattr("app.config.settings.PADDLE_WEBHOOK_SECRET", "")
    monkeypatch.setattr("app.config.settings.PADDLE_PRODUCT_ID", "pro_test")
    monkeypatch.setattr("app.config.settings.PADDLE_PRICE_ID", "pri_test")

    user = _make_user(db)
    db.commit()
    txn1 = _txn("surv1")
    txn2 = _txn("surv2")

    client.post("/webhooks/paddle", json=_completed_payload(txn1, user_id=user.id))
    client.post("/webhooks/paddle", json=_completed_payload(txn2, user_id=user.id))
    db.expire_all()
    db.refresh(user)
    assert user.has_lifetime_access is False

    # Refund first
    client.post("/webhooks/paddle", json=_refund_payload(txn1))
    db.expire_all()
    db.refresh(user)
    assert user.has_lifetime_access is False


def test_unlinked_payment(client, db, monkeypatch):
    monkeypatch.setattr("app.config.settings.PADDLE_WEBHOOK_SECRET", "")
    monkeypatch.setattr("app.config.settings.PADDLE_PRODUCT_ID", "pro_test")
    monkeypatch.setattr("app.config.settings.PADDLE_PRICE_ID", "pri_test")

    txn = _txn("unlinked")
    r = client.post("/webhooks/paddle", json=_completed_payload(txn))
    assert r.json()["entitlement_granted"] is False

    payment = db.query(WebPayment).filter(WebPayment.paddle_transaction_id == txn).first()
    assert payment is not None
    assert payment.user_id is None


def test_purchase_intent(client, db, monkeypatch):
    monkeypatch.setattr("app.config.settings.PADDLE_WEBHOOK_SECRET", "")
    monkeypatch.setattr("app.config.settings.PADDLE_PRODUCT_ID", "pro_test")
    monkeypatch.setattr("app.config.settings.PADDLE_PRICE_ID", "pri_test")

    user = _make_user(db)
    db.commit()

    token = create_access_token(str(user.id))
    r = client.post("/me/checkout/intent", headers={"Authorization": f"Bearer {token}"})
    assert r.status_code == 200
    assert r.json()["checkout_required"] is False
    intent_id = r.json()["intent_id"]

    txn = _txn("intent")
    r2 = client.post("/webhooks/paddle", json=_completed_payload(txn, intent_id=intent_id))
    assert r2.json()["entitlement_granted"] is False

    db.expire_all()
    db.refresh(user)
    assert user.has_lifetime_access is False


def test_discounted_purchase(client, db, monkeypatch):
    monkeypatch.setattr("app.config.settings.PADDLE_WEBHOOK_SECRET", "")
    monkeypatch.setattr("app.config.settings.PADDLE_PRODUCT_ID", "pro_test")
    monkeypatch.setattr("app.config.settings.PADDLE_PRICE_ID", "pri_test")

    user = _make_user(db)
    db.commit()
    txn = _txn("disc")

    payload = _completed_payload(txn, user_id=user.id)
    payload["data"]["discount"] = {"code": "FRIEND100"}
    r = client.post("/webhooks/paddle", json=payload)
    assert r.json()["entitlement_granted"] is False

    payment = db.query(WebPayment).filter(WebPayment.paddle_transaction_id == txn).first()
    assert payment.discount_code == "FRIEND100"


def test_entitlement_in_profile(client, db, monkeypatch):
    monkeypatch.setattr("app.config.settings.PADDLE_WEBHOOK_SECRET", "")
    monkeypatch.setattr("app.config.settings.PADDLE_PRODUCT_ID", "pro_test")
    monkeypatch.setattr("app.config.settings.PADDLE_PRICE_ID", "pri_test")

    user = _make_user(db)
    db.commit()
    txn = _txn("profile")

    client.post("/webhooks/paddle", json=_completed_payload(txn, user_id=user.id))

    token = create_access_token(str(user.id))
    r = client.get("/me", headers={"Authorization": f"Bearer {token}"})
    assert r.json()["has_lifetime_access"] is False
    assert r.json()["has_premium_access"] is True


def test_invalid_signature_rejected(client, monkeypatch):
    """Invalid webhook signature must not create any records."""
    monkeypatch.setattr("app.config.settings.PADDLE_WEBHOOK_SECRET", "real_secret_here")

    from app.models import WebPayment
    from app.database import SessionLocal
    db = SessionLocal()
    count_before = db.query(WebPayment).count()
    db.close()

    r = client.post(
        "/webhooks/paddle",
        content=b'{"event_type":"transaction.completed","data":{"id":"txn_bad"}}',
        headers={
            "Content-Type": "application/json",
            "Paddle-Signature": "ts=123;h1=invalid_hash",
        },
    )
    assert r.status_code == 401

    db2 = SessionLocal()
    count_after = db2.query(WebPayment).count()
    db2.close()
    assert count_after == count_before


def test_admin_promo_requires_secret(client):
    """Admin promo endpoints must reject requests without valid secret."""
    r = client.get("/admin/promo/list")
    assert r.status_code in (403, 503)

    r2 = client.get("/admin/promo/list", headers={"X-Admin-Secret": "wrong"})
    assert r2.status_code in (403, 503)


def test_admin_billing_requires_secret(client):
    """Admin billing endpoints must reject requests without valid secret."""
    r = client.get("/admin/billing/payments")
    assert r.status_code in (403, 503)

    r2 = client.get("/admin/billing/reconcile")
    assert r2.status_code in (403, 503)


def test_free_user_can_save_integrations(client, db):
    """Integrations are no longer paid-gated."""
    user = _make_user(db)
    db.commit()
    assert user.has_lifetime_access is False

    token = create_access_token(str(user.id))
    r = client.put(
        "/me/integrations/TEST_KEY",
        json={"integration_type": "TEST_KEY", "credentials": {"key": "val"}, "storage_mode": "account"},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert r.status_code == 200


def test_free_user_can_save_playlists(client, db):
    """Playlists are no longer paid-gated."""
    user = _make_user(db)
    db.commit()

    token = create_access_token(str(user.id))
    r = client.put(
        "/me/playlists/test_pl",
        json={"playlist_id": "test_pl", "name": "Test", "playlist_type": "m3u", "url": "http://example.com/test.m3u"},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert r.status_code == 200


def test_free_user_can_view_profile(client, db):
    """Free user can still view profile and list integrations."""
    user = _make_user(db)
    db.commit()

    token = create_access_token(str(user.id))
    r1 = client.get("/me", headers={"Authorization": f"Bearer {token}"})
    assert r1.status_code == 200
    assert r1.json()["has_lifetime_access"] is False

    r2 = client.get("/me/integrations", headers={"Authorization": f"Bearer {token}"})
    assert r2.status_code == 200

    r3 = client.get("/me/playlists", headers={"Authorization": f"Bearer {token}"})
    assert r3.status_code == 200


def test_refunded_user_keeps_default_access(client, db, monkeypatch):
    """Refund history does not remove free/default product access."""
    monkeypatch.setattr("app.config.settings.PADDLE_WEBHOOK_SECRET", "")
    monkeypatch.setattr("app.config.settings.PADDLE_PRODUCT_ID", "pro_test")
    monkeypatch.setattr("app.config.settings.PADDLE_PRICE_ID", "pri_test")

    user = _make_user(db)
    db.commit()
    txn = _txn("revoke_access")

    # Purchase
    client.post("/webhooks/paddle", json=_completed_payload(txn, user_id=user.id))

    token = create_access_token(str(user.id))

    r1 = client.put(
        "/me/integrations/TEST_PREMIUM",
        json={"integration_type": "TEST_PREMIUM", "credentials": {"k": "v"}, "storage_mode": "account"},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert r1.status_code == 200

    # Refund
    client.post("/webhooks/paddle", json=_refund_payload(txn))

    r2 = client.put(
        "/me/integrations/TEST_BLOCKED",
        json={"integration_type": "TEST_BLOCKED", "credentials": {"k": "v"}, "storage_mode": "account"},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert r2.status_code == 200
