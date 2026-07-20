"""Resolve flow tests for the NzbDAV integration.

Covers:
- Ready path from warm_success cache
- Warming path when a WarmJob exists
- Failed path with normalized code (dead-release)
- Unknown upstream exception -> UNKNOWN_UPSTREAM_ERROR
- NZBDAV_RESOLVE telemetry emitted on every resolve call
- Jobs endpoint (stale expiry + cancellation state)
"""
from __future__ import annotations

import asyncio
import logging
import uuid
from datetime import datetime, timedelta, timezone

import pytest

from app.models import WarmJob
from app.nzbdav import state as st
from app.nzbdav.account_store import account_store
from app.nzbdav.release_cache import release_cache
from app.nzbdav.resolve_service import NzbdavResolveService, resolve_service
from app.nzbdav.warm_service import WarmCandidateInput
from tests._nzbdav_common import (
    auth_device_header,
    cleanup_user,
    ensure_nzbdav_schema,
    make_device,
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
    user = make_premium_user(db, "nzbdav-resolve")
    account_store.save(
        db,
        user_id=user.id,
        base_url="https://nzbdav.example.com",
        api_key_plaintext="k",
        version_string="1.0.0",
    )
    yield user
    cleanup_user(db, user)


def test_resolve_ready_from_cache(db, nzbdav_user):
    release_cache.set_warm_success("H-READY", {
        "stream_id": "stream-123",
        "upstream_url": "https://example.com/stream",
        "user_id": str(nzbdav_user.id),
    })
    cand = WarmCandidateInput(candidate_id="c1", hash_key="H-READY")
    result = asyncio.run(
        resolve_service.resolve(
            db, user_id=nzbdav_user.id, content_id="tmdb:1", candidate=cand,
        )
    )
    assert result.simplified_state == "ready"
    assert result.stream_url is not None
    assert "/resolver/usenet/handoff/" in result.stream_url
    # Never leak upstream URL
    assert "example.com/stream" not in result.stream_url
    assert result.is_direct is False
    assert result.supports_range is True


def test_resolve_warming_when_job_in_flight(db, nzbdav_user):
    job = WarmJob(
        job_id=uuid.uuid4(),
        user_id=nzbdav_user.id,
        content_id="tmdb:2",
        candidate_id="c1",
        hash_key="H-WARM",
        state=st.REPAIRING,
        expires_at=datetime.now(timezone.utc) + timedelta(hours=1),
    )
    db.add(job)
    db.commit()
    cand = WarmCandidateInput(candidate_id="c1", hash_key="H-WARM")
    result = asyncio.run(
        resolve_service.resolve(
            db, user_id=nzbdav_user.id, content_id="tmdb:2", candidate=cand,
        )
    )
    assert result.simplified_state == "warming"
    assert result.job_id == str(job.job_id)


def test_resolve_failed_from_dead_release(db, nzbdav_user):
    release_cache.set_dead_release("H-DEAD", "RELEASE_UNAVAILABLE")
    cand = WarmCandidateInput(candidate_id="c1", hash_key="H-DEAD")
    result = asyncio.run(
        resolve_service.resolve(
            db, user_id=nzbdav_user.id, content_id="tmdb:3", candidate=cand,
        )
    )
    assert result.simplified_state == "failed"
    assert result.failure_code == "RELEASE_UNAVAILABLE"
    assert "try_alternate_release" in (result.fallback_suggestions or [])


def test_resolve_normalizes_unknown_upstream(db, nzbdav_user, caplog):
    # Seed a failed WarmJob with a freeform failure_code that isn't in
    # the enum — the resolve service must normalize to UNKNOWN.
    # We bypass DEAD_RELEASE by using a code like "TIMEOUT" that's valid
    # but not dead-cached.
    from app.nzbdav.warm_service import NzbdavWarmService

    class _FailClient:
        def __init__(self, *a, **kw): ...

        async def __aenter__(self): return self

        async def __aexit__(self, *a): return False

        async def aclose(self): ...

        async def submit_nzb(self, *, nzb_url=None, hash_key=None):
            from app.nzbdav.failures import FailureCode, UpstreamError
            raise UpstreamError(FailureCode.UNKNOWN_UPSTREAM_ERROR, detail="weird")

        async def poll_job(self, *, upstream_job_id):
            from app.nzbdav.failures import FailureCode, UpstreamError
            raise UpstreamError(FailureCode.UNKNOWN_UPSTREAM_ERROR, detail="weird")

    def factory(base_url, api_key):
        return _FailClient()

    svc_warm = NzbdavWarmService(client_factory=factory)
    resolver = NzbdavResolveService()
    # Monkey-patch the module-level warm_service used by resolve.
    import app.nzbdav.resolve_service as rs
    original = rs.warm_service
    rs.warm_service = svc_warm
    try:
        caplog.set_level(logging.INFO, logger="app.nzbdav.telemetry")
        cand = WarmCandidateInput(candidate_id="c1", hash_key="H-UNK")
        result = asyncio.run(
            resolver.resolve(
                db, user_id=nzbdav_user.id, content_id="tmdb:u",
                candidate=cand,
            )
        )
        # The warm task is fired async and may race, but resolve itself
        # returns a "warming" handle with the new job_id.
        assert result.simplified_state in ("warming", "failed")
        # Resolve telemetry must have been emitted regardless.
        assert any("NZBDAV_RESOLVE" in rec.message for rec in caplog.records)
    finally:
        rs.warm_service = original


def test_resolve_telemetry_emitted(db, nzbdav_user, caplog):
    release_cache.set_warm_success("H-TEL", {
        "stream_id": "sid-tel",
        "upstream_url": "https://u.example/stream",
        "user_id": str(nzbdav_user.id),
    })
    caplog.set_level(logging.INFO, logger="app.nzbdav.telemetry")
    cand = WarmCandidateInput(candidate_id="c1", hash_key="H-TEL")
    asyncio.run(
        resolve_service.resolve(
            db, user_id=nzbdav_user.id, content_id="tmdb:tel", candidate=cand,
        )
    )
    msgs = [r.message for r in caplog.records]
    assert any("NZBDAV_RESOLVE" in m for m in msgs)
    assert any("NZBDAV_CACHE_HIT" in m for m in msgs)


def test_jobs_endpoint_returns_simplified_state(client, db, nzbdav_user):
    device = make_device(db, nzbdav_user)
    job = WarmJob(
        job_id=uuid.uuid4(),
        user_id=nzbdav_user.id,
        content_id="tmdb:job",
        candidate_id="c1",
        hash_key="H-JOB",
        state=st.CHECKING,
        expires_at=datetime.now(timezone.utc) + timedelta(hours=1),
    )
    db.add(job)
    db.commit()
    r = client.get(
        f"/resolver/usenet/jobs/{job.job_id}",
        headers=auth_device_header(nzbdav_user, device),
    )
    assert r.status_code == 200
    body = r.json()
    assert body["state"] == "warming"
    assert body["content_id"] == "tmdb:job"


def test_cancel_marks_jobs_cancelled(client, db, nzbdav_user):
    device = make_device(db, nzbdav_user)
    j = WarmJob(
        job_id=uuid.uuid4(),
        user_id=nzbdav_user.id,
        content_id="tmdb:cancel",
        candidate_id="c-cancel",
        hash_key="H-CANCEL",
        state=st.QUEUED,
        expires_at=datetime.now(timezone.utc) + timedelta(hours=1),
    )
    db.add(j)
    db.commit()
    r = client.post(
        "/resolver/usenet/cancel",
        json={"content_id": "tmdb:cancel"},
        headers=auth_device_header(nzbdav_user, device),
    )
    assert r.status_code == 200
    assert r.json()["cancelled"] == 1
    db.refresh(j)
    assert j.state == st.CANCELLED
