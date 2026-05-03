"""
Tests for the account-settings secret-stripping policy.

Covers:
  - is_forbidden_key: explicit list + regex patterns + non-secret pass-through
  - strip_forbidden_keys: silent-strip preserves safe keys, logs removed ones
  - scrub_stored_settings: read-side legacy cleanup path
  - PATCH router integration: secrets stripped before persistence
  - GET router integration: legacy rows self-heal (scrub + persist-back)
"""
from __future__ import annotations

import logging
import uuid

import pytest

from app.account_settings_policy import (
    FORBIDDEN_ACCOUNT_SETTINGS_KEYS,
    is_forbidden_key,
    scrub_stored_settings,
    strip_forbidden_keys,
)
from app.models import AccountSettings
from app.security import create_access_token


def _auth(user):
    return {"Authorization": f"Bearer {create_access_token(str(user.id))}"}


# ── Unit tests on the policy module ───────────────────────────────────


@pytest.mark.parametrize("key", [
    "debrid_api_key",
    "trakt_access_token",
    "simkl_client_id",
    "claude_api_key",
    "jellyfin_api_key",
    "auth_refresh_token",
    "kodi_hosts_json",
    "_sync_payload",
])
def test_is_forbidden_explicit(key):
    assert is_forbidden_key(key) is True


@pytest.mark.parametrize("key", [
    "my_custom_api_key",          # future-client API key
    "some_access_token",          # future OAuth access token
    "any_refresh_token",
    "new_client_secret",
    "arbitrary_password",
    "whatever_cookie",
    "something_bearer",
    "x_webhook_secret",
])
def test_is_forbidden_pattern_catchall(key):
    assert is_forbidden_key(key) is True


@pytest.mark.parametrize("key", [
    "language",
    "home_layout",
    "ratings_provider",
    "auto_play_enabled",
    "debrid_provider",            # a provider *name*, not credential
    "jellyfin_server_url",        # URL, not the api_key
    "has_password",               # boolean flag, not the password
    "account_settings_migration_done",
])
def test_is_forbidden_non_secret_passthrough(key):
    assert is_forbidden_key(key) is False


def test_strip_preserves_safe_keys_and_removes_secrets(caplog):
    uid = uuid.uuid4()
    incoming = {
        "language": "de",
        "debrid_api_key": "leaked",
        "trakt_access_token": "also leaked",
        "auto_play_enabled": True,
    }
    with caplog.at_level(logging.WARNING, logger="app.account_settings_policy"):
        out = strip_forbidden_keys(incoming, user_id=uid, context="patch")
    assert out == {"language": "de", "auto_play_enabled": True}
    assert any("ACCOUNT_SETTINGS_SECRET_STRIPPED" in r.getMessage() for r in caplog.records)


def test_scrub_stored_returns_both_clean_and_removed():
    uid = uuid.uuid4()
    stored = {
        "language": "en",
        "trakt_refresh_token": "old",
        "chatgpt_api_key": "sk-...",
    }
    scrubbed, removed = scrub_stored_settings(stored, user_id=uid)
    assert scrubbed == {"language": "en"}
    assert set(removed) == {"trakt_refresh_token", "chatgpt_api_key"}


def test_strip_no_op_when_all_keys_safe(caplog):
    with caplog.at_level(logging.WARNING):
        out = strip_forbidden_keys({"language": "en", "auto_play_enabled": False})
    assert out == {"language": "en", "auto_play_enabled": False}
    assert not any("ACCOUNT_SETTINGS_SECRET_STRIPPED" in r.getMessage() for r in caplog.records)


def test_explicit_list_covers_known_android_keys():
    # Regression guard — if the Android client adds a credential field
    # that someone forgets to put on this list, pattern match will still
    # catch it. This test asserts the known-name list hasn't shrunk.
    expected_core = {
        "debrid_api_key", "trakt_access_token", "simkl_access_token",
        "claude_api_key", "jellyfin_api_key", "auth_refresh_token",
        "kodi_hosts_json",
    }
    assert expected_core.issubset(FORBIDDEN_ACCOUNT_SETTINGS_KEYS)


# ── Router integration ───────────────────────────────────────────────


def _cleanup_row(db, uid):
    db.query(AccountSettings).filter(AccountSettings.user_id == uid).delete()
    db.commit()


def test_patch_strips_secrets_before_persistence(client, test_user, db):
    try:
        r = client.patch(
            "/me/account-settings",
            headers=_auth(test_user),
            json={
                "settings": {
                    "language": "de",
                    "debrid_api_key": "SHOULD-NOT-PERSIST",
                    "trakt_access_token": "ALSO-NOT",
                },
            },
        )
        assert r.status_code == 200, r.text
        body = r.json()
        # Server-side storage — neither secret should appear.
        assert "debrid_api_key" not in body["settings"]
        assert "trakt_access_token" not in body["settings"]
        assert body["settings"]["language"] == "de"

        # Confirm the DB row does not contain them either.
        row = (
            db.query(AccountSettings)
            .filter(AccountSettings.user_id == test_user.id)
            .first()
        )
        assert "debrid_api_key" not in row.settings
        assert "trakt_access_token" not in row.settings
    finally:
        _cleanup_row(db, test_user.id)


def test_get_scrubs_legacy_row_and_persists_back(client, test_user, db):
    # Seed a legacy row directly in the DB containing secrets (simulating
    # data written before the policy landed).
    row = AccountSettings(
        user_id=test_user.id,
        settings={
            "language": "en",
            "trakt_refresh_token": "LEGACY-LEAKED",
            "my_custom_api_key": "LEGACY-PATTERN",
        },
    )
    db.add(row)
    db.commit()

    try:
        r = client.get("/me/account-settings", headers=_auth(test_user))
        assert r.status_code == 200
        body = r.json()
        assert "trakt_refresh_token" not in body["settings"]
        assert "my_custom_api_key" not in body["settings"]
        assert body["settings"]["language"] == "en"

        # Persisted back — a second fetch finds a clean row without
        # re-scrubbing. Load fresh from DB.
        db.expire_all()
        fresh = (
            db.query(AccountSettings)
            .filter(AccountSettings.user_id == test_user.id)
            .first()
        )
        assert "trakt_refresh_token" not in fresh.settings
        assert "my_custom_api_key" not in fresh.settings
    finally:
        _cleanup_row(db, test_user.id)


def test_pattern_match_catches_unlisted_key(client, test_user, db):
    # A key not in the explicit list but matching the regex must still
    # be dropped on PATCH.
    try:
        r = client.patch(
            "/me/account-settings",
            headers=_auth(test_user),
            json={
                "settings": {
                    "language": "en",
                    "future_provider_api_key": "SHOULD-NOT-PERSIST",
                },
            },
        )
        assert r.status_code == 200
        assert "future_provider_api_key" not in r.json()["settings"]
    finally:
        _cleanup_row(db, test_user.id)
