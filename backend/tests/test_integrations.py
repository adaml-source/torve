"""Tests for account-scoped integration credential management with storage modes."""
from fastapi import HTTPException

from app.security import create_access_token


def _device_headers(headers, device):
    return {**headers, "X-Torve-Installation-Id": device.installation_id}


def _detail_code(response):
    detail = response.json().get("detail")
    if isinstance(detail, dict):
        return detail.get("code") or detail.get("error_code")
    return detail


def test_list_integrations_empty(client, test_user, auth_header):
    r = client.get("/me/integrations", headers=auth_header)
    assert r.status_code == 200
    assert r.json() == []


def test_save_account_mode_and_list(client, test_user, auth_header):
    r = client.put("/me/integrations/real_debrid", headers=auth_header, json={
        "integration_type": "real_debrid",
        "credentials": {"api_key": "abc123secret"},
        "config": {"preferred_quality": "1080p"},
        "display_identifier": "user@example.com",
        "storage_mode": "account",
    })
    assert r.status_code == 200
    data = r.json()
    assert data["integration_type"] == "real_debrid"
    assert data["storage_mode"] == "account"
    assert data["has_credentials"] is True
    assert data["is_connected"] is True
    # Must NOT contain raw credentials
    assert "credentials" not in data
    assert "encrypted_credentials" not in data

    # List
    r2 = client.get("/me/integrations", headers=auth_header)
    assert len(r2.json()) == 1
    assert r2.json()[0]["storage_mode"] == "account"
    assert r2.json()[0]["has_credentials"] is True


def test_save_device_only_mode(client, test_user, auth_header):
    r = client.put("/me/integrations/custom_addon", headers=auth_header, json={
        "integration_type": "custom_addon",
        "credentials": {},
        "config": {"server_url": "http://myserver:8080"},
        "display_identifier": "My local addon",
        "storage_mode": "device_only",
    })
    assert r.status_code == 200
    data = r.json()
    assert data["storage_mode"] == "device_only"
    assert data["has_credentials"] is False
    assert data["config"]["server_url"] == "http://myserver:8080"


def test_device_only_credentials_returns_empty(client, test_user, auth_header, tv_device):
    client.put("/me/integrations/local_addon", headers=auth_header, json={
        "integration_type": "local_addon",
        "credentials": {},
        "config": {"port": 9090},
        "storage_mode": "device_only",
    })
    r = client.get(
        "/me/integrations/local_addon/credentials",
        headers=_device_headers(auth_header, tv_device),
    )
    assert r.status_code == 200
    assert r.json()["storage_mode"] == "device_only"
    assert r.json()["credentials"] == {}


def test_account_mode_credentials_returns_decrypted(client, test_user, auth_header, tv_device):
    client.put("/me/integrations/real_debrid", headers=auth_header, json={
        "integration_type": "real_debrid",
        "credentials": {"api_key": "my_secret_key_123"},
        "storage_mode": "account",
    })
    r = client.get(
        "/me/integrations/real_debrid/credentials",
        headers=_device_headers(auth_header, tv_device),
    )
    assert r.status_code == 200
    assert r.json()["storage_mode"] == "account"
    assert r.json()["credentials"]["api_key"] == "my_secret_key_123"


def test_credentials_restore_requires_registered_device(client, test_user, auth_header):
    client.put("/me/integrations/real_debrid", headers=auth_header, json={
        "integration_type": "real_debrid",
        "credentials": {"api_key": "stored_secret"},
        "storage_mode": "account",
    })
    r = client.get("/me/integrations/real_debrid/credentials", headers=auth_header)
    assert r.status_code == 403
    assert _detail_code(r) == "device_required"
    assert "stored_secret" not in r.text


def test_credentials_restore_rejects_other_user_device(
    client, test_user, test_user_b, auth_header, phone_device_b
):
    client.put("/me/integrations/real_debrid", headers=auth_header, json={
        "integration_type": "real_debrid",
        "credentials": {"api_key": "stored_secret"},
        "storage_mode": "account",
    })
    r = client.get(
        "/me/integrations/real_debrid/credentials",
        headers=_device_headers(auth_header, phone_device_b),
    )
    assert r.status_code == 403
    assert _detail_code(r) == "device_not_authorized"
    assert "stored_secret" not in r.text


def test_credentials_restore_rate_limited(
    client, test_user, auth_header, tv_device, monkeypatch
):
    client.put("/me/integrations/real_debrid", headers=auth_header, json={
        "integration_type": "real_debrid",
        "credentials": {"api_key": "stored_secret"},
        "storage_mode": "account",
    })

    def fake_limit(**kwargs):
        assert kwargs["category"] == "credential_restore_integration"
        raise HTTPException(
            status_code=429,
            detail={
                "error_code": "rate_limited",
                "message": "Too many requests. Please try again later.",
            },
        )

    monkeypatch.setattr("app.routers.integrations.enforce_rate_limit", fake_limit)
    r = client.get(
        "/me/integrations/real_debrid/credentials",
        headers=_device_headers(auth_header, tv_device),
    )
    assert r.status_code == 429
    assert _detail_code(r) == "rate_limited"
    assert "stored_secret" not in r.text


def test_credentials_restore_logs_are_sanitized(
    client, test_user, auth_header, tv_device, caplog
):
    secret = "do_not_log_integration_secret"
    client.put("/me/integrations/real_debrid", headers=auth_header, json={
        "integration_type": "real_debrid",
        "credentials": {"api_key": secret},
        "storage_mode": "account",
    })
    caplog.set_level("INFO", logger="app.routers.integrations")

    r = client.get(
        "/me/integrations/real_debrid/credentials",
        headers=_device_headers(auth_header, tv_device),
    )
    assert r.status_code == 200
    assert r.json()["credentials"]["api_key"] == secret
    assert "CREDENTIAL_RESTORE kind=integration" in caplog.text
    assert secret not in caplog.text


def test_credentials_restore_decrypt_failure_is_sanitized(
    client, test_user, auth_header, tv_device, db
):
    from app.models import UserIntegration

    db.add(UserIntegration(
        user_id=test_user.id,
        integration_type="broken_restore",
        storage_mode="account",
        encrypted_credentials="not-a-valid-encrypted-secret",
        config={},
        is_connected=True,
    ))
    db.commit()

    r = client.get(
        "/me/integrations/broken_restore/credentials",
        headers=_device_headers(auth_header, tv_device),
    )
    assert r.status_code == 500
    assert _detail_code(r) == "credential_restore_failed"
    assert "not-a-valid-encrypted-secret" not in r.text


def test_account_mode_requires_credentials(client, test_user, auth_header):
    r = client.put("/me/integrations/trakt", headers=auth_header, json={
        "integration_type": "trakt",
        "credentials": {},
        "storage_mode": "account",
    })
    assert r.status_code == 400


def test_device_only_ignores_sent_credentials(client, test_user, auth_header, tv_device):
    """Even if client accidentally sends credentials with device_only, they are not stored."""
    client.put("/me/integrations/test_addon", headers=auth_header, json={
        "integration_type": "test_addon",
        "credentials": {"secret": "should_not_be_stored"},
        "storage_mode": "device_only",
    })
    r = client.get(
        "/me/integrations/test_addon/credentials",
        headers=_device_headers(auth_header, tv_device),
    )
    assert r.json()["credentials"] == {}


def test_update_integration(client, test_user, auth_header):
    client.put("/me/integrations/trakt", headers=auth_header, json={
        "integration_type": "trakt",
        "credentials": {"oauth_token": "old_token"},
        "storage_mode": "account",
    })
    r = client.put("/me/integrations/trakt", headers=auth_header, json={
        "integration_type": "trakt",
        "credentials": {"oauth_token": "new_token"},
        "display_identifier": "trakt_updated",
        "storage_mode": "account",
    })
    assert r.status_code == 200
    assert r.json()["display_identifier"] == "trakt_updated"

    # Only one row
    r2 = client.get("/me/integrations", headers=auth_header)
    trakt = [i for i in r2.json() if i["integration_type"] == "trakt"]
    assert len(trakt) == 1


def test_switch_mode_account_to_device_only(client, test_user, auth_header, tv_device):
    """Switching from account to device_only should clear stored credentials."""
    client.put("/me/integrations/switchable", headers=auth_header, json={
        "integration_type": "switchable",
        "credentials": {"key": "secret"},
        "storage_mode": "account",
    })
    r = client.get("/me/integrations/switchable", headers=auth_header)
    assert r.json()["has_credentials"] is True

    # Switch to device_only
    client.put("/me/integrations/switchable", headers=auth_header, json={
        "integration_type": "switchable",
        "credentials": {},
        "storage_mode": "device_only",
    })
    r2 = client.get("/me/integrations/switchable", headers=auth_header)
    assert r2.json()["storage_mode"] == "device_only"
    assert r2.json()["has_credentials"] is False

    r3 = client.get(
        "/me/integrations/switchable/credentials",
        headers=_device_headers(auth_header, tv_device),
    )
    assert r3.json()["credentials"] == {}


def test_remove_integration(client, test_user, auth_header):
    client.put("/me/integrations/simkl", headers=auth_header, json={
        "integration_type": "simkl",
        "credentials": {"token": "simkl_token"},
        "storage_mode": "account",
    })
    r = client.delete("/me/integrations/simkl", headers=auth_header)
    assert r.status_code == 204

    r2 = client.get("/me/integrations/simkl", headers=auth_header)
    assert r2.status_code == 404


def test_test_account_integration(client, test_user, auth_header):
    client.put("/me/integrations/orion", headers=auth_header, json={
        "integration_type": "orion",
        "credentials": {"api_key": "orion_key"},
        "storage_mode": "account",
    })
    r = client.post("/me/integrations/orion/test", headers=auth_header)
    assert r.status_code == 200
    assert r.json()["success"] is True


def test_test_device_only_integration(client, test_user, auth_header):
    client.put("/me/integrations/local_srv", headers=auth_header, json={
        "integration_type": "local_srv",
        "credentials": {},
        "config": {"url": "http://localhost"},
        "storage_mode": "device_only",
    })
    r = client.post("/me/integrations/local_srv/test", headers=auth_header)
    assert r.status_code == 200
    assert r.json()["success"] is True
    assert "device" in r.json()["message"].lower()


def test_user_isolation(client, test_user, test_user_b, auth_header, auth_header_b, phone_device_b):
    client.put("/me/integrations/real_debrid", headers=auth_header, json={
        "integration_type": "real_debrid",
        "credentials": {"api_key": "user_a_key"},
        "storage_mode": "account",
    })
    r = client.get("/me/integrations", headers=auth_header_b)
    assert len(r.json()) == 0

    r2 = client.get(
        "/me/integrations/real_debrid/credentials",
        headers=_device_headers(auth_header_b, phone_device_b),
    )
    assert r2.status_code == 404


def test_integration_survives_reauth(client, test_user, db, tv_device):
    token1 = create_access_token(str(test_user.id))
    headers1 = {"Authorization": f"Bearer {token1}"}

    client.put("/me/integrations/trakt", headers=headers1, json={
        "integration_type": "trakt",
        "credentials": {"token": "trakt_oauth"},
        "storage_mode": "account",
    })

    token2 = create_access_token(str(test_user.id))
    headers2 = {"Authorization": f"Bearer {token2}"}

    r = client.get("/me/integrations", headers=headers2)
    assert any(i["integration_type"] == "trakt" for i in r.json())

    r2 = client.get(
        "/me/integrations/trakt/credentials",
        headers=_device_headers(headers2, tv_device),
    )
    assert r2.json()["credentials"]["token"] == "trakt_oauth"


def test_default_storage_mode_is_account(client, test_user, auth_header):
    """If client omits storage_mode, it defaults to account."""
    r = client.put("/me/integrations/tmdb", headers=auth_header, json={
        "integration_type": "tmdb",
        "credentials": {"api_key": "tmdb_key"},
    })
    assert r.status_code == 200
    assert r.json()["storage_mode"] == "account"
    assert r.json()["has_credentials"] is True


def test_invalid_storage_mode_rejected(client, test_user, auth_header):
    r = client.put("/me/integrations/test", headers=auth_header, json={
        "integration_type": "test",
        "credentials": {"k": "v"},
        "storage_mode": "invalid_mode",
    })
    assert r.status_code == 422


def test_requires_auth(client):
    r = client.get("/me/integrations")
    assert r.status_code == 403
    r2 = client.get("/me/integrations/real_debrid/credentials")
    assert r2.status_code == 403


def test_type_mismatch_rejected(client, test_user, auth_header):
    r = client.put("/me/integrations/real_debrid", headers=auth_header, json={
        "integration_type": "trakt",
        "credentials": {"k": "v"},
    })
    assert r.status_code == 400


# ── PATCH credentials (partial merge) ──────────────────────────────────


def test_patch_merges_new_keys_preserves_existing(client, test_user, auth_header, tv_device):
    """Real motivating scenario: PANDA_TOKEN row has only {token: ...}
    from the original Android create flow; the desktop's recovery banner
    posts {management_token: ...} via PATCH to fill the hole. Both
    fields must be present after."""
    client.put("/me/integrations/PANDA_TOKEN", headers=auth_header, json={
        "integration_type": "PANDA_TOKEN",
        "credentials": {"token": "manifest_token_xyz"},
        "storage_mode": "account",
    })
    r = client.patch("/me/integrations/PANDA_TOKEN/credentials",
                     headers=auth_header,
                     json={"credentials": {"management_token": "mgmt_abc"}})
    assert r.status_code == 200, r.text

    creds = client.get("/me/integrations/PANDA_TOKEN/credentials",
                       headers=_device_headers(auth_header, tv_device)).json()["credentials"]
    assert creds == {"token": "manifest_token_xyz", "management_token": "mgmt_abc"}


def test_patch_overwrites_matching_key(client, test_user, auth_header, tv_device):
    client.put("/me/integrations/trakt", headers=auth_header, json={
        "integration_type": "trakt",
        "credentials": {"access_token": "old", "refresh_token": "rt"},
        "storage_mode": "account",
    })
    r = client.patch("/me/integrations/trakt/credentials",
                     headers=auth_header,
                     json={"credentials": {"access_token": "new"}})
    assert r.status_code == 200

    creds = client.get("/me/integrations/trakt/credentials",
                       headers=_device_headers(auth_header, tv_device)).json()["credentials"]
    assert creds["access_token"] == "new"
    assert creds["refresh_token"] == "rt"


def test_patch_empty_credentials_is_noop(client, test_user, auth_header, tv_device):
    client.put("/me/integrations/tmdb", headers=auth_header, json={
        "integration_type": "tmdb",
        "credentials": {"api_key": "k1"},
        "storage_mode": "account",
    })
    r = client.patch("/me/integrations/tmdb/credentials",
                     headers=auth_header, json={"credentials": {}})
    assert r.status_code == 200

    creds = client.get("/me/integrations/tmdb/credentials",
                       headers=_device_headers(auth_header, tv_device)).json()["credentials"]
    assert creds == {"api_key": "k1"}


def test_patch_missing_integration_returns_404(client, test_user, auth_header):
    r = client.patch("/me/integrations/never_saved/credentials",
                     headers=auth_header,
                     json={"credentials": {"key": "value"}})
    assert r.status_code == 404


def test_patch_device_only_rejected(client, test_user, auth_header):
    """Device-only integrations have no server-side blob to merge into.
    Reject so the caller knows to PUT in account mode instead of
    silently doing nothing."""
    client.put("/me/integrations/local_addon", headers=auth_header, json={
        "integration_type": "local_addon",
        "config": {"port": 9090},
        "storage_mode": "device_only",
    })
    r = client.patch("/me/integrations/local_addon/credentials",
                     headers=auth_header,
                     json={"credentials": {"key": "v"}})
    assert r.status_code == 400


def test_patch_requires_auth(client):
    r = client.patch("/me/integrations/anything/credentials",
                     json={"credentials": {"k": "v"}})
    assert r.status_code in (401, 403)


def test_patch_user_isolation(client, test_user, test_user_b, auth_header_b, db):
    """User B cannot patch user A's integration. The two are different
    users with different auth headers; the URL path is the same."""
    from app.models import UserIntegration
    from app.crypto import encrypt_secret
    import json as _json
    db.add(UserIntegration(
        user_id=test_user.id,
        integration_type="trakt",
        storage_mode="account",
        encrypted_credentials=encrypt_secret(_json.dumps({"access_token": "owner_token"})),
        config={},
        is_connected=True,
    ))
    db.commit()
    r = client.patch("/me/integrations/trakt/credentials",
                     headers=auth_header_b,
                     json={"credentials": {"access_token": "stolen"}})
    assert r.status_code == 404

    db.query(UserIntegration).filter(UserIntegration.user_id == test_user.id).delete()
    db.commit()
