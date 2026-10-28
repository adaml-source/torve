"""Config lifecycle tests for the NzbDAV integration.

Covers:
- POST /integrations/nzbdav/test success + failures (bad scheme, unreachable)
- PUT  /integrations/nzbdav/config stores encrypted secret (round-trip)
- GET  /integrations/nzbdav/status for configured vs unconfigured users
- DELETE /integrations/nzbdav/config
- SSRF private-host rejection
"""
from __future__ import annotations

import pytest

from app.crypto import decrypt_secret
from app.models import NzbdavConfig
from app.nzbdav import account_store as acc_mod
from app.nzbdav import client as client_mod
from app.nzbdav.client import ConnectionTestResult
from tests._nzbdav_common import (
    auth_header,
    cleanup_user,
    ensure_nzbdav_schema,
    make_premium_user,
    patch_validator_passthrough,
    reset_caches,
)


@pytest.fixture(autouse=True, scope="module")
def _schema():
    ensure_nzbdav_schema()


@pytest.fixture(autouse=True)
def _reset_caches():
    reset_caches()
    yield
    reset_caches()


@pytest.fixture()
def nzbdav_user(db):
    user = make_premium_user(db, "nzbdav-config")
    yield user
    cleanup_user(db, user)


class _FakeOkClient:
    def __init__(self, *, base_url: str, api_key: str) -> None:
        self.base_url = base_url
        self.api_key = api_key

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return False

    async def test_connection(self):
        return ConnectionTestResult(
            ok=True, version_string="1.2.3", capabilities={"webdav": True}
        )


class _FakeAuthFailClient(_FakeOkClient):
    async def test_connection(self):
        from app.nzbdav.failures import FailureCode, UpstreamError
        raise UpstreamError(FailureCode.AUTH_INVALID, detail="401")


def _patch_client(monkeypatch, klass):
    # Patch in the router module namespace + bypass the SSRF/DNS validator
    # so tests can use an inert host without network calls.
    import app.routers.nzbdav as router_mod
    monkeypatch.setattr(router_mod, "NzbdavClient", klass)
    patch_validator_passthrough(monkeypatch)


def test_test_endpoint_success(client, nzbdav_user, monkeypatch):
    _patch_client(monkeypatch, _FakeOkClient)
    r = client.post(
        "/integrations/nzbdav/test",
        json={"base_url": "https://nzbdav.example.com", "api_key": "k"},
        headers=auth_header(nzbdav_user),
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["ok"] is True
    # Version 1.2.3 is >= NZBDAV_MIN_KNOWN_GOOD default 0.0.0
    assert body["degraded"] is False


def test_test_endpoint_bad_scheme(client, nzbdav_user, monkeypatch):
    # Don't even reach the client — base_url validation rejects.
    _patch_client(monkeypatch, _FakeOkClient)
    r = client.post(
        "/integrations/nzbdav/test",
        json={"base_url": "file:///etc/passwd", "api_key": "k"},
        headers=auth_header(nzbdav_user),
    )
    assert r.status_code == 200
    body = r.json()
    assert body["ok"] is False
    assert body["reason"] == "UPSTREAM_UNREACHABLE"


def test_test_endpoint_auth_failure(client, nzbdav_user, monkeypatch):
    _patch_client(monkeypatch, _FakeAuthFailClient)
    r = client.post(
        "/integrations/nzbdav/test",
        json={"base_url": "https://nzbdav.example.com", "api_key": "bad"},
        headers=auth_header(nzbdav_user),
    )
    body = r.json()
    assert body["ok"] is False
    assert body["reason"] == "AUTH_INVALID"


def test_save_config_encrypts_secret(client, db, nzbdav_user, monkeypatch):
    _patch_client(monkeypatch, _FakeOkClient)
    r = client.put(
        "/integrations/nzbdav/config",
        json={"base_url": "https://nzbdav.example.com", "api_key": "s3cret-key"},
        headers=auth_header(nzbdav_user),
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["configured"] is True
    assert body["is_enabled"] is True

    # Round-trip: the encrypted blob decrypts to the plaintext secret, and
    # the plaintext secret is NOT in the DB.
    row = (
        db.query(NzbdavConfig)
        .filter(NzbdavConfig.user_id == nzbdav_user.id)
        .one()
    )
    assert row.api_key_encrypted != "s3cret-key"
    assert "s3cret-key" not in row.api_key_encrypted
    assert decrypt_secret(row.api_key_encrypted) == "s3cret-key"

    # Save response must NOT include the api key in any form.
    assert "s3cret-key" not in r.text
    assert "api_key" not in body


def test_status_unconfigured(client, nzbdav_user):
    r = client.get(
        "/integrations/nzbdav/status", headers=auth_header(nzbdav_user)
    )
    assert r.status_code == 200
    assert r.json() == {
        "configured": False,
        "is_enabled": False,
        "last_tested_at": None,
        "last_healthy_at": None,
        "degraded": False,
        "reason": None,
    }


def test_delete_config_removes_row(client, db, nzbdav_user, monkeypatch):
    _patch_client(monkeypatch, _FakeOkClient)
    client.put(
        "/integrations/nzbdav/config",
        json={"base_url": "https://nzbdav.example.com", "api_key": "k"},
        headers=auth_header(nzbdav_user),
    )
    assert db.query(NzbdavConfig).filter_by(user_id=nzbdav_user.id).count() == 1

    r = client.delete(
        "/integrations/nzbdav/config", headers=auth_header(nzbdav_user)
    )
    assert r.status_code == 204
    assert db.query(NzbdavConfig).filter_by(user_id=nzbdav_user.id).count() == 0


def test_save_config_rejects_private_host(client, nzbdav_user, monkeypatch):
    _patch_client(monkeypatch, _FakeOkClient)
    r = client.put(
        "/integrations/nzbdav/config",
        json={"base_url": "http://10.0.0.1", "api_key": "k"},
        headers=auth_header(nzbdav_user),
    )
    assert r.status_code == 400
    assert r.json()["detail"]["code"] == "UPSTREAM_UNREACHABLE"


def test_degraded_version_marks_response(client, nzbdav_user, monkeypatch):
    # Simulate an upstream with a version below the floor by patching the
    # setting AND returning an old version. Use a value > 0.0.0 floor.
    from app.config import settings as live_settings
    monkeypatch.setattr(live_settings, "NZBDAV_MIN_KNOWN_GOOD", "5.0.0")

    class _OldVer(_FakeOkClient):
        async def test_connection(self):
            return ConnectionTestResult(
                ok=True, version_string="1.0.0", capabilities=None
            )

    _patch_client(monkeypatch, _OldVer)
    r = client.post(
        "/integrations/nzbdav/test",
        json={"base_url": "https://nzbdav.example.com", "api_key": "k"},
        headers=auth_header(nzbdav_user),
    )
    body = r.json()
    assert body["ok"] is True
    assert body["degraded"] is True
    assert body["reason"] == "upstream_version_below_min_known_good"
