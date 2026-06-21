"""Tests for audit-identified fixes: 422 redaction, expired sub, device cap."""
import uuid
from datetime import datetime, timedelta, timezone

from app.models import Device, User, UserEntitlement
from app.security import create_access_token, hash_password


def _make_user(db, premium=False, lifetime=False):
    u = User(
        id=uuid.uuid4(),
        email=f"audit-{uuid.uuid4().hex[:6]}@test.com",
        password_hash=hash_password("TestPass123!"),
        is_verified=True,
        has_premium_access=premium,
        has_lifetime_access=lifetime,
    )
    db.add(u)
    db.commit()
    return u


def _token(user):
    return create_access_token(str(user.id))


# ── Fix 1: 422 response redacts user input ────────────────────────────────


def test_422_response_redacts_input(client, db):
    """Validation errors must not echo raw user input back in the response."""
    u = _make_user(db, premium=True, lifetime=True)
    r = client.put(
        "/me/integrations/TEST_TYPE",
        json={
            "integration_type": "TEST_TYPE",
            # Send a secret-like value that would trigger validation error
            # by making credentials an invalid type (int instead of dict/str)
            "credentials": 12345,
        },
        headers={"Authorization": f"Bearer {_token(u)}"},
    )
    assert r.status_code == 422
    body = r.json()
    # The detail should contain REDACTED, not the raw input
    for err in body.get("detail", []):
        assert err.get("input") == "[REDACTED]", f"Input not redacted: {err}"

    db.delete(u)
    db.commit()


# ── Fix 2: Expired subscription loses premium ─────────────────────────────


def test_expired_subscription_does_not_block_authenticated_access(client, db):
    """Expired subscriptions no longer affect feature access."""
    u = _make_user(db, premium=True, lifetime=False)

    # Create an expired subscription entitlement
    ent = UserEntitlement(
        user_id=u.id,
        entitlement_type="subscription_monthly",
        source="google_play",
        source_ref=f"expired-sub-{u.id}",
        status="active",
        expires_at=datetime.now(timezone.utc) - timedelta(days=1),
    )
    db.add(ent)
    db.commit()

    r = client.put(
        "/me/integrations/EXPIRED_TEST",
        json={
            "integration_type": "EXPIRED_TEST",
            "credentials": {"key": "val"},
        },
        headers={"Authorization": f"Bearer {_token(u)}"},
    )
    assert r.status_code == 200

    db.query(UserEntitlement).filter(UserEntitlement.user_id == u.id).delete()
    db.delete(u)
    db.commit()


def test_lifetime_user_not_affected_by_sub_expiry_check(client, db):
    """A lifetime-history user should pass account access without sub expiry logic."""
    u = _make_user(db, premium=True, lifetime=True)

    r = client.get(
        "/me/integrations",
        headers={"Authorization": f"Bearer {_token(u)}"},
    )
    assert r.status_code == 200

    db.delete(u)
    db.commit()


# ── Fix 3: Free users don't hit device cap ─────────────────────────────────


def test_free_user_can_register_many_devices(client, db):
    """Free users should not be blocked by device cap."""
    u = _make_user(db, premium=False, lifetime=False)
    token = _token(u)

    # Register 6 devices (more than the 5 cap)
    for i in range(6):
        r = client.post(
            "/me/devices/register",
            json={
                "device_type": "phone",
                "platform": "android",
                "installation_id": f"free-dev-{i}-{u.id}",
            },
            headers={"Authorization": f"Bearer {token}"},
        )
        assert r.status_code == 201, f"Device {i} failed: {r.status_code} {r.text}"

    # Cleanup
    db.query(Device).filter(Device.user_id == u.id).delete()
    db.delete(u)
    db.commit()


def test_historical_premium_user_does_not_hit_paid_device_cap(client, db):
    """Historical premium records do not create a paid device cap."""
    u = _make_user(db, premium=True, lifetime=True)
    # Real entitlement required — check_premium_active queries entitlements
    ent = UserEntitlement(
        user_id=u.id,
        entitlement_type="lifetime_access",
        source="admin_grant",
        source_ref=f"cap-test-{u.id}",
        status="active",
    )
    db.add(ent)
    db.commit()
    token = _token(u)

    # Register 5 devices (at the cap)
    for i in range(5):
        r = client.post(
            "/me/devices/register",
            json={
                "device_type": "phone",
                "platform": "android",
                "installation_id": f"prem-dev-{i}-{u.id}",
            },
            headers={"Authorization": f"Bearer {token}"},
        )
        assert r.status_code == 201

    # 6th should still succeed; paid device caps are disabled.
    r = client.post(
        "/me/devices/register",
        json={
            "device_type": "phone",
            "platform": "android",
            "installation_id": f"prem-dev-6-{u.id}",
        },
        headers={"Authorization": f"Bearer {token}"},
    )
    assert r.status_code == 201

    # Cleanup
    db.query(Device).filter(Device.user_id == u.id).delete()
    db.query(UserEntitlement).filter(UserEntitlement.user_id == u.id).delete()
    db.commit()
    u = db.query(User).filter(User.id == u.id).first()
    if u:
        db.delete(u)
        db.commit()


# ── Desktop device type support ────────────────────────────────────────


def test_desktop_device_type_accepted(client, db):
    """Desktop device type should be accepted for registration."""
    u = _make_user(db, premium=False, lifetime=False)
    token = _token(u)

    r = client.post(
        "/me/devices/register",
        json={
            "device_type": "desktop",
            "platform": "windows",
            "installation_id": f"desktop-{u.id}",
        },
        headers={"Authorization": f"Bearer {token}"},
    )
    assert r.status_code == 201
    data = r.json()
    assert data["device_type"] == "desktop"
    assert data["platform"] == "windows"

    db.query(Device).filter(Device.user_id == u.id).delete()
    db.delete(u)
    db.commit()


def test_desktop_device_type_in_login(client, db):
    """Desktop device type should work during login bootstrap."""
    u = _make_user(db, premium=False, lifetime=False)

    r = client.post(
        "/auth/login",
        json={
            "email": u.email,
            "password": "TestPass123!",
            "device_type": "desktop",
            "platform": "windows",
            "installation_id": f"desktop-login-{u.id}",
        },
    )
    assert r.status_code == 200
    data = r.json()
    assert data["device"]["device_type"] == "desktop"

    db.query(Device).filter(Device.user_id == u.id).delete()
    db.delete(u)
    db.commit()


def test_invalid_device_type_rejected(client, db):
    """Unknown device types should still be rejected."""
    u = _make_user(db, premium=False, lifetime=False)
    token = _token(u)

    r = client.post(
        "/me/devices/register",
        json={
            "device_type": "smartwatch",
            "platform": "wearos",
            "installation_id": f"watch-{u.id}",
        },
        headers={"Authorization": f"Bearer {token}"},
    )
    assert r.status_code == 422

    db.delete(u)
    db.commit()


def test_windows_platform_infers_desktop(client, db):
    """Platform 'windows' should infer device_type 'desktop' during login."""
    u = _make_user(db, premium=False, lifetime=False)

    r = client.post(
        "/auth/login",
        json={
            "email": u.email,
            "password": "TestPass123!",
            "platform": "windows",
            "installation_id": f"win-infer-{u.id}",
        },
    )
    assert r.status_code == 200
    data = r.json()
    assert data["device"]["device_type"] == "desktop"

    db.query(Device).filter(Device.user_id == u.id).delete()
    db.delete(u)
    db.commit()
