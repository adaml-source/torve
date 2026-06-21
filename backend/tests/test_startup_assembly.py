"""Tests for the startup candidate assembler.

Covers:
- Scoring: exact match, carryforward, frequency, recency, inventory, cached, provider reliability
- Score ordering: higher-confidence candidates rank first
- Reason codes: each signal produces the correct reason
- Deduplication: same hash from multiple sources = one candidate
- Empty state: returns empty, no crash
- Provider reliability computation
- Determinism: same inputs always produce same order
- API integration: /startup endpoint returns scored candidates
"""
import uuid
from datetime import datetime, timedelta, timezone

from app.hash_availability_service import record_observation
from app.inventory_service import InventoryItem, ingest_snapshot
from app.models import (
    HashAvailabilityMemory,
    ProviderInventorySnapshot,
    ResolveSuccessMemory,
    User,
    UserEntitlement,
)
from app.resolve_memory_service import record_success
from app.security import create_access_token, hash_password
from app.startup_assembly import (
    REASON_EXACT_MATCH,
    REASON_CARRYFORWARD,
    REASON_HIGH_FREQUENCY,
    REASON_INVENTORY_HIT,
    REASON_KNOWN_CACHED,
    REASON_PROVIDER_RELIABLE,
    REASON_RECENT_SUCCESS,
    ScoredCandidate,
    _compute_provider_reliability,
    _score_candidate,
    assemble_startup_candidates,
)


def _make_user(db, *, premium=True):
    user = User(
        id=uuid.uuid4(),
        email=f"asm-{uuid.uuid4().hex[:8]}@test.com",
        password_hash=hash_password("TestPass123!"),
        is_verified=True,
        has_lifetime_access=premium,
        has_premium_access=premium,
    )
    db.add(user)
    db.flush()
    if premium:
        ent = UserEntitlement(
            user_id=user.id,
            entitlement_type="lifetime_access",
            source="admin_grant",
            source_ref=f"asm-{user.id}",
            status="active",
        )
        db.add(ent)
    db.commit()
    db.refresh(user)
    return user


def _auth(user):
    return {"Authorization": f"Bearer {create_access_token(str(user.id))}"}


def _cleanup(db, user):
    db.query(ResolveSuccessMemory).filter(ResolveSuccessMemory.user_id == user.id).delete()
    db.query(HashAvailabilityMemory).filter(HashAvailabilityMemory.user_id == user.id).delete()
    db.query(ProviderInventorySnapshot).filter(ProviderInventorySnapshot.user_id == user.id).delete()
    db.query(UserEntitlement).filter(UserEntitlement.user_id == user.id).delete()
    db.commit()
    u = db.query(User).filter(User.id == user.id).first()
    if u:
        db.delete(u)
        db.commit()


# ── Unit tests: _score_candidate ────────────────────────────────────────


class TestScoring:

    def test_exact_match_scores_highest_base(self):
        now = datetime.now(timezone.utc)
        c = ScoredCandidate(
            provider_type="rd", source_key="src:1",
            provenance="recent_success",
            success_count=5,
            last_success_at=now.isoformat(),
        )
        _score_candidate(c, now, {"rd": 1.0}, is_exact_episode=True)
        assert c.score > 0
        assert REASON_EXACT_MATCH in c.reasons
        assert "exact_content_match" in c.score_breakdown

    def test_carryforward_scores_lower_than_exact(self):
        now = datetime.now(timezone.utc)
        exact = ScoredCandidate(
            provider_type="rd", provenance="recent_success",
            success_count=1, last_success_at=now.isoformat(),
        )
        carry = ScoredCandidate(
            provider_type="rd", provenance="carryforward",
            success_count=1, last_success_at=now.isoformat(),
        )
        _score_candidate(exact, now, {}, is_exact_episode=True)
        _score_candidate(carry, now, {}, is_exact_episode=True)
        assert exact.score > carry.score
        assert REASON_CARRYFORWARD in carry.reasons

    def test_high_frequency_reason(self):
        now = datetime.now(timezone.utc)
        c = ScoredCandidate(
            provider_type="rd", provenance="recent_success",
            success_count=10, last_success_at=now.isoformat(),
        )
        _score_candidate(c, now, {}, is_exact_episode=False)
        assert REASON_HIGH_FREQUENCY in c.reasons
        assert c.score_breakdown.get("success_frequency", 0) > 0

    def test_low_frequency_no_reason(self):
        now = datetime.now(timezone.utc)
        c = ScoredCandidate(
            provider_type="rd", provenance="recent_success",
            success_count=1, last_success_at=now.isoformat(),
        )
        _score_candidate(c, now, {}, is_exact_episode=False)
        assert REASON_HIGH_FREQUENCY not in c.reasons

    def test_recency_decay(self):
        now = datetime.now(timezone.utc)
        recent = ScoredCandidate(
            provider_type="rd", provenance="recent_success",
            success_count=1, last_success_at=now.isoformat(),
        )
        old = ScoredCandidate(
            provider_type="rd", provenance="recent_success",
            success_count=1,
            last_success_at=(now - timedelta(days=10)).isoformat(),
        )
        _score_candidate(recent, now, {}, is_exact_episode=False)
        _score_candidate(old, now, {}, is_exact_episode=False)
        assert recent.score_breakdown["success_recency"] > old.score_breakdown["success_recency"]
        assert REASON_RECENT_SUCCESS in recent.reasons
        assert REASON_RECENT_SUCCESS not in old.reasons

    def test_inventory_match_signal(self):
        now = datetime.now(timezone.utc)
        c = ScoredCandidate(
            provider_type="rd", provenance="inventory_match",
            inventory_class="cloud",
        )
        _score_candidate(c, now, {}, is_exact_episode=False)
        assert REASON_INVENTORY_HIT in c.reasons
        assert c.score_breakdown.get("inventory_direct", 0) > 0

    def test_known_cached_signal(self):
        now = datetime.now(timezone.utc)
        c = ScoredCandidate(
            provider_type="rd", provenance="recent_success",
            is_cached=True, success_count=1,
            last_success_at=now.isoformat(),
        )
        _score_candidate(c, now, {}, is_exact_episode=False)
        assert REASON_KNOWN_CACHED in c.reasons
        assert c.score_breakdown.get("known_cached", 0) > 0

    def test_provider_reliability_signal(self):
        now = datetime.now(timezone.utc)
        c = ScoredCandidate(
            provider_type="rd", provenance="recent_success",
            success_count=1, last_success_at=now.isoformat(),
        )
        _score_candidate(c, now, {"rd": 0.8}, is_exact_episode=False)
        assert REASON_PROVIDER_RELIABLE in c.reasons
        assert c.score_breakdown.get("provider_reliability", 0) > 0

    def test_low_reliability_no_reason(self):
        now = datetime.now(timezone.utc)
        c = ScoredCandidate(
            provider_type="rd", provenance="recent_success",
            success_count=1, last_success_at=now.isoformat(),
        )
        _score_candidate(c, now, {"rd": 0.3}, is_exact_episode=False)
        assert REASON_PROVIDER_RELIABLE not in c.reasons

    def test_combined_max_score(self):
        """A candidate with all signals should score near the max."""
        now = datetime.now(timezone.utc)
        c = ScoredCandidate(
            provider_type="rd", provenance="recent_success",
            success_count=20, last_success_at=now.isoformat(),
            is_cached=True, inventory_class="cloud",
        )
        _score_candidate(c, now, {"rd": 1.0}, is_exact_episode=True)
        # Theoretical max ≈ 92 (exact 20 + freq 15 + recency 15 + inv 12 + cached 20 + prov 10)
        assert c.score >= 70
        assert len(c.reasons) >= 4


# ── Unit tests: provider reliability ────────────────────────────────────


class TestProviderReliability:

    def test_single_provider(self, db):
        user = _make_user(db)
        try:
            for _ in range(5):
                record_success(db, user_id=user.id, content_id="tmdb:r1",
                               provider_type="rd", source_key="src:r1")
            db.commit()
            rel = _compute_provider_reliability(db, user.id)
            assert rel["rd"] == 1.0
        finally:
            _cleanup(db, user)

    def test_multiple_providers_relative(self, db):
        user = _make_user(db)
        try:
            for _ in range(10):
                record_success(db, user_id=user.id, content_id="tmdb:r2",
                               provider_type="rd", source_key="src:a")
            for _ in range(5):
                record_success(db, user_id=user.id, content_id="tmdb:r2",
                               provider_type="pm", source_key="src:b")
            db.commit()
            rel = _compute_provider_reliability(db, user.id)
            assert rel["rd"] == 1.0
            assert 0.4 <= rel["pm"] <= 0.6
        finally:
            _cleanup(db, user)

    def test_empty_returns_empty(self, db):
        user = _make_user(db)
        try:
            rel = _compute_provider_reliability(db, user.id)
            assert rel == {}
        finally:
            _cleanup(db, user)


# ── Integration tests: assemble_startup_candidates ──────────────────────


class TestAssembly:

    def test_empty_state(self, db):
        user = _make_user(db)
        try:
            result = assemble_startup_candidates(
                db, user_id=user.id, content_id="tmdb:empty",
            )
            assert result.candidates == []
            assert result.sources_used == []
        finally:
            _cleanup(db, user)

    def test_exact_match_ranked_first(self, db):
        user = _make_user(db)
        try:
            # Two sources, different success counts
            for _ in range(5):
                record_success(db, user_id=user.id, content_id="tmdb:rank",
                               provider_type="rd", source_key="src:frequent",
                               infohash="a" * 40)
            record_success(db, user_id=user.id, content_id="tmdb:rank",
                           provider_type="rd", source_key="src:rare",
                           infohash="b" * 40)
            db.commit()

            result = assemble_startup_candidates(
                db, user_id=user.id, content_id="tmdb:rank",
            )
            assert len(result.candidates) == 2
            assert result.candidates[0].source_key == "src:frequent"
            assert result.candidates[0].score > result.candidates[1].score
        finally:
            _cleanup(db, user)

    def test_cached_enrichment_boosts_score(self, db):
        user = _make_user(db)
        try:
            record_success(db, user_id=user.id, content_id="tmdb:cached",
                           provider_type="rd", source_key="src:c",
                           infohash="c" * 40)
            record_success(db, user_id=user.id, content_id="tmdb:cached",
                           provider_type="rd", source_key="src:nc",
                           infohash="d" * 40)
            # Mark one as cached
            record_observation(db, user_id=user.id, provider_type="rd",
                               infohash="c" * 40, is_cached=True)
            db.commit()

            result = assemble_startup_candidates(
                db, user_id=user.id, content_id="tmdb:cached",
            )
            cached_c = next(c for c in result.candidates if c.infohash == "c" * 40)
            not_cached = next(c for c in result.candidates if c.infohash == "d" * 40)
            assert cached_c.is_cached is True
            assert cached_c.score > not_cached.score
            assert "known_cached" in result.sources_used
        finally:
            _cleanup(db, user)

    def test_inventory_plus_success_dedup(self, db):
        user = _make_user(db)
        try:
            # Same hash from success and inventory
            record_success(db, user_id=user.id, content_id="tmdb:dedup",
                           provider_type="rd", source_key="src:dd",
                           infohash="e" * 40)
            ingest_snapshot(db, user_id=user.id, provider_type="rd", items=[
                InventoryItem(remote_item_id="rd:dd", infohash="e" * 40,
                              inventory_class="cloud"),
            ])
            db.commit()

            result = assemble_startup_candidates(
                db, user_id=user.id, content_id="tmdb:dedup",
                infohash="e" * 40,
            )
            # Should be deduped to one candidate
            hashes = [c.infohash for c in result.candidates]
            assert hashes.count("e" * 40) == 1
            # Should have inventory_class merged
            c = result.candidates[0]
            assert c.inventory_class == "cloud"
        finally:
            _cleanup(db, user)

    def test_episodic_carryforward(self, db):
        user = _make_user(db)
        try:
            for ep in (1, 2, 3):
                record_success(db, user_id=user.id, content_id="tmdb:series",
                               season=1, episode=ep,
                               provider_type="rd", source_key="src:pack")
            db.commit()

            result = assemble_startup_candidates(
                db, user_id=user.id, content_id="tmdb:series",
                season=1, episode=4,
            )
            assert len(result.candidates) >= 1
            assert "carryforward" in result.sources_used
            assert result.candidates[0].provenance == "carryforward"
            assert REASON_CARRYFORWARD in result.candidates[0].reasons
        finally:
            _cleanup(db, user)

    def test_deterministic_order(self, db):
        user = _make_user(db)
        try:
            for i in range(5):
                record_success(db, user_id=user.id, content_id="tmdb:det",
                               provider_type="rd", source_key=f"src:{i}",
                               infohash=f"{i}" * 40)
            db.commit()

            r1 = assemble_startup_candidates(db, user_id=user.id, content_id="tmdb:det")
            r2 = assemble_startup_candidates(db, user_id=user.id, content_id="tmdb:det")
            keys1 = [(c.source_key, c.score) for c in r1.candidates]
            keys2 = [(c.source_key, c.score) for c in r2.candidates]
            assert keys1 == keys2
        finally:
            _cleanup(db, user)

    def test_max_candidates_limit(self, db):
        user = _make_user(db)
        try:
            for i in range(20):
                record_success(db, user_id=user.id, content_id="tmdb:limit",
                               provider_type="rd", source_key=f"src:{i}")
            db.commit()

            result = assemble_startup_candidates(
                db, user_id=user.id, content_id="tmdb:limit",
                max_candidates=5,
            )
            assert len(result.candidates) <= 5
        finally:
            _cleanup(db, user)


# ── API integration ─────────────────────────────────────────────────────


class TestStartupAPI:

    def test_startup_returns_scored(self, client, db):
        user = _make_user(db)
        try:
            # Record some data
            client.post("/me/acceleration/outcome", headers=_auth(user), json={
                "content_id": "tmdb:api", "provider_type": "rd",
                "source_key": "src:api", "success": True,
                "infohash": "f" * 40, "quality": "1080p",
            })

            r = client.get("/me/acceleration/startup?content_id=tmdb:api",
                           headers=_auth(user))
            assert r.status_code == 200
            body = r.json()
            assert len(body["candidates"]) >= 1

            c = body["candidates"][0]
            assert "score" in c
            assert c["score"] > 0
            assert "reasons" in c
            assert len(c["reasons"]) > 0
            assert "score_breakdown" in c
            assert isinstance(c["score_breakdown"], dict)
            assert body.get("assembled_at") is not None
        finally:
            _cleanup(db, user)

    def test_startup_empty_is_graceful(self, client, db):
        user = _make_user(db)
        try:
            r = client.get("/me/acceleration/startup?content_id=tmdb:nope",
                           headers=_auth(user))
            assert r.status_code == 200
            body = r.json()
            assert body["candidates"] == []
            assert body["sources_used"] == []
        finally:
            _cleanup(db, user)

    def test_startup_provider_reliability_included(self, client, db):
        user = _make_user(db)
        try:
            for _ in range(3):
                client.post("/me/acceleration/outcome", headers=_auth(user), json={
                    "content_id": "tmdb:prel", "provider_type": "rd",
                    "source_key": "src:rel", "success": True,
                })

            r = client.get("/me/acceleration/startup?content_id=tmdb:prel",
                           headers=_auth(user))
            body = r.json()
            assert "provider_reliability" in body
            assert "rd" in body["provider_reliability"]
            assert body["provider_reliability"]["rd"] > 0
        finally:
            _cleanup(db, user)


# ── Hardening edge cases ────────────────────────────────────────────────


class TestHardeningEdgeCases:

    def test_null_infohash_and_source_key_not_collapsed(self, db):
        """Candidates with no infohash and no source_key should not be collapsed."""
        from app.startup_assembly import _deduplicate, ScoredCandidate
        c1 = ScoredCandidate(provider_type="rd", provenance="inventory_match", score=10.0)
        c2 = ScoredCandidate(provider_type="rd", provenance="inventory_match", score=5.0)
        result = _deduplicate([c1, c2])
        assert len(result) == 2

    def test_deduplicate_preserves_memory_id_from_lower_scored_duplicate(self, db):
        """Inventory can win scoring without dropping the backend memory reference."""
        from app.startup_assembly import _deduplicate, ScoredCandidate
        recent = ScoredCandidate(
            provider_type="real_debrid",
            provenance="recent_success",
            memory_id="mem-123",
            source_key=None,
            infohash="a" * 40,
            success_count=1,
            last_success_at="2026-05-17T00:00:00+00:00",
            score=20.0,
        )
        inventory = ScoredCandidate(
            provider_type="real_debrid",
            provenance="inventory_match",
            infohash="a" * 40,
            inventory_class="cloud",
            score=40.0,
        )

        result = _deduplicate([recent, inventory])

        assert len(result) == 1
        assert result[0].provenance == "inventory_match"
        assert result[0].memory_id == "mem-123"
        assert result[0].success_count == 1

    def test_unicode_content_id(self, client, db):
        """Unicode in content_id doesn't crash."""
        user = _make_user(db)
        try:
            r = client.post("/me/acceleration/outcome", headers=_auth(user), json={
                "content_id": "tmdb:日本語テスト", "provider_type": "rd",
                "source_key": "src:unicode", "success": True,
            })
            assert r.status_code == 201

            r = client.get("/me/acceleration/sources?content_id=tmdb:日本語テスト",
                           headers=_auth(user))
            assert r.status_code == 200
            assert len(r.json()["candidates"]) == 1
        finally:
            _cleanup(db, user)

    def test_empty_inventory_ingest(self, client, db):
        """Ingesting empty items list doesn't crash."""
        user = _make_user(db)
        try:
            r = client.post("/me/acceleration/inventory/ingest", headers=_auth(user), json={
                "provider_type": "real_debrid",
                "items": [],
            })
            assert r.status_code == 201
            assert r.json()["upserted"] == 0
        finally:
            _cleanup(db, user)

    def test_cleanup_when_nothing_expired(self, db):
        """cleanup_expired with no expired rows returns 0, no crash."""
        from app.resolve_memory_service import cleanup_expired as rsm_cleanup
        from app.inventory_service import cleanup_expired as inv_cleanup
        assert rsm_cleanup(db) == 0
        assert inv_cleanup(db) == 0

    def test_record_batch_empty_observations(self, db):
        """Empty observations batch returns 0."""
        from app.hash_availability_service import record_batch
        user = _make_user(db)
        try:
            count = record_batch(db, user_id=user.id, provider_type="rd", observations=[])
            assert count == 0
        finally:
            _cleanup(db, user)

    def test_record_batch_invalid_observations(self, db):
        """Observations without infohash are skipped."""
        from app.hash_availability_service import record_batch
        user = _make_user(db)
        try:
            count = record_batch(db, user_id=user.id, provider_type="rd",
                                 observations=[{"is_cached": True}, {}, {"infohash": "", "is_cached": True}])
            assert count == 0  # All skipped (empty or missing infohash)
        finally:
            _cleanup(db, user)

    def test_success_then_success_clears_failure(self, db):
        """A success after a failure should make the source rank-eligible again."""
        from app.resolve_memory_service import record_success, record_failure, get_best_for_content
        user = _make_user(db)
        try:
            record_success(db, user_id=user.id, content_id="tmdb:recover",
                           provider_type="rd", source_key="src:flaky")
            db.commit()
            record_failure(db, user_id=user.id, content_id="tmdb:recover",
                           provider_type="rd", source_key="src:flaky")
            db.commit()

            # Source should be excluded (failure > success)
            best = get_best_for_content(db, user_id=user.id, content_id="tmdb:recover")
            assert best is None

            # Succeed again
            record_success(db, user_id=user.id, content_id="tmdb:recover",
                           provider_type="rd", source_key="src:flaky")
            db.commit()

            # Now it should be back (success > failure)
            best = get_best_for_content(db, user_id=user.id, content_id="tmdb:recover")
            assert best is not None
            assert best.success_count == 2
        finally:
            _cleanup(db, user)

    def test_movie_vs_episode_isolation(self, db):
        """Movie (no season/episode) and episode data don't interfere."""
        from app.resolve_memory_service import record_success, get_best_for_content
        user = _make_user(db)
        try:
            record_success(db, user_id=user.id, content_id="tmdb:mixed",
                           provider_type="rd", source_key="src:movie")
            record_success(db, user_id=user.id, content_id="tmdb:mixed",
                           season=1, episode=1,
                           provider_type="rd", source_key="src:ep1")
            db.commit()

            movie = get_best_for_content(db, user_id=user.id, content_id="tmdb:mixed")
            assert movie.source_key == "src:movie"

            ep = get_best_for_content(db, user_id=user.id, content_id="tmdb:mixed",
                                      season=1, episode=1)
            assert ep.source_key == "src:ep1"
        finally:
            _cleanup(db, user)
