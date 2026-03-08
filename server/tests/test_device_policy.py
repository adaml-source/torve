"""
Tests for device governance policy (Phase 2).
Covers device activation, cap enforcement, stale expiry, swap limits,
and combined entitlement + device access resolution.
"""
import pytest
import pytest_asyncio
from datetime import datetime, timedelta, timezone
from httpx import AsyncClient

from .conftest import DEVICE, register_user


def make_device(suffix: str, device_type: str = "phone", platform: str = "google_play_mobile") -> dict:
    return {
        "installation_id": f"test-device-{suffix}",
        "device_name": f"Device {suffix}",
        "device_type": device_type,
        "platform": platform,
    }


async def register_and_get_token(client: AsyncClient, email: str = "test@example.com") -> str:
    data = await register_user(client, email=email)
    return data["tokens"]["access_token"]


async def login_with_device(client: AsyncClient, email: str, password: str, device: dict) -> dict:
    resp = await client.post("/auth/login", json={
        "email": email,
        "password": password,
        "device": device,
    })
    assert resp.status_code == 200
    return resp.json()


async def verify_apple_purchase(client: AsyncClient, token: str, product_id: str = "com.torve.pro.lifetime") -> dict:
    """Helper to create a verified purchase via Apple endpoint."""
    import base64, json as json_mod
    payload = {
        "transactionId": "txn_test_123",
        "originalTransactionId": "txn_test_123",
        "productId": product_id,
        "bundleId": "com.torve.app",
        "purchaseDate": 1700000000000,
    }
    encoded = base64.urlsafe_b64encode(json_mod.dumps(payload).encode()).rstrip(b"=").decode()
    jws = f"header.{encoded}.signature"

    resp = await client.post(
        "/purchases/apple/verify",
        json={"transaction_jws": jws, "product_id": product_id, "platform": "ios"},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 200
    return resp.json()


# ── A. Device Policy Tests ──


@pytest.mark.asyncio
async def test_first_device_activates(client: AsyncClient):
    """First device on a premium account should activate automatically."""
    token = await register_and_get_token(client)
    await verify_apple_purchase(client, token)

    resp = await client.get("/me/access-state", headers={"Authorization": f"Bearer {token}"})
    assert resp.status_code == 200
    data = resp.json()
    assert data["premium"]["has_entitlement"] is True
    assert data["premium"]["premium_access"] is True
    assert data["device"]["is_active"] is True
    assert data["device"]["active_device_count"] == 1


@pytest.mark.asyncio
async def test_activate_up_to_5_devices(client: AsyncClient):
    """Should be able to activate up to 5 devices."""
    # Register user with device 1
    data = await register_user(client, email="multi@test.com")
    token1 = data["tokens"]["access_token"]
    await verify_apple_purchase(client, token1)

    # Log in from 4 more devices
    for i in range(2, 6):
        device = make_device(f"multi-{i}")
        login_data = await login_with_device(client, "multi@test.com", "TestPass123!", device)
        t = login_data["tokens"]["access_token"]
        resp = await client.get("/me/access-state", headers={"Authorization": f"Bearer {t}"})
        assert resp.status_code == 200
        d = resp.json()
        assert d["premium"]["premium_access"] is True
        assert d["device"]["is_active"] is True

    # Verify the 5th device reports count=5
    device5 = make_device("multi-5")
    login5 = await login_with_device(client, "multi@test.com", "TestPass123!", device5)
    resp = await client.get("/me/access-state", headers={"Authorization": f"Bearer {login5['tokens']['access_token']}"})
    assert resp.json()["device"]["active_device_count"] == 5


@pytest.mark.asyncio
async def test_6th_device_denied(client: AsyncClient):
    """6th device should be denied (cap reached)."""
    data = await register_user(client, email="cap@test.com")
    token1 = data["tokens"]["access_token"]
    await verify_apple_purchase(client, token1)

    # Activate devices 2-5
    for i in range(2, 6):
        device = make_device(f"cap-{i}")
        login_data = await login_with_device(client, "cap@test.com", "TestPass123!", device)
        t = login_data["tokens"]["access_token"]
        resp = await client.get("/me/access-state", headers={"Authorization": f"Bearer {t}"})
        assert resp.json()["premium"]["premium_access"] is True

    # 6th device
    device6 = make_device("cap-6")
    login6 = await login_with_device(client, "cap@test.com", "TestPass123!", device6)
    resp = await client.get("/me/access-state", headers={"Authorization": f"Bearer {login6['tokens']['access_token']}"})
    d = resp.json()
    assert d["premium"]["has_entitlement"] is True
    assert d["premium"]["premium_access"] is False
    assert d["premium"]["reason"] == "device_cap_reached"
    assert d["device_limit"]["cap_reached"] is True


@pytest.mark.asyncio
async def test_same_device_recheckin_idempotent(client: AsyncClient):
    """Re-checking in the same device should not consume extra slots."""
    token = await register_and_get_token(client, email="recheck@test.com")
    await verify_apple_purchase(client, token)

    # First check-in
    resp1 = await client.get("/me/access-state", headers={"Authorization": f"Bearer {token}"})
    assert resp1.json()["device"]["active_device_count"] == 1

    # Re-check-in
    resp2 = await client.get("/me/access-state", headers={"Authorization": f"Bearer {token}"})
    assert resp2.json()["device"]["active_device_count"] == 1
    assert resp2.json()["device"]["is_active"] is True


@pytest.mark.asyncio
async def test_remove_device_frees_slot(client: AsyncClient):
    """Removing a device should free a slot immediately."""
    data = await register_user(client, email="remove@test.com")
    token1 = data["tokens"]["access_token"]
    await verify_apple_purchase(client, token1)

    # Fill all 5 slots
    tokens = [token1]
    device_ids = []
    for i in range(2, 6):
        device = make_device(f"rm-{i}")
        login_data = await login_with_device(client, "remove@test.com", "TestPass123!", device)
        t = login_data["tokens"]["access_token"]
        tokens.append(t)
        resp = await client.get("/me/access-state", headers={"Authorization": f"Bearer {t}"})
        device_ids.append(resp.json()["device"]["id"])

    # Try 6th device — blocked
    device6 = make_device("rm-6")
    login6 = await login_with_device(client, "remove@test.com", "TestPass123!", device6)
    t6 = login6["tokens"]["access_token"]
    resp = await client.get("/me/access-state", headers={"Authorization": f"Bearer {t6}"})
    assert resp.json()["premium"]["premium_access"] is False

    # Remove device 2
    resp_remove = await client.post(
        f"/me/devices/{device_ids[0]}/remove",
        headers={"Authorization": f"Bearer {token1}"},
    )
    assert resp_remove.status_code == 200
    assert resp_remove.json()["removed"] is True

    # Now device 6 can activate
    resp2 = await client.get("/me/access-state", headers={"Authorization": f"Bearer {t6}"})
    assert resp2.json()["premium"]["premium_access"] is True
    assert resp2.json()["device"]["is_active"] is True


@pytest.mark.asyncio
async def test_remove_already_removed_device(client: AsyncClient):
    """Removing an already-removed device should be safe."""
    data = await register_user(client, email="double-rm@test.com")
    token = data["tokens"]["access_token"]
    await verify_apple_purchase(client, token)

    # Get device list
    resp = await client.get("/me/devices", headers={"Authorization": f"Bearer {token}"})
    device_id = resp.json()["devices"][0]["id"]

    # Remove once
    resp1 = await client.post(f"/me/devices/{device_id}/remove", headers={"Authorization": f"Bearer {token}"})
    assert resp1.json()["removed"] is True

    # Remove again
    resp2 = await client.post(f"/me/devices/{device_id}/remove", headers={"Authorization": f"Bearer {token}"})
    assert resp2.json()["removed"] is False
    assert resp2.json()["reason"] == "already_removed"


@pytest.mark.asyncio
async def test_swap_limit_enforced(client: AsyncClient):
    """After 3 removals in 30 days, further removals should be blocked."""
    data = await register_user(client, email="swap@test.com")
    token1 = data["tokens"]["access_token"]
    await verify_apple_purchase(client, token1)

    # Create and activate 3 additional devices, then remove them
    for i in range(2, 5):
        device = make_device(f"swap-{i}")
        login_data = await login_with_device(client, "swap@test.com", "TestPass123!", device)
        t = login_data["tokens"]["access_token"]
        # Activate
        resp = await client.get("/me/access-state", headers={"Authorization": f"Bearer {t}"})
        did = resp.json()["device"]["id"]
        # Remove
        resp_rm = await client.post(f"/me/devices/{did}/remove", headers={"Authorization": f"Bearer {token1}"})
        assert resp_rm.json()["removed"] is True

    # 4th removal should be blocked by swap limit
    device5 = make_device("swap-5")
    login5 = await login_with_device(client, "swap@test.com", "TestPass123!", device5)
    t5 = login5["tokens"]["access_token"]
    resp = await client.get("/me/access-state", headers={"Authorization": f"Bearer {t5}"})
    did5 = resp.json()["device"]["id"]
    resp_rm4 = await client.post(f"/me/devices/{did5}/remove", headers={"Authorization": f"Bearer {token1}"})
    assert resp_rm4.json()["removed"] is False
    assert resp_rm4.json()["reason"] == "swap_limit_reached"
    assert resp_rm4.json()["swaps_remaining"] == 0


# ── B. Entitlement + Device Combined Tests ──


@pytest.mark.asyncio
async def test_premium_owned_but_device_blocked(client: AsyncClient):
    """User owns premium but current device is blocked by cap."""
    data = await register_user(client, email="blocked@test.com")
    token1 = data["tokens"]["access_token"]
    await verify_apple_purchase(client, token1)

    # Fill 5 slots (device 1 is already activated)
    for i in range(2, 6):
        device = make_device(f"blk-{i}")
        login_data = await login_with_device(client, "blocked@test.com", "TestPass123!", device)
        t = login_data["tokens"]["access_token"]
        await client.get("/me/access-state", headers={"Authorization": f"Bearer {t}"})

    # 6th device: owns premium but blocked
    device6 = make_device("blk-6")
    login6 = await login_with_device(client, "blocked@test.com", "TestPass123!", device6)
    t6 = login6["tokens"]["access_token"]
    resp = await client.get("/me/access-state", headers={"Authorization": f"Bearer {t6}"})
    d = resp.json()
    assert d["premium"]["has_entitlement"] is True
    assert d["premium"]["premium_access"] is False
    assert d["premium"]["reason"] == "device_cap_reached"


@pytest.mark.asyncio
async def test_no_entitlement_no_access(client: AsyncClient):
    """User without premium should get no access regardless of device."""
    token = await register_and_get_token(client, email="free@test.com")
    resp = await client.get("/me/access-state", headers={"Authorization": f"Bearer {token}"})
    d = resp.json()
    assert d["premium"]["has_entitlement"] is False
    assert d["premium"]["premium_access"] is False
    assert d["premium"]["reason"] == "no_entitlement"


@pytest.mark.asyncio
async def test_restore_on_second_device_under_cap(client: AsyncClient):
    """Restoring on a second device under cap should succeed."""
    data = await register_user(client, email="restore@test.com")
    token1 = data["tokens"]["access_token"]
    await verify_apple_purchase(client, token1)

    # Second device
    device2 = make_device("restore-2")
    login2 = await login_with_device(client, "restore@test.com", "TestPass123!", device2)
    t2 = login2["tokens"]["access_token"]

    # Restore
    resp = await client.post(
        "/purchases/restore",
        json={"store": "apple", "platform": "ios"},
        headers={"Authorization": f"Bearer {t2}"},
    )
    assert resp.status_code == 200
    assert resp.json()["premium_access"] is True  # entitlement exists

    # Access state should be active
    resp2 = await client.get("/me/access-state", headers={"Authorization": f"Bearer {t2}"})
    assert resp2.json()["premium"]["premium_access"] is True


# ── C. API Tests ──


@pytest.mark.asyncio
async def test_get_access_state_returns_device_aware(client: AsyncClient):
    """GET /me/access-state returns full device-aware state."""
    token = await register_and_get_token(client, email="api1@test.com")
    await verify_apple_purchase(client, token)

    resp = await client.get("/me/access-state", headers={"Authorization": f"Bearer {token}"})
    assert resp.status_code == 200
    data = resp.json()
    assert "user" in data
    assert "premium" in data
    assert "device" in data
    assert "device_limit" in data
    assert data["premium"]["has_entitlement"] is True
    assert data["device"]["max_active_devices"] == 5


@pytest.mark.asyncio
async def test_get_devices_returns_current_flag(client: AsyncClient):
    """GET /me/devices marks the current device."""
    token = await register_and_get_token(client, email="devlist@test.com")

    resp = await client.get("/me/devices", headers={"Authorization": f"Bearer {token}"})
    assert resp.status_code == 200
    data = resp.json()
    assert len(data["devices"]) >= 1
    current_devices = [d for d in data["devices"] if d["is_current"]]
    assert len(current_devices) == 1


@pytest.mark.asyncio
async def test_activate_current_idempotent(client: AsyncClient):
    """POST /me/devices/activate-current is idempotent."""
    token = await register_and_get_token(client, email="activ@test.com")
    await verify_apple_purchase(client, token)

    # Activate via access-state
    await client.get("/me/access-state", headers={"Authorization": f"Bearer {token}"})

    # Explicit activate should also succeed
    resp = await client.post("/me/devices/activate-current", headers={"Authorization": f"Bearer {token}"})
    assert resp.status_code == 200
    assert resp.json()["activated"] is True
    assert resp.json()["active_device_count"] == 1


@pytest.mark.asyncio
async def test_rename_device(client: AsyncClient):
    """PATCH /me/devices/{id} renames safely."""
    token = await register_and_get_token(client, email="rename@test.com")

    resp = await client.get("/me/devices", headers={"Authorization": f"Bearer {token}"})
    device_id = resp.json()["devices"][0]["id"]

    resp2 = await client.patch(
        f"/me/devices/{device_id}",
        json={"device_name": "My Cool Phone"},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp2.status_code == 200
    assert resp2.json()["device_name"] == "My Cool Phone"


@pytest.mark.asyncio
async def test_remove_other_users_device_fails(client: AsyncClient):
    """Cannot remove another user's device."""
    # Use unique devices so registration doesn't reassign the shared default
    device1 = make_device("crossuser-1")
    resp1 = await client.post("/auth/register", json={
        "email": "user1@test.com",
        "password": "TestPass123!",
        "device": device1,
    })
    assert resp1.status_code == 200
    token1 = resp1.json()["tokens"]["access_token"]

    device2 = make_device("crossuser-2")
    resp2_reg = await client.post("/auth/register", json={
        "email": "user2@test.com",
        "password": "TestPass123!",
        "device": device2,
    })
    assert resp2_reg.status_code == 200
    token2 = resp2_reg.json()["tokens"]["access_token"]

    # Get user1's device
    resp = await client.get("/me/devices", headers={"Authorization": f"Bearer {token1}"})
    assert len(resp.json()["devices"]) >= 1
    user1_device_id = resp.json()["devices"][0]["id"]

    # user2 tries to remove user1's device
    resp2 = await client.post(
        f"/me/devices/{user1_device_id}/remove",
        headers={"Authorization": f"Bearer {token2}"},
    )
    assert resp2.status_code == 404


@pytest.mark.asyncio
async def test_purchase_on_full_account_grants_ownership_not_device(client: AsyncClient):
    """Purchase on an account at cap grants entitlement but not device access on 6th device."""
    data = await register_user(client, email="fullbuy@test.com")
    token1 = data["tokens"]["access_token"]

    # Fill 5 devices first (without premium — they'll register but not activate)
    tokens_devices = [token1]
    for i in range(2, 6):
        device = make_device(f"fb-{i}")
        login_data = await login_with_device(client, "fullbuy@test.com", "TestPass123!", device)
        tokens_devices.append(login_data["tokens"]["access_token"])

    # Now buy premium from device 1 — all existing devices will be under cap
    await verify_apple_purchase(client, token1)

    # Device 1 gets activated
    resp1 = await client.get("/me/access-state", headers={"Authorization": f"Bearer {token1}"})
    assert resp1.json()["premium"]["premium_access"] is True

    # Activate devices 2-5
    for t in tokens_devices[1:]:
        resp = await client.get("/me/access-state", headers={"Authorization": f"Bearer {t}"})
        assert resp.json()["premium"]["premium_access"] is True

    # 6th device logs in and tries to buy — purchase verifies but device blocked
    device6 = make_device("fb-6")
    login6 = await login_with_device(client, "fullbuy@test.com", "TestPass123!", device6)
    t6 = login6["tokens"]["access_token"]
    resp6 = await client.get("/me/access-state", headers={"Authorization": f"Bearer {t6}"})
    assert resp6.json()["premium"]["has_entitlement"] is True
    assert resp6.json()["premium"]["premium_access"] is False
