"""Tests for pairing endpoints: /pairing/code, /pairing/claim, /me/pairings, /me/pairings/{id}/revoke."""
import pytest
from httpx import AsyncClient

from .conftest import register_user


def make_device(suffix: str, device_type: str = "phone", platform: str = "android") -> dict:
    return {
        "installation_id": f"pair-test-{suffix}",
        "device_name": f"Pair Test {suffix}",
        "device_type": device_type,
        "platform": platform,
    }


@pytest.mark.asyncio
async def test_pairing_flow(client: AsyncClient):
    """Full pairing: TV generates code, phone claims, TV polls, list shows pairing."""
    # Step 1: TV generates pairing code (unauthenticated)
    tv_device = make_device("tv-001", device_type="tv", platform="android_tv")
    resp = await client.post("/pairing/code", json=tv_device)
    assert resp.status_code == 200
    code = resp.json()["code"]
    assert len(code) == 6

    # Step 2: Phone registers and claims code
    phone_device = make_device("phone-001")
    reg = await client.post("/auth/register", json={
        "email": "pairtest@example.com",
        "password": "TestPass123!",
        "device": phone_device,
    })
    phone_token = reg.json()["tokens"]["access_token"]

    resp = await client.post("/pairing/claim", json={"code": code},
                            headers={"Authorization": f"Bearer {phone_token}"})
    assert resp.status_code == 200
    assert resp.json()["status"] == "claimed"

    # Step 3: Phone lists pairings — should see TV
    resp = await client.get("/me/pairings", headers={"Authorization": f"Bearer {phone_token}"})
    assert resp.status_code == 200
    pairings = resp.json()["pairings"]
    assert len(pairings) >= 1
    tv_pairing = next(p for p in pairings if p["device_type"] == "tv")
    assert tv_pairing["pairing_state"] == "paired"
    assert tv_pairing["device_name"] == "Pair Test tv-001"

    # Step 4: TV polls and gets tokens
    resp = await client.post("/pairing/status", json={
        "code": code,
        "installation_id": "pair-test-tv-001",
    })
    assert resp.status_code == 200
    assert resp.json()["status"] == "claimed"
    assert resp.json()["tokens"] is not None


@pytest.mark.asyncio
async def test_multiple_pairings(client: AsyncClient):
    """One phone paired with multiple TVs."""
    phone_device = make_device("multi-phone")
    reg = await client.post("/auth/register", json={
        "email": "multi@example.com",
        "password": "TestPass123!",
        "device": phone_device,
    })
    phone_token = reg.json()["tokens"]["access_token"]

    for i in range(3):
        tv = make_device(f"multi-tv-{i}", device_type="tv", platform="android_tv")
        resp = await client.post("/pairing/code", json=tv)
        code = resp.json()["code"]
        await client.post("/pairing/claim", json={"code": code},
                         headers={"Authorization": f"Bearer {phone_token}"})

    resp = await client.get("/me/pairings", headers={"Authorization": f"Bearer {phone_token}"})
    pairings = resp.json()["pairings"]
    tv_pairings = [p for p in pairings if p["device_type"] == "tv"]
    assert len(tv_pairings) == 3


@pytest.mark.asyncio
async def test_revoke_pairing(client: AsyncClient):
    """Revoking a pairing marks it as revoked."""
    phone_device = make_device("revoke-phone")
    reg = await client.post("/auth/register", json={
        "email": "revoke@example.com",
        "password": "TestPass123!",
        "device": phone_device,
    })
    phone_token = reg.json()["tokens"]["access_token"]

    tv = make_device("revoke-tv", device_type="tv", platform="android_tv")
    resp = await client.post("/pairing/code", json=tv)
    code = resp.json()["code"]
    await client.post("/pairing/claim", json={"code": code},
                     headers={"Authorization": f"Bearer {phone_token}"})

    # Get pairing ID
    resp = await client.get("/me/pairings", headers={"Authorization": f"Bearer {phone_token}"})
    tv_pairing = next(p for p in resp.json()["pairings"] if p["device_type"] == "tv")
    pairing_id = tv_pairing["pairing_id"]

    # Revoke
    resp = await client.post(f"/me/pairings/{pairing_id}/revoke",
                            headers={"Authorization": f"Bearer {phone_token}"})
    assert resp.status_code == 200
    assert resp.json()["status"] == "ok"

    # List pairings — should show as revoked
    resp = await client.get("/me/pairings", headers={"Authorization": f"Bearer {phone_token}"})
    tv_pairing = next(p for p in resp.json()["pairings"] if p["device_type"] == "tv")
    assert tv_pairing["pairing_state"] == "revoked"


@pytest.mark.asyncio
async def test_cross_user_revoke_denied(client: AsyncClient):
    """Cannot revoke another user's pairing."""
    dev1 = make_device("cross-phone-1")
    reg1 = await client.post("/auth/register", json={
        "email": "cross1@example.com",
        "password": "TestPass123!",
        "device": dev1,
    })
    token1 = reg1.json()["tokens"]["access_token"]

    dev2 = make_device("cross-phone-2")
    reg2 = await client.post("/auth/register", json={
        "email": "cross2@example.com",
        "password": "TestPass123!",
        "device": dev2,
    })
    token2 = reg2.json()["tokens"]["access_token"]

    tv = make_device("cross-tv", device_type="tv", platform="android_tv")
    resp = await client.post("/pairing/code", json=tv)
    code = resp.json()["code"]
    await client.post("/pairing/claim", json={"code": code},
                     headers={"Authorization": f"Bearer {token1}"})

    resp = await client.get("/me/pairings", headers={"Authorization": f"Bearer {token1}"})
    pairing_id = resp.json()["pairings"][0]["pairing_id"]

    # User2 tries to revoke user1's pairing
    resp = await client.post(f"/me/pairings/{pairing_id}/revoke",
                            headers={"Authorization": f"Bearer {token2}"})
    assert resp.status_code == 404
