from __future__ import annotations

from datetime import datetime, timezone

import pytest
from fastapi import HTTPException

from app.models import ResolveSuccessMemory, UserAddon
from app.rate_limits import reset_rate_limits_for_tests
from app.stream_handoff import reset_for_tests as reset_stream_handoffs


@pytest.fixture(autouse=True)
def _reset_state(db):
    reset_stream_handoffs(db)
    reset_rate_limits_for_tests()
    yield
    reset_stream_handoffs(db)
    reset_rate_limits_for_tests()


def _headers(auth_header: dict, device) -> dict:
    return {**auth_header, "X-Torve-Installation-Id": device.installation_id}


def _detail_code(response) -> str:
    detail = response.json()["detail"]
    return detail["code"] if isinstance(detail, dict) else detail


class _FakeResponse:
    def __init__(self, status_code: int, payload: dict) -> None:
        self.status_code = status_code
        self._payload = payload

    def json(self) -> dict:
        return self._payload


class _FakeRealDebridClient:
    def __init__(self, *args, **kwargs) -> None:
        self.calls: list[dict] = []

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def post(self, url, *, headers=None, data=None):
        assert url == "https://api.real-debrid.com/rest/1.0/unrestrict/link"
        assert headers["Authorization"] == "Bearer rd_api_secret"
        assert data["link"] == "https://hoster.example/source-video"
        return _FakeResponse(200, {
            "download": "https://cdn.example/resolved.mp4?token=provider-secret",
            "filename": "Example.Movie.2026.1080p.mkv",
            "filesize": 123456789,
        })


class _FakeProviderErrorClient(_FakeRealDebridClient):
    def post(self, url, *, headers=None, data=None):
        return _FakeResponse(500, {
            "error": "provider exploded token=provider-secret rd_api_secret",
            "url": "https://cdn.example/resolved.mp4?token=provider-secret",
        })


class _FakeAddonClient:
    def __init__(self, *args, **kwargs) -> None:
        pass

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def get(self, url, *, headers=None):
        assert url == "https://panda.torve.app/u/abc/stream/movie/tt123.json"
        assert headers["Accept"] == "application/json"
        return _FakeResponse(200, {
            "streams": [
                {
                    "name": "Panda 1080p",
                    "url": "https://panda-cdn.example/video.mp4?token=panda-secret",
                }
            ]
        })


def _save_real_debrid_credentials(client, auth_header) -> None:
    r = client.put("/me/integrations/real_debrid", headers=auth_header, json={
        "integration_type": "real_debrid",
        "credentials": {"api_key": "rd_api_secret"},
        "storage_mode": "account",
    })
    assert r.status_code == 200, r.text


def test_backend_provider_resolve_requires_device_but_not_premium(
    client, free_auth_header, free_tv_device, auth_header
):
    r = client.post(
        "/resolver/stream/resolve",
        headers=_headers(free_auth_header, free_tv_device),
        json={
            "content_id": "tmdb:provider-gate",
            "provider_type": "real_debrid",
            "source_url": "https://hoster.example/source-video",
        },
    )
    assert r.status_code == 409
    assert _detail_code(r) == "provider_credentials_missing"

    r2 = client.post(
        "/resolver/stream/resolve",
        headers=auth_header,
        json={
            "content_id": "tmdb:provider-gate",
            "provider_type": "real_debrid",
            "source_url": "https://hoster.example/source-video",
        },
    )
    assert r2.status_code == 403
    assert _detail_code(r2) == "device_required"


def test_real_debrid_backend_resolution_returns_memory_id_without_raw_url(
    client, db, test_user, auth_header, tv_device, monkeypatch
):
    _save_real_debrid_credentials(client, auth_header)
    import app.provider_resolution as provider_resolution

    monkeypatch.setattr(provider_resolution.httpx, "Client", _FakeRealDebridClient)

    r = client.post(
        "/resolver/stream/resolve",
        headers=_headers(auth_header, tv_device),
        json={
            "content_id": "tmdb:rd-native",
            "provider_type": "real_debrid",
            "source_url": "https://hoster.example/source-video",
            "quality": "1080p",
        },
    )

    assert r.status_code == 200, r.text
    body = r.json()
    assert body["memory_id"]
    assert body["provider_type"] == "real_debrid"
    assert body["is_direct"] is False
    assert body["supports_handoff"] is True
    assert "provider-secret" not in r.text
    assert "cdn.example" not in r.text
    assert "rd_api_secret" not in r.text

    row = db.query(ResolveSuccessMemory).filter(
        ResolveSuccessMemory.id == body["memory_id"],
        ResolveSuccessMemory.user_id == test_user.id,
    ).first()
    assert row is not None
    assert row.source_key == "https://cdn.example/resolved.mp4?token=provider-secret"
    assert row.provenance_kind == "DEBRID_PROVIDER"
    assert row.file_name == "Example.Movie.2026.1080p.mkv"

    handoff = client.post(
        "/resolver/stream/handoff",
        headers=_headers(auth_header, tv_device),
        json={"content_id": "tmdb:rd-native", "memory_id": body["memory_id"]},
    )
    assert handoff.status_code == 200, handoff.text
    assert "provider-secret" not in handoff.text
    assert "cdn.example" not in handoff.text


def test_real_debrid_backend_resolution_sanitizes_provider_error(
    client, auth_header, tv_device, monkeypatch, caplog
):
    _save_real_debrid_credentials(client, auth_header)
    import app.provider_resolution as provider_resolution

    monkeypatch.setattr(provider_resolution.httpx, "Client", _FakeProviderErrorClient)
    caplog.set_level("INFO", logger="app.provider_resolution")

    r = client.post(
        "/resolver/stream/resolve",
        headers=_headers(auth_header, tv_device),
        json={
            "content_id": "tmdb:rd-failure",
            "provider_type": "real_debrid",
            "source_url": "https://hoster.example/source-video",
        },
    )

    assert r.status_code == 502
    assert _detail_code(r) == "provider_resolution_failed"
    assert "provider-secret" not in r.text
    assert "rd_api_secret" not in r.text
    assert "hoster.example" not in r.text
    assert "provider-secret" not in caplog.text
    assert "rd_api_secret" not in caplog.text


def test_backend_provider_resolve_is_rate_limited(
    client, auth_header, tv_device, monkeypatch
):
    def fake_limit(**kwargs):
        assert kwargs["category"] == "stream_backend_resolve"
        raise HTTPException(
            status_code=429,
            detail={
                "code": "rate_limited",
                "message": "Too many requests. Please try again later.",
            },
        )

    monkeypatch.setattr("app.routers.stream_handoff.enforce_rate_limit", fake_limit)

    r = client.post(
        "/resolver/stream/resolve",
        headers=_headers(auth_header, tv_device),
        json={
            "content_id": "tmdb:rate-limit",
            "provider_type": "real_debrid",
            "source_url": "https://hoster.example/source-video",
        },
    )

    assert r.status_code == 429
    assert _detail_code(r) == "rate_limited"


def test_panda_addon_backend_resolution_returns_memory_id_without_raw_url(
    client, db, test_user, auth_header, tv_device, monkeypatch
):
    addon = UserAddon(
        user_id=test_user.id,
        manifest_url="https://panda.torve.app/u/abc/manifest.json",
        addon_id="com.torve.panda",
        name="Panda",
        has_streams=True,
        is_enabled=True,
        created_at=datetime.now(timezone.utc),
    )
    db.add(addon)
    db.commit()
    db.refresh(addon)

    import app.provider_resolution as provider_resolution

    monkeypatch.setattr(provider_resolution.httpx, "Client", _FakeAddonClient)

    r = client.post(
        "/resolver/stream/resolve",
        headers=_headers(auth_header, tv_device),
        json={
            "content_id": "imdb:tt123",
            "provider_type": "panda",
            "stream_type": "movie",
            "stream_id": "tt123",
            "quality": "1080p",
        },
    )

    assert r.status_code == 200, r.text
    body = r.json()
    assert body["provider_type"] == "panda"
    assert body["memory_id"]
    assert "panda-secret" not in r.text
    assert "panda-cdn.example" not in r.text

    row = db.query(ResolveSuccessMemory).filter(
        ResolveSuccessMemory.id == body["memory_id"],
        ResolveSuccessMemory.user_id == test_user.id,
    ).first()
    assert row is not None
    assert row.provider_type == "panda"
    assert row.source_key == "https://panda-cdn.example/video.mp4?token=panda-secret"
    assert row.provenance_kind == "ADDON_PROVIDER"


def test_provider_resolution_source_key_is_not_logged(
    client, auth_header, tv_device, monkeypatch, caplog
):
    _save_real_debrid_credentials(client, auth_header)
    import app.provider_resolution as provider_resolution

    monkeypatch.setattr(provider_resolution.httpx, "Client", _FakeRealDebridClient)
    caplog.set_level("INFO", logger="app.resolve_memory_service")

    r = client.post(
        "/resolver/stream/resolve",
        headers=_headers(auth_header, tv_device),
        json={
            "content_id": "tmdb:rd-log",
            "provider_type": "real_debrid",
            "source_url": "https://hoster.example/source-video",
        },
    )
    assert r.status_code == 200, r.text
    assert "provider-secret" not in caplog.text
    assert "https://cdn.example/resolved.mp4" not in caplog.text
