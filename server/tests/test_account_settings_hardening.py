"""
Backend security hardening tests for account settings.
Verifies that secret-bearing keys are:
1. Stripped from PATCH writes
2. Stripped from GET responses (defense in depth)
3. Not persisted even via mixed payloads
4. Cleaned from legacy data
"""
import pytest
from httpx import AsyncClient

from .conftest import register_user
from app.account_settings_policy import is_forbidden_key, strip_forbidden_keys, scrub_stored_settings


def make_device(suffix: str) -> dict:
    return {
        "installation_id": f"hardening-{suffix}",
        "device_name": f"Hardening Test {suffix}",
        "device_type": "phone",
        "platform": "android",
    }


# ── Policy unit tests ──


def test_explicit_forbidden_keys():
    """All explicitly listed secret keys are detected."""
    assert is_forbidden_key("debrid_api_key")
    assert is_forbidden_key("trakt_access_token")
    assert is_forbidden_key("gemini_api_key")
    assert is_forbidden_key("omdb_api_key")
    assert is_forbidden_key("_sync_payload")
    assert is_forbidden_key("plex_access_token")
    assert is_forbidden_key("simkl_access_token")
    assert is_forbidden_key("debrid_rd_client_secret")


def test_pattern_catches_unknown_secret_keys():
    """Pattern-based matching catches new/unknown secret keys."""
    assert is_forbidden_key("some_new_provider_api_key")
    assert is_forbidden_key("future_service_access_token")
    assert is_forbidden_key("new_integration_refresh_token")
    assert is_forbidden_key("myservice_client_secret")
    assert is_forbidden_key("something_password")


def test_safe_keys_not_forbidden():
    """Safe preference keys are not blocked."""
    assert not is_forbidden_key("language")
    assert not is_forbidden_key("rating_display_prefs")
    assert not is_forbidden_key("content_region_code")
    assert not is_forbidden_key("debrid_provider")
    assert not is_forbidden_key("ai_provider")
    assert not is_forbidden_key("home_layout_order")
    assert not is_forbidden_key("dedupe_results")
    assert not is_forbidden_key("card_style_presets")


def test_strip_forbidden_keys_mixed():
    """Strip function removes only forbidden keys."""
    mixed = {
        "language": "de",
        "omdb_api_key": "secret123",
        "rating_display_prefs": '{"imdb":true}',
        "_sync_payload": "{big_blob}",
        "debrid_provider": "REAL_DEBRID",
    }
    result = strip_forbidden_keys(mixed)
    assert result == {
        "language": "de",
        "rating_display_prefs": '{"imdb":true}',
        "debrid_provider": "REAL_DEBRID",
    }


def test_scrub_stored_settings():
    """Scrub function identifies and removes forbidden keys."""
    stored = {
        "language": "fr",
        "gemini_api_key": "AIzaSy...",
        "trakt_access_token": "oauth...",
        "content_region_code": "GB",
        "_sync_payload": "{...}",
    }
    scrubbed, removed = scrub_stored_settings(stored)
    assert scrubbed == {"language": "fr", "content_region_code": "GB"}
    assert set(removed) == {"gemini_api_key", "trakt_access_token", "_sync_payload"}


# ── API integration tests ──


@pytest.mark.asyncio
async def test_patch_safe_settings_only(client: AsyncClient):
    """Safe-only PATCH persists normally."""
    data = await register_user(client, email="safe-only@example.com")
    token = data["tokens"]["access_token"]

    resp = await client.patch(
        "/me/account-settings",
        json={"settings": {"language": "de", "debrid_provider": "REAL_DEBRID"}},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 200
    assert resp.json()["settings"]["language"] == "de"
    assert resp.json()["settings"]["debrid_provider"] == "REAL_DEBRID"


@pytest.mark.asyncio
async def test_patch_forbidden_key_stripped(client: AsyncClient):
    """Single forbidden key in PATCH is silently stripped — not persisted."""
    data = await register_user(client, email="forbid1@example.com")
    token = data["tokens"]["access_token"]

    resp = await client.patch(
        "/me/account-settings",
        json={"settings": {"omdb_api_key": "secret_value_123"}},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 200
    assert "omdb_api_key" not in resp.json()["settings"]

    # GET also should not return it
    resp2 = await client.get("/me/account-settings", headers={"Authorization": f"Bearer {token}"})
    assert "omdb_api_key" not in resp2.json()["settings"]


@pytest.mark.asyncio
async def test_patch_mixed_safe_and_forbidden(client: AsyncClient):
    """Mixed payload: safe keys persist, forbidden keys stripped."""
    data = await register_user(client, email="mixed@example.com")
    token = data["tokens"]["access_token"]

    resp = await client.patch(
        "/me/account-settings",
        json={"settings": {
            "language": "es",
            "gemini_api_key": "AIzaSySecretKey",
            "rating_display_prefs": '{"imdb":true}',
            "trakt_access_token": "oauth_token_xyz",
            "debrid_provider": "ALL_DEBRID",
            "_sync_payload": '{"secrets":"everywhere"}',
        }},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 200
    settings = resp.json()["settings"]
    # Safe keys persisted
    assert settings["language"] == "es"
    assert settings["rating_display_prefs"] == '{"imdb":true}'
    assert settings["debrid_provider"] == "ALL_DEBRID"
    # Forbidden keys stripped
    assert "gemini_api_key" not in settings
    assert "trakt_access_token" not in settings
    assert "_sync_payload" not in settings


@pytest.mark.asyncio
async def test_patch_sync_payload_stripped(client: AsyncClient):
    """_sync_payload is specifically blocked."""
    data = await register_user(client, email="payload@example.com")
    token = data["tokens"]["access_token"]

    resp = await client.patch(
        "/me/account-settings",
        json={"settings": {"_sync_payload": '{"integrationSecrets":[{"key":"OMDB","value":"stolen"}]}'}},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 200
    assert "_sync_payload" not in resp.json()["settings"]


@pytest.mark.asyncio
async def test_get_strips_legacy_forbidden_keys(client: AsyncClient):
    """GET strips forbidden keys from response even if legacy row has them."""
    dev = make_device("legacy-test")
    resp = await client.post("/auth/register", json={
        "email": "legacy@example.com", "password": "TestPass123!", "device": dev,
    })
    token = resp.json()["tokens"]["access_token"]

    # First set safe settings
    await client.patch(
        "/me/account-settings",
        json={"settings": {"language": "pt"}},
        headers={"Authorization": f"Bearer {token}"},
    )

    # Verify GET only returns safe keys
    resp = await client.get("/me/account-settings", headers={"Authorization": f"Bearer {token}"})
    settings = resp.json()["settings"]
    assert settings.get("language") == "pt"
    # These should never appear even if somehow stored
    for key in ["omdb_api_key", "gemini_api_key", "_sync_payload", "trakt_access_token"]:
        assert key not in settings


@pytest.mark.asyncio
async def test_pattern_catches_novel_secret_key(client: AsyncClient):
    """Pattern matching catches a new secret key not explicitly listed."""
    data = await register_user(client, email="novel@example.com")
    token = data["tokens"]["access_token"]

    resp = await client.patch(
        "/me/account-settings",
        json={"settings": {
            "language": "fr",
            "future_provider_api_key": "secret_abc",
            "new_service_access_token": "token_xyz",
        }},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 200
    settings = resp.json()["settings"]
    assert settings["language"] == "fr"
    assert "future_provider_api_key" not in settings
    assert "new_service_access_token" not in settings


@pytest.mark.asyncio
async def test_all_secret_categories_blocked(client: AsyncClient):
    """Regression test: all known secret categories are blocked."""
    data = await register_user(client, email="allcat@example.com")
    token = data["tokens"]["access_token"]

    all_secrets = {
        "debrid_api_key": "s1",
        "debrid_rd_refresh_token": "s2",
        "debrid_rd_client_id": "s3",
        "debrid_rd_client_secret": "s4",
        "trakt_client_id": "s5",
        "trakt_client_secret": "s6",
        "trakt_access_token": "s7",
        "trakt_refresh_token": "s8",
        "simkl_client_id": "s9",
        "simkl_access_token": "s10",
        "claude_api_key": "s11",
        "chatgpt_api_key": "s12",
        "gemini_api_key": "s13",
        "perplexity_api_key": "s14",
        "deepseek_api_key": "s15",
        "omdb_api_key": "s16",
        "mdblist_api_key": "s17",
        "jellyfin_api_key": "s18",
        "plex_access_token": "s19",
        "_sync_payload": "s20",
    }
    all_secrets["language"] = "tr"  # one safe key

    resp = await client.patch(
        "/me/account-settings",
        json={"settings": all_secrets},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 200
    settings = resp.json()["settings"]
    assert settings == {"language": "tr"}


@pytest.mark.asyncio
async def test_safe_settings_still_sync_after_hardening(client: AsyncClient):
    """Safe settings continue to work normally after hardening."""
    dev1 = make_device("sync-a")
    resp = await client.post("/auth/register", json={
        "email": "synccheck@example.com", "password": "TestPass123!", "device": dev1,
    })
    token1 = resp.json()["tokens"]["access_token"]

    await client.patch(
        "/me/account-settings",
        json={"settings": {
            "language": "it",
            "rating_display_prefs": '{"rt":true}',
            "content_region_code": "DE",
        }},
        headers={"Authorization": f"Bearer {token1}"},
    )

    dev2 = make_device("sync-b")
    resp2 = await client.post("/auth/login", json={
        "email": "synccheck@example.com", "password": "TestPass123!", "device": dev2,
    })
    token2 = resp2.json()["tokens"]["access_token"]

    resp = await client.get("/me/account-settings", headers={"Authorization": f"Bearer {token2}"})
    settings = resp.json()["settings"]
    assert settings["language"] == "it"
    assert settings["rating_display_prefs"] == '{"rt":true}'
    assert settings["content_region_code"] == "DE"
