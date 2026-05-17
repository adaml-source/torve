import uuid
from datetime import datetime, timedelta, timezone

import pytest

from app.billing import ENTITLEMENT_LIFETIME, ENTITLEMENT_SUBSCRIPTION
from app.models import UserEntitlement
from app.routers import stripe_billing
from app.security import create_access_token


def _auth(user):
    return {"Authorization": f"Bearer {create_access_token(str(user.id))}"}


def _grant(
    db,
    user,
    *,
    entitlement_type=ENTITLEMENT_LIFETIME,
    source="stripe",
    expires_at=None,
):
    ent = UserEntitlement(
        user_id=user.id,
        entitlement_type=entitlement_type,
        source=source,
        source_ref=f"{source}-{uuid.uuid4().hex}",
        product_id=f"{source}-{entitlement_type}",
        status="active",
        expires_at=expires_at,
        auto_renew=entitlement_type == ENTITLEMENT_SUBSCRIPTION,
    )
    db.add(ent)
    user.has_premium_access = True
    if entitlement_type == ENTITLEMENT_LIFETIME:
        user.has_lifetime_access = True
    db.commit()
    return ent


@pytest.fixture()
def checkout_calls(monkeypatch):
    calls = []

    def _fake_checkout(user, purchase_type):
        calls.append((str(user.id), purchase_type))
        return f"https://checkout.stripe.test/{purchase_type}"

    monkeypatch.setattr(stripe_billing, "create_stripe_checkout_session", _fake_checkout)
    return calls


def test_no_premium_can_create_monthly_checkout(client, free_user, checkout_calls):
    r = client.post(
        "/billing/stripe/checkout-session",
        json={"purchase_type": "monthly"},
        headers=_auth(free_user),
    )

    assert r.status_code == 200
    assert r.json()["checkout_url"] == "https://checkout.stripe.test/monthly"
    assert checkout_calls == [(str(free_user.id), "monthly")]


def test_no_premium_can_create_lifetime_checkout(client, free_user, checkout_calls):
    r = client.post(
        "/billing/stripe/checkout-session",
        json={"purchase_type": "lifetime"},
        headers=_auth(free_user),
    )

    assert r.status_code == 200
    assert r.json()["checkout_url"] == "https://checkout.stripe.test/lifetime"
    assert checkout_calls == [(str(free_user.id), "lifetime")]


def test_active_stripe_monthly_cannot_create_duplicate_monthly(client, db, free_user, checkout_calls):
    _grant(
        db,
        free_user,
        entitlement_type=ENTITLEMENT_SUBSCRIPTION,
        source="stripe",
        expires_at=datetime.now(timezone.utc) + timedelta(days=30),
    )

    r = client.post(
        "/billing/stripe/checkout-session",
        json={"purchase_type": "monthly"},
        headers=_auth(free_user),
    )

    assert r.status_code == 409
    assert r.json() == {
        "error_code": "stripe_duplicate_subscription",
        "message": "A Stripe monthly subscription is already active.",
    }
    assert checkout_calls == []


def test_active_stripe_monthly_can_create_lifetime_checkout(client, db, free_user, checkout_calls):
    _grant(
        db,
        free_user,
        entitlement_type=ENTITLEMENT_SUBSCRIPTION,
        source="stripe",
        expires_at=datetime.now(timezone.utc) + timedelta(days=30),
    )

    r = client.post(
        "/billing/stripe/checkout-session",
        json={"purchase_type": "lifetime"},
        headers=_auth(free_user),
    )

    assert r.status_code == 200
    assert r.json()["checkout_url"] == "https://checkout.stripe.test/lifetime"
    assert checkout_calls == [(str(free_user.id), "lifetime")]


@pytest.mark.parametrize("purchase_type", ["monthly", "lifetime"])
def test_active_stripe_lifetime_blocks_all_stripe_checkout(
    client,
    db,
    free_user,
    checkout_calls,
    purchase_type,
):
    _grant(db, free_user, entitlement_type=ENTITLEMENT_LIFETIME, source="stripe")

    r = client.post(
        "/billing/stripe/checkout-session",
        json={"purchase_type": purchase_type},
        headers=_auth(free_user),
    )

    assert r.status_code == 409
    assert r.json()["error_code"] == "stripe_lifetime_already_owned"
    assert "Stripe" not in r.json()["message"]
    assert checkout_calls == []


@pytest.mark.parametrize(
    "source",
    ["google_play", "amazon", "admin_grant", "rebate_code", "paddle_web"],
)
def test_non_stripe_lifetime_sources_block_stripe_checkout(
    client,
    db,
    free_user,
    checkout_calls,
    source,
):
    _grant(db, free_user, entitlement_type=ENTITLEMENT_LIFETIME, source=source)

    r = client.post(
        "/billing/stripe/checkout-session",
        json={"purchase_type": "monthly"},
        headers=_auth(free_user),
    )

    assert r.status_code == 409
    assert r.json() == {
        "error_code": "stripe_cross_store_purchase_blocked",
        "message": "Premium is already active from another purchase source.",
    }
    assert checkout_calls == []


def test_non_stripe_active_subscription_blocks_stripe_checkout(client, db, free_user, checkout_calls):
    _grant(
        db,
        free_user,
        entitlement_type=ENTITLEMENT_SUBSCRIPTION,
        source="google_play",
        expires_at=datetime.now(timezone.utc) + timedelta(days=30),
    )

    r = client.post(
        "/billing/stripe/checkout-session",
        json={"purchase_type": "lifetime"},
        headers=_auth(free_user),
    )

    assert r.status_code == 409
    assert r.json()["error_code"] == "stripe_cross_store_purchase_blocked"
    assert checkout_calls == []


def test_checkout_not_created_when_policy_blocks_and_response_is_sanitized(
    client,
    db,
    free_user,
    checkout_calls,
):
    ent = _grant(db, free_user, entitlement_type=ENTITLEMENT_LIFETIME, source="google_play")

    r = client.post(
        "/billing/stripe/checkout-session",
        json={"purchase_type": "lifetime"},
        headers=_auth(free_user),
    )

    body = r.json()
    assert r.status_code == 409
    assert body["error_code"] == "stripe_cross_store_purchase_blocked"
    assert str(ent.source_ref) not in body["message"]
    assert str(free_user.id) not in body["message"]
    assert checkout_calls == []
