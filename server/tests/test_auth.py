"""Tests for authentication endpoints."""
import pytest
from .conftest import DEVICE, register_user, login_user


@pytest.mark.asyncio
async def test_register_success(client):
    data = await register_user(client)
    assert data["user"]["email"] == "test@example.com"
    assert data["tokens"]["access_token"]
    assert data["tokens"]["refresh_token"]
    assert data["device"]["installation_id"] == DEVICE["installation_id"]


@pytest.mark.asyncio
async def test_register_duplicate_email(client):
    await register_user(client)
    resp = await client.post("/auth/register", json={
        "email": "test@example.com",
        "password": "TestPass123!",
        "device": DEVICE,
    })
    assert resp.status_code == 409


@pytest.mark.asyncio
async def test_login_success(client):
    await register_user(client)
    data = await login_user(client)
    assert data["user"]["email"] == "test@example.com"
    assert data["tokens"]["access_token"]
    assert data["device"]["installation_id"] == DEVICE["installation_id"]


@pytest.mark.asyncio
async def test_login_wrong_password(client):
    await register_user(client)
    resp = await client.post("/auth/login", json={
        "email": "test@example.com",
        "password": "WrongPassword",
        "device": DEVICE,
    })
    assert resp.status_code == 401


@pytest.mark.asyncio
async def test_me_authenticated(client):
    data = await register_user(client)
    token = data["tokens"]["access_token"]
    resp = await client.get("/me", headers={"Authorization": f"Bearer {token}"})
    assert resp.status_code == 200
    me = resp.json()
    assert me["user"]["email"] == "test@example.com"
    assert me["premium_access"] is False
    assert me["entitlements"] == []


@pytest.mark.asyncio
async def test_me_unauthenticated(client):
    resp = await client.get("/me")
    assert resp.status_code in (401, 403, 422)


@pytest.mark.asyncio
async def test_refresh_token(client):
    data = await register_user(client)
    refresh = data["tokens"]["refresh_token"]
    resp = await client.post("/auth/refresh", json={"refresh_token": refresh})
    assert resp.status_code == 200
    new_data = resp.json()
    assert new_data["tokens"]["access_token"] != data["tokens"]["access_token"]


@pytest.mark.asyncio
async def test_logout(client):
    data = await register_user(client)
    token = data["tokens"]["access_token"]
    refresh = data["tokens"]["refresh_token"]
    resp = await client.post(
        "/auth/logout",
        json={"refresh_token": refresh},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 200


@pytest.mark.asyncio
async def test_login_same_installation_is_visible_in_device_list_and_idempotent(client):
    await register_user(client)

    login1 = await login_user(client)
    token1 = login1["tokens"]["access_token"]
    device1 = login1["device"]

    resp1 = await client.get("/me/devices", headers={"Authorization": f"Bearer {token1}"})
    assert resp1.status_code == 200
    devices1 = resp1.json()["devices"]
    assert len(devices1) == 1
    assert devices1[0]["id"] == device1["id"]
    assert devices1[0]["is_current"] is True

    login2 = await login_user(client)
    token2 = login2["tokens"]["access_token"]
    device2 = login2["device"]

    resp2 = await client.get("/me/devices", headers={"Authorization": f"Bearer {token2}"})
    assert resp2.status_code == 200
    devices2 = resp2.json()["devices"]
    assert len(devices2) == 1
    assert devices2[0]["id"] == device2["id"]
    assert device2["id"] == device1["id"]


@pytest.mark.asyncio
async def test_devices_register_route_is_mounted_and_visible_in_device_list(client):
    data = await register_user(client, email="register-route@test.com")
    token = data["tokens"]["access_token"]

    resp = await client.post(
        "/devices/register",
        json=DEVICE,
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 200
    registered = resp.json()
    assert registered["installation_id"] == DEVICE["installation_id"]

    devices_resp = await client.get("/me/devices", headers={"Authorization": f"Bearer {token}"})
    assert devices_resp.status_code == 200
    devices = devices_resp.json()["devices"]
    assert len(devices) == 1
    assert devices[0]["id"] == registered["id"]
    assert devices_resp.json()["active_count"] == 1


@pytest.mark.asyncio
async def test_devices_register_second_install_creates_second_visible_row(client):
    data = await register_user(client, email="register-second@test.com")
    token = data["tokens"]["access_token"]

    device2 = {
        "installation_id": "test-device-002",
        "device_name": "Second Phone",
        "device_type": "phone",
        "platform": "amazon",
    }

    resp = await client.post(
        "/devices/register",
        json=device2,
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 200

    devices_resp = await client.get("/me/devices", headers={"Authorization": f"Bearer {token}"})
    assert devices_resp.status_code == 200
    devices = devices_resp.json()["devices"]
    installation_ids = {device["device_name"]: device for device in devices}
    assert len(devices) == 2
    assert "Test Phone" in installation_ids
    assert "Second Phone" in installation_ids
    assert devices_resp.json()["active_count"] == 2
