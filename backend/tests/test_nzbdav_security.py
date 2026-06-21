"""Security hardening tests for the NzbDAV integration.

Covers:
- No raw upstream URL in any API response
- No decrypted secret in any log line
- SSRF rejects 127.0.0.1 / 10.0.0.1 / 169.254.x.x / 0.0.0.0
- Non-http schemes rejected
- Over-length base URLs rejected
"""
from __future__ import annotations

import logging

import pytest

from app.nzbdav.client import NzbdavClient, validate_base_url
from app.nzbdav.failures import FailureCode, UpstreamError
from app.nzbdav.release_cache import release_cache
from app.nzbdav.resolve_service import resolve_service
from app.nzbdav.warm_service import WarmCandidateInput
from tests._nzbdav_common import (
    auth_device_header,
    auth_header,
    cleanup_user,
    ensure_nzbdav_schema,
    make_device,
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
    user = make_premium_user(db, "nzbdav-sec")
    yield user
    cleanup_user(db, user)


@pytest.mark.parametrize("url", [
    "http://127.0.0.1",
    "http://127.0.0.1:8080",
    "http://10.0.0.1",
    "http://169.254.169.254",  # link-local / cloud metadata
    "http://0.0.0.0",
    "http://[::1]",
])
def test_ssrf_rejects_private_hosts(url):
    with pytest.raises(UpstreamError) as ei:
        validate_base_url(url)
    assert ei.value.code == FailureCode.UPSTREAM_UNREACHABLE


@pytest.mark.parametrize("url", [
    "file:///etc/passwd",
    "ftp://example.com",
    "gopher://example.com",
    "javascript:alert(1)",
])
def test_ssrf_rejects_non_http_schemes(url):
    with pytest.raises(UpstreamError):
        validate_base_url(url)


def test_ssrf_rejects_overlong_url():
    too_long = "http://example.com/" + ("a" * 3000)
    with pytest.raises(UpstreamError):
        validate_base_url(too_long)


def test_client_constructor_enforces_ssrf():
    with pytest.raises(UpstreamError):
        NzbdavClient(base_url="http://127.0.0.1", api_key="k")


def test_ssrf_allow_private_when_flag_set(monkeypatch):
    from app.config import settings as live_settings
    monkeypatch.setattr(live_settings, "NZBDAV_ALLOW_PRIVATE_HOSTS", True)
    # Should NOT raise now.
    out = validate_base_url("http://127.0.0.1:9000/")
    assert out == "http://127.0.0.1:9000"


def test_no_raw_upstream_url_in_resolve_ready(client, db, nzbdav_user):
    import asyncio
    from app.nzbdav.account_store import account_store
    device = make_device(db, nzbdav_user)
    account_store.save(
        db, user_id=nzbdav_user.id,
        base_url="https://nzbdav.example.com", api_key_plaintext="k",
    )
    release_cache.set_warm_success("H-SEC1", {
        "stream_id": "sid-sec1",
        "upstream_url": "https://super-secret-upstream.example/raw.mp4?token=hidden",
        "user_id": str(nzbdav_user.id),
    })
    r = client.post(
        "/resolver/usenet/resolve",
        json={
            "content_id": "tmdb:sec",
            "candidate": {"candidate_id": "c1", "hash_key": "H-SEC1"},
        },
        headers=auth_device_header(nzbdav_user, device),
    )
    assert r.status_code == 200, r.text
    body = r.text
    assert "super-secret-upstream" not in body
    assert "token=hidden" not in body
    # The handoff URL should be present.
    assert "/resolver/usenet/handoff/" in r.json()["stream"]["url"]


def test_resolver_requires_registered_device_header(client, db, nzbdav_user):
    import asyncio
    from app.nzbdav.account_store import account_store

    account_store.save(
        db, user_id=nzbdav_user.id,
        base_url="https://nzbdav.example.com", api_key_plaintext="k",
    )
    release_cache.set_warm_success("H-NODEVICE", {
        "stream_id": "sid-nodevice",
        "upstream_url": "https://upstream.example.com/raw.mp4",
        "user_id": str(nzbdav_user.id),
    })
    r = client.post(
        "/resolver/usenet/resolve",
        json={
            "content_id": "tmdb:nodevice",
            "candidate": {"candidate_id": "c1", "hash_key": "H-NODEVICE"},
        },
        headers=auth_header(nzbdav_user),
    )
    assert r.status_code == 403
    assert r.json()["detail"]["code"] == "device_required"


def test_no_secret_in_logs(client, db, nzbdav_user, monkeypatch, caplog):
    """Save a config and verify the api_key never appears in captured logs."""
    from app.nzbdav.client import ConnectionTestResult
    import app.routers.nzbdav as router_mod

    secret = "VERY-SPECIFIC-SECRET-VALUE-XYZ"

    class _FakeOk:
        def __init__(self, *, base_url, api_key):
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, *a):
            return False

        async def test_connection(self):
            return ConnectionTestResult(ok=True, version_string="1.0.0", capabilities=None)

    monkeypatch.setattr(router_mod, "NzbdavClient", _FakeOk)
    patch_validator_passthrough(monkeypatch)

    caplog.set_level(logging.DEBUG)
    r = client.put(
        "/integrations/nzbdav/config",
        json={"base_url": "https://nzbdav.example.com", "api_key": secret},
        headers=auth_header(nzbdav_user),
    )
    assert r.status_code == 200, r.text
    # No log record anywhere contains the plaintext secret.
    for rec in caplog.records:
        msg = rec.getMessage()
        assert secret not in msg, f"secret leaked in log: {rec.name}: {msg}"
    # And no response body contains it.
    assert secret not in r.text
