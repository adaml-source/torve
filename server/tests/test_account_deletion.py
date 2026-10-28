"""Tests for DELETE /me/account."""
import uuid

from app.models import AccountSettings, Device, DevicePairing, PairingCode, RefreshToken, User
from app.security import create_access_token, hash_password, generate_refresh_token, hash_refresh_token
from datetime import datetime, timedelta, timezone


def _seed_full_account(db):
    """Create a user with devices, pairings, settings, and tokens."""
    user = User(
        email=f"del-{uuid.uuid4().hex[:8]}@test.com",
        password_hash=hash_password("TestPass123!"),
        display_name="Delete Me",
        is_verified=True,
    )
    db.add(user)
    db.flush()

    # Device
    dev = Device(
        user_id=user.id,
        device_type="phone",
        platform="android",
        display_name="Test Phone",
        installation_id=f"del-{uuid.uuid4().hex[:8]}",
    )
    db.add(dev)
    db.flush()

    # Account settings
    settings = AccountSettings(
        user_id=user.id,
        settings={"language": "en"},
    )
    db.add(settings)

    # Refresh token
    raw = generate_refresh_token()
    token = RefreshToken(
        user_id=user.id,
        token_hash=hash_refresh_token(raw),
        expires_at=datetime.now(timezone.utc) + timedelta(days=90),
    )
    db.add(token)

    db.commit()
    db.refresh(user)
    return user


def test_delete_own_account(client, db):
    user = _seed_full_account(db)
    token = create_access_token(str(user.id))

    r = client.delete("/me/account", headers={"Authorization": f"Bearer {token}"})
    assert r.status_code == 200
    assert "deleted" in r.json()["message"].lower()

    # User row gone
    assert db.query(User).filter(User.id == user.id).first() is None
    # Cascaded children gone
    assert db.query(Device).filter(Device.user_id == user.id).first() is None
    assert db.query(AccountSettings).filter(AccountSettings.user_id == user.id).first() is None
    assert db.query(RefreshToken).filter(RefreshToken.user_id == user.id).first() is None


def test_delete_account_unauthenticated(client):
    r = client.delete("/me/account")
    assert r.status_code in (401, 403)


def test_deleted_account_cannot_login(client, db):
    user = _seed_full_account(db)
    email = user.email
    token = create_access_token(str(user.id))

    # Delete
    r = client.delete("/me/account", headers={"Authorization": f"Bearer {token}"})
    assert r.status_code == 200

    # Try to login
    r2 = client.post("/auth/login", json={
        "email": email,
        "password": "TestPass123!",
    })
    assert r2.status_code == 401


def test_delete_is_idempotent_safe(client, db):
    """Second delete with stale token returns 404 (user gone), not 500."""
    user = _seed_full_account(db)
    token = create_access_token(str(user.id))

    r1 = client.delete("/me/account", headers={"Authorization": f"Bearer {token}"})
    assert r1.status_code == 200

    r2 = client.delete("/me/account", headers={"Authorization": f"Bearer {token}"})
    assert r2.status_code == 404


def test_cannot_delete_other_user(client, db):
    """A valid token for user A cannot delete user B."""
    user_a = _seed_full_account(db)
    user_b = _seed_full_account(db)
    token_a = create_access_token(str(user_a.id))

    # User A deletes their own account
    r = client.delete("/me/account", headers={"Authorization": f"Bearer {token_a}"})
    assert r.status_code == 200

    # User B still exists
    assert db.query(User).filter(User.id == user_b.id).first() is not None

    # Cleanup user B
    db.delete(db.query(User).filter(User.id == user_b.id).first())
    db.commit()
