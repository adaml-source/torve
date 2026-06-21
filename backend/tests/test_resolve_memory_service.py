"""Tests for resolve memory service write and read paths.

Covers:
- record_success: insert, upsert/increment, metadata update
- record_failure: marks known sources, ignores unknown
- get_best_for_content: ranking, failure penalty
- get_recent_for_content: recency ordering
- get_provider_success_set: aggregation across content
- get_series_carryforward: adjacent episode lookup
- Empty memory returns (no crash, no results)
- User isolation
- Expiry behavior
- cleanup_expired
"""
import uuid
from datetime import datetime, timedelta, timezone

import pytest
from sqlalchemy.orm import Session

from app.models import ResolveSuccessMemory, User
from app.resolve_memory_service import (
    cleanup_expired,
    get_best_for_content,
    get_provider_success_set,
    get_recent_for_content,
    get_series_carryforward,
    record_failure,
    record_success,
)
from app.security import hash_password


def _make_user(db: Session) -> User:
    user = User(
        id=uuid.uuid4(),
        email=f"rms-{uuid.uuid4().hex[:8]}@test.com",
        password_hash=hash_password("TestPass123!"),
        is_verified=True,
    )
    db.add(user)
    db.commit()
    db.refresh(user)
    return user


def _cleanup(db: Session, user: User):
    db.query(ResolveSuccessMemory).filter(
        ResolveSuccessMemory.user_id == user.id
    ).delete()
    db.commit()
    u = db.query(User).filter(User.id == user.id).first()
    if u:
        db.delete(u)
        db.commit()


# ── record_success ──────────────────────────────────────────────────────


class TestRecordSuccess:

    def test_new_insert(self, db):
        user = _make_user(db)
        try:
            row = record_success(
                db, user_id=user.id,
                content_id="tmdb:100", provider_type="real_debrid",
                source_key="torrentio:abc",
                infohash="a" * 40, quality="1080p",
                file_name="Movie.2024.1080p.mkv",
            )
            db.commit()
            assert row.success_count == 1
            assert row.quality == "1080p"
            assert row.infohash == "a" * 40
        finally:
            _cleanup(db, user)

    def test_upsert_increments(self, db):
        user = _make_user(db)
        try:
            record_success(
                db, user_id=user.id,
                content_id="tmdb:200", provider_type="rd",
                source_key="src:1",
            )
            db.commit()

            row = record_success(
                db, user_id=user.id,
                content_id="tmdb:200", provider_type="rd",
                source_key="src:1",
            )
            db.commit()
            assert row.success_count == 2

            row = record_success(
                db, user_id=user.id,
                content_id="tmdb:200", provider_type="rd",
                source_key="src:1",
            )
            db.commit()
            assert row.success_count == 3
        finally:
            _cleanup(db, user)

    def test_episodic_content(self, db):
        user = _make_user(db)
        try:
            r1 = record_success(
                db, user_id=user.id,
                content_id="tmdb:300", season=1, episode=1,
                provider_type="rd", source_key="src:ep1",
            )
            r2 = record_success(
                db, user_id=user.id,
                content_id="tmdb:300", season=1, episode=2,
                provider_type="rd", source_key="src:ep2",
            )
            db.commit()
            # Different episodes = different rows
            assert r1.id != r2.id
        finally:
            _cleanup(db, user)

    def test_metadata_backfill(self, db):
        """Metadata gets enriched on subsequent successes."""
        user = _make_user(db)
        try:
            record_success(
                db, user_id=user.id,
                content_id="tmdb:400", provider_type="rd",
                source_key="src:meta",
            )
            db.commit()

            row = record_success(
                db, user_id=user.id,
                content_id="tmdb:400", provider_type="rd",
                source_key="src:meta",
                infohash="f" * 40,
                quality="4k",
                audio_flags="atmos",
                file_size=5_000_000_000,
                device_type="tv",
            )
            db.commit()
            assert row.infohash == "f" * 40
            assert row.quality == "4k"
            assert row.audio_flags == "atmos"
            assert row.file_size == 5_000_000_000
            assert row.last_device_type == "tv"
        finally:
            _cleanup(db, user)

    def test_different_providers_separate_rows(self, db):
        user = _make_user(db)
        try:
            r1 = record_success(
                db, user_id=user.id,
                content_id="tmdb:500", provider_type="real_debrid",
                source_key="src:same",
            )
            r2 = record_success(
                db, user_id=user.id,
                content_id="tmdb:500", provider_type="premiumize",
                source_key="src:same",
            )
            db.commit()
            assert r1.id != r2.id
        finally:
            _cleanup(db, user)


# ── record_failure ──────────────────────────────────────────────────────


class TestRecordFailure:

    def test_marks_known_source(self, db):
        user = _make_user(db)
        try:
            record_success(
                db, user_id=user.id,
                content_id="tmdb:600", provider_type="rd",
                source_key="src:fail",
            )
            db.commit()

            row = record_failure(
                db, user_id=user.id,
                content_id="tmdb:600", provider_type="rd",
                source_key="src:fail",
            )
            db.commit()
            assert row is not None
            assert row.last_failure_at is not None
            assert row.last_failure_at > row.last_success_at
        finally:
            _cleanup(db, user)

    def test_ignores_unknown_source(self, db):
        user = _make_user(db)
        try:
            result = record_failure(
                db, user_id=user.id,
                content_id="tmdb:never", provider_type="rd",
                source_key="src:unknown",
            )
            assert result is None
        finally:
            _cleanup(db, user)

    def test_episodic_failure(self, db):
        user = _make_user(db)
        try:
            record_success(
                db, user_id=user.id,
                content_id="tmdb:700", season=2, episode=5,
                provider_type="rd", source_key="src:ep5",
            )
            db.commit()

            row = record_failure(
                db, user_id=user.id,
                content_id="tmdb:700", season=2, episode=5,
                provider_type="rd", source_key="src:ep5",
            )
            db.commit()
            assert row is not None
            assert row.last_failure_at is not None
        finally:
            _cleanup(db, user)


# ── get_best_for_content ────────────────────────────────────────────────


class TestGetBestForContent:

    def test_returns_highest_count(self, db):
        user = _make_user(db)
        try:
            # Source A: 3 successes
            for _ in range(3):
                record_success(
                    db, user_id=user.id,
                    content_id="tmdb:800", provider_type="rd",
                    source_key="src:a",
                )
            # Source B: 1 success
            record_success(
                db, user_id=user.id,
                content_id="tmdb:800", provider_type="rd",
                source_key="src:b",
            )
            db.commit()

            best = get_best_for_content(
                db, user_id=user.id, content_id="tmdb:800",
            )
            assert best is not None
            assert best.source_key == "src:a"
            assert best.success_count == 3
        finally:
            _cleanup(db, user)

    def test_excludes_recently_failed(self, db):
        user = _make_user(db)
        try:
            # Source A: 5 successes but then failed
            for _ in range(5):
                record_success(
                    db, user_id=user.id,
                    content_id="tmdb:810", provider_type="rd",
                    source_key="src:broken",
                )
            db.commit()
            record_failure(
                db, user_id=user.id,
                content_id="tmdb:810", provider_type="rd",
                source_key="src:broken",
            )
            db.commit()

            # Source B: 1 success, still working
            record_success(
                db, user_id=user.id,
                content_id="tmdb:810", provider_type="rd",
                source_key="src:works",
            )
            db.commit()

            best = get_best_for_content(
                db, user_id=user.id, content_id="tmdb:810",
            )
            assert best is not None
            assert best.source_key == "src:works"
        finally:
            _cleanup(db, user)

    def test_empty_memory(self, db):
        user = _make_user(db)
        try:
            best = get_best_for_content(
                db, user_id=user.id, content_id="tmdb:never",
            )
            assert best is None
        finally:
            _cleanup(db, user)

    def test_provider_filter(self, db):
        user = _make_user(db)
        try:
            record_success(
                db, user_id=user.id,
                content_id="tmdb:820", provider_type="rd",
                source_key="src:rd",
            )
            record_success(
                db, user_id=user.id,
                content_id="tmdb:820", provider_type="premiumize",
                source_key="src:pm",
            )
            db.commit()

            best = get_best_for_content(
                db, user_id=user.id, content_id="tmdb:820",
                provider_type="premiumize",
            )
            assert best is not None
            assert best.provider_type == "premiumize"
        finally:
            _cleanup(db, user)

    def test_episodic_exact_match(self, db):
        user = _make_user(db)
        try:
            record_success(
                db, user_id=user.id,
                content_id="tmdb:830", season=1, episode=1,
                provider_type="rd", source_key="src:e1",
            )
            record_success(
                db, user_id=user.id,
                content_id="tmdb:830", season=1, episode=2,
                provider_type="rd", source_key="src:e2",
            )
            db.commit()

            best = get_best_for_content(
                db, user_id=user.id, content_id="tmdb:830",
                season=1, episode=2,
            )
            assert best is not None
            assert best.source_key == "src:e2"
        finally:
            _cleanup(db, user)


# ── get_recent_for_content ──────────────────────────────────────────────


class TestGetRecentForContent:

    def test_recency_order(self, db):
        user = _make_user(db)
        try:
            now = datetime.now(timezone.utc)
            for i, key in enumerate(["old", "medium", "new"]):
                row = record_success(
                    db, user_id=user.id,
                    content_id="tmdb:900", provider_type="rd",
                    source_key=f"src:{key}",
                )
                # Manually set times to control order
                row.last_success_at = now - timedelta(hours=10 - i * 5)
                db.flush()
            db.commit()

            results = get_recent_for_content(
                db, user_id=user.id, content_id="tmdb:900",
            )
            assert len(results) == 3
            assert results[0].source_key == "src:new"
        finally:
            _cleanup(db, user)

    def test_limit(self, db):
        user = _make_user(db)
        try:
            for i in range(10):
                record_success(
                    db, user_id=user.id,
                    content_id="tmdb:910", provider_type="rd",
                    source_key=f"src:{i}",
                )
            db.commit()

            results = get_recent_for_content(
                db, user_id=user.id, content_id="tmdb:910", limit=3,
            )
            assert len(results) == 3
        finally:
            _cleanup(db, user)

    def test_empty_returns_empty_list(self, db):
        user = _make_user(db)
        try:
            results = get_recent_for_content(
                db, user_id=user.id, content_id="tmdb:never",
            )
            assert results == []
        finally:
            _cleanup(db, user)


# ── get_provider_success_set ────────────────────────────────────────────


class TestGetProviderSuccessSet:

    def test_aggregates_across_content(self, db):
        user = _make_user(db)
        try:
            # RD: 3 successes across 2 content items
            for _ in range(2):
                record_success(
                    db, user_id=user.id,
                    content_id="tmdb:1000", provider_type="real_debrid",
                    source_key="src:rd1",
                )
            record_success(
                db, user_id=user.id,
                content_id="tmdb:1001", provider_type="real_debrid",
                source_key="src:rd2",
            )
            # PM: 1 success
            record_success(
                db, user_id=user.id,
                content_id="tmdb:1000", provider_type="premiumize",
                source_key="src:pm1",
            )
            db.commit()

            result = get_provider_success_set(db, user_id=user.id)
            assert len(result) == 2
            # RD should be first (higher total)
            assert result[0]["provider_type"] == "real_debrid"
            assert result[0]["total_successes"] == 3
            assert result[0]["distinct_sources"] == 2
            assert result[1]["provider_type"] == "premiumize"
            assert result[1]["total_successes"] == 1
        finally:
            _cleanup(db, user)

    def test_respects_lookback_window(self, db):
        user = _make_user(db)
        try:
            row = record_success(
                db, user_id=user.id,
                content_id="tmdb:1010", provider_type="rd",
                source_key="src:old",
            )
            # Push success time to 30 days ago
            row.last_success_at = datetime.now(timezone.utc) - timedelta(days=30)
            db.flush()
            db.commit()

            result = get_provider_success_set(db, user_id=user.id, since_days=7)
            assert len(result) == 0
        finally:
            _cleanup(db, user)

    def test_empty_user(self, db):
        user = _make_user(db)
        try:
            result = get_provider_success_set(db, user_id=user.id)
            assert result == []
        finally:
            _cleanup(db, user)


# ── get_series_carryforward ─────────────────────────────────────────────


class TestGetSeriesCarryforward:

    def test_returns_adjacent_episodes(self, db):
        user = _make_user(db)
        try:
            # S01E01, E02, E03 all worked with src:pack
            for ep in (1, 2, 3):
                record_success(
                    db, user_id=user.id,
                    content_id="tmdb:2000", season=1, episode=ep,
                    provider_type="rd", source_key="src:pack",
                )
            db.commit()

            # Looking for carryforward for E04
            results = get_series_carryforward(
                db, user_id=user.id,
                content_id="tmdb:2000", season=1, episode=4,
            )
            assert len(results) == 3
            # Closest episode first
            assert results[0].episode == 3
        finally:
            _cleanup(db, user)

    def test_lookback_limit(self, db):
        user = _make_user(db)
        try:
            for ep in range(1, 10):
                record_success(
                    db, user_id=user.id,
                    content_id="tmdb:2010", season=1, episode=ep,
                    provider_type="rd", source_key=f"src:e{ep}",
                )
            db.commit()

            results = get_series_carryforward(
                db, user_id=user.id,
                content_id="tmdb:2010", season=1, episode=10,
                lookback_episodes=3,
            )
            # Only episodes 7, 8, 9 (3 back from 10)
            episodes = [r.episode for r in results]
            assert all(ep >= 7 for ep in episodes)
        finally:
            _cleanup(db, user)

    def test_excludes_failed_carryforward(self, db):
        user = _make_user(db)
        try:
            for ep in (1, 2, 3):
                record_success(
                    db, user_id=user.id,
                    content_id="tmdb:2020", season=1, episode=ep,
                    provider_type="rd", source_key="src:pack",
                )
            db.commit()

            # E03 source failed
            record_failure(
                db, user_id=user.id,
                content_id="tmdb:2020", season=1, episode=3,
                provider_type="rd", source_key="src:pack",
            )
            db.commit()

            results = get_series_carryforward(
                db, user_id=user.id,
                content_id="tmdb:2020", season=1, episode=4,
            )
            # E03 excluded due to failure, E01 and E02 remain
            episodes = [r.episode for r in results]
            assert 3 not in episodes
            assert len(results) == 2
        finally:
            _cleanup(db, user)

    def test_episode_1_returns_empty(self, db):
        user = _make_user(db)
        try:
            results = get_series_carryforward(
                db, user_id=user.id,
                content_id="tmdb:2030", season=1, episode=1,
            )
            assert results == []
        finally:
            _cleanup(db, user)

    def test_cross_season_no_leakage(self, db):
        user = _make_user(db)
        try:
            # Season 1 data
            record_success(
                db, user_id=user.id,
                content_id="tmdb:2040", season=1, episode=8,
                provider_type="rd", source_key="src:s1",
            )
            db.commit()

            # Season 2 carryforward should not see season 1
            results = get_series_carryforward(
                db, user_id=user.id,
                content_id="tmdb:2040", season=2, episode=2,
            )
            assert results == []
        finally:
            _cleanup(db, user)


# ── User isolation ──────────────────────────────────────────────────────


class TestUserIsolation:

    def test_user_a_cannot_see_user_b(self, db):
        user_a = _make_user(db)
        user_b = _make_user(db)
        try:
            record_success(
                db, user_id=user_a.id,
                content_id="tmdb:shared", provider_type="rd",
                source_key="src:a_only",
            )
            record_success(
                db, user_id=user_b.id,
                content_id="tmdb:shared", provider_type="rd",
                source_key="src:b_only",
            )
            db.commit()

            best_a = get_best_for_content(db, user_id=user_a.id, content_id="tmdb:shared")
            best_b = get_best_for_content(db, user_id=user_b.id, content_id="tmdb:shared")
            assert best_a.source_key == "src:a_only"
            assert best_b.source_key == "src:b_only"
        finally:
            _cleanup(db, user_a)
            _cleanup(db, user_b)


# ── Expiry ──────────────────────────────────────────────────────────────


class TestExpiry:

    def test_expired_rows_excluded_from_reads(self, db):
        user = _make_user(db)
        try:
            row = record_success(
                db, user_id=user.id,
                content_id="tmdb:3000", provider_type="rd",
                source_key="src:expired",
                retention_days=0,  # expires immediately
            )
            # Push expiry into the past
            row.expires_at = datetime.now(timezone.utc) - timedelta(hours=1)
            db.flush()
            db.commit()

            best = get_best_for_content(db, user_id=user.id, content_id="tmdb:3000")
            assert best is None
        finally:
            _cleanup(db, user)

    def test_cleanup_expired(self, db):
        user = _make_user(db)
        try:
            row = record_success(
                db, user_id=user.id,
                content_id="tmdb:3010", provider_type="rd",
                source_key="src:stale",
            )
            row.expires_at = datetime.now(timezone.utc) - timedelta(days=1)
            db.flush()
            db.commit()

            deleted = cleanup_expired(db)
            assert deleted >= 1

            remaining = db.query(ResolveSuccessMemory).filter(
                ResolveSuccessMemory.user_id == user.id,
            ).count()
            assert remaining == 0
        finally:
            _cleanup(db, user)
