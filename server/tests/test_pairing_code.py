"""Tests for code-based pairing flow (POST /pairing/code, POST /pairing/claim)."""
import uuid
from datetime import datetime, timedelta, timezone

from app.models import DevicePairing, PairingCode


# ── POST /pairing/code ──────────────────────────────────────────────────────


def test_create_pairing_code(client, auth_header, tv_device):
    r = client.post(
        "/pairing/code",
        json={"device_id": str(tv_device.id)},
        headers=auth_header,
    )
    assert r.status_code == 201
    data = r.json()
    assert len(data["code"]) == 6
    assert data["target_device_id"] == str(tv_device.id)
    assert "expires_at" in data


def test_create_pairing_code_unauthenticated(client, tv_device):
    r = client.post("/pairing/code", json={"device_id": str(tv_device.id)})
    assert r.status_code == 403


def test_create_pairing_code_wrong_device(client, auth_header):
    r = client.post(
        "/pairing/code",
        json={"device_id": str(uuid.uuid4())},
        headers=auth_header,
    )
    assert r.status_code == 404


def test_create_pairing_code_invalidates_previous(client, auth_header, tv_device, db):
    # Create first code
    r1 = client.post(
        "/pairing/code",
        json={"device_id": str(tv_device.id)},
        headers=auth_header,
    )
    code1 = r1.json()["code"]

    # Create second code for same device
    r2 = client.post(
        "/pairing/code",
        json={"device_id": str(tv_device.id)},
        headers=auth_header,
    )
    code2 = r2.json()["code"]
    assert code1 != code2

    # First code should now be expired
    now = datetime.now(timezone.utc)
    old = (
        db.query(PairingCode)
        .filter(PairingCode.code == code1)
        .first()
    )
    assert old.expires_at <= now


# ── POST /pairing/claim ─────────────────────────────────────────────────────


def test_claim_pairing_code(client, auth_header, tv_device, phone_device, db):
    # TV generates code
    r = client.post(
        "/pairing/code",
        json={"device_id": str(tv_device.id)},
        headers=auth_header,
    )
    code = r.json()["code"]

    # Phone claims code
    r2 = client.post(
        "/pairing/claim",
        json={"code": code, "device_id": str(phone_device.id)},
        headers=auth_header,
    )
    assert r2.status_code == 200
    data = r2.json()
    assert data["controller_device_id"] == str(phone_device.id)
    assert data["target_device_id"] == str(tv_device.id)
    assert data["status"] == "active"

    # Code should be marked as claimed
    pc = db.query(PairingCode).filter(PairingCode.code == code).first()
    assert pc.claimed_at is not None
    assert pc.claimed_by_device_id == phone_device.id


def test_claim_pairing_code_unauthenticated(client, tv_device, auth_header):
    r = client.post(
        "/pairing/code",
        json={"device_id": str(tv_device.id)},
        headers=auth_header,
    )
    code = r.json()["code"]

    r2 = client.post(
        "/pairing/claim",
        json={"code": code, "device_id": str(uuid.uuid4())},
    )
    assert r2.status_code == 403


def test_claim_expired_code(client, auth_header, tv_device, phone_device, db):
    # Create code and manually expire it
    r = client.post(
        "/pairing/code",
        json={"device_id": str(tv_device.id)},
        headers=auth_header,
    )
    code = r.json()["code"]

    pc = db.query(PairingCode).filter(PairingCode.code == code).first()
    pc.expires_at = datetime.now(timezone.utc) - timedelta(minutes=1)
    db.commit()

    r2 = client.post(
        "/pairing/claim",
        json={"code": code, "device_id": str(phone_device.id)},
        headers=auth_header,
    )
    assert r2.status_code == 404


def test_claim_already_claimed_code(client, auth_header, tv_device, phone_device, tv_device_2, db):
    # Generate code and claim it
    r = client.post(
        "/pairing/code",
        json={"device_id": str(tv_device.id)},
        headers=auth_header,
    )
    code = r.json()["code"]

    client.post(
        "/pairing/claim",
        json={"code": code, "device_id": str(phone_device.id)},
        headers=auth_header,
    )

    # Second phone tries same code -> 404 (already claimed)
    r3 = client.post(
        "/pairing/claim",
        json={"code": code, "device_id": str(tv_device_2.id)},
        headers=auth_header,
    )
    assert r3.status_code == 404


def test_claim_cross_user_rejected(
    client, auth_header, auth_header_b, tv_device, phone_device_b
):
    # User A generates code
    r = client.post(
        "/pairing/code",
        json={"device_id": str(tv_device.id)},
        headers=auth_header,
    )
    code = r.json()["code"]

    # User B tries to claim -> 404 (user mismatch, no info leak)
    r2 = client.post(
        "/pairing/claim",
        json={"code": code, "device_id": str(phone_device_b.id)},
        headers=auth_header_b,
    )
    assert r2.status_code == 404


def test_self_pairing_rejected(client, auth_header, tv_device):
    r = client.post(
        "/pairing/code",
        json={"device_id": str(tv_device.id)},
        headers=auth_header,
    )
    code = r.json()["code"]

    r2 = client.post(
        "/pairing/claim",
        json={"code": code, "device_id": str(tv_device.id)},
        headers=auth_header,
    )
    assert r2.status_code == 400
    assert "itself" in r2.json()["detail"]


def test_duplicate_pairing_returns_existing(
    client, auth_header, tv_device, phone_device, db
):
    # Create first pairing via code
    r = client.post(
        "/pairing/code",
        json={"device_id": str(tv_device.id)},
        headers=auth_header,
    )
    code1 = r.json()["code"]
    r2 = client.post(
        "/pairing/claim",
        json={"code": code1, "device_id": str(phone_device.id)},
        headers=auth_header,
    )
    pairing_id_1 = r2.json()["id"]

    # Generate new code for same TV and claim with same phone
    r3 = client.post(
        "/pairing/code",
        json={"device_id": str(tv_device.id)},
        headers=auth_header,
    )
    code2 = r3.json()["code"]
    r4 = client.post(
        "/pairing/claim",
        json={"code": code2, "device_id": str(phone_device.id)},
        headers=auth_header,
    )
    assert r4.status_code == 200
    assert r4.json()["id"] == pairing_id_1  # Same pairing returned, no duplicate


def test_one_phone_two_tvs(client, auth_header, tv_device, tv_device_2, phone_device):
    # TV 1 code
    r1 = client.post(
        "/pairing/code",
        json={"device_id": str(tv_device.id)},
        headers=auth_header,
    )
    client.post(
        "/pairing/claim",
        json={"code": r1.json()["code"], "device_id": str(phone_device.id)},
        headers=auth_header,
    )

    # TV 2 code
    r2 = client.post(
        "/pairing/code",
        json={"device_id": str(tv_device_2.id)},
        headers=auth_header,
    )
    r3 = client.post(
        "/pairing/claim",
        json={"code": r2.json()["code"], "device_id": str(phone_device.id)},
        headers=auth_header,
    )
    assert r3.status_code == 200
    assert r3.json()["target_device_id"] == str(tv_device_2.id)


# ── GET /me/pairings and revoke ─────────────────────────────────────────────


def test_list_pairings_shows_code_created_pairing(
    client, auth_header, tv_device, phone_device
):
    r = client.post(
        "/pairing/code",
        json={"device_id": str(tv_device.id)},
        headers=auth_header,
    )
    client.post(
        "/pairing/claim",
        json={"code": r.json()["code"], "device_id": str(phone_device.id)},
        headers=auth_header,
    )

    r2 = client.get("/me/pairings", headers=auth_header)
    assert r2.status_code == 200
    pairings = r2.json()
    matching = [p for p in pairings if p["target_device_id"] == str(tv_device.id)]
    assert len(matching) >= 1


def test_revoke_code_created_pairing(
    client, auth_header, tv_device, phone_device
):
    r = client.post(
        "/pairing/code",
        json={"device_id": str(tv_device.id)},
        headers=auth_header,
    )
    r2 = client.post(
        "/pairing/claim",
        json={"code": r.json()["code"], "device_id": str(phone_device.id)},
        headers=auth_header,
    )
    pairing_id = r2.json()["id"]

    r3 = client.post(
        f"/me/pairings/{pairing_id}/revoke",
        headers=auth_header,
    )
    assert r3.status_code == 200
    assert r3.json()["status"] == "revoked"


def test_code_normalizes_lowercase_and_spaces(
    client, auth_header, tv_device, phone_device
):
    r = client.post(
        "/pairing/code",
        json={"device_id": str(tv_device.id)},
        headers=auth_header,
    )
    code = r.json()["code"]

    # Send as lowercase with spaces
    r2 = client.post(
        "/pairing/claim",
        json={"code": f" {code.lower()} ", "device_id": str(phone_device.id)},
        headers=auth_header,
    )
    assert r2.status_code == 200
