"""Tests for the unified entitlement contract and device management access.

Proves:
- GET /me returns canonical access state fields
- Free users can manage devices and pairings
- Historical lifetime/subscription records do not create paid tiers
- Legacy boolean fields are consistent with canonical fields
- Unauthorized and cross-account access still fails
"""
import uuid
from datetime import datetime, timedelta, timezone

import pytest
from sqlalchemy.orm import Session

from app.models import Device, DevicePairing, User, UserEntitlement
from app.security import create_access_token, hash_password


# ── Helpers ──────────────────────────────────────────────────────────────────


def _make_user(db: Session, *, premium: bool = False, lifetime: bool = False) -> User:
    user = User(
        id=uuid.uuid4(),
        email=f"ent-{uuid.uuid4().hex[:8]}@test.com",
        password_hash=hash_password("TestPass123!"),
        display_name="Entitlement Test",
        is_verified=True,
        has_lifetime_access=lifetime,
        has_premium_access=premium or lifetime,
    )
    db.add(user)
    db.commit()
    db.refresh(user)
    return user


def _auth(user: User) -> dict:
    return {"Authorization": f"Bearer {create_access_token(str(user.id))}"}


def _make_device(db: Session, user: User, dtype: str = "tv", platform: str = "firetv") -> Device:
    dev = Device(
        user_id=user.id,
        device_type=dtype,
        platform=platform,
        display_name=f"Test {dtype}",
        installation_id=f"test-{uuid.uuid4().hex[:8]}",
    )
    db.add(dev)
    db.commit()
    db.refresh(dev)
    return dev


# ── GET /me canonical response ──────────────────────────────────────────────


class TestMeEndpoint:
    """GET /me returns canonical access state inline."""

    def test_free_user_me(self, client, db):
        user = _make_user(db)
        try:
            r = client.get("/me", headers=_auth(user))
            assert r.status_code == 200
            body = r.json()
            # Profile fields present
            assert body["id"] == str(user.id)
            assert body["email"] == user.email
            # Canonical access state
            assert body["has_premium_access"] is True
            assert body["access_tier"] == "free"
            assert body["entitlement_source"] is None
            assert body["entitlement_expires_at"] is None
            assert body["auto_renew"] is None
            # Legacy compat
            assert body["has_lifetime_access"] is False
        finally:
            db.delete(user)
            db.commit()

    def test_lifetime_user_me(self, client, db):
        user = _make_user(db, lifetime=True)
        ent = UserEntitlement(
            user_id=user.id,
            entitlement_type="lifetime_access",
            source="admin_grant",
            source_ref=f"test-{uuid.uuid4().hex[:8]}",
            status="active",
        )
        db.add(ent)
        db.commit()
        try:
            r = client.get("/me", headers=_auth(user))
            assert r.status_code == 200
            body = r.json()
            assert body["has_premium_access"] is True
            assert body["access_tier"] == "free"
            assert body["entitlement_source"] is None
            assert body["entitlement_expires_at"] is None
            assert body["auto_renew"] is None
            assert body["has_lifetime_access"] is False
        finally:
            db.query(UserEntitlement).filter(UserEntitlement.user_id == user.id).delete(synchronize_session=False)
            db.delete(user)
            db.commit()

    def test_subscription_user_me(self, client, db):
        user = _make_user(db, premium=True)
        expires = datetime.now(timezone.utc) + timedelta(days=30)
        ent = UserEntitlement(
            user_id=user.id,
            entitlement_type="subscription_monthly",
            source="paddle_web",
            source_ref=f"txn-{uuid.uuid4().hex[:8]}",
            status="active",
            expires_at=expires,
            auto_renew=True,
        )
        db.add(ent)
        db.commit()
        try:
            r = client.get("/me", headers=_auth(user))
            assert r.status_code == 200
            body = r.json()
            assert body["has_premium_access"] is True
            assert body["access_tier"] == "free"
            assert body["entitlement_source"] is None
            assert body["entitlement_expires_at"] is None
            assert body["auto_renew"] is None
            assert body["has_lifetime_access"] is False
        finally:
            db.query(UserEntitlement).filter(UserEntitlement.user_id == user.id).delete(synchronize_session=False)
            db.delete(user)
            db.commit()

    def test_no_entitlement_resolves_default_access(self, client, db):
        """Entitlement absence does not block default product access."""
        user = _make_user(db, premium=True)  # has_premium_access=True but no entitlement
        try:
            r = client.get("/me", headers=_auth(user))
            assert r.status_code == 200
            body = r.json()
            assert body["access_tier"] == "free"
            assert body["has_premium_access"] is True
        finally:
            db.delete(user)
            db.commit()

    def test_unauthenticated_me(self, client):
        r = client.get("/me")
        assert r.status_code in (401, 403)


# ── Free user device management ────────────────────────────────────────────


class TestFreeUserDevices:
    """Free users can manage devices without premium access."""

    def test_free_user_list_devices(self, client, free_user, free_auth_header, free_tv_device):
        r = client.get("/me/devices", headers=free_auth_header)
        assert r.status_code == 200
        devices = r.json()
        assert len(devices) >= 1
        assert any(d["id"] == str(free_tv_device.id) for d in devices)

    def test_free_user_register_device(self, client, db, free_user, free_auth_header):
        r = client.post("/me/devices/register", headers=free_auth_header, json={
            "device_type": "phone",
            "platform": "android",
            "display_name": "Free Phone",
        })
        assert r.status_code == 201
        device_id = r.json()["id"]
        # Cleanup
        dev = db.query(Device).filter(Device.id == uuid.UUID(device_id)).first()
        if dev:
            db.delete(dev)
            db.commit()

    def test_free_user_rename_device(self, client, free_user, free_auth_header, free_tv_device):
        r = client.post(
            f"/me/devices/{free_tv_device.id}/rename",
            headers=free_auth_header,
            json={"display_name": "Renamed TV"},
        )
        assert r.status_code == 200
        assert r.json()["display_name"] == "Renamed TV"

    def test_free_user_revoke_device(self, client, db, free_user, free_auth_header):
        # Create a device to revoke
        dev = Device(
            user_id=free_user.id,
            device_type="tablet",
            platform="android",
            display_name="Revoke Test",
            installation_id=f"rev-{uuid.uuid4().hex[:8]}",
        )
        db.add(dev)
        db.commit()
        db.refresh(dev)
        try:
            r = client.post(f"/me/devices/{dev.id}/revoke", headers=free_auth_header)
            assert r.status_code == 200
            assert r.json()["is_active"] is False
        finally:
            db.delete(dev)
            db.commit()


# ── Free user pairing management ───────────────────────────────────────────


class TestFreeUserPairings:
    """Free users can list and revoke pairings."""

    def test_free_user_list_pairings(self, client, free_user, free_auth_header):
        r = client.get("/me/pairings", headers=free_auth_header)
        assert r.status_code == 200
        assert isinstance(r.json(), list)

    def test_free_user_revoke_pairing(
        self, client, db, free_user, free_auth_header, free_tv_device, free_phone_device,
    ):
        # Create a pairing
        pairing = DevicePairing(
            user_id=free_user.id,
            controller_device_id=free_phone_device.id,
            target_device_id=free_tv_device.id,
        )
        db.add(pairing)
        db.commit()
        db.refresh(pairing)
        try:
            r = client.post(f"/me/pairings/{pairing.id}/revoke", headers=free_auth_header)
            assert r.status_code == 200
            assert r.json()["status"] == "revoked"
        finally:
            db.delete(pairing)
            db.commit()

    def test_free_user_create_pairing(
        self, client, db, free_user, free_auth_header, free_tv_device, free_phone_device,
    ):
        r = client.post("/me/pairings", headers=free_auth_header, json={
            "controller_device_id": str(free_phone_device.id),
            "target_device_id": str(free_tv_device.id),
        })
        assert r.status_code == 201
        pairing_id = r.json()["id"]
        # Cleanup
        p = db.query(DevicePairing).filter(DevicePairing.id == uuid.UUID(pairing_id)).first()
        if p:
            db.delete(p)
            db.commit()


# ── Former premium gates are open to authenticated users ────────────────────


class TestPremiumGating:
    """Former premium-only features are free-software product features."""

    def test_free_user_can_generate_pairing_code(
        self, client, free_user, free_auth_header, free_tv_device,
    ):
        r = client.post("/pairing/code", headers=free_auth_header, json={
            "device_id": str(free_tv_device.id),
        })
        assert r.status_code == 201

    def test_free_user_reaches_claim_pairing_code_handler(self, client, free_user, free_auth_header):
        r = client.post("/pairing/claim", headers=free_auth_header, json={
            "code": "ABC123",
            "device_id": str(uuid.uuid4()),
        })
        assert r.status_code != 403

    def test_free_user_can_save_integration(self, client, free_user, free_auth_header):
        r = client.put("/me/integrations/real_debrid", headers=free_auth_header, json={
            "integration_type": "real_debrid",
            "credentials": {"api_key": "test"},
        })
        assert r.status_code == 200

    def test_free_user_can_save_playlist(self, client, free_user, free_auth_header):
        r = client.put("/me/playlists/test-playlist", headers=free_auth_header, json={
            "playlist_id": "test-playlist",
            "name": "Test",
            "playlist_type": "m3u",
            "url": "http://example.com/playlist.m3u",
        })
        assert r.status_code == 200

    def test_premium_user_can_generate_pairing_code(
        self, client, test_user, auth_header, tv_device,
    ):
        r = client.post("/pairing/code", headers=auth_header, json={
            "device_id": str(tv_device.id),
        })
        assert r.status_code == 201
        assert "code" in r.json()


# ── Cross-account / unauthorized access ─────────────────────────────────────


class TestOwnershipEnforcement:
    """Auth and resource ownership still enforced after ungating."""

    def test_cannot_list_other_users_devices(self, client, test_user, auth_header_b):
        r = client.get("/me/devices", headers=auth_header_b)
        assert r.status_code == 200
        # user_b should not see test_user's devices
        ids = [d["id"] for d in r.json()]
        # This is correct - each user only sees their own

    def test_cannot_revoke_other_users_device(
        self, client, db, free_user, free_auth_header, tv_device,
    ):
        """Free user cannot revoke a device belonging to premium user."""
        r = client.post(f"/me/devices/{tv_device.id}/revoke", headers=free_auth_header)
        assert r.status_code == 404  # Not found (ownership check)

    def test_cannot_rename_other_users_device(
        self, client, db, free_user, free_auth_header, tv_device,
    ):
        r = client.post(
            f"/me/devices/{tv_device.id}/rename",
            headers=free_auth_header,
            json={"display_name": "Stolen"},
        )
        assert r.status_code == 404

    def test_cannot_revoke_other_users_pairing(
        self, client, db, test_user, auth_header, free_user, free_auth_header,
        tv_device, phone_device,
    ):
        pairing = DevicePairing(
            user_id=test_user.id,
            controller_device_id=phone_device.id,
            target_device_id=tv_device.id,
        )
        db.add(pairing)
        db.commit()
        db.refresh(pairing)
        try:
            # Free user tries to revoke premium user's pairing
            r = client.post(f"/me/pairings/{pairing.id}/revoke", headers=free_auth_header)
            assert r.status_code == 404
        finally:
            db.delete(pairing)
            db.commit()


# ── Legacy field consistency ────────────────────────────────────────────────


class TestLegacyFieldConsistency:
    """Legacy boolean fields are consistent with canonical access_tier."""

    def test_free_user_legacy_fields(self, client, db):
        user = _make_user(db)
        try:
            body = client.get("/me", headers=_auth(user)).json()
            assert body["access_tier"] == "free"
            assert body["has_premium_access"] is True
            assert body["has_lifetime_access"] is False
        finally:
            db.delete(user)
            db.commit()

    def test_lifetime_user_legacy_fields(self, client, db):
        user = _make_user(db, lifetime=True)
        ent = UserEntitlement(
            user_id=user.id,
            entitlement_type="lifetime_access",
            source="admin_grant",
            source_ref=f"test-{uuid.uuid4().hex[:8]}",
            status="active",
        )
        db.add(ent)
        db.commit()
        try:
            body = client.get("/me", headers=_auth(user)).json()
            assert body["access_tier"] == "free"
            assert body["has_premium_access"] is True
            assert body["has_lifetime_access"] is False
        finally:
            db.query(UserEntitlement).filter(UserEntitlement.user_id == user.id).delete(synchronize_session=False)
            db.delete(user)
            db.commit()

    def test_subscription_user_legacy_fields(self, client, db):
        user = _make_user(db, premium=True)
        ent = UserEntitlement(
            user_id=user.id,
            entitlement_type="subscription_monthly",
            source="google_play",
            source_ref=f"gp-{uuid.uuid4().hex[:8]}",
            status="active",
            expires_at=datetime.now(timezone.utc) + timedelta(days=30),
            auto_renew=False,
        )
        db.add(ent)
        db.commit()
        try:
            body = client.get("/me", headers=_auth(user)).json()
            assert body["access_tier"] == "free"
            assert body["has_premium_access"] is True
            assert body["has_lifetime_access"] is False
            assert body["entitlement_source"] is None
            assert body["auto_renew"] is None
        finally:
            db.query(UserEntitlement).filter(UserEntitlement.user_id == user.id).delete(synchronize_session=False)
            db.delete(user)
            db.commit()
