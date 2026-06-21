"""
Tests for /me/watch_state/{report,latest}.

Mirrors the invariants the F3 work relies on:
  - 401 when unauthenticated
  - 404 when no row exists for (user, content)
  - latest() returns the newest row (most recent reported_at)
  - cross-user isolation: user B cannot see user A's reports
  - blank content_id rejected by Query validation
  - device_id: valid UUID belonging to the user is persisted;
               omitted device_id is inferred from X-Torve-Installation-Id;
               foreign UUID is silently dropped (report still lands);
               malformed UUID → 400
"""
from __future__ import annotations

import time
import uuid

from app.models import Device, WatchStateReport
from app.security import create_access_token


def _auth(user):
    return {"Authorization": f"Bearer {create_access_token(str(user.id))}"}


def _mk_device(db, user, installation_id="inst-1"):
    d = Device(
        user_id=user.id,
        device_type="phone",
        platform="android",
        display_name="Test phone",
        installation_id=installation_id,
    )
    db.add(d)
    db.commit()
    db.refresh(d)
    return d


def _cleanup_reports(db, user_id):
    db.query(WatchStateReport).filter(WatchStateReport.user_id == user_id).delete()
    db.commit()


def test_report_then_latest_roundtrip(client, test_user, db):
    try:
        r = client.post(
            "/me/watch_state/report",
            headers=_auth(test_user),
            json={
                "content_id": "tt1234567",
                "provider": "torrentio",
                "position_ms": 123456,
            },
        )
        assert r.status_code == 200, r.text
        body = r.json()
        assert body["status"] == "ok"
        assert "reported_at" in body

        r2 = client.get(
            "/me/watch_state/latest?content_id=tt1234567",
            headers=_auth(test_user),
        )
        assert r2.status_code == 200, r2.text
        body2 = r2.json()
        assert body2["content_id"] == "tt1234567"
        assert body2["provider"] == "torrentio"
        assert body2["position_ms"] == 123456
        assert body2["device_id"] is None
    finally:
        _cleanup_reports(db, test_user.id)


def test_latest_returns_newest_row(client, test_user, db):
    try:
        # Three reports, last one wins.
        for pos in (10_000, 25_000, 90_000):
            r = client.post(
                "/me/watch_state/report",
                headers=_auth(test_user),
                json={
                    "content_id": "tt_multi",
                    "provider": "torrentio",
                    "position_ms": pos,
                },
            )
            assert r.status_code == 200
            time.sleep(0.01)  # spread reported_at
        r = client.get(
            "/me/watch_state/latest?content_id=tt_multi",
            headers=_auth(test_user),
        )
        assert r.status_code == 200
        assert r.json()["position_ms"] == 90_000
    finally:
        _cleanup_reports(db, test_user.id)


def test_latest_404_when_no_row(client, test_user):
    r = client.get(
        "/me/watch_state/latest?content_id=tt_unknown",
        headers=_auth(test_user),
    )
    assert r.status_code == 404


def test_cross_user_isolation(client, test_user, test_user_b, db):
    try:
        # User A reports on tt_shared.
        r = client.post(
            "/me/watch_state/report",
            headers=_auth(test_user),
            json={
                "content_id": "tt_shared",
                "provider": "panda",
                "position_ms": 60_000,
            },
        )
        assert r.status_code == 200
        # User B must NOT see it.
        r2 = client.get(
            "/me/watch_state/latest?content_id=tt_shared",
            headers=_auth(test_user_b),
        )
        assert r2.status_code == 404
        # User A still sees it.
        r3 = client.get(
            "/me/watch_state/latest?content_id=tt_shared",
            headers=_auth(test_user),
        )
        assert r3.status_code == 200
    finally:
        _cleanup_reports(db, test_user.id)
        _cleanup_reports(db, test_user_b.id)


def test_unauthenticated_rejected(client):
    r = client.post(
        "/me/watch_state/report",
        json={"content_id": "tt_x", "provider": "x", "position_ms": 1},
    )
    assert r.status_code in (401, 403)
    r2 = client.get("/me/watch_state/latest?content_id=tt_x")
    assert r2.status_code in (401, 403)


def test_blank_content_id_rejected(client, test_user):
    # Query-parameter min_length=1 validation.
    r = client.get(
        "/me/watch_state/latest?content_id=",
        headers=_auth(test_user),
    )
    assert r.status_code == 422


def test_device_id_own_device_persisted(client, test_user, db):
    device = _mk_device(db, test_user)
    try:
        r = client.post(
            "/me/watch_state/report",
            headers=_auth(test_user),
            json={
                "content_id": "tt_dev",
                "provider": "panda",
                "position_ms": 5_000,
                "device_id": str(device.id),
            },
        )
        assert r.status_code == 200
        r2 = client.get(
            "/me/watch_state/latest?content_id=tt_dev",
            headers=_auth(test_user),
        )
        assert r2.json()["device_id"] == str(device.id)
    finally:
        _cleanup_reports(db, test_user.id)
        db.query(Device).filter(Device.id == device.id).delete()
        db.commit()


def test_device_id_inferred_from_installation_header(client, test_user, db):
    device = _mk_device(db, test_user, installation_id="install-header-1")
    try:
        r = client.post(
            "/me/watch_state/report",
            headers={
                **_auth(test_user),
                "X-Torve-Installation-Id": "install-header-1",
            },
            json={
                "content_id": "tt_header_dev",
                "provider": "panda",
                "position_ms": 6_000,
            },
        )
        assert r.status_code == 200
        r2 = client.get(
            "/me/watch_state/latest?content_id=tt_header_dev",
            headers=_auth(test_user),
        )
        assert r2.json()["device_id"] == str(device.id)
    finally:
        _cleanup_reports(db, test_user.id)
        db.query(Device).filter(Device.id == device.id).delete()
        db.commit()


def test_device_id_foreign_dropped_silently(client, test_user, test_user_b, db):
    foreign = _mk_device(db, test_user_b, installation_id="foreign")
    try:
        r = client.post(
            "/me/watch_state/report",
            headers=_auth(test_user),
            json={
                "content_id": "tt_fdev",
                "provider": "panda",
                "position_ms": 9_000,
                "device_id": str(foreign.id),
            },
        )
        assert r.status_code == 200
        r2 = client.get(
            "/me/watch_state/latest?content_id=tt_fdev",
            headers=_auth(test_user),
        )
        # Report landed; device attribution dropped because foreign.
        assert r2.status_code == 200
        assert r2.json()["device_id"] is None
    finally:
        _cleanup_reports(db, test_user.id)
        db.query(Device).filter(Device.id == foreign.id).delete()
        db.commit()


def test_device_id_malformed_uuid_400(client, test_user):
    r = client.post(
        "/me/watch_state/report",
        headers=_auth(test_user),
        json={
            "content_id": "tt_bad",
            "provider": "panda",
            "position_ms": 1_000,
            "device_id": "not-a-uuid",
        },
    )
    assert r.status_code == 400
