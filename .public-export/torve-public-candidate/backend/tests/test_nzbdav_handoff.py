"""Handoff token + proxy tests for the NzbDAV integration."""
from __future__ import annotations

import asyncio
import time
import uuid

import pytest

from app.nzbdav.handoff import (
    HandoffClaims,
    HandoffError,
    mint,
    verify,
)
from app.nzbdav.release_cache import release_cache
from tests._nzbdav_common import (
    auth_header,
    cleanup_user,
    ensure_nzbdav_schema,
    make_premium_user,
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
    user = make_premium_user(db, "nzbdav-handoff")
    yield user
    cleanup_user(db, user)


def test_mint_verify_round_trip():
    uid = str(uuid.uuid4())
    did = str(uuid.uuid4())
    sid = "stream-abc"
    tok = mint(user_id=uid, device_id=did, content_id="tmdb:1", stream_id=sid)
    claims = verify(tok)
    assert claims.user_id == uid
    assert claims.device_id == did
    assert claims.content_id == "tmdb:1"
    assert claims.stream_id == sid
    assert isinstance(claims.issued_at, int)
    assert claims.jti
    assert isinstance(claims, HandoffClaims)


def test_expired_token_rejected():
    uid = str(uuid.uuid4())
    tok = mint(user_id=uid, stream_id="s", ttl_seconds=-1)
    with pytest.raises(HandoffError):
        verify(tok)


def test_tampered_signature_rejected():
    tok = mint(user_id="u1", stream_id="s1")
    payload, _sig = tok.split(".")
    bad = payload + ".YWJjZA"  # wrong signature
    with pytest.raises(HandoffError):
        verify(bad)


def test_different_user_token_rejected_on_handoff_endpoint(client, db, nzbdav_user):
    # Prime cache as if warming finished for user A
    other_uid = str(uuid.uuid4())
    release_cache.set_warm_success("H-HND", {
        "stream_id": "stream-xyz",
        "upstream_url": "https://upstream.example.com/f.mp4",
        "user_id": other_uid,
    })
    # Mint a token for user B (nzbdav_user) with the SAME stream_id.
    bad_tok = mint(user_id=str(nzbdav_user.id), stream_id="stream-xyz")
    r = client.get(f"/resolver/usenet/handoff/{bad_tok}")
    # Either 403 (user mismatch) or 404 depending on ordering; both are
    # acceptable "deny" outcomes that never leak upstream URL.
    assert r.status_code in (403, 404)
    assert "upstream.example.com" not in r.text


def test_proxy_streams_with_range_forwarding(client, monkeypatch, nzbdav_user):
    uid = str(nzbdav_user.id)
    release_cache.set_warm_success("H-PROXY", {
        "stream_id": "stream-proxy",
        "upstream_url": "https://upstream.example.com/v.mp4",
        "user_id": uid,
    })
    tok = mint(user_id=uid, stream_id="stream-proxy")

    captured = {}

    class _FakeResp:
        def __init__(self):
            self.status_code = 206
            self.headers = {
                "content-type": "video/mp4",
                "content-range": "bytes 0-99/1000",
                "content-length": "100",
                "accept-ranges": "bytes",
            }

        async def aiter_raw(self):
            yield b"hello-bytes"

        async def aclose(self): ...

    class _FakeClient:
        def __init__(self, *a, **kw):
            pass

        def build_request(self, method, url, headers=None):
            captured["url"] = url
            captured["headers"] = dict(headers or {})
            return ("req", url)

        async def send(self, req, stream=False):
            return _FakeResp()

        async def aclose(self): ...

    import app.routers.nzbdav as rmod
    monkeypatch.setattr(rmod, "httpx", type("x", (), {
        "AsyncClient": _FakeClient,
        "Timeout": lambda **kw: None,
        "TimeoutException": Exception,
        "HTTPError": Exception,
    }))

    r = client.get(
        f"/resolver/usenet/handoff/{tok}",
        headers={"Range": "bytes=0-99"},
    )
    assert r.status_code == 206
    assert r.content == b"hello-bytes"
    # Range header was forwarded to upstream.
    assert captured["headers"].get("Range") == "bytes=0-99"
    # Upstream URL was used server-side but is not in the response body.
    assert "upstream.example.com" not in r.text
