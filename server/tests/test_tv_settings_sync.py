"""
Live validation: TV shared settings sync through account sign-in.
Verifies the exact flow: mobile changes language → backend reflects → TV fetches → TV sees German.
"""
import pytest
from httpx import AsyncClient


def make_device(suffix: str, device_type: str = "phone", platform: str = "android") -> dict:
    return {
        "installation_id": f"tv-sync-{suffix}",
        "device_name": f"Sync Device {suffix}",
        "device_type": device_type,
        "platform": platform,
    }


@pytest.mark.asyncio
async def test_language_sync_mobile_to_tv(client: AsyncClient):
    """
    Check 1: Mobile changes language → backend reflects → TV reads it.
    """
    EMAIL = "tvsync@example.com"
    PASSWORD = "TestPass123!"

    # Register user from mobile
    mobile_device = make_device("mobile", device_type="phone", platform="android")
    resp = await client.post("/auth/register", json={
        "email": EMAIL, "password": PASSWORD, "device": mobile_device,
    })
    assert resp.status_code == 200
    mobile_token = resp.json()["tokens"]["access_token"]

    # Mobile sets language to German
    resp = await client.patch(
        "/me/account-settings",
        json={"settings": {"language": "de"}},
        headers={"Authorization": f"Bearer {mobile_token}"},
    )
    assert resp.status_code == 200
    assert resp.json()["settings"]["language"] == "de"
    print("\n=== Check 1: Language sync ===")
    print(f"  Mobile PATCH /me/account-settings language=de: OK")

    # Verify backend state
    resp = await client.get("/me/account-settings", headers={"Authorization": f"Bearer {mobile_token}"})
    assert resp.json()["settings"]["language"] == "de"
    print(f"  GET /me/account-settings from mobile: language={resp.json()['settings']['language']}")

    # TV signs in (same account, different device)
    tv_device = make_device("firetv", device_type="tv", platform="amazon_fire_tv")
    resp = await client.post("/auth/login", json={
        "email": EMAIL, "password": PASSWORD, "device": tv_device,
    })
    assert resp.status_code == 200
    tv_token = resp.json()["tokens"]["access_token"]

    # TV fetches account settings (this is what bootstrapAfterSignIn does)
    resp = await client.get("/me/account-settings", headers={"Authorization": f"Bearer {tv_token}"})
    assert resp.status_code == 200
    tv_settings = resp.json()
    print(f"  GET /me/account-settings from TV: language={tv_settings['settings'].get('language')}")
    assert tv_settings["settings"]["language"] == "de", \
        f"TV should see language=de, got: {tv_settings['settings'].get('language')}"
    print(f"  PASS: TV sees language=de without pairing")


@pytest.mark.asyncio
async def test_second_shared_setting_sync(client: AsyncClient):
    """
    Check 3: Mobile changes rating_prefs → TV picks it up.
    """
    EMAIL = "tvsync2@example.com"
    PASSWORD = "TestPass123!"

    # Register and set up
    mobile_device = make_device("mobile2", device_type="phone", platform="android")
    resp = await client.post("/auth/register", json={
        "email": EMAIL, "password": PASSWORD, "device": mobile_device,
    })
    mobile_token = resp.json()["tokens"]["access_token"]

    # Set rating prefs from mobile
    rating_prefs = '{"imdb":true,"tmdb":false,"rt":true}'
    resp = await client.patch(
        "/me/account-settings",
        json={"settings": {"rating_prefs": rating_prefs}},
        headers={"Authorization": f"Bearer {mobile_token}"},
    )
    assert resp.status_code == 200
    print("\n=== Check 3: Second shared setting ===")
    print(f"  Mobile PATCH rating_prefs: OK")

    # TV fetches
    tv_device = make_device("firetv2", device_type="tv", platform="amazon_fire_tv")
    resp = await client.post("/auth/login", json={
        "email": EMAIL, "password": PASSWORD, "device": tv_device,
    })
    tv_token = resp.json()["tokens"]["access_token"]

    resp = await client.get("/me/account-settings", headers={"Authorization": f"Bearer {tv_token}"})
    assert resp.json()["settings"]["rating_prefs"] == rating_prefs
    print(f"  TV GET rating_prefs: {resp.json()['settings']['rating_prefs']}")
    print(f"  PASS: TV sees rating_prefs from mobile")


@pytest.mark.asyncio
async def test_local_only_setting_not_synced(client: AsyncClient):
    """
    Check 4: A device-local setting (e.g. playback_quality) does not appear
    in account settings.
    """
    EMAIL = "tvsync3@example.com"
    PASSWORD = "TestPass123!"

    mobile_device = make_device("mobile3", device_type="phone", platform="android")
    resp = await client.post("/auth/register", json={
        "email": EMAIL, "password": PASSWORD, "device": mobile_device,
    })
    mobile_token = resp.json()["tokens"]["access_token"]

    # Set a "local-only" key — the backend stores it if sent, but the client
    # AccountSettingsSyncPolicy.isSharedKey() should prevent sending it.
    # We verify the backend doesn't spontaneously create local keys.
    resp = await client.get("/me/account-settings", headers={"Authorization": f"Bearer {mobile_token}"})
    settings = resp.json()["settings"]
    print("\n=== Check 4: Local-only sanity ===")
    print(f"  Account settings keys: {list(settings.keys())}")
    assert "playback_quality" not in settings
    assert "hdr_mode" not in settings
    assert "audio_passthrough" not in settings
    print(f"  PASS: No local-only keys in account settings")


@pytest.mark.asyncio
async def test_refresh_returns_latest(client: AsyncClient):
    """
    Check 5: Refresh (re-GET) returns latest settings, not stale cache.
    """
    EMAIL = "tvsync4@example.com"
    PASSWORD = "TestPass123!"

    mobile_device = make_device("mobile4", device_type="phone", platform="android")
    resp = await client.post("/auth/register", json={
        "email": EMAIL, "password": PASSWORD, "device": mobile_device,
    })
    mobile_token = resp.json()["tokens"]["access_token"]

    tv_device = make_device("firetv4", device_type="tv", platform="amazon_fire_tv")
    resp = await client.post("/auth/login", json={
        "email": EMAIL, "password": PASSWORD, "device": tv_device,
    })
    tv_token = resp.json()["tokens"]["access_token"]

    # TV fetches initially — empty
    resp = await client.get("/me/account-settings", headers={"Authorization": f"Bearer {tv_token}"})
    assert resp.json()["settings"] == {}

    # Mobile sets language
    await client.patch(
        "/me/account-settings",
        json={"settings": {"language": "pt"}},
        headers={"Authorization": f"Bearer {mobile_token}"},
    )

    # TV refreshes — should see update immediately
    resp = await client.get("/me/account-settings", headers={"Authorization": f"Bearer {tv_token}"})
    print("\n=== Check 5: Refresh returns latest ===")
    print(f"  TV GET after mobile change: language={resp.json()['settings'].get('language')}")
    assert resp.json()["settings"]["language"] == "pt"
    print(f"  PASS: Refresh returns latest settings")


@pytest.mark.asyncio
async def test_no_pairing_required(client: AsyncClient):
    """
    Verify that account settings sync works without any pairing.
    No pairing code, no LAN transport, no paired devices.
    """
    EMAIL = "nopair@example.com"
    PASSWORD = "TestPass123!"

    mobile_device = make_device("nopair-m", device_type="phone", platform="android")
    resp = await client.post("/auth/register", json={
        "email": EMAIL, "password": PASSWORD, "device": mobile_device,
    })
    mobile_token = resp.json()["tokens"]["access_token"]

    # Set settings from mobile
    await client.patch(
        "/me/account-settings",
        json={"settings": {"language": "tr", "rating_prefs": "{\"imdb\":true}"}},
        headers={"Authorization": f"Bearer {mobile_token}"},
    )

    # TV logs in — no pairing at all
    tv_device = make_device("nopair-tv", device_type="tv", platform="amazon_fire_tv")
    resp = await client.post("/auth/login", json={
        "email": EMAIL, "password": PASSWORD, "device": tv_device,
    })
    tv_token = resp.json()["tokens"]["access_token"]

    # TV fetches settings — should work without any pairing
    resp = await client.get("/me/account-settings", headers={"Authorization": f"Bearer {tv_token}"})
    assert resp.status_code == 200
    settings = resp.json()["settings"]
    print("\n=== No-pairing verification ===")
    print(f"  TV settings without pairing: {settings}")
    assert settings["language"] == "tr"
    assert settings["rating_prefs"] == "{\"imdb\":true}"
    print(f"  PASS: Settings sync works without pairing")
