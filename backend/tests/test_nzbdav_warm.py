"""Warm flow tests for the NzbDAV integration.

Covers:
- Cache hit short-circuit (warm_success + dead_release)
- Dedupe on same hash_key (no duplicate WarmJob created)
- Dead-release suppression returns failed immediately
- Per-user concurrency cap enforcement
"""
from __future__ import annotations

import asyncio
import uuid

import pytest

from app.config import settings
from app.models import WarmJob
from app.nzbdav import warm_service as ws_mod
from app.nzbdav.account_store import account_store
from app.nzbdav.release_cache import release_cache
from app.nzbdav.warm_service import (
    NzbdavWarmService,
    WarmCandidateInput,
    concurrency,
)
from tests._nzbdav_common import (
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
    user = make_premium_user(db, "nzbdav-warm")
    # Save a config row directly (bypassing router)
    account_store.save(
        db,
        user_id=user.id,
        base_url="https://nzbdav.example.com",
        api_key_plaintext="k",
        version_string="1.0.0",
    )
    yield user
    cleanup_user(db, user)


class _NeverCalledClient:
    """Factory target: blows up if anyone tries to actually submit."""

    def __init__(self, *a, **kw):
        raise AssertionError("upstream client should not be constructed")


def _no_client_factory(base_url, api_key):  # pragma: no cover - guard
    raise AssertionError("client factory should not be invoked")


def test_cache_hit_short_circuits_upstream(db, nzbdav_user):
    svc = NzbdavWarmService(client_factory=_no_client_factory)
    # Prime warm_success cache.
    release_cache.set_warm_success("HASH-A", {
        "stream_id": str(uuid.uuid4()),
        "upstream_url": "https://example.com/stream",
        "user_id": str(nzbdav_user.id),
    })
    cand = WarmCandidateInput(candidate_id="c1", hash_key="HASH-A")
    results = asyncio.run(
        svc.start_warm(
            db,
            user_id=nzbdav_user.id,
            content_id="tmdb:1",
            candidates=[cand],
            top_n=1,
        )
    )
    assert len(results) == 1
    assert results[0].simplified_state == "ready"
    # No WarmJob row should be created.
    assert db.query(WarmJob).filter_by(user_id=nzbdav_user.id).count() == 0


def test_dead_release_short_circuits_upstream(db, nzbdav_user):
    svc = NzbdavWarmService(client_factory=_no_client_factory)
    release_cache.set_dead_release("HASH-B", "RELEASE_UNAVAILABLE")
    cand = WarmCandidateInput(candidate_id="c1", hash_key="HASH-B")
    results = asyncio.run(
        svc.start_warm(
            db,
            user_id=nzbdav_user.id,
            content_id="tmdb:1",
            candidates=[cand],
            top_n=1,
        )
    )
    assert len(results) == 1
    assert results[0].simplified_state == "failed"
    assert results[0].failure_code == "RELEASE_UNAVAILABLE"
    assert "try_alternate_release" in results[0].fallback_suggestions
    assert db.query(WarmJob).filter_by(user_id=nzbdav_user.id).count() == 0


def test_dedupe_same_hash_key(db, nzbdav_user):
    # Seed a WarmJob that's already "warming" (state=QUEUED)
    from app.nzbdav import state as st
    from datetime import datetime, timedelta, timezone
    job = WarmJob(
        job_id=uuid.uuid4(),
        user_id=nzbdav_user.id,
        content_id="tmdb:1",
        candidate_id="c1",
        hash_key="HASH-C",
        state=st.QUEUED,
        expires_at=datetime.now(timezone.utc) + timedelta(hours=1),
    )
    db.add(job)
    db.commit()

    svc = NzbdavWarmService(client_factory=_no_client_factory)
    cand = WarmCandidateInput(candidate_id="c1", hash_key="HASH-C")
    results = asyncio.run(
        svc.start_warm(
            db,
            user_id=nzbdav_user.id,
            content_id="tmdb:1",
            candidates=[cand],
            top_n=1,
        )
    )
    assert len(results) == 1
    assert results[0].simplified_state == "warming"
    assert results[0].job_id == str(job.job_id)
    # Still exactly one job row (no duplicate was created).
    assert db.query(WarmJob).filter_by(
        user_id=nzbdav_user.id, hash_key="HASH-C"
    ).count() == 1


def test_concurrency_cap_returns_warming_without_upstream(
    db, nzbdav_user, monkeypatch
):
    # Pin cap to 1 so the second candidate can't acquire.
    monkeypatch.setattr(settings, "NZBDAV_WARM_CONCURRENCY_PER_USER", 1)

    calls = {"n": 0}

    def factory(base_url, api_key):
        calls["n"] += 1

        class _C:
            async def __aenter__(self):
                return self

            async def __aexit__(self, *a):
                return False

            async def aclose(self):
                pass

            async def submit_nzb(self, *, nzb_url=None, hash_key=None):
                # Sleep forever so concurrency stays pinned at 1.
                await asyncio.sleep(60)

            async def poll_job(self, *, upstream_job_id):
                await asyncio.sleep(60)

        return _C()

    svc = NzbdavWarmService(client_factory=factory)

    async def _run():
        cands = [
            WarmCandidateInput(candidate_id="c1", hash_key="H1"),
            WarmCandidateInput(candidate_id="c2", hash_key="H2"),
        ]
        r = await svc.start_warm(
            db,
            user_id=nzbdav_user.id,
            content_id="tmdb:1",
            candidates=cands,
            top_n=2,
        )
        # Release the background task so it doesn't linger.
        return r

    loop = asyncio.new_event_loop()
    try:
        results = loop.run_until_complete(_run())
    finally:
        # Cancel any pending background tasks before closing.
        pending = asyncio.all_tasks(loop)
        for t in pending:
            t.cancel()
        loop.run_until_complete(asyncio.gather(*pending, return_exceptions=True))
        loop.close()

    assert len(results) == 2
    # First acquired the slot, second got over-cap -> warming (no upstream).
    states = sorted(r.simplified_state for r in results)
    assert states == ["warming", "warming"]
    # Only one upstream client was constructed (second over-cap never got one).
    assert calls["n"] == 1
    # Reset the leaked concurrency count from the still-running task.
    concurrency.reset()
