"""Tests for stable_device_id deduplication.

Proves:
- Same stable_device_id with different installation_ids reuses one device row
- stable_device_id takes priority over installation_id for matching
- Legacy clients without stable_device_id still work (installation_id dedup)
- Backfill: existing device gets stable_device_id populated on next registration
- Reinstall scenario: new installation_id, same stable_device_id = same device
"""
import uuid

from app.models import Device, User, UserEntitlement
from app.security import create_access_token, hash_password


def _make_user(db, *, premium=False, lifetime=False):
    user = User(
        id=uuid.uuid4(),
        email=f"stable-{uuid.uuid4().hex[:8]}@test.com",
        password_hash=hash_password("TestPass123!"),
        display_name="Stable ID Test",
        is_verified=True,
        has_lifetime_access=lifetime,
        has_premium_access=premium or lifetime,
    )
    db.add(user)
    db.commit()
    return user


def _auth(user):
    return {"Authorization": f"Bearer {create_access_token(str(user.id))}"}


def _cleanup(db, user):
    db.query(Device).filter(Device.user_id == user.id).delete()
    db.query(UserEntitlement).filter(UserEntitlement.user_id == user.id).delete()
    db.commit()
    u = db.query(User).filter(User.id == user.id).first()
    if u:
        db.delete(u)
        db.commit()


class TestStableDeviceDedup:

    def test_same_stable_id_different_install_id(self, client, db):
        """Reinstall: new installation_id but same stable_device_id = same row."""
        user = _make_user(db)
        stable = f"android-id-{uuid.uuid4().hex[:8]}"
        try:
            # First install
            r1 = client.post("/me/devices/register", headers=_auth(user), json={
                "device_type": "phone",
                "platform": "android",
                "installation_id": "install-v1",
                "stable_device_id": stable,
            })
            assert r1.status_code == 201
            dev_id = r1.json()["id"]
            assert r1.json()["stable_device_id"] == stable

            # Reinstall — new installation_id, same stable_device_id
            r2 = client.post("/me/devices/register", headers=_auth(user), json={
                "device_type": "phone",
                "platform": "android",
                "installation_id": "install-v2",
                "stable_device_id": stable,
            })
            # Same device row reused
            assert r2.json()["id"] == dev_id
            # installation_id updated to new one
            assert r2.json()["installation_id"] == "install-v2"
            assert r2.json()["stable_device_id"] == stable
        finally:
            _cleanup(db, user)

    def test_stable_id_priority_over_install_id(self, client, db):
        """stable_device_id match takes priority even if installation_id differs."""
        user = _make_user(db)
        stable = f"priority-{uuid.uuid4().hex[:8]}"
        try:
            r1 = client.post("/me/devices/register", headers=_auth(user), json={
                "device_type": "tv",
                "platform": "firetv",
                "installation_id": "old-install",
                "stable_device_id": stable,
            })
            dev_id = r1.json()["id"]

            # Different installation_id, same stable — should match
            r2 = client.post("/me/devices/register", headers=_auth(user), json={
                "device_type": "tv",
                "platform": "firetv",
                "installation_id": "new-install",
                "stable_device_id": stable,
            })
            assert r2.json()["id"] == dev_id
        finally:
            _cleanup(db, user)

    def test_legacy_client_no_stable_id(self, client, db):
        """Old clients without stable_device_id still dedup on installation_id."""
        user = _make_user(db)
        try:
            r1 = client.post("/me/devices/register", headers=_auth(user), json={
                "device_type": "phone",
                "platform": "android",
                "installation_id": "legacy-install",
            })
            assert r1.status_code == 201
            dev_id = r1.json()["id"]
            assert r1.json()["stable_device_id"] is None

            r2 = client.post("/me/devices/register", headers=_auth(user), json={
                "device_type": "phone",
                "platform": "android",
                "installation_id": "legacy-install",
            })
            assert r2.json()["id"] == dev_id
        finally:
            _cleanup(db, user)

    def test_backfill_stable_id(self, client, db):
        """Legacy device gets stable_device_id populated on next registration."""
        user = _make_user(db)
        stable = f"backfill-{uuid.uuid4().hex[:8]}"
        try:
            # Register without stable_device_id (old client)
            r1 = client.post("/me/devices/register", headers=_auth(user), json={
                "device_type": "phone",
                "platform": "android",
                "installation_id": "backfill-install",
            })
            dev_id = r1.json()["id"]
            assert r1.json()["stable_device_id"] is None

            # Same installation_id, now with stable_device_id (updated client)
            r2 = client.post("/me/devices/register", headers=_auth(user), json={
                "device_type": "phone",
                "platform": "android",
                "installation_id": "backfill-install",
                "stable_device_id": stable,
            })
            assert r2.json()["id"] == dev_id
            assert r2.json()["stable_device_id"] == stable
        finally:
            _cleanup(db, user)

    def test_reinstall_does_not_consume_extra_slot(self, client, db):
        """Premium user reinstalling doesn't waste a device slot."""
        user = _make_user(db, lifetime=True)
        ent = UserEntitlement(
            user_id=user.id,
            entitlement_type="lifetime_access",
            source="admin_grant",
            source_ref=f"stable-test-{user.id}",
            status="active",
        )
        db.add(ent)
        db.commit()
        stable = f"slot-{uuid.uuid4().hex[:8]}"
        try:
            # Register 4 other devices
            for i in range(4):
                client.post("/me/devices/register", headers=_auth(user), json={
                    "device_type": "phone",
                    "platform": "android",
                    "installation_id": f"other-{i}-{user.id}",
                    "stable_device_id": f"other-stable-{i}-{user.id}",
                })

            # Register the 5th device
            r1 = client.post("/me/devices/register", headers=_auth(user), json={
                "device_type": "tv",
                "platform": "firetv",
                "installation_id": "tv-install-v1",
                "stable_device_id": stable,
            })
            assert r1.status_code == 201

            # "Reinstall" — same stable_device_id, new installation_id
            # Should NOT hit cap because it's the same physical device
            r2 = client.post("/me/devices/register", headers=_auth(user), json={
                "device_type": "tv",
                "platform": "firetv",
                "installation_id": "tv-install-v2",
                "stable_device_id": stable,
            })
            assert r2.json()["id"] == r1.json()["id"]
            assert r2.status_code in (200, 201)
        finally:
            _cleanup(db, user)

    def test_revoked_device_reactivated_by_stable_id(self, client, db):
        """Revoked device is reactivated when same stable_device_id registers."""
        user = _make_user(db)
        stable = f"reactivate-{uuid.uuid4().hex[:8]}"
        try:
            r1 = client.post("/me/devices/register", headers=_auth(user), json={
                "device_type": "tv",
                "platform": "firetv",
                "installation_id": "rev-install-v1",
                "stable_device_id": stable,
            })
            dev_id = r1.json()["id"]

            # Revoke it
            client.post(f"/me/devices/{dev_id}/revoke", headers=_auth(user))

            # Re-register with new install ID but same stable ID
            r2 = client.post("/me/devices/register", headers=_auth(user), json={
                "device_type": "tv",
                "platform": "firetv",
                "installation_id": "rev-install-v2",
                "stable_device_id": stable,
            })
            assert r2.json()["id"] == dev_id
            assert r2.json()["is_active"] is True
            assert r2.json()["installation_id"] == "rev-install-v2"
        finally:
            _cleanup(db, user)

    def test_login_with_stable_device_id(self, client, db):
        """Login with stable_device_id deduplicates correctly."""
        user = _make_user(db)
        stable = f"login-{uuid.uuid4().hex[:8]}"
        try:
            # First login with device info
            r1 = client.post("/auth/login", json={
                "email": user.email,
                "password": "TestPass123!",
                "device_type": "phone",
                "platform": "android",
                "installation_id": "login-install-v1",
                "stable_device_id": stable,
            })
            assert r1.status_code == 200
            dev1 = r1.json().get("device")
            assert dev1 is not None
            dev_id = dev1["id"]

            # Second login — new install ID, same stable ID
            r2 = client.post("/auth/login", json={
                "email": user.email,
                "password": "TestPass123!",
                "device_type": "phone",
                "platform": "android",
                "installation_id": "login-install-v2",
                "stable_device_id": stable,
            })
            assert r2.status_code == 200
            dev2 = r2.json().get("device")
            assert dev2["id"] == dev_id
        finally:
            _cleanup(db, user)
