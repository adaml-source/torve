"""
Security tests: verify secrets are NOT synced, safe preferences ARE synced,
and old secret-bearing payloads are not restored.
"""
import pytest
from httpx import AsyncClient

from .conftest import register_user


def make_device(suffix: str) -> dict:
    return {
        "installation_id": f"sec-test-{suffix}",
        "device_name": f"Security Test {suffix}",
        "device_type": "phone",
        "platform": "android",
    }


@pytest.mark.asyncio
async def test_safe_settings_still_sync(client: AsyncClient):
    """Non-sensitive preferences sync normally between devices."""
    dev1 = make_device("safe-dev1")
    resp = await client.post("/auth/register", json={
        "email": "safe@example.com", "password": "TestPass123!", "device": dev1,
    })
    token1 = resp.json()["tokens"]["access_token"]

    # Set safe preferences from device 1
    resp = await client.patch(
        "/me/account-settings",
        json={"settings": {
            "language": "de",
            "rating_display_prefs": '{"imdb":true}',
            "content_region_code": "GB",
            "dedupe_results": "true",
            "debrid_provider": "REAL_DEBRID",
            "ai_provider": "GEMINI",
        }},
        headers={"Authorization": f"Bearer {token1}"},
    )
    assert resp.status_code == 200

    # Device 2 should see all safe settings
    dev2 = make_device("safe-dev2")
    resp = await client.post("/auth/login", json={
        "email": "safe@example.com", "password": "TestPass123!", "device": dev2,
    })
    token2 = resp.json()["tokens"]["access_token"]

    resp = await client.get("/me/account-settings", headers={"Authorization": f"Bearer {token2}"})
    settings = resp.json()["settings"]
    assert settings["language"] == "de"
    assert settings["rating_display_prefs"] == '{"imdb":true}'
    assert settings["content_region_code"] == "GB"
    assert settings["debrid_provider"] == "REAL_DEBRID"
    assert settings["ai_provider"] == "GEMINI"


@pytest.mark.asyncio
async def test_old_secret_payload_rejected_server_side(client: AsyncClient):
    """_sync_payload is now stripped server-side. Backend no longer stores it."""
    dev = make_device("old-payload")
    resp = await client.post("/auth/register", json={
        "email": "oldsecret@example.com", "password": "TestPass123!", "device": dev,
    })
    token = resp.json()["tokens"]["access_token"]

    fake_payload = '{"integrationSecrets":[{"key":"OMDB_API_KEY","value":"stolen_key"}]}'
    resp = await client.patch(
        "/me/account-settings",
        json={"settings": {
            "_sync_payload": fake_payload,
            "language": "fr",
        }},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 200

    resp = await client.get("/me/account-settings", headers={"Authorization": f"Bearer {token}"})
    settings = resp.json()["settings"]
    # Backend now strips _sync_payload — enforced server-side
    assert "_sync_payload" not in settings
    assert settings["language"] == "fr"


@pytest.mark.asyncio
async def test_secrets_not_in_safe_keys(client: AsyncClient):
    """Verify that even if a client pushes secret keys to account-settings,
    they're stored but the new client-side AccountSettingsSyncPolicy
    would not treat them as shared keys."""
    dev = make_device("secret-push")
    resp = await client.post("/auth/register", json={
        "email": "secretpush@example.com", "password": "TestPass123!", "device": dev,
    })
    token = resp.json()["tokens"]["access_token"]

    # Old client pushes secrets directly as account settings keys
    resp = await client.patch(
        "/me/account-settings",
        json={"settings": {
            "omdb_api_key": "secret123",
            "gemini_api_key": "ai-secret-456",
            "trakt_access_token": "oauth-token-789",
            "debrid_api_key": "debrid-key-abc",
            "language": "es",
        }},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 200

    # Backend now strips secret keys server-side — only safe keys persist
    resp = await client.get("/me/account-settings", headers={"Authorization": f"Bearer {token}"})
    settings = resp.json()["settings"]
    assert settings["language"] == "es"
    assert "omdb_api_key" not in settings
    assert "gemini_api_key" not in settings
    assert "trakt_access_token" not in settings
    assert "debrid_api_key" not in settings


@pytest.mark.asyncio
async def test_mask_secret_function():
    """Test the maskSecret utility function logic."""
    # Imported inline since this is a KMP function — we test the logic here
    def mask_secret(value: str) -> str:
        if not value.strip():
            return ""
        if len(value) <= 4:
            return "••••"
        return "••••" + value[-4:]

    assert mask_secret("") == ""
    assert mask_secret("   ") == ""
    assert mask_secret("ab") == "••••"
    assert mask_secret("abcd") == "••••"
    assert mask_secret("5330a336") == "••••a336"
    assert mask_secret("sk-very-long-api-key-12345") == "••••2345"
    assert mask_secret("x" * 100) == "••••" + "x" * 4
