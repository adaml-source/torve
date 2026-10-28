"""Protocol tests against backend-shaped HTTP responses, without paid accounts."""
import asyncio
import uuid
from urllib.parse import parse_qs

import httpx
import pytest

from app.nzbdav.backends import BackendConnection, BackendType
from app.nzbdav.client import NzbdavClient, _xml
from app.nzbdav.failures import FailureCode, UpstreamError
from app.nzbdav.release_cache import NzbdavReleaseCache


NZB = b'<?xml version="1.0"?><nzb xmlns="http://www.newzbin.com/DTD/2003/nzb"><file><segments><segment bytes="10" number="1">article@example.test</segment></segments></file></nzb>'


def dav(*items):
    responses = []
    for path, is_dir, size in items:
        kind = "<d:collection/>" if is_dir else ""
        responses.append(f'<d:response><d:href>{path}</d:href><d:propstat><d:prop>'
                         f'<d:resourcetype>{kind}</d:resourcetype><d:getcontentlength>{size}</d:getcontentlength>'
                         '</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>')
    return ('<d:multistatus xmlns:d="DAV:">' + ''.join(responses) + '</d:multistatus>').encode()


@pytest.fixture(autouse=True)
def public_dns(monkeypatch):
    monkeypatch.setattr("app.nzbdav.client._resolve_addresses", lambda host: ["93.184.216.34"])


@pytest.mark.parametrize("kind,api_path,folder", [
    ("nzbdav", "/api", "/content/movies/Release"),
    ("infinidysk", "/api", "/content/movies/Release"),
    ("nzbdavex", "/api", "/content/movies/Release"),
    ("altmount", "/sabnzbd/api", "/webdav/films/Release"),
    ("decypharr", "/sabnzbd/api", "/webdav/__all__/Release.nzb"),
])
def test_real_protocol_lifecycle(kind, api_path, folder, caplog):
    import logging
    caplog.set_level(logging.INFO, logger="httpx")
    seen = []

    async def handle(request):
        seen.append(request)
        if request.method == "GET":
            if request.url.host == "indexer.example":
                return httpx.Response(200, content=NZB)
            assert request.headers["range"] == "bytes=0-0"
            assert request.headers["authorization"] == "Basic ZGF2LXVzZXI6ZGF2LXNlY3JldA=="
            return httpx.Response(206, content=b"x", headers={"Content-Range": "bytes 0-0/1000"})
        if request.method == "PROPFIND":
            assert request.headers["authorization"] == "Basic ZGF2LXVzZXI6ZGF2LXNlY3JldA=="
            if request.headers["depth"] == "0":
                return httpx.Response(207, content=dav((request.url.path, True, 0)))
            assert request.url.path.rstrip("/") == folder
            return httpx.Response(207, content=dav(
                (folder + "/", True, 0), (folder + "/video%20one.mkv", False, 1000),
                (folder + "/sample.mkv", False, 200), (folder + "/poster.jpg", False, 100)))
        assert request.url.path == api_path
        mode = request.url.params["mode"]
        assert "apikey" not in request.url.params
        if mode == "addfile":
            assert request.headers["content-type"].startswith("multipart/form-data")
            assert NZB in request.content
            assert b"api-secret" in request.content
            assert b"indexer-secret" not in request.content
            return httpx.Response(200, json={"status": True, "nzo_ids": ["job-37"]})
        assert parse_qs(request.content.decode())["apikey"] == ["api-secret"]
        if mode == "history":
            assert request.url.params["nzo_ids"] == "job-37"
            return httpx.Response(200, json={"history": {"slots": [{
                "nzo_id": "job-37", "status": "Completed", "name": "Release.nzb" if kind == "decypharr" else "Unreliable NZB Name",
                "storage": "/mnt/complete/Release", "category": "movies"}]}})
        if mode == "get_config":
            return httpx.Response(200, json={"config": {"categories": [{"name": "movies", "dir": "films"}]}})
        assert mode == "queue"
        return httpx.Response(200, json={"queue": {"slots": []}})

    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as http:
            client = NzbdavClient(base_url="https://stream.example", api_key="api-secret",
                connection=BackendConnection(backend_type=kind, webdav_username="dav-user",
                                              webdav_password="dav-secret", category="movies"), http_client=http)
            assert (await client.test_connection()).capabilities["backend_type"] == kind
            job = await client.submit_nzb(nzb_url="https://indexer.example/get?id=37&apikey=indexer-secret")
            ready = await client.poll_job(upstream_job_id=job.upstream_job_id)
            assert ready.upstream_state == "ready"
            assert ready.stream_url == f"https://stream.example{folder}/video%20one.mkv"
            assert "secret" not in ready.stream_url
    asyncio.run(run())
    assert all("/api/jobs" not in str(r.url) and "/api/health" not in str(r.url) for r in seen)
    assert all(secret not in caplog.text for secret in ("indexer-secret", "api-secret", "dav-secret"))


@pytest.mark.parametrize("status,payload,code", [
    (200, {"status": False, "error": "Invalid API key"}, FailureCode.AUTH_INVALID),
    (401, {}, FailureCode.AUTH_INVALID),
    (429, {}, FailureCode.RATE_LIMITED),
    (500, {}, FailureCode.UPSTREAM_UNREACHABLE),
    (200, {}, FailureCode.UNKNOWN_UPSTREAM_ERROR),
])
def test_connection_rejects_errors_and_missing_contract(status, payload, code):
    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(lambda r: httpx.Response(status, json=payload))) as http:
            client = NzbdavClient(base_url="https://stream.example", api_key="bad", http_client=http)
            with pytest.raises(UpstreamError) as err:
                await client.test_connection()
            assert err.value.code == code
    asyncio.run(run())


def test_connection_rejects_html_and_dav_auth_failure():
    async def run():
        for html in (True, False):
            def handle(r):
                if html:
                    return httpx.Response(200, text="<html>Sign in</html>")
                return httpx.Response(200, json={"queue": {"slots": []}}) if r.method == "POST" else httpx.Response(401)
            async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as http:
                client = NzbdavClient(base_url="https://stream.example", api_key="k", http_client=http)
                with pytest.raises(UpstreamError):
                    await client.test_connection()
    asyncio.run(run())


def test_submit_timeout_is_not_retried():
    calls = []
    def handle(r):
        if r.method == "GET":
            return httpx.Response(200, content=NZB)
        calls.append(r)
        raise httpx.ReadTimeout("timeout", request=r)
    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as http:
            client = NzbdavClient(base_url="https://stream.example", api_key="k", http_client=http)
            with pytest.raises(UpstreamError):
                await client.submit_nzb(nzb_url="https://indexer.example/file.nzb")
    asyncio.run(run())
    assert len(calls) == 1


@pytest.mark.parametrize("href", ["https://attacker.example/f.mkv", "/../f.mkv", "/content/%2e%2e/f.mkv", "/other/f.mkv"])
def test_dav_does_not_leak_credentials_to_external_hrefs(href):
    client = NzbdavClient(base_url="https://stream.example", api_key="k",
                          connection=BackendConnection(webdav_url="https://dav.example/content"))
    with pytest.raises(UpstreamError):
        client.validate_stream_url(href if href.startswith("https:") else "https://dav.example" + href)


def test_cache_isolation_and_playback_session_lifetime():
    cache = NzbdavReleaseCache()
    cache.set_dead_release("same-release", "RELEASE_UNAVAILABLE", scope="user:backend-a")
    cache.set_warm_success("same-release", {"stream_id": "a"}, scope="user:backend-a")
    assert cache.get_dead_release("same-release", scope="user:backend-b") is None
    assert cache.get_warm_success("same-release", scope="other-user:backend-a") is None
    cache.register_stream("stream", {"upstream_url": "https://stream.example/file"})
    cache.warm_success.clear()
    assert cache.streams.get("stream") is not None


def test_invalid_profiles_and_hash_only_submission():
    for options in ({"backend_type": "unknown"}, {"api_path": "//attacker.example"},
                    {"content_path": "../{name}"}, {"webdav_username": "user"}):
        with pytest.raises(ValueError):
            BackendConnection(**options)
    client = NzbdavClient(base_url="https://stream.example", api_key="k")
    with pytest.raises(UpstreamError):
        asyncio.run(client.submit_nzb(hash_key="not-an-nzb-url"))


def test_nzb_dtd_is_accepted_but_entities_are_not():
    document = b'<!DOCTYPE nzb PUBLIC "-//newzBin//DTD NZB 1.1//EN" "http://www.newzbin.com/DTD/nzb/nzb-1.1.dtd"><nzb/>'
    assert _xml(document).tag == "nzb"
    with pytest.raises(UpstreamError):
        _xml(b'<!DOCTYPE nzb [<!ENTITY x "expanded">]><nzb>&x;</nzb>')


def test_custom_subpaths_and_percent_encoded_file_names():
    connection = BackendConnection(backend_type="decypharr", api_path="/custom/api",
                                  webdav_url="https://dav.example/custom/dav",
                                  content_path="/library/{category}/{name}")
    client = NzbdavClient(base_url="https://stream.example/prefix", api_key="k", connection=connection)
    assert client.connection.resolved_api_path() == "/custom/api"
    assert client.validate_stream_url("https://dav.example/custom/dav/library/video%20%26%20name.mkv")
    with pytest.raises(UpstreamError):
        client.validate_stream_url("https://dav.example/custom/dav2/video.mkv")


@pytest.mark.parametrize("videos,probe_status,expected", [
    (0, 206, FailureCode.RELEASE_UNAVAILABLE),
    (2, 206, FailureCode.RELEASE_UNAVAILABLE),
    (1, 200, FailureCode.STREAM_NOT_READY),
])
def test_ready_requires_unambiguous_media_and_working_ranges(videos, probe_status, expected):
    folder = "/content/movies/Release"
    def handle(request):
        if request.method == "POST":
            return httpx.Response(200, json={"history": {"slots": [{"nzo_id": "job", "status": "Completed",
                "storage": "/mnt/Release", "category": "movies"}]}})
        if request.method == "PROPFIND":
            items = [(folder, True, 0)] + [(folder + f"/episode-{i}.mkv", False, 1000) for i in range(videos)]
            return httpx.Response(207, content=dav(*items))
        return httpx.Response(probe_status, content=b"x", headers={"Content-Range": "bytes 0-0/1000"})
    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as http:
            client = NzbdavClient(base_url="https://stream.example", api_key="k", http_client=http)
            with pytest.raises(UpstreamError) as error:
                await client.poll_job(upstream_job_id="job")
            assert error.value.code == expected
    asyncio.run(run())
