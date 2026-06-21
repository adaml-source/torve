from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone

import pytest

from app.models import (
    ResolveSuccessMemory,
    StreamMemoryCoverageSnapshot,
    StreamPathTelemetry,
)
from app.rate_limits import reset_rate_limits_for_tests


ADMIN_HEADERS = {"X-Admin-Secret": "test-admin-secret"}


@pytest.fixture(autouse=True)
def _clean_stream_telemetry(db):
    db.query(StreamPathTelemetry).delete()
    db.query(StreamMemoryCoverageSnapshot).delete()
    db.commit()
    reset_rate_limits_for_tests()
    yield
    db.query(StreamPathTelemetry).delete()
    db.query(StreamMemoryCoverageSnapshot).delete()
    db.commit()
    reset_rate_limits_for_tests()


@pytest.fixture(autouse=True)
def _admin_secret(monkeypatch):
    monkeypatch.setattr("app.config.settings.PADDLE_ADMIN_SECRET", "test-admin-secret")


def _headers(auth_header: dict, device) -> dict:
    return {**auth_header, "X-Torve-Installation-Id": device.installation_id}


def _event(path_type: str = "generic_handoff_memory_id") -> dict:
    return {
        "path_type": path_type,
        "platform": "android",
        "app_version": "1.2.3",
        "distribution_channel": "google_play",
        "content_type": "movie",
        "provider_category": "debrid",
        "source_category": "acceleration",
        "device_category": "tv",
        "generated_at_epoch_millis": 1_770_000_000_000,
    }


@pytest.mark.parametrize(
    "path_type",
    [
        "usenet_handoff",
        "generic_handoff_memory_id",
        "legacy_direct_no_memory_id",
        "iptv_direct",
        "direct_free",
    ],
)
def test_stream_path_telemetry_accepts_allowed_path_types(
    client, db, auth_header, tv_device, path_type
):
    r = client.post(
        "/telemetry/stream-path",
        headers=_headers(auth_header, tv_device),
        json=_event(path_type),
    )

    assert r.status_code == 200, r.text
    assert r.json() == {"accepted": True, "reason": None}
    row = db.query(StreamPathTelemetry).filter(StreamPathTelemetry.path_type == path_type).first()
    assert row is not None
    assert row.user_hash and str(tv_device.user_id) not in row.user_hash
    assert row.device_hash and tv_device.installation_id not in row.device_hash


def test_stream_path_telemetry_rejects_unknown_path_type_safely(
    client, db, auth_header, tv_device
):
    r = client.post(
        "/telemetry/stream-path",
        headers=_headers(auth_header, tv_device),
        json=_event("raw_provider_url"),
    )

    assert r.status_code == 422
    assert db.query(StreamPathTelemetry).count() == 0


def test_stream_path_telemetry_rejects_sensitive_extra_fields(
    client, db, auth_header, tv_device
):
    payload = _event()
    payload["memory_id"] = str(uuid.uuid4())
    payload["stream_url"] = "https://provider.example/video.mp4?token=secret"

    r = client.post(
        "/telemetry/stream-path",
        headers=_headers(auth_header, tv_device),
        json=payload,
    )

    assert r.status_code == 422
    assert "provider.example" not in r.text
    assert "secret" not in r.text
    assert db.query(StreamPathTelemetry).count() == 0


def test_stream_path_telemetry_duplicate_is_ignored(client, db, auth_header, tv_device):
    headers = _headers(auth_header, tv_device)
    first = client.post("/telemetry/stream-path", headers=headers, json=_event())
    second = client.post("/telemetry/stream-path", headers=headers, json=_event())

    assert first.status_code == 200
    assert first.json()["accepted"] is True
    assert second.status_code == 200
    assert second.json() == {"accepted": False, "reason": "duplicate"}
    assert db.query(StreamPathTelemetry).count() == 1


def test_stream_protection_metrics_ratios_and_breakdowns(client, db):
    now = datetime.now(timezone.utc)
    rows = [
        StreamPathTelemetry(
            path_type="generic_handoff_memory_id",
            platform="android",
            app_version="1.0",
            provider_category="debrid",
            content_type="movie",
            created_at=now,
        ),
        StreamPathTelemetry(
            path_type="generic_handoff_memory_id",
            platform="android",
            app_version="1.0",
            provider_category="debrid",
            content_type="movie",
            created_at=now,
        ),
        StreamPathTelemetry(
            path_type="legacy_direct_no_memory_id",
            platform="desktop",
            app_version="1.1",
            provider_category="addon",
            content_type="episode",
            created_at=now,
        ),
        StreamPathTelemetry(path_type="usenet_handoff", platform="android", created_at=now),
        StreamPathTelemetry(path_type="iptv_direct", platform="tv", created_at=now),
        StreamPathTelemetry(path_type="direct_free", platform="web", created_at=now),
        StreamPathTelemetry(path_type="stream_expired", platform="backend", created_at=now),
        StreamPathTelemetry(path_type="stream_reference_not_found", platform="backend", created_at=now),
        StreamPathTelemetry(path_type="stream_handoff_unavailable", platform="backend", created_at=now),
    ]
    db.add_all(rows)
    db.commit()

    r = client.get("/admin/metrics/stream-protection?window_hours=24", headers=ADMIN_HEADERS)

    assert r.status_code == 200, r.text
    body = r.json()
    assert body["total_events"] == 6
    assert body["generic_handoff_memory_id_count"] == 2
    assert body["legacy_direct_no_memory_id_count"] == 1
    assert body["stream_expired_count"] == 1
    assert body["stream_reference_not_found_count"] == 1
    assert body["stream_handoff_unavailable_count"] == 1
    assert body["handoff_error_counts"] == {
        "stream_expired": 1,
        "stream_reference_not_found": 1,
        "stream_handoff_unavailable": 1,
    }
    assert body["protected_handoff_ratio"] == pytest.approx(2 / 3)
    assert body["legacy_fallback_ratio"] == pytest.approx(1 / 3)
    assert body["breakdown_by_platform"]["android"]["total"] == 3
    assert "backend" not in body["breakdown_by_platform"]
    assert body["breakdown_by_provider_category"]["debrid"]["generic_handoff_memory_id"] == 2
    assert body["breakdown_by_app_version"]["1.0"]["total"] == 2


def test_stream_protection_metrics_admin_only(client):
    assert client.get("/admin/metrics/stream-protection").status_code == 403
    assert client.get(
        "/admin/metrics/stream-protection",
        headers={"X-Admin-Secret": "wrong"},
    ).status_code == 403


def test_memory_coverage_snapshots_are_aggregated(client, db):
    db.add_all([
        StreamMemoryCoverageSnapshot(
            endpoint="sources",
            content_type="movie",
            eligible_candidate_count=3,
            memory_id_emitted_count=2,
            memory_id_missing_count=1,
            memory_id_coverage_ratio=2 / 3,
            missing_reason_counts={"memory_reference_unavailable": 1},
            provider_category_counts={"debrid": 2, "addon": 1},
            source_category_counts={"debrid": 2, "addon": 1},
            created_at=datetime.now(timezone.utc),
        ),
        StreamMemoryCoverageSnapshot(
            endpoint="startup",
            content_type="episode",
            eligible_candidate_count=1,
            memory_id_emitted_count=1,
            memory_id_missing_count=0,
            memory_id_coverage_ratio=1.0,
            missing_reason_counts={},
            provider_category_counts={"panda": 1},
            source_category_counts={"panda": 1},
            created_at=datetime.now(timezone.utc),
        ),
    ])
    db.commit()

    r = client.get("/admin/metrics/stream-protection", headers=ADMIN_HEADERS)

    assert r.status_code == 200, r.text
    coverage = r.json()["memory_id_coverage"]
    assert coverage["eligible_candidate_count"] == 4
    assert coverage["memory_id_emitted_count"] == 3
    assert coverage["memory_id_missing_count"] == 1
    assert coverage["memory_id_coverage_ratio"] == pytest.approx(0.75)
    assert coverage["missing_reason_counts"]["memory_reference_unavailable"] == 1
    assert coverage["provider_category_breakdown"]["debrid"] == 2
    assert coverage["content_type_breakdown"]["movie"] == 3


def test_telemetry_validation_failure_does_not_break_handoff(
    client, db, test_user, auth_header, tv_device, monkeypatch
):
    bad = _event()
    bad["source_key"] = "https://provider.example/video.mp4?token=secret"
    r = client.post("/telemetry/stream-path", headers=_headers(auth_header, tv_device), json=bad)
    assert r.status_code == 422

    memory = ResolveSuccessMemory(
        user_id=test_user.id,
        content_id="tmdb:telemetry-handoff",
        provider_type="real_debrid",
        source_key="https://cdn.example/video.mp4?token=provider-secret",
        success_count=1,
        last_success_at=datetime.now(timezone.utc),
        expires_at=datetime.now(timezone.utc) + timedelta(days=1),
    )
    db.add(memory)
    db.commit()
    db.refresh(memory)

    async def _ok(upstream_url, request, user_id):
        from starlette.responses import Response

        return Response(content=b"ok", media_type="video/mp4")

    import app.routers.stream_handoff as router_mod

    monkeypatch.setattr(router_mod, "_proxy_stream", _ok)
    created = client.post(
        "/resolver/stream/handoff",
        headers=_headers(auth_header, tv_device),
        json={"content_id": memory.content_id, "memory_id": str(memory.id)},
    )
    assert created.status_code == 200, created.text
    played = client.get(created.json()["url"])
    assert played.status_code == 200
    assert played.content == b"ok"
