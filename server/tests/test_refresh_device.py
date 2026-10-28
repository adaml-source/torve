"""Tests for /auth/refresh device registration/recovery path."""
import uuid

from app.models import Device
from app.security import create_access_token, generate_refresh_token, hash_refresh_token
from app.models import RefreshToken
from datetime import datetime, timedelta, timezone


def _create_refresh_token_row(db, user):
    raw = generate_refresh_token()
    token_row = RefreshToken(
        user_id=user.id,
        token_hash=hash_refresh_token(raw),
        expires_at=datetime.now(timezone.utc) + timedelta(days=90),
    )
    db.add(token_row)
    db.commit()
    return raw


def test_refresh_with_nested_device_creates_device(client, test_user, db):
    raw_token = _create_refresh_token_row(db, test_user)

    r = client.post("/auth/refresh", json={
        "refresh_token": raw_token,
        "device": {
            "device_type": "tv",
            "platform": "firetv",
            "device_name": "Fire TV Stick",
            "installation_id": "refresh-test-001",
            "app_version": "1.0.0",
        },
    })
    assert r.status_code == 200
    data = r.json()
    assert data["device"] is not None
    assert data["device"]["device_type"] == "tv"
    assert data["device"]["platform"] == "firetv"
    assert data["device"]["installation_id"] == "refresh-test-001"
    assert data["tokens"]["access_token"]


def test_refresh_with_flat_device_creates_device(client, test_user, db):
    raw_token = _create_refresh_token_row(db, test_user)

    r = client.post("/auth/refresh", json={
        "refresh_token": raw_token,
        "device_type": "phone",
        "platform": "android",
        "device_name": "Pixel 8",
        "installation_id": "refresh-flat-001",
    })
    assert r.status_code == 200
    data = r.json()
    assert data["device"] is not None
    assert data["device"]["device_type"] == "phone"


def test_refresh_without_device_returns_null_device(client, test_user, db):
    raw_token = _create_refresh_token_row(db, test_user)

    r = client.post("/auth/refresh", json={
        "refresh_token": raw_token,
    })
    assert r.status_code == 200
    data = r.json()
    assert data["device"] is None
    assert data["tokens"]["access_token"]


def test_refresh_device_upserts_on_same_installation_id(client, test_user, db):
    raw_token = _create_refresh_token_row(db, test_user)

    # First refresh creates device
    r1 = client.post("/auth/refresh", json={
        "refresh_token": raw_token,
        "device": {
            "device_type": "tv",
            "platform": "firetv",
            "installation_id": "upsert-test",
            "app_version": "1.0.0",
        },
    })
    dev_id_1 = r1.json()["device"]["id"]

    # Second refresh with same installation_id returns same device
    raw_token2 = _create_refresh_token_row(db, test_user)
    r2 = client.post("/auth/refresh", json={
        "refresh_token": raw_token2,
        "device": {
            "device_type": "tv",
            "platform": "firetv",
            "installation_id": "upsert-test",
            "app_version": "2.0.0",
        },
    })
    dev_id_2 = r2.json()["device"]["id"]
    assert dev_id_1 == dev_id_2
    assert r2.json()["device"]["app_version"] == "2.0.0"


def test_refresh_device_then_pairing_code(client, test_user, db):
    test_user.has_lifetime_access = True
    db.commit()
    """End-to-end: refresh creates device, then that device can generate pairing code."""
    raw_token = _create_refresh_token_row(db, test_user)

    r1 = client.post("/auth/refresh", json={
        "refresh_token": raw_token,
        "device": {
            "device_type": "tv",
            "platform": "firetv",
            "installation_id": "pair-after-refresh",
        },
    })
    device_id = r1.json()["device"]["id"]
    access_token = r1.json()["tokens"]["access_token"]

    r2 = client.post(
        "/pairing/code",
        json={"device_id": device_id},
        headers={"Authorization": f"Bearer {access_token}"},
    )
    assert r2.status_code == 201
    assert len(r2.json()["code"]) == 6
    assert r2.json()["target_device_id"] == device_id
