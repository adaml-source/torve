import asyncio

import httpx
import pytest

from app.models import NzbdavConfig
from app.nzbdav.account_store import account_store
from app.nzbdav.backends import BackendConnection
from app.nzbdav.client import ConnectionTestResult, PollJobResult, SubmitJobResult
from app.nzbdav.release_cache import connection_scope, release_cache
from app.nzbdav.resolve_service import resolve_service
from app.nzbdav.warm_service import NzbdavWarmService, WarmCandidateInput
from tests._nzbdav_common import auth_header, cleanup_user, make_premium_user, patch_validator_passthrough


@pytest.fixture
def streaming_user(db):
    release_cache.reset()
    user = make_premium_user(db, "streaming-backends")
    yield user
    cleanup_user(db, user)
    release_cache.reset()


@pytest.mark.parametrize("kind", ["nzbdav", "infinidysk", "nzbdavex", "altmount", "decypharr"])
def test_save_profile_encrypts_dav_credentials_and_preserves_enabled(client, db, streaming_user, monkeypatch, kind):
    import app.routers.nzbdav as router
    seen = []

    class FakeClient:
        def __init__(self, **kwargs):
            seen.append(kwargs)
        async def __aenter__(self):
            return self
        async def __aexit__(self, *args):
            pass
        async def test_connection(self):
            return ConnectionTestResult(True, None, {"sabnzbd": True, "webdav": True})

    monkeypatch.setattr(router, "NzbdavClient", FakeClient)
    patch_validator_passthrough(monkeypatch)
    response = client.put("/integrations/nzbdav/config", headers=auth_header(streaming_user), json={
        "base_url": "https://stream.example", "api_key": "api-secret", "enabled": False,
        "connection": {"backend_type": kind, "webdav_username": "dav-user", "webdav_password": "dav-secret"},
    })
    assert response.status_code == 200, response.text
    assert response.json()["is_enabled"] is False
    cfg = account_store.get_for_user(db, streaming_user.id)
    assert "dav-secret" not in cfg.connection_options_encrypted
    assert "dav-user" not in cfg.connection_options_encrypted
    assert account_store.get_connection(cfg).webdav_password == "dav-secret"
    assert seen[0]["connection"].backend_type.value == kind
    assert all(secret not in response.text for secret in ("api-secret", "dav-secret", "dav-user"))


def test_warm_uses_selected_backend_and_handoff_keeps_credentials_server_side(client, db, streaming_user, monkeypatch):
    import app.routers.nzbdav as router
    patch_validator_passthrough(monkeypatch)
    cfg = account_store.save(db, user_id=streaming_user.id, base_url="https://stream.example", api_key_plaintext="api-secret",
        connection=BackendConnection(backend_type="decypharr", webdav_username="dav-user", webdav_password="dav-secret"))
    observed = []
    response_status = {"value": 206}

    class Upstream:
        async def submit_nzb(self, **kwargs):
            return SubmitJobResult("job-1")
        async def poll_job(self, **kwargs):
            return PollJobResult("ready", "ready", "https://stream.example/webdav/__all__/Release/movie.mkv", None)
        async def aclose(self):
            pass

    def factory(base_url, api_key, *, connection):
        assert connection.backend_type.value == "decypharr"
        assert connection.webdav_password == "dav-secret"
        return Upstream()

    candidate = WarmCandidateInput(candidate_id="candidate", hash_key="same-release", nzb_url="https://indexer.example/file.nzb")
    async def mount():
        await NzbdavWarmService(client_factory=factory).start_warm(db, user_id=streaming_user.id,
            content_id="tmdb:1", candidates=[candidate], top_n=1)
        pending = asyncio.all_tasks() - {asyncio.current_task()}
        await asyncio.gather(*pending)
        return await resolve_service.resolve(db, user_id=streaming_user.id, content_id="tmdb:1", candidate=candidate)
    result = asyncio.run(mount())
    assert result.simplified_state == "ready"
    assert "stream.example" not in result.stream_url and "secret" not in result.stream_url

    def stream(request):
        observed.append(request)
        assert request.headers["authorization"] == "Basic ZGF2LXVzZXI6ZGF2LXNlY3JldA=="
        assert request.headers["range"] == "bytes=100-199"
        assert request.headers["if-range"] == '"etag-1"'
        if response_status["value"] != 206:
            return httpx.Response(response_status["value"], stream=httpx.ByteStream(b"private upstream detail"),
                                  headers={"Content-Range": "bytes */1000", "Location": "https://attacker.example"})
        return httpx.Response(206, stream=httpx.ByteStream(b"x" * 100), headers={
            "Content-Range": "bytes 100-199/1000", "Content-Length": "100", "Accept-Ranges": "bytes"})
    original_client = httpx.AsyncClient
    monkeypatch.setattr(router.httpx, "AsyncClient", lambda **kwargs: original_client(transport=httpx.MockTransport(stream), **kwargs))
    # Expiring the preparation cache must not break seeks in an active session.
    release_cache.warm_success.clear()
    path = result.stream_url.replace("https://api.torve.app", "")
    headers = {"Range": "bytes=100-199", "If-Range": '"etag-1"'}
    response = client.get(path, headers=headers)
    assert response.status_code == 206 and len(response.content) == 100
    assert response.headers["content-range"] == "bytes 100-199/1000"
    assert all(secret not in str(response.headers) for secret in ("dav-secret", "dav-user", "stream.example"))
    head = client.head(path, headers=headers)
    assert head.status_code == 206 and not head.content
    assert observed[-1].method == "HEAD"
    for status in (416, 307, 401):
        response_status["value"] = status
        rejected = client.get(path, headers=headers)
        assert rejected.status_code == (416 if status == 416 else 502)
        assert b"private upstream detail" not in rejected.content
        assert "attacker.example" not in str(rejected.headers)
    # Changing the selected server immediately revokes its old playback URLs.
    previous_scope = connection_scope(cfg)
    account_store.save(db, user_id=streaming_user.id, base_url="https://replacement.example", api_key_plaintext="replacement")
    assert connection_scope(cfg) != previous_scope
    assert client.get(path).status_code == 401
