"""Tests for /api/v1/transfer/* — sealed-envelope relay between two
devices of the same Torve user.

The server is a dumb relay: never decrypts the envelope, never inspects
its keys. These tests pin auth (same user only), payload size,
TTL ceiling, state transitions, and that the envelope only appears in a
delivered GET response.
"""
from __future__ import annotations

import base64
import os
import time
import uuid

from app.models import TransferSession
from app.security import create_access_token


def _auth(user_id) -> dict:
    return {"Authorization": f"Bearer {create_access_token(str(user_id))}"}


def _b64url_x25519() -> str:
    """A valid base64url-encoded 32-byte payload. Not a real X25519 key —
    the relay only checks length, not curve membership."""
    return base64.urlsafe_b64encode(os.urandom(32)).rstrip(b"=").decode()


def _future_ms(seconds_ahead: int) -> int:
    return int((time.time() + seconds_ahead) * 1000)


def _create(client, user_id, *, ttl_seconds: int = 60, **overrides) -> dict:
    body = {
        "receiver_public_key": _b64url_x25519(),
        "expires_at_epoch_ms": _future_ms(ttl_seconds),
        "receiver_device_id": "device-test-001",
    }
    body.update(overrides)
    r = client.post("/api/v1/transfer/sessions", json=body, headers=_auth(user_id))
    assert r.status_code == 201, r.text
    return r.json()


def _cleanup(db, session_id):
    db.query(TransferSession).filter(TransferSession.id == session_id).delete()
    db.commit()


# ── Create ──────────────────────────────────────────────────────────────


def test_create_session_returns_pending_state(client, test_user, db):
    data = _create(client, test_user.id)
    assert data["state"] == "pending"
    assert data["envelope"] is None
    assert "session_id" in data
    assert isinstance(data["expires_at_epoch_ms"], int)
    _cleanup(db, uuid.UUID(data["session_id"]))


def test_create_rejects_invalid_public_key(client, test_user):
    body = {
        "receiver_public_key": "not_base64@@@",
        "expires_at_epoch_ms": _future_ms(60),
        "receiver_device_id": "d",
    }
    r = client.post("/api/v1/transfer/sessions", json=body, headers=_auth(test_user.id))
    assert r.status_code == 400


def test_create_rejects_short_public_key(client, test_user):
    """Base64url that decodes to fewer than 32 bytes is rejected."""
    short = base64.urlsafe_b64encode(b"\x00" * 16).rstrip(b"=").decode()
    body = {
        "receiver_public_key": short,
        "expires_at_epoch_ms": _future_ms(60),
        "receiver_device_id": "d",
    }
    r = client.post("/api/v1/transfer/sessions", json=body, headers=_auth(test_user.id))
    assert r.status_code == 400


def test_create_rejects_expired_ttl(client, test_user):
    body = {
        "receiver_public_key": _b64url_x25519(),
        "expires_at_epoch_ms": _future_ms(-10),  # in the past
        "receiver_device_id": "d",
    }
    r = client.post("/api/v1/transfer/sessions", json=body, headers=_auth(test_user.id))
    assert r.status_code == 400


def test_create_rejects_ttl_over_10_minutes(client, test_user):
    body = {
        "receiver_public_key": _b64url_x25519(),
        "expires_at_epoch_ms": _future_ms(11 * 60),
        "receiver_device_id": "d",
    }
    r = client.post("/api/v1/transfer/sessions", json=body, headers=_auth(test_user.id))
    assert r.status_code == 400


# ── Auth isolation ──────────────────────────────────────────────────────


def test_other_user_cannot_read_session(client, test_user, test_user_b, db):
    data = _create(client, test_user.id)
    sid = data["session_id"]
    r = client.get(f"/api/v1/transfer/sessions/{sid}", headers=_auth(test_user_b.id))
    assert r.status_code == 404
    _cleanup(db, uuid.UUID(sid))


def test_other_user_cannot_post_envelope(client, test_user, test_user_b, db):
    data = _create(client, test_user.id)
    sid = data["session_id"]
    r = client.post(
        f"/api/v1/transfer/sessions/{sid}/envelope",
        json={"any": "thing"},
        headers=_auth(test_user_b.id),
    )
    assert r.status_code == 404
    _cleanup(db, uuid.UUID(sid))


def test_other_user_cannot_consume(client, test_user, test_user_b, db):
    data = _create(client, test_user.id)
    sid = data["session_id"]
    r = client.post(f"/api/v1/transfer/sessions/{sid}/consume", headers=_auth(test_user_b.id))
    assert r.status_code == 404
    _cleanup(db, uuid.UUID(sid))


def test_unauthenticated_requests_rejected(client):
    r = client.get(f"/api/v1/transfer/sessions/{uuid.uuid4()}")
    assert r.status_code in (401, 403)


# ── Envelope post ───────────────────────────────────────────────────────


def test_envelope_post_then_get_delivered(client, test_user, db):
    data = _create(client, test_user.id)
    sid = data["session_id"]
    envelope = {"v": 1, "ciphertext": "abc", "nonce": "def", "ephemeralPub": "xyz"}
    r = client.post(
        f"/api/v1/transfer/sessions/{sid}/envelope",
        json=envelope,
        headers=_auth(test_user.id),
    )
    assert r.status_code == 204

    g = client.get(f"/api/v1/transfer/sessions/{sid}", headers=_auth(test_user.id))
    assert g.status_code == 200
    body = g.json()
    assert body["state"] == "delivered"
    assert body["envelope"] == envelope
    _cleanup(db, uuid.UUID(sid))


def test_pending_get_does_not_include_envelope(client, test_user, db):
    data = _create(client, test_user.id)
    sid = data["session_id"]
    g = client.get(f"/api/v1/transfer/sessions/{sid}", headers=_auth(test_user.id))
    assert g.json()["state"] == "pending"
    assert g.json()["envelope"] is None
    _cleanup(db, uuid.UUID(sid))


def test_second_envelope_post_rejected_with_410(client, test_user, db):
    data = _create(client, test_user.id)
    sid = data["session_id"]
    r1 = client.post(
        f"/api/v1/transfer/sessions/{sid}/envelope",
        json={"v": 1, "ciphertext": "a"},
        headers=_auth(test_user.id),
    )
    assert r1.status_code == 204
    r2 = client.post(
        f"/api/v1/transfer/sessions/{sid}/envelope",
        json={"v": 1, "ciphertext": "b"},
        headers=_auth(test_user.id),
    )
    assert r2.status_code == 410
    _cleanup(db, uuid.UUID(sid))


def test_envelope_must_be_json_object(client, test_user, db):
    data = _create(client, test_user.id)
    sid = data["session_id"]
    # JSON array — valid JSON but not an object.
    r = client.post(
        f"/api/v1/transfer/sessions/{sid}/envelope",
        json=[1, 2, 3],
        headers=_auth(test_user.id),
    )
    assert r.status_code == 400
    _cleanup(db, uuid.UUID(sid))


def test_envelope_size_limit_enforced(client, test_user, db):
    data = _create(client, test_user.id)
    sid = data["session_id"]
    # > 64 KiB serialized — single key with a fat string value.
    huge = {"big": "x" * (65 * 1024)}
    r = client.post(
        f"/api/v1/transfer/sessions/{sid}/envelope",
        json=huge,
        headers=_auth(test_user.id),
    )
    assert r.status_code == 413
    _cleanup(db, uuid.UUID(sid))


# ── Consume ─────────────────────────────────────────────────────────────


def test_consume_clears_envelope_and_blocks_replay(client, test_user, db):
    data = _create(client, test_user.id)
    sid = data["session_id"]
    client.post(
        f"/api/v1/transfer/sessions/{sid}/envelope",
        json={"v": 1, "x": "y"},
        headers=_auth(test_user.id),
    )
    r = client.post(f"/api/v1/transfer/sessions/{sid}/consume", headers=_auth(test_user.id))
    assert r.status_code == 200
    assert r.json()["state"] == "consumed"
    assert r.json()["envelope"] is None

    # Subsequent GET still says consumed, no envelope.
    g = client.get(f"/api/v1/transfer/sessions/{sid}", headers=_auth(test_user.id))
    assert g.json()["state"] == "consumed"
    assert g.json()["envelope"] is None

    # Posting an envelope after consume is rejected.
    again = client.post(
        f"/api/v1/transfer/sessions/{sid}/envelope",
        json={"v": 1},
        headers=_auth(test_user.id),
    )
    assert again.status_code == 410

    # DB row should have envelope_json cleared.
    row = db.query(TransferSession).filter(TransferSession.id == uuid.UUID(sid)).first()
    assert row is not None
    assert row.envelope_json is None
    assert row.consumed_at is not None
    _cleanup(db, uuid.UUID(sid))


def test_consume_is_idempotent(client, test_user, db):
    data = _create(client, test_user.id)
    sid = data["session_id"]
    r1 = client.post(f"/api/v1/transfer/sessions/{sid}/consume", headers=_auth(test_user.id))
    r2 = client.post(f"/api/v1/transfer/sessions/{sid}/consume", headers=_auth(test_user.id))
    assert r1.status_code == 200
    assert r2.status_code == 200
    assert r1.json()["state"] == "consumed"
    assert r2.json()["state"] == "consumed"
    _cleanup(db, uuid.UUID(sid))


# ── Expiry ──────────────────────────────────────────────────────────────


def test_expired_session_cannot_receive_envelope(client, test_user, db):
    """Force-expire by editing the row directly (we can't wait through a
    real TTL in a unit test). After expiry, envelope POST returns 410."""
    data = _create(client, test_user.id, ttl_seconds=60)
    sid = uuid.UUID(data["session_id"])

    from datetime import datetime, timedelta, timezone
    row = db.query(TransferSession).filter(TransferSession.id == sid).first()
    row.expires_at = datetime.now(timezone.utc) - timedelta(seconds=10)
    db.commit()

    r = client.post(
        f"/api/v1/transfer/sessions/{sid}/envelope",
        json={"v": 1},
        headers=_auth(test_user.id),
    )
    assert r.status_code == 410

    # GET reports "expired".
    g = client.get(f"/api/v1/transfer/sessions/{sid}", headers=_auth(test_user.id))
    assert g.json()["state"] == "expired"
    assert g.json()["envelope"] is None

    _cleanup(db, sid)


def test_expired_session_consume_returns_410(client, test_user, db):
    data = _create(client, test_user.id, ttl_seconds=60)
    sid = uuid.UUID(data["session_id"])
    from datetime import datetime, timedelta, timezone
    row = db.query(TransferSession).filter(TransferSession.id == sid).first()
    row.expires_at = datetime.now(timezone.utc) - timedelta(seconds=10)
    db.commit()
    r = client.post(f"/api/v1/transfer/sessions/{sid}/consume", headers=_auth(test_user.id))
    assert r.status_code == 410
    _cleanup(db, sid)


# ── 404 / unknown sessions ─────────────────────────────────────────────


def test_unknown_session_returns_404(client, test_user):
    fake = uuid.uuid4()
    r = client.get(f"/api/v1/transfer/sessions/{fake}", headers=_auth(test_user.id))
    assert r.status_code == 404
    r2 = client.post(
        f"/api/v1/transfer/sessions/{fake}/envelope",
        json={"v": 1},
        headers=_auth(test_user.id),
    )
    assert r2.status_code == 404
    r3 = client.post(f"/api/v1/transfer/sessions/{fake}/consume", headers=_auth(test_user.id))
    assert r3.status_code == 404
