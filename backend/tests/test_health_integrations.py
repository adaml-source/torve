"""
Tests for GET /me/health/integrations.

Uses the conftest fixtures (test_user + db + client). Probes are mocked
at the httpx client level so we never touch real upstreams.
"""
from __future__ import annotations

import uuid
from unittest.mock import AsyncMock, patch

import httpx
import pytest

from app.crypto import encrypt_secret
from app.models import NzbdavConfig, UserAddon
from app.nzbdav.client import ConnectionTestResult
from app.nzbdav.failures import FailureCode, UpstreamError
from app.routers.health_integrations import _CACHE
from app.security import create_access_token


def _auth(user):
    return {"Authorization": f"Bearer {create_access_token(str(user.id))}"}


@pytest.fixture(autouse=True)
def _clear_cache():
    _CACHE.clear()
    yield
    _CACHE.clear()


def _mk_addon(db, user, *, name, manifest_url, enabled=True):
    row = UserAddon(
        id=uuid.uuid4(),
        user_id=user.id,
        manifest_url=manifest_url,
        name=name,
        addon_id=f"com.test.{name.lower()}",
        has_streams=True,
        is_enabled=enabled,
    )
    db.add(row)
    db.commit()
    return row


def _mk_nzbdav(db, user, *, base_url="https://nzbdav.example.com", enabled=True):
    row = NzbdavConfig(
        id=uuid.uuid4(),
        user_id=user.id,
        base_url=base_url,
        api_key_encrypted=encrypt_secret("dummy-key"),
        is_enabled=enabled,
    )
    db.add(row)
    db.commit()
    return row


class FakeResponse:
    def __init__(self, status_code: int):
        self.status_code = status_code


class FakeAsyncClient:
    """Context-manager stub for httpx.AsyncClient.get used by _probe_addon."""

    def __init__(self, *, responses: dict[str, int]):
        self._responses = responses

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, tb):
        return False

    async def get(self, url, *args, **kwargs):
        if url in self._responses:
            code = self._responses[url]
            if code == -1:
                raise httpx.TimeoutException("simulated timeout")
            if code == -2:
                raise httpx.ConnectError("simulated connect error")
            return FakeResponse(code)
        return FakeResponse(200)


def test_no_integrations_returns_not_configured_nzbdav(client, test_user, db):
    r = client.get("/me/health/integrations", headers=_auth(test_user))
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["overall"] == "healthy"
    # Always-present entries when nothing else is configured: nzbdav,
    # google_play, and stripe (global readiness signals).
    kinds = [i["kind"] for i in body["integrations"]]
    assert "nzbdav" in kinds
    assert "google_play" in kinds
    assert "stripe" in kinds
    nz = next(i for i in body["integrations"] if i["kind"] == "nzbdav")
    assert nz["status"] == "not_configured"


def test_healthy_addon_and_nzbdav(client, test_user, db):
    _mk_addon(db, test_user,
              name="Panda",
              manifest_url="https://panda.torve.app/u/tok123/manifest.json")
    _mk_nzbdav(db, test_user)

    fake = FakeAsyncClient(responses={
        "https://panda.torve.app/u/tok123/manifest.json": 200,
    })

    async def _ok_test():
        return ConnectionTestResult(ok=True, version_string="1.2.3", capabilities=None)

    with patch("app.routers.health_integrations.httpx.AsyncClient", return_value=fake), \
         patch("app.routers.health_integrations.NzbdavClient") as mc:
        inst = mc.return_value
        inst.__aenter__ = AsyncMock(return_value=inst)
        inst.__aexit__ = AsyncMock(return_value=False)
        inst.test_connection = AsyncMock(return_value=ConnectionTestResult(
            ok=True, version_string="1.2.3", capabilities=None,
        ))
        r = client.get("/me/health/integrations", headers=_auth(test_user))
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["overall"] == "healthy"
    # addon + nzbdav + google_play + stripe (global readiness)
    assert len(body["integrations"]) == 4
    addon = next(i for i in body["integrations"] if i["kind"] == "addon")
    assert addon["name"] == "Panda"
    assert addon["status"] == "healthy"
    assert addon["host"] == "panda.torve.app"
    # Raw manifest URL must NEVER be returned — only host.
    assert "tok123" not in r.text
    assert "/manifest.json" not in r.text
    nz = next(i for i in body["integrations"] if i["kind"] == "nzbdav")
    assert nz["status"] == "healthy"
    assert nz["version"] == "1.2.3"


def test_unreachable_addon_surfaces_unreachable(client, test_user, db):
    _mk_addon(db, test_user,
              name="Dead", manifest_url="https://dead.example/manifest.json")

    fake = FakeAsyncClient(responses={
        "https://dead.example/manifest.json": 503,
    })
    with patch("app.routers.health_integrations.httpx.AsyncClient", return_value=fake):
        r = client.get("/me/health/integrations", headers=_auth(test_user))
    assert r.status_code == 200
    body = r.json()
    addon = next(i for i in body["integrations"] if i["kind"] == "addon")
    assert addon["status"] == "unreachable"
    assert "503" in (addon["detail"] or "")
    assert body["overall"] == "unhealthy"


def test_addon_timeout_maps_to_unreachable(client, test_user, db):
    _mk_addon(db, test_user,
              name="Slow", manifest_url="https://slow.example/manifest.json")
    fake = FakeAsyncClient(responses={
        "https://slow.example/manifest.json": -1,   # signal timeout
    })
    with patch("app.routers.health_integrations.httpx.AsyncClient", return_value=fake):
        r = client.get("/me/health/integrations", headers=_auth(test_user))
    body = r.json()
    addon = next(i for i in body["integrations"] if i["kind"] == "addon")
    assert addon["status"] == "unreachable"
    assert addon["detail"] == "timeout"


def test_nzbdav_auth_invalid_maps_to_unauthorised(client, test_user, db):
    _mk_nzbdav(db, test_user)
    with patch("app.routers.health_integrations.NzbdavClient") as mc:
        inst = mc.return_value
        inst.__aenter__ = AsyncMock(return_value=inst)
        inst.__aexit__ = AsyncMock(return_value=False)
        inst.test_connection = AsyncMock(
            side_effect=UpstreamError(FailureCode.AUTH_INVALID, detail="bad key")
        )
        r = client.get("/me/health/integrations", headers=_auth(test_user))
    body = r.json()
    nz = next(i for i in body["integrations"] if i["kind"] == "nzbdav")
    assert nz["status"] == "unauthorised"
    assert nz["detail"] == "AUTH_INVALID"


def test_disabled_addon_not_probed(client, test_user, db):
    _mk_addon(db, test_user, name="On",
              manifest_url="https://on.example/manifest.json", enabled=True)
    _mk_addon(db, test_user, name="Off",
              manifest_url="https://off.example/manifest.json", enabled=False)
    calls = []

    class Recording(FakeAsyncClient):
        async def get(self, url, *a, **kw):
            calls.append(url)
            return FakeResponse(200)

    rec = Recording(responses={})
    with patch("app.routers.health_integrations.httpx.AsyncClient", return_value=rec):
        r = client.get("/me/health/integrations", headers=_auth(test_user))
    assert r.status_code == 200
    assert "off.example" not in " ".join(calls)
    body = r.json()
    # Exactly one addon entry.
    kinds = [i["kind"] for i in body["integrations"]]
    assert kinds.count("addon") == 1


def test_response_is_cached_per_user(client, test_user, db):
    _mk_addon(db, test_user, name="A",
              manifest_url="https://a.example/manifest.json")
    probe_count = {"n": 0}

    class Counting(FakeAsyncClient):
        async def get(self, url, *a, **kw):
            probe_count["n"] += 1
            return FakeResponse(200)

    with patch("app.routers.health_integrations.httpx.AsyncClient",
               return_value=Counting(responses={})):
        r1 = client.get("/me/health/integrations", headers=_auth(test_user))
        r2 = client.get("/me/health/integrations", headers=_auth(test_user))
    assert r1.status_code == 200 and r2.status_code == 200
    # Second call served from cache — probe fn not re-run.
    assert probe_count["n"] == 1
    # checked_at must be identical across the two cached responses.
    assert r1.json()["checked_at"] == r2.json()["checked_at"]


def test_unauthenticated_rejected(client):
    r = client.get("/me/health/integrations")
    # FastAPI HTTPBearer returns 403 on missing credential, 401 on invalid.
    assert r.status_code in (401, 403)
