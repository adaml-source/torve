"""Tests for /me/account-settings GET and PATCH endpoints."""
import pytest
from httpx import AsyncClient

from .conftest import register_user


def make_device(suffix: str) -> dict:
    return {
        "installation_id": f"acctset-{suffix}",
        "device_name": f"Device {suffix}",
        "device_type": "phone",
        "platform": "android",
    }


@pytest.mark.asyncio
async def test_get_empty_settings(client: AsyncClient):
    """New user has empty settings."""
    data = await register_user(client, email="empty@example.com")
    token = data["tokens"]["access_token"]

    resp = await client.get("/me/account-settings", headers={"Authorization": f"Bearer {token}"})
    assert resp.status_code == 200
    body = resp.json()
    assert body["settings"] == {}
    assert body["updated_at"] is None


@pytest.mark.asyncio
async def test_patch_settings(client: AsyncClient):
    """PATCH creates and updates settings."""
    data = await register_user(client, email="patch@example.com")
    token = data["tokens"]["access_token"]

    # Patch language
    resp = await client.patch(
        "/me/account-settings",
        json={"settings": {"language": "de", "rating_prefs": "{\"imdb\":true}"}},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["settings"]["language"] == "de"
    assert body["settings"]["rating_prefs"] == "{\"imdb\":true}"
    assert body["updated_at"] is not None

    # GET returns same
    resp2 = await client.get("/me/account-settings", headers={"Authorization": f"Bearer {token}"})
    assert resp2.json()["settings"]["language"] == "de"


@pytest.mark.asyncio
async def test_patch_merge(client: AsyncClient):
    """PATCH merges — does not overwrite unrelated keys."""
    data = await register_user(client, email="merge@example.com")
    token = data["tokens"]["access_token"]

    # Set language
    await client.patch(
        "/me/account-settings",
        json={"settings": {"language": "fr"}},
        headers={"Authorization": f"Bearer {token}"},
    )

    # Set rating_prefs — language should survive
    resp = await client.patch(
        "/me/account-settings",
        json={"settings": {"rating_prefs": "{\"tmdb\":true}"}},
        headers={"Authorization": f"Bearer {token}"},
    )
    body = resp.json()
    assert body["settings"]["language"] == "fr"
    assert body["settings"]["rating_prefs"] == "{\"tmdb\":true}"


@pytest.mark.asyncio
async def test_patch_null_removes_key(client: AsyncClient):
    """PATCH with null removes a key."""
    data = await register_user(client, email="null@example.com")
    token = data["tokens"]["access_token"]

    await client.patch(
        "/me/account-settings",
        json={"settings": {"language": "es", "extra": "value"}},
        headers={"Authorization": f"Bearer {token}"},
    )

    # Remove extra
    resp = await client.patch(
        "/me/account-settings",
        json={"settings": {"extra": None}},
        headers={"Authorization": f"Bearer {token}"},
    )
    body = resp.json()
    assert "extra" not in body["settings"]
    assert body["settings"]["language"] == "es"


@pytest.mark.asyncio
async def test_settings_isolated_per_user(client: AsyncClient):
    """Users cannot see each other's settings."""
    dev1 = make_device("user1-dev")
    resp1 = await client.post("/auth/register", json={
        "email": "user1set@example.com", "password": "TestPass123!", "device": dev1,
    })
    token1 = resp1.json()["tokens"]["access_token"]

    dev2 = make_device("user2-dev")
    resp2 = await client.post("/auth/register", json={
        "email": "user2set@example.com", "password": "TestPass123!", "device": dev2,
    })
    token2 = resp2.json()["tokens"]["access_token"]

    # User1 sets language
    await client.patch(
        "/me/account-settings",
        json={"settings": {"language": "tr"}},
        headers={"Authorization": f"Bearer {token1}"},
    )

    # User2 should have empty settings
    resp = await client.get("/me/account-settings", headers={"Authorization": f"Bearer {token2}"})
    assert resp.json()["settings"] == {}


@pytest.mark.asyncio
async def test_cross_device_sync(client: AsyncClient):
    """Settings saved from one device are visible from another."""
    dev1 = make_device("sync-dev1")
    resp1 = await client.post("/auth/register", json={
        "email": "xdev@example.com", "password": "TestPass123!", "device": dev1,
    })
    token1 = resp1.json()["tokens"]["access_token"]

    # Set language from device 1
    await client.patch(
        "/me/account-settings",
        json={"settings": {"language": "it"}},
        headers={"Authorization": f"Bearer {token1}"},
    )

    # Login from device 2
    dev2 = make_device("sync-dev2")
    resp2 = await client.post("/auth/login", json={
        "email": "xdev@example.com", "password": "TestPass123!", "device": dev2,
    })
    token2 = resp2.json()["tokens"]["access_token"]

    # Device 2 should see language=it
    resp = await client.get("/me/account-settings", headers={"Authorization": f"Bearer {token2}"})
    assert resp.json()["settings"]["language"] == "it"


@pytest.mark.asyncio
async def test_unauthenticated_rejected(client: AsyncClient):
    """No token → 401."""
    resp = await client.get("/me/account-settings")
    assert resp.status_code in (401, 403)

    resp2 = await client.patch("/me/account-settings", json={"settings": {"language": "de"}})
    assert resp2.status_code in (401, 403)
