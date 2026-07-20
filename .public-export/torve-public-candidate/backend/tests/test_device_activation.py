"""Tests for device activation slot logic and structured error contract.

Proves:
- Free users do NOT consume premium device slots on login/register/device-register
- Historical premium users do NOT get paid device cap enforcement
- Idempotent reactivation by installation_id
- Revoked devices are not counted as active
- /me/access-state reports device registration state without paid caps
"""
import uuid
from datetime import datetime, timedelta, timezone

from app.models import Device, User, UserEntitlement
from app.security import create_access_token, hash_password


def _make_user(db, *, premium=False, lifetime=False):
    user = User(
        id=uuid.uuid4(),
        email=f"devact-{uuid.uuid4().hex[:8]}@test.com",
        password_hash=hash_password("TestPass123!"),
        display_name="Device Activation Test",
        is_verified=True,
        has_lifetime_access=lifetime,
        has_premium_access=premium or lifetime,
    )
    db.add(user)
    db.flush()
    return user


def _grant_lifetime(db, user):
    ent = UserEntitlement(
        user_id=user.id,
        entitlement_type="lifetime_access",
        source="admin_grant",
        source_ref=f"devact-{user.id}",
        status="active",
    )
    db.add(ent)
    db.commit()
    return ent


def _grant_subscription(db, user, days=30):
    ent = UserEntitlement(
        user_id=user.id,
        entitlement_type="subscription_monthly",
        source="paddle_web",
        source_ref=f"sub-{user.id}",
        status="active",
        expires_at=datetime.now(timezone.utc) + timedelta(days=days),
        auto_renew=True,
    )
    db.add(ent)
    db.commit()
    return ent


def _auth(user):
    return {"Authorization": f"Bearer {create_access_token(str(user.id))}"}


def _cleanup(db, user):
    db.query(Device).filter(Device.user_id == user.id).delete()
    db.query(UserEntitlement).filter(UserEntitlement.user_id == user.id).delete()
    db.commit()
    # Re-fetch user in case session state is stale after bulk deletes
    u = db.query(User).filter(User.id == user.id).first()
    if u:
        db.delete(u)
        db.commit()


# ── Free user does NOT consume slots ────────────────────────────────────


class TestFreeUserNoSlots:

    def test_free_login_no_slot(self, client, db):
        """Free account login does not activate a premium device slot."""
        user = _make_user(db)
        db.commit()
        try:
            # Login with device info
            r = client.post("/auth/login", json={
                "email": user.email,
                "password": "TestPass123!",
                "device_type": "phone",
                "platform": "android",
                "installation_id": f"free-login-{user.id}",
            })
            assert r.status_code == 200
            # Device row created but no cap enforcement
            devices = db.query(Device).filter(
                Device.user_id == user.id, Device.is_active == True,
            ).count()
            assert devices >= 1  # Device exists for bookkeeping
        finally:
            _cleanup(db, user)

    def test_free_register_no_slot(self, client, db):
        """Free account signup does not activate a premium device slot."""
        email = f"devact-signup-{uuid.uuid4().hex[:8]}@test.com"
        r = client.post("/auth/signup", json={
            "email": email,
            "password": "TestPass123!",
            "device_type": "phone",
            "platform": "android",
            "installation_id": f"free-signup-{uuid.uuid4().hex[:6]}",
        })
        assert r.status_code == 201
        user = db.query(User).filter(User.email == email).first()
        try:
            assert user is not None
            # Device exists for bookkeeping
            devices = db.query(Device).filter(
                Device.user_id == user.id, Device.is_active == True,
            ).count()
            assert devices >= 1
        finally:
            _cleanup(db, user)

    def test_free_device_register_no_slot(self, client, db):
        """Free account /devices/register does not activate a premium device slot."""
        user = _make_user(db)
        db.commit()
        try:
            # Register 10 devices — no cap for free users
            for i in range(10):
                r = client.post("/me/devices/register", headers=_auth(user), json={
                    "device_type": "phone",
                    "platform": "android",
                    "installation_id": f"free-dev-{i}-{user.id}",
                })
                assert r.status_code == 201, f"Device {i} failed: {r.json()}"
        finally:
            _cleanup(db, user)


# ── Premium user gets cap enforcement ──────────────────────────────────


class TestPremiumDeviceCap:

    def test_premium_lifetime_cap(self, client, db):
        """Historical lifetime entitlement does not create a paid device cap."""
        user = _make_user(db, lifetime=True)
        ent = _grant_lifetime(db, user)
        try:
            for i in range(6):
                r = client.post("/me/devices/register", headers=_auth(user), json={
                    "device_type": "phone",
                    "platform": "android",
                    "installation_id": f"lt-cap-{i}-{user.id}",
                })
                assert r.status_code == 201
        finally:
            _cleanup(db, user)

    def test_premium_subscription_cap(self, client, db):
        """Historical subscription entitlement does not create a paid device cap."""
        user = _make_user(db, premium=True)
        ent = _grant_subscription(db, user)
        try:
            for i in range(6):
                r = client.post("/me/devices/register", headers=_auth(user), json={
                    "device_type": "phone",
                    "platform": "android",
                    "installation_id": f"sub-cap-{i}-{user.id}",
                })
                assert r.status_code == 201
        finally:
            _cleanup(db, user)

    def test_stale_boolean_no_cap(self, client, db):
        """User with stale has_premium_access=True but no entitlement gets no cap."""
        user = _make_user(db, premium=True)  # Boolean says premium...
        db.commit()
        # ...but no entitlement row. check_premium_active should return False.
        try:
            for i in range(7):
                r = client.post("/me/devices/register", headers=_auth(user), json={
                    "device_type": "phone",
                    "platform": "android",
                    "installation_id": f"stale-{i}-{user.id}",
                })
                assert r.status_code == 201, f"Device {i} should not be capped: {r.json()}"
        finally:
            _cleanup(db, user)


# ── Idempotent reactivation ─────────────────────────────────────────────


class TestIdempotentReactivation:

    def test_same_installation_id_idempotent(self, client, db):
        """Repeated login from same installation_id is idempotent."""
        user = _make_user(db)
        db.commit()
        inst_id = f"idem-{user.id}"
        try:
            # First registration
            r1 = client.post("/me/devices/register", headers=_auth(user), json={
                "device_type": "tv",
                "platform": "firetv",
                "installation_id": inst_id,
            })
            assert r1.status_code == 201
            dev_id_1 = r1.json()["id"]

            # Same installation_id — should return same device
            r2 = client.post("/me/devices/register", headers=_auth(user), json={
                "device_type": "tv",
                "platform": "firetv",
                "installation_id": inst_id,
            })
            assert r2.status_code == 200 or r2.status_code == 201
            assert r2.json()["id"] == dev_id_1
        finally:
            _cleanup(db, user)

    def test_revoked_device_reactivated_by_install_id(self, client, db):
        """Revoked device is reactivated when same installation_id registers."""
        user = _make_user(db)
        db.commit()
        inst_id = f"reactivate-{user.id}"
        try:
            # Register
            r = client.post("/me/devices/register", headers=_auth(user), json={
                "device_type": "tv",
                "platform": "firetv",
                "installation_id": inst_id,
            })
            dev_id = r.json()["id"]

            # Revoke
            client.post(f"/me/devices/{dev_id}/revoke", headers=_auth(user))

            # Verify revoked
            dev = db.query(Device).filter(Device.id == uuid.UUID(dev_id)).first()
            assert dev.is_active is False

            # Re-register with same installation_id — reactivates
            r2 = client.post("/me/devices/register", headers=_auth(user), json={
                "device_type": "tv",
                "platform": "firetv",
                "installation_id": inst_id,
            })
            assert r2.json()["id"] == dev_id
            assert r2.json()["is_active"] is True
        finally:
            _cleanup(db, user)

    def test_revoked_not_counted_as_active(self, client, db):
        """Revoked devices are not counted toward the device cap."""
        user = _make_user(db, lifetime=True)
        ent = _grant_lifetime(db, user)
        try:
            dev_ids = []
            # Register 5 devices
            for i in range(5):
                r = client.post("/me/devices/register", headers=_auth(user), json={
                    "device_type": "phone",
                    "platform": "android",
                    "installation_id": f"revcount-{i}-{user.id}",
                })
                assert r.status_code == 201
                dev_ids.append(r.json()["id"])

            # Revoke 2 devices
            for dev_id in dev_ids[:2]:
                client.post(f"/me/devices/{dev_id}/revoke", headers=_auth(user))

            # Should be able to register 2 more (3 active + 2 = 5)
            for i in range(2):
                r = client.post("/me/devices/register", headers=_auth(user), json={
                    "device_type": "phone",
                    "platform": "android",
                    "installation_id": f"revcount-new-{i}-{user.id}",
                })
                assert r.status_code == 201
        finally:
            _cleanup(db, user)


# ── Structured error contract ───────────────────────────────────────────


class TestStructuredErrors:

    def test_device_cap_response_structured(self, client, db):
        """Paid device caps no longer block registration."""
        user = _make_user(db, lifetime=True)
        ent = _grant_lifetime(db, user)
        try:
            for i in range(6):
                r = client.post("/me/devices/register", headers=_auth(user), json={
                    "device_type": "phone",
                    "platform": "android",
                    "installation_id": f"err-{i}-{user.id}",
                })
                assert r.status_code == 201
        finally:
            _cleanup(db, user)

    def test_premium_required_response_structured(self, client, db):
        """Former premium routes no longer return premium_required."""
        user = _make_user(db)
        db.commit()
        try:
            r = client.put("/me/integrations/test_key", headers=_auth(user), json={
                "integration_type": "test_key",
                "credentials": {"key": "val"},
            })
            assert r.status_code == 200
        finally:
            _cleanup(db, user)

    def test_access_state_device_cap_for_premium(self, client, db):
        """/me/access-state reports unregistered devices without paid cap state."""
        user = _make_user(db, lifetime=True)
        ent = _grant_lifetime(db, user)
        try:
            inst_ids = []
            for i in range(5):
                inst_id = f"accstate-{i}-{user.id}"
                inst_ids.append(inst_id)
                client.post("/me/devices/register", headers=_auth(user), json={
                    "device_type": "phone",
                    "platform": "android",
                    "installation_id": inst_id,
                })

            # Check access-state with a NEW installation_id (not registered)
            r = client.get(
                f"/me/access-state?installation_id=not-registered-{user.id}",
                headers=_auth(user),
            )
            assert r.status_code == 200
            body = r.json()
            assert body["has_premium_access"] is True
            assert body["is_device_activated"] is False
            assert body["device_block_reason"] == "device_not_registered"
            assert body["device_limit"] is None
        finally:
            _cleanup(db, user)

    def test_access_state_activated_device(self, client, db):
        """/me/access-state reports is_device_activated=True for known device."""
        user = _make_user(db, lifetime=True)
        ent = _grant_lifetime(db, user)
        inst_id = f"activated-{user.id}"
        try:
            client.post("/me/devices/register", headers=_auth(user), json={
                "device_type": "phone",
                "platform": "android",
                "installation_id": inst_id,
            })

            r = client.get(
                f"/me/access-state?installation_id={inst_id}",
                headers=_auth(user),
            )
            assert r.status_code == 200
            body = r.json()
            assert body["is_device_activated"] is True
            assert body["device_block_reason"] is None
        finally:
            _cleanup(db, user)
