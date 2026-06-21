"""Tests for deprecated Google Play and Amazon purchase verification."""
import uuid as _uuid

from app.models import User, WebPayment
from app.security import create_access_token, hash_password


def _make_user(db):
    user = User(email=f"store_{_uuid.uuid4().hex[:8]}@test.com", password_hash=hash_password("testpass"))
    db.add(user)
    db.flush()
    return user


def _auth(user_id):
    return {"Authorization": f"Bearer {create_access_token(str(user_id))}"}


# ── Product validation ─────────────────────────────────────────────────

def test_gp_exact_product_mismatch_rejected(client, db, monkeypatch):
    """Wrong Google Play product IDs no longer matter for product access."""
    monkeypatch.setattr("app.config.settings.GOOGLE_PLAY_PRODUCT_ID", "com.torve.lifetime")
    monkeypatch.setattr("app.config.settings.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON", "")

    user = _make_user(db)
    db.commit()

    r = client.post(
        "/me/purchases/google-play/verify",
        json={"purchase_token": "tok", "product_id": "com.pirate.fake", "order_id": "GPA.wrong1"},
        headers=_auth(user.id),
    )
    assert r.status_code == 200
    assert r.json()["verified"] is True
    assert r.json()["entitlement_granted"] is False
    assert r.json()["error_code"] == "deprecated_free_software"


def test_gp_exact_product_match_proceeds(client, db, monkeypatch):
    """Correct product IDs also short-circuit without store verification."""
    # all_google_play_product_ids is computed from the union of LIFETIME
    # + SUBSCRIPTION + legacy PRODUCT_ID. Clear LIFETIME/SUBSCRIPTION so
    # only the legacy fallback we set here is in the allow-list — matches
    # what this test asserts about.
    monkeypatch.setattr("app.config.settings.GOOGLE_PLAY_LIFETIME_PRODUCT_ID", "")
    monkeypatch.setattr("app.config.settings.GOOGLE_PLAY_SUBSCRIPTION_ID", "")
    monkeypatch.setattr("app.config.settings.GOOGLE_PLAY_PRODUCT_ID", "com.torve.lifetime")
    monkeypatch.setattr("app.config.settings.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON", "")

    user = _make_user(db)
    db.commit()

    r = client.post(
        "/me/purchases/google-play/verify",
        json={"purchase_token": "tok", "product_id": "com.torve.lifetime", "order_id": "GPA.match1"},
        headers=_auth(user.id),
    )
    assert r.status_code == 200
    assert r.json()["verified"] is True
    assert r.json()["entitlement_granted"] is False
    assert r.json()["error_code"] == "deprecated_free_software"


def test_gp_missing_product_id_production_fails_closed(client, db, monkeypatch):
    """Production config no longer controls product access."""
    monkeypatch.setattr("app.config.settings.GOOGLE_PLAY_PRODUCT_ID", "")
    monkeypatch.setattr("app.config.settings.APP_ENV", "production")
    monkeypatch.setattr("app.config.settings.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON", "")

    user = _make_user(db)
    db.commit()

    r = client.post(
        "/me/purchases/google-play/verify",
        json={"purchase_token": "tok", "product_id": "com.torve.lifetime", "order_id": "GPA.prod1"},
        headers=_auth(user.id),
    )
    assert r.json()["verified"] is True
    assert r.json()["entitlement_granted"] is False


def test_amazon_missing_product_id_production_fails_closed(client, db, monkeypatch):
    """In production mode, missing AMAZON_PRODUCT_ID rejects all."""
    monkeypatch.setattr("app.config.settings.AMAZON_PRODUCT_ID", "")
    monkeypatch.setattr("app.config.settings.APP_ENV", "production")
    monkeypatch.setattr("app.config.settings.AMAZON_APP_SECRET", "")

    user = _make_user(db)
    db.commit()

    r = client.post(
        "/me/purchases/amazon/verify",
        json={"receipt_id": "rcpt", "user_id": "amz_user", "product_id": "com.torve.lifetime"},
        headers=_auth(user.id),
    )
    # Should fail: either product validation or Amazon config missing
    assert r.json()["entitlement_granted"] is False


# ── Provider config missing ────────────────────────────────────────────

def test_gp_no_credentials_no_grant(client, db, monkeypatch):
    """Without Google service account, no entitlement granted."""
    monkeypatch.setattr("app.config.settings.GOOGLE_PLAY_PRODUCT_ID", "com.torve.lifetime")
    monkeypatch.setattr("app.config.settings.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON", "")

    user = _make_user(db)
    db.commit()

    r = client.post(
        "/me/purchases/google-play/verify",
        json={"purchase_token": "tok", "product_id": "com.torve.lifetime"},
        headers=_auth(user.id),
    )
    assert r.json()["entitlement_granted"] is False

    r2 = client.get("/me", headers=_auth(user.id))
    assert r2.json()["has_lifetime_access"] is False


def test_amazon_no_secret_no_grant(client, db, monkeypatch):
    """Without Amazon app secret, no entitlement granted."""
    monkeypatch.setattr("app.config.settings.AMAZON_APP_SECRET", "")

    user = _make_user(db)
    db.commit()

    r = client.post(
        "/me/purchases/amazon/verify",
        json={"receipt_id": "rcpt", "user_id": "amz_user"},
        headers=_auth(user.id),
    )
    assert r.json()["entitlement_granted"] is False


# ── Auth required ──────────────────────────────────────────────────────

def test_verify_requires_auth(client):
    """Both endpoints require authentication."""
    r1 = client.post("/me/purchases/google-play/verify", json={"purchase_token": "x", "product_id": "x"})
    assert r1.status_code in (401, 403)

    r2 = client.post("/me/purchases/amazon/verify", json={"receipt_id": "x", "user_id": "x"})
    assert r2.status_code in (401, 403)


# ── Idempotency ────────────────────────────────────────────────────────

def test_gp_idempotent_same_order(client, db, monkeypatch):
    """Repeated verification of the same order creates only one payment record."""
    monkeypatch.setattr("app.config.settings.GOOGLE_PLAY_PRODUCT_ID", "com.torve.lifetime")
    monkeypatch.setattr("app.config.settings.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON", "")

    user = _make_user(db)
    db.commit()

    body = {"purchase_token": "tok", "product_id": "com.torve.lifetime", "order_id": "GPA.idem2"}
    client.post("/me/purchases/google-play/verify", json=body, headers=_auth(user.id))
    client.post("/me/purchases/google-play/verify", json=body, headers=_auth(user.id))

    count = db.query(WebPayment).filter(WebPayment.paddle_transaction_id == "gp_GPA.idem2").count()
    assert count == 1


# ── Fake tokens ────────────────────────────────────────────────────────

def test_fake_tokens_never_grant(client, db, monkeypatch):
    """Crafted tokens must not grant access regardless of product ID."""
    monkeypatch.setattr("app.config.settings.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON", "")
    monkeypatch.setattr("app.config.settings.AMAZON_APP_SECRET", "")

    user = _make_user(db)
    db.commit()
    headers = _auth(user.id)

    client.post("/me/purchases/google-play/verify",
        json={"purchase_token": "PIRATED", "product_id": "com.torve.lifetime"}, headers=headers)
    client.post("/me/purchases/amazon/verify",
        json={"receipt_id": "PIRATED", "user_id": "FAKE"}, headers=headers)

    r = client.get("/me", headers=headers)
    assert r.json()["has_lifetime_access"] is False
