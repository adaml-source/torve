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
