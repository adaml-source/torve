from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest
from starlette.responses import Response

from app.models import ResolveSuccessMemory, StreamHandoffReference, StreamPathTelemetry
from app.nzbdav.failures import FailureCode, UpstreamError
from app.nzbdav.handoff import mint, verify
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
    return {
        **auth_header,
        "X-Torve-Installation-Id": device.installation_id,
    }


def _detail_code(response) -> str:
    detail = response.json()["detail"]
    return detail["code"] if isinstance(detail, dict) else detail


def _memory(
    db,
    user,
    *,
    content_id: str = "tmdb:stream-handoff",
    provider_type: str = "real_debrid",
    source_key: str = "https://provider.example/video.mp4?token=secret-provider-token",
) -> ResolveSuccessMemory:
    row = ResolveSuccessMemory(
        user_id=user.id,
        content_id=content_id,
        provider_type=provider_type,
        source_key=source_key,
        infohash="a" * 40,
        file_name="Example.1080p.mkv",
        quality="1080p",
        success_count=1,
        last_success_at=datetime.now(timezone.utc),
        expires_at=datetime.now(timezone.utc) + timedelta(days=1),
    )
    db.add(row)
    db.commit()
    db.refresh(row)
    return row


def test_generic_stream_handoff_allows_free_account_without_entitlement(
    client, db, free_user, free_auth_header, free_tv_device
):
    row = _memory(db, free_user)

    r = client.post(
        "/resolver/stream/handoff",
        headers=_headers(free_auth_header, free_tv_device),
        json={"content_id": row.content_id, "memory_id": str(row.id)},
    )

    assert r.status_code == 200
    assert r.json()["is_direct"] is False


def test_generic_stream_handoff_rejects_missing_installation_id(
    client, db, test_user, auth_header
):
    row = _memory(db, test_user)

    r = client.post(
        "/resolver/stream/handoff",
        headers=auth_header,
        json={"content_id": row.content_id, "memory_id": str(row.id)},
    )

    assert r.status_code == 403
    assert r.json()["detail"]["code"] == "device_required"


def test_generic_stream_handoff_rejects_unauthorized_device(
    client, db, test_user, auth_header
):
    row = _memory(db, test_user)

    r = client.post(
        "/resolver/stream/handoff",
        headers={**auth_header, "X-Torve-Installation-Id": "missing-device"},
        json={"content_id": row.content_id, "memory_id": str(row.id)},
    )

    assert r.status_code == 403
    assert r.json()["detail"]["code"] == "device_not_authorized"


def test_generic_stream_handoff_rejects_unknown_memory_id_with_stable_code(
    client, test_user, auth_header, tv_device
):
    missing_id = "11111111-1111-4111-8111-111111111111"

    r = client.post(
        "/resolver/stream/handoff",
        headers=_headers(auth_header, tv_device),
        json={"content_id": "tmdb:missing-stream", "memory_id": missing_id},
    )

    assert r.status_code == 404
    assert _detail_code(r) == "stream_reference_not_found"
    assert missing_id not in r.text


def test_generic_stream_handoff_mints_bound_token_without_raw_provider_url(
    client, db, test_user, auth_header, tv_device
):
    row = _memory(db, test_user)

    r = client.post(
        "/resolver/stream/handoff",
        headers=_headers(auth_header, tv_device),
        json={"content_id": row.content_id, "memory_id": str(row.id)},
    )

    assert r.status_code == 200
    body_text = r.text
    body = r.json()
    assert body["url"].startswith("https://api.torve.app/resolver/stream/handoff/")
    assert body["is_direct"] is False
    assert body["supports_range"] is True
    assert body["expires_in_seconds"] <= 300
    assert "provider.example" not in body_text
    assert "secret-provider-token" not in body_text

    token = body["url"].rsplit("/", 1)[-1]
    claims = verify(token)
    assert claims.user_id == str(test_user.id)
    assert claims.device_id == str(tv_device.id)
    assert claims.content_id == row.content_id
    assert claims.stream_id == body["stream_id"]
    assert claims.exp
    assert claims.jti
    ref = db.get(StreamHandoffReference, body["stream_id"])
    assert ref is not None
    assert ref.expires_at > datetime.now(timezone.utc)
    assert "provider.example" not in ref.upstream_url_encrypted
    assert "secret-provider-token" not in ref.upstream_url_encrypted


def test_generic_stream_handoff_reference_survives_process_local_reset(
    client, db, test_user, auth_header, tv_device, monkeypatch
):
    row = _memory(db, test_user)
    proxied: dict[str, str | None] = {}

    async def _ok(upstream_url, request, user_id):
        proxied["upstream_url"] = upstream_url
        return Response(content=b"ok", media_type="video/mp4")

    import app.routers.stream_handoff as router_mod

    monkeypatch.setattr(router_mod, "_proxy_stream", _ok)
    created = client.post(
        "/resolver/stream/handoff",
        headers=_headers(auth_header, tv_device),
        json={"content_id": row.content_id, "memory_id": str(row.id)},
    )
    assert created.status_code == 200

    # Simulates the old per-process cache being unavailable on a different
    # worker; playback must still find the shared TTL row.
    reset_stream_handoffs()
    playback = client.get(created.json()["url"])

    assert playback.status_code == 200
    assert playback.content == b"ok"
    assert proxied["upstream_url"] == row.source_key


def test_generic_stream_handoff_rejects_non_url_backend_reference(
    client, db, test_user, auth_header, tv_device
):
    row = _memory(db, test_user, provider_type="panda", source_key="panda:stream-1")
    before = db.query(StreamPathTelemetry).filter(
        StreamPathTelemetry.path_type == "stream_handoff_unavailable"
    ).count()

    r = client.post(
        "/resolver/stream/handoff",
        headers=_headers(auth_header, tv_device),
        json={"content_id": row.content_id, "memory_id": str(row.id)},
    )

    assert r.status_code == 409
    assert r.json()["detail"]["code"] == "stream_handoff_unavailable"
    assert "panda:stream-1" not in r.text
    after = db.query(StreamPathTelemetry).filter(
        StreamPathTelemetry.path_type == "stream_handoff_unavailable"
    ).count()
    assert after == before + 1


def test_generic_stream_handoff_sanitizes_provider_proxy_errors(
    client, db, test_user, auth_header, tv_device, monkeypatch
):
    row = _memory(db, test_user)

    async def _boom(upstream_url, request, user_id):
        raise UpstreamError(
            FailureCode.UPSTREAM_UNREACHABLE,
            detail="raw provider body token=SECRET_VALUE",
        )

    import app.routers.stream_handoff as router_mod

    monkeypatch.setattr(router_mod, "_proxy_stream", _boom)

    created = client.post(
        "/resolver/stream/handoff",
        headers=_headers(auth_header, tv_device),
        json={"content_id": row.content_id, "memory_id": str(row.id)},
    )
    assert created.status_code == 200

    r = client.get(created.json()["url"])

    assert r.status_code == 502
    assert r.json()["detail"]["code"] == FailureCode.UPSTREAM_UNREACHABLE.value
    assert "SECRET_VALUE" not in r.text
    assert "provider.example" not in r.text


def test_expired_generic_handoff_token_returns_stream_expired(
    client, db, test_user, tv_device
):
    before = db.query(StreamPathTelemetry).filter(
        StreamPathTelemetry.path_type == "stream_expired"
    ).count()
    token = mint(
        user_id=str(test_user.id),
        device_id=str(tv_device.id),
        content_id="tmdb:expired-stream",
        stream_id="generic_expired_stream",
        ttl_seconds=-1,
    )

    r = client.get(f"/resolver/stream/handoff/{token}")

    assert r.status_code == 410
    assert _detail_code(r) == "stream_expired"
    assert "generic_expired_stream" not in r.text
    after = db.query(StreamPathTelemetry).filter(
        StreamPathTelemetry.path_type == "stream_expired"
    ).count()
    assert after == before + 1


def test_malformed_generic_handoff_token_returns_invalid_handoff(client):
    r = client.get("/resolver/stream/handoff/not-a-valid-token")

    assert r.status_code == 401
    assert _detail_code(r) == "invalid_handoff"


def test_bad_signature_generic_handoff_token_returns_invalid_handoff(
    client, test_user, tv_device
):
    token = mint(
        user_id=str(test_user.id),
        device_id=str(tv_device.id),
        content_id="tmdb:bad-signature",
        stream_id="generic_bad_signature",
    )
    payload, _sig = token.rsplit(".", 1)
    tampered = f"{payload}.AAAA"

    r = client.get(f"/resolver/stream/handoff/{tampered}")

    assert r.status_code == 401
    assert _detail_code(r) == "invalid_handoff"
    assert "generic_bad_signature" not in r.text


def test_valid_generic_handoff_token_cache_miss_is_sanitized(
    client, db, test_user, tv_device
):
    before = db.query(StreamPathTelemetry).filter(
        StreamPathTelemetry.path_type == "stream_reference_not_found"
    ).count()
    token = mint(
        user_id=str(test_user.id),
        device_id=str(tv_device.id),
        content_id="tmdb:cache-miss",
        stream_id="generic_cache_miss",
    )

    r = client.get(f"/resolver/stream/handoff/{token}")

    assert r.status_code == 404
    assert _detail_code(r) == "stream_reference_not_found"
    assert "provider.example" not in r.text
    assert "secret-provider-token" not in r.text
    assert "generic_cache_miss" not in r.text
    after = db.query(StreamPathTelemetry).filter(
        StreamPathTelemetry.path_type == "stream_reference_not_found"
    ).count()
    assert after == before + 1


def test_valid_generic_handoff_token_expired_shared_reference_is_sanitized(
    client, db, test_user, auth_header, tv_device
):
    row = _memory(db, test_user)
    created = client.post(
        "/resolver/stream/handoff",
        headers=_headers(auth_header, tv_device),
        json={"content_id": row.content_id, "memory_id": str(row.id)},
    )
    assert created.status_code == 200
    stream_id = created.json()["stream_id"]
    ref = db.get(StreamHandoffReference, stream_id)
    ref.expires_at = datetime.now(timezone.utc) - timedelta(seconds=1)
    db.commit()

    r = client.get(created.json()["url"])

    assert r.status_code == 404
    assert _detail_code(r) == "stream_reference_not_found"
    assert "provider.example" not in r.text
    assert "secret-provider-token" not in r.text


def test_generic_stream_handoff_rate_limits_create_and_playback(
    client, db, test_user, auth_header, tv_device, monkeypatch
):
    row = _memory(db, test_user)
    calls: list[str] = []
    proxied: dict[str, str | None] = {}

    def _record_rate_limit(**kwargs):
        calls.append(kwargs["category"])

    async def _ok(upstream_url, request, user_id):
        proxied["range"] = request.headers.get("range")
        proxied["upstream_url"] = upstream_url
        return Response(content=b"ok", media_type="video/mp4")

    import app.routers.stream_handoff as router_mod

    monkeypatch.setattr(router_mod, "enforce_rate_limit", _record_rate_limit)
    monkeypatch.setattr(router_mod, "_proxy_stream", _ok)

    created = client.post(
        "/resolver/stream/handoff",
        headers=_headers(auth_header, tv_device),
        json={"content_id": row.content_id, "memory_id": str(row.id)},
    )
    assert created.status_code == 200

    playback = client.get(created.json()["url"], headers={"Range": "bytes=0-1"})
    assert playback.status_code == 200
    assert playback.content == b"ok"
    assert proxied["range"] == "bytes=0-1"
    assert proxied["upstream_url"] == row.source_key
    assert "provider.example" not in playback.text
    assert "secret-provider-token" not in playback.text
    assert "stream_handoff_create" in calls
    assert "stream_handoff_playback" in calls
