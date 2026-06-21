"""Prompt 4 release-prep auth boundary regressions.

These tests keep the free-software model separate from authentication and
ownership. Product access is free, but account-scoped data remains private to
the authenticated account that owns it.
"""
from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone

from jose import jwt

from app.config import settings
from app.models import AccountSettings, Device, UserEntitlement, WebPayment
from app.security import create_access_token


def _auth(user) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(str(user.id))}"}


def _expired_auth(user) -> dict[str, str]:
    token = jwt.encode(
        {
            "sub": str(user.id),
            "exp": datetime.now(timezone.utc) - timedelta(minutes=1),
        },
        settings.JWT_SECRET,
        algorithm=settings.JWT_ALGORITHM,
    )
    return {"Authorization": f"Bearer {token}"}


def test_sync_preferences_reject_unauthenticated_and_expired_tokens(client, test_user):
    unauth = client.get("/me/account-settings")
    assert unauth.status_code in (401, 403)

    expired = client.get("/me/account-settings", headers=_expired_auth(test_user))
    assert expired.status_code in (401, 403)


def test_authenticated_preferences_sync_is_owner_scoped(client, test_user, test_user_b, db):
    try:
        saved = client.patch(
            "/me/account-settings",
            headers=_auth(test_user),
            json={"settings": {"language": "en", "home_layout": "compact"}},
        )
        assert saved.status_code == 200, saved.text

        owner = client.get("/me/account-settings", headers=_auth(test_user))
        assert owner.status_code == 200
        assert owner.json()["settings"]["home_layout"] == "compact"

        other = client.get("/me/account-settings", headers=_auth(test_user_b))
        assert other.status_code == 200
        assert other.json()["settings"] == {}
    finally:
        db.query(AccountSettings).filter(AccountSettings.user_id.in_([test_user.id, test_user_b.id])).delete()
        db.commit()


def test_other_account_cannot_modify_device(client, test_user, test_user_b, db):
    device = Device(
        user_id=test_user.id,
        device_type="phone",
        platform="android",
        display_name="Owner phone",
        installation_id=f"prompt4-{uuid.uuid4().hex}",
    )
    db.add(device)
    db.commit()
    db.refresh(device)

    try:
        revoked = client.post(f"/me/devices/{device.id}/revoke", headers=_auth(test_user_b))
        assert revoked.status_code == 404

        db.refresh(device)
        assert device.is_active is True
    finally:
        db.delete(device)
        db.commit()


def test_pairing_code_validates_device_ownership(client, auth_header, auth_header_b, tv_device, phone_device_b):
    code = client.post(
        "/pairing/code",
        headers=auth_header,
        json={"device_id": str(tv_device.id)},
    )
    assert code.status_code == 201, code.text

    claimed = client.post(
        "/pairing/claim",
        headers=auth_header_b,
        json={"code": code.json()["code"], "device_id": str(phone_device_b.id)},
    )
    assert claimed.status_code == 404


def test_payment_and_donation_state_do_not_change_sync_or_owner_boundaries(
    client,
    test_user,
    test_user_b,
    db,
):
    db.add_all([
        UserEntitlement(
            user_id=test_user.id,
            entitlement_type="subscription_monthly",
            source="google_play",
            source_ref=f"legacy-sub-{test_user.id}",
            status="revoked",
        ),
        WebPayment(
            paddle_transaction_id=f"legacy-payment-{uuid.uuid4().hex}",
            user_id=test_user.id,
            product_id="legacy_lifetime",
            price_id="legacy_price",
            amount="10.00",
            currency="USD",
            status="refunded",
            entitlement_granted=False,
        ),
    ])
    db.commit()

    try:
        owner = client.patch(
            "/me/account-settings",
            headers=_auth(test_user),
            json={"settings": {"ratings_provider": "tmdb"}},
        )
        assert owner.status_code == 200

        other = client.get("/me/account-settings", headers=_auth(test_user_b))
        assert other.status_code == 200
        assert "ratings_provider" not in other.json()["settings"]

        access = client.get("/me/access-state", headers=_auth(test_user))
        assert access.status_code == 200
        assert access.json()["has_premium_access"] is True
        assert access.json()["access_tier"] == "free"
        assert "donation" not in access.text.lower()
    finally:
        db.query(AccountSettings).filter(AccountSettings.user_id.in_([test_user.id, test_user_b.id])).delete()
        db.query(WebPayment).filter(WebPayment.user_id == test_user.id).delete()
        db.query(UserEntitlement).filter(UserEntitlement.user_id == test_user.id).delete()
        db.commit()
