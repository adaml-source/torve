"""
Live validation: 5-device-limit enforcement and revoke-then-retry recovery UX.

Tests the complete lifecycle:
1. Fill to 5 active devices
2. 6th device rejection
3. Revoke one active device
4. Retry 6th device after revoke
5. Revoked-device behavior
"""
import pytest
from httpx import AsyncClient

from .conftest import register_user


def make_device(suffix: str, device_type: str = "phone", platform: str = "android") -> dict:
    return {
        "installation_id": f"validation-{suffix}",
        "device_name": f"Validation Device {suffix}",
        "device_type": device_type,
        "platform": platform,
    }


async def login_with_device(client: AsyncClient, email: str, password: str, device: dict) -> dict:
    resp = await client.post("/auth/login", json={
        "email": email,
        "password": password,
        "device": device,
    })
    assert resp.status_code == 200, f"Login failed: {resp.status_code} {resp.text}"
    return resp.json()


async def verify_apple_purchase(client: AsyncClient, token: str) -> dict:
    import base64, json as json_mod
    payload = {
        "transactionId": "txn_validation_001",
        "originalTransactionId": "txn_validation_001",
        "productId": "com.torve.pro.lifetime",
        "bundleId": "com.torve.app",
        "purchaseDate": 1700000000000,
    }
    encoded = base64.urlsafe_b64encode(json_mod.dumps(payload).encode()).rstrip(b"=").decode()
    jws = f"header.{encoded}.signature"
    resp = await client.post(
        "/purchases/apple/verify",
        json={"transaction_jws": jws, "product_id": "com.torve.pro.lifetime", "platform": "ios"},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 200, f"Purchase verify failed: {resp.status_code} {resp.text}"
    return resp.json()


# ═══════════════════════════════════════════════════════════════
# SCENARIO 1-5: Full lifecycle validation
# ═══════════════════════════════════════════════════════════════


@pytest.mark.asyncio
async def test_full_device_limit_lifecycle(client: AsyncClient):
    """
    Complete lifecycle validation:
    1. Fill to 5 active devices
    2. 6th device rejected
    3. Revoke one device
    4. Retry 6th device succeeds
    5. Revoked device behavior
    """
    EMAIL = "lifecycle@example.com"
    PASSWORD = "TestPass123!"

    # ── SETUP: Register user with device 1 and grant lifetime access ──
    device1 = make_device("dev-001", device_type="phone", platform="android")
    reg_resp = await client.post("/auth/register", json={
        "email": EMAIL,
        "password": PASSWORD,
        "device": device1,
    })
    assert reg_resp.status_code == 200, f"Register failed: {reg_resp.status_code} {reg_resp.text}"
    token1 = reg_resp.json()["tokens"]["access_token"]
    await verify_apple_purchase(client, token1)

    # Verify device 1 is activated
    resp = await client.get("/me/access-state", headers={"Authorization": f"Bearer {token1}"})
    assert resp.status_code == 200
    state1 = resp.json()
    print(f"\n=== SCENARIO 1: Fill to 5 devices ===")
    print(f"  Device 1 active: {state1['device']['is_active']}")
    print(f"  Device 1 count: {state1['device']['active_device_count']}/{state1['device']['max_active_devices']}")
    assert state1["device"]["is_active"] is True
    assert state1["device"]["active_device_count"] == 1
    assert state1["device"]["max_active_devices"] == 5

    # ── SCENARIO 1: Fill to 5 active devices ──
    tokens = {1: token1}
    device_ids = {}

    # Get device 1's ID
    devices_resp = await client.get("/me/devices", headers={"Authorization": f"Bearer {token1}"})
    assert devices_resp.status_code == 200
    device_ids[1] = devices_resp.json()["devices"][0]["id"]

    for i in range(2, 6):
        device_i = make_device(f"dev-00{i}", device_type="phone" if i <= 4 else "tv", platform="android" if i <= 4 else "android_tv")
        login_data = await login_with_device(client, EMAIL, PASSWORD, device_i)
        tokens[i] = login_data["tokens"]["access_token"]

        # Activate by checking access state
        resp = await client.get("/me/access-state", headers={"Authorization": f"Bearer {tokens[i]}"})
        assert resp.status_code == 200
        state_i = resp.json()
        device_ids[i] = state_i["device"]["id"]

        print(f"  Device {i} active: {state_i['device']['is_active']}, count: {state_i['device']['active_device_count']}/{state_i['device']['max_active_devices']}")
        assert state_i["premium"]["has_entitlement"] is True
        assert state_i["premium"]["premium_access"] is True
        assert state_i["device"]["is_active"] is True
        assert state_i["device"]["active_device_count"] == i

    # Final verification: confirm 5 of 5 via /me/devices
    devices_resp = await client.get("/me/devices", headers={"Authorization": f"Bearer {token1}"})
    assert devices_resp.status_code == 200
    devices_data = devices_resp.json()
    print(f"\n  /me/devices response:")
    print(f"    active_count: {devices_data['active_count']}")
    print(f"    max_active: {devices_data['max_active']}")
    print(f"    device count: {len(devices_data['devices'])}")
    assert devices_data["active_count"] == 5
    assert devices_data["max_active"] == 5
    assert len(devices_data["devices"]) == 5
    # Verify all devices report is_active=True
    for d in devices_data["devices"]:
        assert d["is_active"] is True, f"Device {d['device_name']} not active"

    print(f"  PASS: 5/5 devices active")

    # ── SCENARIO 2: 6th device rejection ──
    print(f"\n=== SCENARIO 2: 6th device rejection ===")
    device6 = make_device("dev-006", device_type="phone", platform="android")
    login6 = await login_with_device(client, EMAIL, PASSWORD, device6)
    token6 = login6["tokens"]["access_token"]

    resp6 = await client.get("/me/access-state", headers={"Authorization": f"Bearer {token6}"})
    assert resp6.status_code == 200
    state6 = resp6.json()

    print(f"  Endpoint: GET /me/access-state")
    print(f"  Status: {resp6.status_code}")
    print(f"  has_entitlement: {state6['premium']['has_entitlement']}")
    print(f"  premium_access: {state6['premium']['premium_access']}")
    print(f"  reason: {state6['premium']['reason']}")
    print(f"  device.is_active: {state6['device']['is_active']}")
    print(f"  device_limit.cap_reached: {state6['device_limit']['cap_reached']}")
    print(f"  device_limit.swaps_remaining: {state6['device_limit']['swaps_remaining']}")
    print(f"  active_devices listed: {len(state6['device_limit']['active_devices'])}")

    # Verify 6th device is rejected
    assert state6["premium"]["has_entitlement"] is True, "Entitlement should exist"
    assert state6["premium"]["premium_access"] is False, "Premium access should be blocked"
    assert state6["premium"]["reason"] == "device_cap_reached", f"Wrong reason: {state6['premium']['reason']}"
    assert state6["device"]["is_active"] is False, "6th device should not be active"
    assert state6["device_limit"]["cap_reached"] is True, "Cap should be reached"
    assert state6["device_limit"]["swaps_remaining"] == 3, "Should have 3 swaps remaining"
    assert len(state6["device_limit"]["active_devices"]) == 5, "Should list 5 active devices"

    # Verify explicit activate also fails
    activate_resp = await client.post("/me/devices/activate-current", headers={"Authorization": f"Bearer {token6}"})
    assert activate_resp.status_code == 200
    activate_data = activate_resp.json()
    print(f"\n  POST /me/devices/activate-current:")
    print(f"    activated: {activate_data['activated']}")
    print(f"    reason: {activate_data['reason']}")
    assert activate_data["activated"] is False
    assert activate_data["reason"] == "device_cap_reached"

    print(f"  PASS: 6th device correctly rejected")

    # ── SCENARIO 3: Revoke one active device ──
    print(f"\n=== SCENARIO 3: Revoke device 3 ===")
    revoke_target_id = device_ids[3]

    remove_resp = await client.post(
        f"/me/devices/{revoke_target_id}/remove",
        headers={"Authorization": f"Bearer {token1}"},
    )
    assert remove_resp.status_code == 200
    remove_data = remove_resp.json()
    print(f"  Endpoint: POST /me/devices/{revoke_target_id}/remove")
    print(f"  Status: {remove_resp.status_code}")
    print(f"  removed: {remove_data['removed']}")
    print(f"  reason: {remove_data['reason']}")
    print(f"  swaps_remaining: {remove_data['swaps_remaining']}")

    assert remove_data["removed"] is True
    assert remove_data["reason"] == "ok"
    assert remove_data["swaps_remaining"] == 2

    # Verify active count dropped to 4
    devices_after = await client.get("/me/devices", headers={"Authorization": f"Bearer {token1}"})
    devices_after_data = devices_after.json()
    print(f"\n  /me/devices after removal:")
    print(f"    active_count: {devices_after_data['active_count']}")
    active_devices_after = [d for d in devices_after_data["devices"] if d["is_active"]]
    print(f"    active devices: {len(active_devices_after)}")
    assert devices_after_data["active_count"] == 4
    # Verify revoked device is NOT in active list
    active_ids = {d["id"] for d in active_devices_after}
    assert revoke_target_id not in active_ids, "Revoked device should not be in active list"

    print(f"  PASS: Active count dropped to 4, revoked device excluded")

    # ── SCENARIO 4: Retry 6th device after revoke ──
    print(f"\n=== SCENARIO 4: Retry 6th device after revoke ===")
    resp6_retry = await client.get("/me/access-state", headers={"Authorization": f"Bearer {token6}"})
    assert resp6_retry.status_code == 200
    state6_retry = resp6_retry.json()

    print(f"  Endpoint: GET /me/access-state (6th device retry)")
    print(f"  Status: {resp6_retry.status_code}")
    print(f"  has_entitlement: {state6_retry['premium']['has_entitlement']}")
    print(f"  premium_access: {state6_retry['premium']['premium_access']}")
    print(f"  reason: {state6_retry['premium']['reason']}")
    print(f"  device.is_active: {state6_retry['device']['is_active']}")
    print(f"  active_device_count: {state6_retry['device']['active_device_count']}")

    assert state6_retry["premium"]["has_entitlement"] is True
    assert state6_retry["premium"]["premium_access"] is True, "6th device should now have access"
    assert state6_retry["device"]["is_active"] is True, "6th device should now be active"
    assert state6_retry["device"]["active_device_count"] == 5, "Count should be back to 5"

    print(f"  PASS: 6th device now active, count restored to 5")

    # ── SCENARIO 5: Revoked device behavior ──
    print(f"\n=== SCENARIO 5: Revoked device (device 3) behavior ===")
    resp3_revoked = await client.get("/me/access-state", headers={"Authorization": f"Bearer {tokens[3]}"})
    assert resp3_revoked.status_code == 200
    state3_revoked = resp3_revoked.json()

    print(f"  Endpoint: GET /me/access-state (revoked device 3)")
    print(f"  Status: {resp3_revoked.status_code}")
    print(f"  has_entitlement: {state3_revoked['premium']['has_entitlement']}")
    print(f"  premium_access: {state3_revoked['premium']['premium_access']}")
    print(f"  reason: {state3_revoked['premium']['reason']}")
    print(f"  device.is_active: {state3_revoked['device']['is_active']}")
    print(f"  device_limit.cap_reached: {state3_revoked['device_limit']['cap_reached']}")
    print(f"  active_device_count: {state3_revoked['device']['active_device_count']}")

    # Revoked device trying to re-check-in: should be blocked because cap is now full again (5/5)
    assert state3_revoked["premium"]["has_entitlement"] is True
    # The revoked device should NOT be re-activated automatically since cap is now 5/5
    assert state3_revoked["premium"]["premium_access"] is False, "Revoked device should be blocked (cap full)"
    assert state3_revoked["device"]["is_active"] is False
    assert state3_revoked["device_limit"]["cap_reached"] is True

    print(f"  PASS: Revoked device correctly blocked (cap full at 5/5)")

    # ── Final state summary ──
    print(f"\n=== Final State Summary ===")
    final_devices = await client.get("/me/devices?include_removed=true", headers={"Authorization": f"Bearer {token1}"})
    if final_devices.status_code == 200:
        final_data = final_devices.json()
        print(f"  Total devices (including removed): {len(final_data['devices'])}")
        print(f"  Active: {final_data['active_count']}")
        print(f"  Max: {final_data['max_active']}")
        for d in final_data["devices"]:
            status = "ACTIVE" if d["is_active"] else ("REMOVED" if d.get("removed_at") else "INACTIVE")
            current = " (CURRENT)" if d["is_current"] else ""
            print(f"    {d['device_name']}: {status}{current} [{d['platform']}]")

    print("\n[PASS] All 5 scenarios validated successfully")
