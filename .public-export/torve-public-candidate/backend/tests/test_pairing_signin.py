"""Tests for the anonymous TV-QR sign-in flow at /pairing/signin/*.

Covers the exact contract the Android TV / iOS clients are pinned to:
  - POST /code is anonymous, returns {code, expires_at}
  - POST /status is anonymous, validates installation_id binding
  - POST /claim requires a Torve JWT, mints fresh access+refresh for the TV
  - first claimed-poll returns tokens; subsequent polls return "expired"
  - mismatched installation_id never leaks tokens (404)

Also pins that the existing authenticated /pairing/code remote-control
flow remains untouched and still 403s without auth — the two flows
share nothing.
"""
from __future__ import annotations

import uuid as _uuid
from datetime import datetime, timedelta, timezone

from app.crypto import decrypt_secret
from app.models import Device, PairingSigninCode, User
from app.security import create_access_token, hash_password


def _auth(user_id) -> dict:
    return {"Authorization": f"Bearer {create_access_token(str(user_id))}"}


def _make_user(db) -> User:
    """Fresh user with no devices, no premium — a typical TV-pairing
    scenario from the user's perspective is the receiver-phone signing
    in for the first time. Cap-checking only fires for premium users."""
    user = User(
        email=f"pairing-signin-{_uuid.uuid4().hex[:8]}@test.com",
        password_hash=hash_password("testpass"),
        is_verified=True,
    )
    db.add(user)
    db.commit()
    db.refresh(user)
    return user


def _cleanup_user(db, user_id):
    db.query(Device).filter(Device.user_id == user_id).delete()
    db.query(User).filter(User.id == user_id).delete()
    db.commit()


def _cleanup_code(db, code):
    db.query(PairingSigninCode).filter(PairingSigninCode.code == code).delete()
    db.commit()


# ── /code ──────────────────────────────────────────────────────────────


def test_create_code_is_anonymous_and_returns_code(client, db):
    """No Authorization header — must succeed."""
    r = client.post(
        "/pairing/signin/code",
        json={
            "installation_id": "tv-anon-1",
            "device_name": "Living Room TV",
            "device_type": "tv",
            "platform": "google_play_tv",
        },
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert isinstance(body["code"], str) and len(body["code"]) == 6
    assert body["code"].isupper() or any(c.isdigit() for c in body["code"])
    assert "expires_at" in body
    _cleanup_code(db, body["code"])


def test_create_code_rejects_invalid_device_type(client):
    r = client.post(
        "/pairing/signin/code",
        json={
            "installation_id": "tv-bad-type",
            "device_name": "X",
            "device_type": "smart_fridge",
            "platform": "x",
        },
    )
    assert r.status_code == 422


# ── /status — pending and not-found paths ─────────────────────────────


def test_status_pending_for_fresh_code(client, db):
    code_resp = client.post(
        "/pairing/signin/code",
        json={"installation_id": "tv-pending-1", "device_type": "tv", "device_name": "TV1"},
    ).json()
    r = client.post(
        "/pairing/signin/status",
        json={"code": code_resp["code"], "installation_id": "tv-pending-1"},
    )
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "pending"
    assert body["tokens"] is None
    _cleanup_code(db, code_resp["code"])


def test_status_404_for_unknown_code(client):
    r = client.post(
        "/pairing/signin/status",
        json={"code": "ZZZZZZ", "installation_id": "tv-x"},
    )
    assert r.status_code == 404


def test_status_404_for_mismatched_installation_id(client, db):
    """Critical security property: a different device polling with the
    same code MUST get 404 — never tokens, never even existence-leak."""
    code_resp = client.post(
        "/pairing/signin/code",
        json={"installation_id": "tv-owner", "device_type": "tv", "device_name": "TV1"},
    ).json()
    r = client.post(
        "/pairing/signin/status",
        json={"code": code_resp["code"], "installation_id": "tv-stranger"},
    )
    assert r.status_code == 404
    body_str = r.text
    # No token-shaped strings in the error response.
    assert "access_token" not in body_str
    assert "refresh_token" not in body_str
    _cleanup_code(db, code_resp["code"])


def test_status_expired_after_ttl(client, db):
    code_resp = client.post(
        "/pairing/signin/code",
        json={"installation_id": "tv-expiry", "device_type": "tv", "device_name": "TV1"},
    ).json()
    code = code_resp["code"]
    # Force-expire by editing the row.
    row = db.query(PairingSigninCode).filter(PairingSigninCode.code == code).first()
    row.expires_at = datetime.now(timezone.utc) - timedelta(minutes=1)
    db.commit()

    r = client.post(
        "/pairing/signin/status",
        json={"code": code, "installation_id": "tv-expiry"},
    )
    assert r.status_code == 200
    assert r.json()["status"] == "expired"
    _cleanup_code(db, code)


# ── /claim — auth + state transitions ──────────────────────────────────


def test_claim_requires_auth(client, db):
    code_resp = client.post(
        "/pairing/signin/code",
        json={"installation_id": "tv-claim-noauth", "device_type": "tv", "device_name": "TV1"},
    ).json()
    r = client.post("/pairing/signin/claim", json={"code": code_resp["code"]})
    assert r.status_code in (401, 403)
    _cleanup_code(db, code_resp["code"])


def test_claim_unknown_code_returns_404(client, db):
    user = _make_user(db)
    r = client.post(
        "/pairing/signin/claim",
        headers=_auth(user.id),
        json={"code": "NOPE99"},
    )
    assert r.status_code == 404
    _cleanup_user(db, user.id)


def test_claim_already_claimed_returns_410(client, db):
    user = _make_user(db)
    code_resp = client.post(
        "/pairing/signin/code",
        json={"installation_id": "tv-claim-twice", "device_type": "tv", "device_name": "TV1"},
    ).json()
    code = code_resp["code"]
    r1 = client.post("/pairing/signin/claim", headers=_auth(user.id), json={"code": code})
    assert r1.status_code == 200, r1.text
    r2 = client.post("/pairing/signin/claim", headers=_auth(user.id), json={"code": code})
    assert r2.status_code == 410
    _cleanup_code(db, code)
    _cleanup_user(db, user.id)


def test_claim_expired_returns_410(client, db):
    user = _make_user(db)
    code_resp = client.post(
        "/pairing/signin/code",
        json={"installation_id": "tv-claim-expired", "device_type": "tv", "device_name": "TV1"},
    ).json()
    code = code_resp["code"]
    row = db.query(PairingSigninCode).filter(PairingSigninCode.code == code).first()
    row.expires_at = datetime.now(timezone.utc) - timedelta(seconds=10)
    db.commit()

    r = client.post("/pairing/signin/claim", headers=_auth(user.id), json={"code": code})
    assert r.status_code == 410
    _cleanup_code(db, code)
    _cleanup_user(db, user.id)


# ── End-to-end: pending → claim → claimed-with-tokens → expired ───────


def test_full_signin_round_trip(client, db):
    """The flow that powers TV sign-in:
       1. TV creates code anonymously
       2. TV polls — pending
       3. Phone (signed-in user) claims with bearer
       4. TV polls — claimed + fresh tokens (first time only)
       5. TV polls again — expired (single-shot delivery enforced)"""
    user = _make_user(db)
    install = "tv-roundtrip-1"

    # 1. Create code
    code_resp = client.post(
        "/pairing/signin/code",
        json={
            "installation_id": install,
            "device_name": "Bedroom TV",
            "device_type": "tv",
            "platform": "google_play_tv",
        },
    ).json()
    code = code_resp["code"]

    # 2. Pending
    r = client.post(
        "/pairing/signin/status",
        json={"code": code, "installation_id": install},
    )
    assert r.json()["status"] == "pending"
    assert r.json()["tokens"] is None

    # 3. Claim
    claim = client.post(
        "/pairing/signin/claim",
        headers=_auth(user.id),
        json={"code": code},
    )
    assert claim.status_code == 200, claim.text
    assert claim.json()["status"] == "claimed"
    assert "id" in claim.json()["paired_device"]

    # 4. First claimed-poll: tokens delivered
    delivered = client.post(
        "/pairing/signin/status",
        json={"code": code, "installation_id": install},
    )
    assert delivered.status_code == 200
    body = delivered.json()
    assert body["status"] == "claimed"
    assert body["tokens"] is not None
    tokens = body["tokens"]
    assert tokens["token_type"] == "bearer"
    assert isinstance(tokens["access_token"], str) and len(tokens["access_token"]) > 20
    assert isinstance(tokens["refresh_token"], str) and len(tokens["refresh_token"]) > 20
    assert isinstance(tokens["expires_in"], int) and tokens["expires_in"] > 0
    assert body["user"]["id"] == str(user.id)
    assert body["user"]["email"] == user.email

    # The freshly-minted access token must actually authenticate as the user.
    me = client.get("/me", headers={"Authorization": f"Bearer {tokens['access_token']}"})
    assert me.status_code == 200
    assert me.json()["email"] == user.email

    # 5. Second poll: expired (consumed_at stamped, ciphertext cleared).
    again = client.post(
        "/pairing/signin/status",
        json={"code": code, "installation_id": install},
    )
    assert again.status_code == 200
    again_body = again.json()
    assert again_body["status"] == "expired"
    assert again_body["tokens"] is None

    # Verify the row state directly: encrypted blobs cleared, consumed
    # set, access token was indeed encrypted at rest before delivery.
    row = db.query(PairingSigninCode).filter(PairingSigninCode.code == code).first()
    assert row is not None
    assert row.consumed_at is not None
    assert row.access_token_encrypted is None
    assert row.refresh_token_encrypted is None
    assert row.claimed_device_id is not None

    _cleanup_code(db, code)
    _cleanup_user(db, user.id)


def test_tokens_are_encrypted_at_rest_between_claim_and_poll(client, db):
    """Pin that we don't store plaintext access/refresh tokens on disk.
    Between claim and the first status poll, the row carries ciphertext;
    only decrypt on the way out the door."""
    user = _make_user(db)
    install = "tv-encrypted-rest"
    code_resp = client.post(
        "/pairing/signin/code",
        json={"installation_id": install, "device_type": "tv", "device_name": "TV"},
    ).json()
    code = code_resp["code"]
    client.post("/pairing/signin/claim", headers=_auth(user.id), json={"code": code})

    row = db.query(PairingSigninCode).filter(PairingSigninCode.code == code).first()
    assert row.access_token_encrypted is not None
    assert row.refresh_token_encrypted is not None
    # Not literally a JWT in the column.
    assert "." not in (row.access_token_encrypted[:50] or "")
    # Round-trip via decrypt_secret yields a usable JWT.
    plaintext_access = decrypt_secret(row.access_token_encrypted)
    assert plaintext_access.count(".") == 2  # JWT shape: header.payload.sig

    _cleanup_code(db, code)
    _cleanup_user(db, user.id)


# ── Existing /pairing/code MUST be untouched ───────────────────────────


def test_legacy_pairing_code_still_requires_auth(client):
    """Sanity: don't break the existing authenticated remote-control
    pairing flow. Without auth it should still 401/403, exactly as
    before this change."""
    r = client.post(
        "/pairing/code",
        json={"device_id": str(_uuid.uuid4())},
    )
    assert r.status_code in (401, 403)
