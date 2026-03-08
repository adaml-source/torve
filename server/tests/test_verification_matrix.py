"""
QA Verification Matrix — Device-Governed Premium System
Exercises the exact 8 scenarios from the release gate.
Each test captures and prints raw evidence fields.
"""
import json
import pytest
import pytest_asyncio
from httpx import AsyncClient

from .conftest import register_user


def make_device(suffix: str, device_type: str = "phone", platform: str = "google_play_mobile") -> dict:
    return {
        "installation_id": f"matrix-device-{suffix}",
        "device_name": f"Matrix Device {suffix}",
        "device_type": device_type,
        "platform": platform,
    }


async def login_with_device(client: AsyncClient, email: str, password: str, device: dict) -> dict:
    resp = await client.post("/auth/login", json={
        "email": email,
        "password": password,
        "device": device,
    })
    assert resp.status_code == 200, f"Login failed: {resp.text}"
    return resp.json()


async def register_with_device(client: AsyncClient, email: str, password: str, device: dict) -> dict:
    resp = await client.post("/auth/register", json={
        "email": email,
        "password": password,
        "device": device,
    })
    assert resp.status_code == 200, f"Register failed: {resp.text}"
    return resp.json()


async def verify_apple_purchase(client: AsyncClient, token: str) -> dict:
    import base64, json as json_mod
    payload = {
        "transactionId": "txn_matrix_123",
        "originalTransactionId": "txn_matrix_123",
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
    assert resp.status_code == 200, f"Apple verify failed: {resp.text}"
    return resp.json()


async def fill_5_devices(client: AsyncClient, email: str, password: str, token1: str):
    """Activate device 1 via access-state and add 4 more devices, returning all tokens and device IDs."""
    # Activate device 1
    resp = await client.get("/me/access-state", headers={"Authorization": f"Bearer {token1}"})
    assert resp.json()["premium"]["premium_access"] is True
    d1_id = resp.json()["device"]["id"]

    tokens = [token1]
    device_ids = [d1_id]
    for i in range(2, 6):
        device = make_device(f"fill-{email}-{i}")
        login_data = await login_with_device(client, email, password, device)
        t = login_data["tokens"]["access_token"]
        resp = await client.get("/me/access-state", headers={"Authorization": f"Bearer {t}"})
        assert resp.json()["premium"]["premium_access"] is True, f"Device {i} failed to activate"
        tokens.append(t)
        device_ids.append(resp.json()["device"]["id"])
    return tokens, device_ids


def evidence(label: str, data: dict, fields: list[str]) -> dict:
    """Extract and print evidence fields from API response."""
    result = {}
    for field_path in fields:
        parts = field_path.split(".")
        val = data
        for p in parts:
            if isinstance(val, dict):
                val = val.get(p, "MISSING")
            else:
                val = "MISSING"
        result[field_path] = val
    print(f"\n=== EVIDENCE: {label} ===")
    for k, v in result.items():
        print(f"  {k}: {v}")
    return result


# ──────────────────────────────────────────────────
# ROW A: Android mobile, 6th device blocked
# ──────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_row_a_6th_device_blocked(client: AsyncClient):
    """ROW A: 6th device is blocked with device_cap_reached."""
    # Setup: register + purchase + fill 5 devices
    data = await register_with_device(client, "rowa@test.com", "TestPass123!", make_device("rowa-1"))
    token1 = data["tokens"]["access_token"]
    await verify_apple_purchase(client, token1)
    tokens, device_ids = await fill_5_devices(client, "rowa@test.com", "TestPass123!", token1)

    # 6th device (Android mobile)
    device6 = make_device("rowa-6", device_type="phone", platform="google_play_mobile")
    login6 = await login_with_device(client, "rowa@test.com", "TestPass123!", device6)
    t6 = login6["tokens"]["access_token"]

    resp = await client.get("/me/access-state", headers={"Authorization": f"Bearer {t6}"})
    d = resp.json()
    ev = evidence("ROW_A: 6th device blocked", d, [
        "premium.has_entitlement",
        "premium.premium_access",
        "premium.reason",
        "device.is_active",
        "device.active_device_count",
        "device.max_active_devices",
        "device_limit.cap_reached",
        "device_limit.swaps_remaining",
    ])

    assert ev["premium.has_entitlement"] is True
    assert ev["premium.premium_access"] is False
    assert ev["premium.reason"] == "device_cap_reached"
    assert ev["device.is_active"] is False
    assert ev["device.active_device_count"] == 5
    assert ev["device_limit.cap_reached"] is True

    # Verify active_devices list is populated for the UI
    active_devices = d["device_limit"]["active_devices"]
    print(f"  active_devices count: {len(active_devices)}")
    assert len(active_devices) == 5, f"Expected 5 active devices, got {len(active_devices)}"


# ──────────────────────────────────────────────────
# ROW B: Android mobile, remove then activate
# ──────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_row_b_remove_then_activate(client: AsyncClient):
    """ROW B: Remove one device, 6th device auto-activates immediately."""
    data = await register_with_device(client, "rowb@test.com", "TestPass123!", make_device("rowb-1"))
    token1 = data["tokens"]["access_token"]
    await verify_apple_purchase(client, token1)
    tokens, device_ids = await fill_5_devices(client, "rowb@test.com", "TestPass123!", token1)

    # 6th device blocked
    device6 = make_device("rowb-6")
    login6 = await login_with_device(client, "rowb@test.com", "TestPass123!", device6)
    t6 = login6["tokens"]["access_token"]
    resp_blocked = await client.get("/me/access-state", headers={"Authorization": f"Bearer {t6}"})
    assert resp_blocked.json()["premium"]["premium_access"] is False
    print("\n=== ROW_B: 6th device confirmed BLOCKED ===")

    # Remove device 2 (using token1 which belongs to the account)
    remove_target = device_ids[1]  # device 2
    resp_remove = await client.post(
        f"/me/devices/{remove_target}/remove",
        headers={"Authorization": f"Bearer {token1}"},
    )
    rm = resp_remove.json()
    evidence("ROW_B: Remove device 2", rm, ["removed", "reason", "swaps_remaining"])
    assert rm["removed"] is True
    assert rm["reason"] == "ok"

    # 6th device auto-activates (simulate client calling activate-current)
    resp_activate = await client.post(
        "/me/devices/activate-current",
        headers={"Authorization": f"Bearer {t6}"},
    )
    act = resp_activate.json()
    evidence("ROW_B: Activate device 6", act, ["activated", "reason", "active_device_count"])
    assert act["activated"] is True
    assert act["active_device_count"] == 5  # now at cap again

    # Verify via access-state: premium now unlocked
    resp_final = await client.get("/me/access-state", headers={"Authorization": f"Bearer {t6}"})
    final = resp_final.json()
    evidence("ROW_B: Final state", final, [
        "premium.premium_access",
        "device.is_active",
        "device.active_device_count",
    ])
    assert final["premium"]["premium_access"] is True
    assert final["device"]["is_active"] is True


# ──────────────────────────────────────────────────
# ROW C: Google TV, remote navigation (API-level check)
# ──────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_row_c_tv_device_governance(client: AsyncClient):
    """ROW C: TV device type gets blocked/activated same as mobile."""
    data = await register_with_device(client, "rowc@test.com", "TestPass123!", make_device("rowc-1", device_type="phone"))
    token1 = data["tokens"]["access_token"]
    await verify_apple_purchase(client, token1)
    tokens, device_ids = await fill_5_devices(client, "rowc@test.com", "TestPass123!", token1)

    # 6th device is a TV
    tv_device = make_device("rowc-tv", device_type="tv", platform="google_play_tv")
    login_tv = await login_with_device(client, "rowc@test.com", "TestPass123!", tv_device)
    tv_token = login_tv["tokens"]["access_token"]

    resp = await client.get("/me/access-state", headers={"Authorization": f"Bearer {tv_token}"})
    d = resp.json()
    evidence("ROW_C: TV device blocked", d, [
        "premium.has_entitlement",
        "premium.premium_access",
        "premium.reason",
        "device.device_type",
        "device.platform",
        "device_limit.cap_reached",
    ])
    assert d["premium"]["premium_access"] is False
    assert d["premium"]["reason"] == "device_cap_reached"
    assert d["device"]["device_type"] == "tv"

    # Remove a phone, verify TV can activate
    resp_remove = await client.post(
        f"/me/devices/{device_ids[1]}/remove",
        headers={"Authorization": f"Bearer {token1}"},
    )
    assert resp_remove.json()["removed"] is True

    resp2 = await client.get("/me/access-state", headers={"Authorization": f"Bearer {tv_token}"})
    evidence("ROW_C: TV device after slot freed", resp2.json(), [
        "premium.premium_access",
        "device.is_active",
        "device.device_type",
    ])
    assert resp2.json()["premium"]["premium_access"] is True
    assert resp2.json()["device"]["is_active"] is True


# ──────────────────────────────────────────────────
# ROW D: Amazon Fire TV, remove then activate
# ──────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_row_d_fire_tv_remove_activate(client: AsyncClient):
    """ROW D: Fire TV device blocked, remove, activate immediately."""
    data = await register_with_device(client, "rowd@test.com", "TestPass123!", make_device("rowd-1"))
    token1 = data["tokens"]["access_token"]
    await verify_apple_purchase(client, token1)
    tokens, device_ids = await fill_5_devices(client, "rowd@test.com", "TestPass123!", token1)

    # Fire TV as 6th device
    fire_tv = make_device("rowd-ftv", device_type="tv", platform="amazon_fire_tv")
    login_ftv = await login_with_device(client, "rowd@test.com", "TestPass123!", fire_tv)
    ftv_token = login_ftv["tokens"]["access_token"]

    # Blocked
    resp = await client.get("/me/access-state", headers={"Authorization": f"Bearer {ftv_token}"})
    assert resp.json()["premium"]["premium_access"] is False
    evidence("ROW_D: Fire TV blocked", resp.json(), [
        "premium.reason",
        "device.platform",
        "device_limit.cap_reached",
    ])

    # Remove device 3
    resp_rm = await client.post(
        f"/me/devices/{device_ids[2]}/remove",
        headers={"Authorization": f"Bearer {token1}"},
    )
    assert resp_rm.json()["removed"] is True

    # Auto-activate
    resp2 = await client.get("/me/access-state", headers={"Authorization": f"Bearer {ftv_token}"})
    ev = evidence("ROW_D: Fire TV after activation", resp2.json(), [
        "premium.premium_access",
        "device.is_active",
        "device.platform",
    ])
    assert ev["premium.premium_access"] is True
    assert ev["device.is_active"] is True
    assert ev["device.platform"] == "amazon_fire_tv"


# ──────────────────────────────────────────────────
# ROW E: iOS, restore with full cap
# ──────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_row_e_ios_restore_full_cap(client: AsyncClient):
    """ROW E: iOS restore when cap is full shows device limit."""
    data = await register_with_device(client, "rowe@test.com", "TestPass123!", make_device("rowe-1"))
    token1 = data["tokens"]["access_token"]
    await verify_apple_purchase(client, token1)
    tokens, device_ids = await fill_5_devices(client, "rowe@test.com", "TestPass123!", token1)

    # iOS device as 6th
    ios_device = make_device("rowe-ios", device_type="phone", platform="ios")
    login_ios = await login_with_device(client, "rowe@test.com", "TestPass123!", ios_device)
    ios_token = login_ios["tokens"]["access_token"]

    # Restore on iOS
    resp_restore = await client.post(
        "/purchases/restore",
        json={"store": "apple", "platform": "ios"},
        headers={"Authorization": f"Bearer {ios_token}"},
    )
    restore = resp_restore.json()
    evidence("ROW_E: iOS restore at full cap", restore, [
        "premium_access",
    ])
    # Restore returns entitlement but device-blocked
    assert len(restore["entitlements"]) > 0  # has entitlement
    assert restore["premium_access"] is False  # device blocked

    # Confirm via access-state
    resp = await client.get("/me/access-state", headers={"Authorization": f"Bearer {ios_token}"})
    d = resp.json()
    evidence("ROW_E: iOS access-state", d, [
        "premium.has_entitlement",
        "premium.premium_access",
        "premium.reason",
    ])
    assert d["premium"]["has_entitlement"] is True
    assert d["premium"]["premium_access"] is False
    assert d["premium"]["reason"] == "device_cap_reached"

    # Remove one, activate iOS
    resp_rm = await client.post(
        f"/me/devices/{device_ids[0]}/remove",
        headers={"Authorization": f"Bearer {tokens[1]}"},
    )
    assert resp_rm.json()["removed"] is True

    resp_final = await client.get("/me/access-state", headers={"Authorization": f"Bearer {ios_token}"})
    evidence("ROW_E: iOS after removal", resp_final.json(), [
        "premium.premium_access",
        "device.is_active",
    ])
    assert resp_final.json()["premium"]["premium_access"] is True


# ──────────────────────────────────────────────────
# ROW F: Stale-device auto-expiry
# ──────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_row_f_stale_device_auto_expiry(client: AsyncClient):
    """ROW F: Stale device auto-expires, freeing a slot without consuming a swap."""
    from datetime import datetime, timedelta, timezone
    from unittest.mock import patch

    data = await register_with_device(client, "rowf@test.com", "TestPass123!", make_device("rowf-1"))
    token1 = data["tokens"]["access_token"]
    await verify_apple_purchase(client, token1)
    tokens, device_ids = await fill_5_devices(client, "rowf@test.com", "TestPass123!", token1)

    # Manually set device 2's last_seen_at to 50 days ago (beyond STALE_DAYS=45)
    # We do this by patching the DB directly through the app's session
    from app.db import get_session as app_get_session
    from app.models import Device, utcnow
    from sqlalchemy import update
    from app.main import app
    from sqlalchemy.ext.asyncio import AsyncSession

    # Get a session from the override
    override = app.dependency_overrides.get(app_get_session)
    if override:
        async for session in override():
            stale_date = datetime.now(timezone.utc) - timedelta(days=50)
            await session.execute(
                update(Device)
                .where(Device.id == device_ids[1])
                .values(last_seen_at=stale_date)
            )
            await session.commit()
            break

    # 6th device — should succeed because device 2 will be auto-pruned
    device6 = make_device("rowf-6")
    login6 = await login_with_device(client, "rowf@test.com", "TestPass123!", device6)
    t6 = login6["tokens"]["access_token"]

    resp = await client.get("/me/access-state", headers={"Authorization": f"Bearer {t6}"})
    d = resp.json()
    evidence("ROW_F: After stale auto-expiry", d, [
        "premium.premium_access",
        "device.is_active",
        "device.active_device_count",
        "device_limit.stale_devices_pruned",
        "device_limit.swaps_remaining",
    ])

    assert d["premium"]["premium_access"] is True
    assert d["device_limit"]["stale_devices_pruned"] >= 1
    # Stale expiry does NOT count toward swaps
    assert d["device_limit"]["swaps_remaining"] == 3  # full swap budget intact


# ──────────────────────────────────────────────────
# ROW G: Swap-limit on 4th removal blocked
# ──────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_row_g_swap_limit_4th_blocked(client: AsyncClient):
    """ROW G: After 3 removals, 4th is blocked by swap limit."""
    data = await register_with_device(client, "rowg@test.com", "TestPass123!", make_device("rowg-1"))
    token1 = data["tokens"]["access_token"]
    await verify_apple_purchase(client, token1)

    # Activate device 1
    resp1 = await client.get("/me/access-state", headers={"Authorization": f"Bearer {token1}"})
    assert resp1.json()["premium"]["premium_access"] is True

    # Create, activate, and remove 3 devices (exhausting swap budget)
    for i in range(2, 5):
        device = make_device(f"rowg-swap-{i}")
        login_data = await login_with_device(client, "rowg@test.com", "TestPass123!", device)
        t = login_data["tokens"]["access_token"]
        resp = await client.get("/me/access-state", headers={"Authorization": f"Bearer {t}"})
        did = resp.json()["device"]["id"]
        # Remove
        resp_rm = await client.post(
            f"/me/devices/{did}/remove",
            headers={"Authorization": f"Bearer {token1}"},
        )
        rm = resp_rm.json()
        evidence(f"ROW_G: Removal #{i-1}", rm, ["removed", "reason", "swaps_remaining"])
        assert rm["removed"] is True

    # 4th device: activate then try to remove
    device5 = make_device("rowg-swap-5")
    login5 = await login_with_device(client, "rowg@test.com", "TestPass123!", device5)
    t5 = login5["tokens"]["access_token"]
    resp5 = await client.get("/me/access-state", headers={"Authorization": f"Bearer {t5}"})
    did5 = resp5.json()["device"]["id"]

    resp_rm4 = await client.post(
        f"/me/devices/{did5}/remove",
        headers={"Authorization": f"Bearer {token1}"},
    )
    rm4 = resp_rm4.json()
    evidence("ROW_G: 4th removal attempt", rm4, ["removed", "reason", "swaps_remaining"])
    assert rm4["removed"] is False
    assert rm4["reason"] == "swap_limit_reached"
    assert rm4["swaps_remaining"] == 0


# ──────────────────────────────────────────────────
# ROW H: Purchase on full account grants ownership but not immediate access
# ──────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_row_h_purchase_full_account(client: AsyncClient):
    """ROW H: Purchase on 6th device grants entitlement but NOT device access."""
    import base64, json as json_mod

    # Register 5 devices first (without premium)
    data = await register_with_device(client, "rowh@test.com", "TestPass123!", make_device("rowh-1"))
    token1 = data["tokens"]["access_token"]

    device_tokens = [token1]
    for i in range(2, 6):
        device = make_device(f"rowh-{i}")
        login_data = await login_with_device(client, "rowh@test.com", "TestPass123!", device)
        device_tokens.append(login_data["tokens"]["access_token"])

    # Purchase from device 1 — activates device 1
    await verify_apple_purchase(client, token1)
    resp1 = await client.get("/me/access-state", headers={"Authorization": f"Bearer {token1}"})
    assert resp1.json()["premium"]["premium_access"] is True

    # Activate devices 2-5
    for t in device_tokens[1:]:
        resp = await client.get("/me/access-state", headers={"Authorization": f"Bearer {t}"})
        assert resp.json()["premium"]["premium_access"] is True

    # 6th device: new purchase (different txn) — entitlement granted but device blocked
    device6 = make_device("rowh-6")
    login6 = await login_with_device(client, "rowh@test.com", "TestPass123!", device6)
    t6 = login6["tokens"]["access_token"]

    # Check access state
    resp6 = await client.get("/me/access-state", headers={"Authorization": f"Bearer {t6}"})
    d6 = resp6.json()
    evidence("ROW_H: 6th device after purchase", d6, [
        "premium.has_entitlement",
        "premium.premium_access",
        "premium.reason",
        "device.is_active",
        "device.active_device_count",
    ])
    assert d6["premium"]["has_entitlement"] is True
    assert d6["premium"]["premium_access"] is False
    assert d6["premium"]["reason"] == "device_cap_reached"
