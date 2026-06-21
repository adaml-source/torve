"""Tests for source acceleration data models.

Proves:
- ResolveSuccessMemory: insert, upsert, expiry, per-user isolation
- HashAvailabilityMemory: insert, upsert, expiry, per-user isolation
- ProviderInventorySnapshot: insert, upsert, expiry, per-user isolation
- Cascade delete when user is removed
- Index-backed query patterns work correctly
"""
import uuid
from datetime import datetime, timedelta, timezone

from app.models import (
    HashAvailabilityMemory,
    ProviderInventorySnapshot,
    ResolveSuccessMemory,
    User,
)
from app.security import hash_password


def _make_user(db):
    user = User(
        id=uuid.uuid4(),
        email=f"accel-{uuid.uuid4().hex[:8]}@test.com",
        password_hash=hash_password("TestPass123!"),
        is_verified=True,
    )
    db.add(user)
    db.commit()
    db.refresh(user)
    return user


def _cleanup(db, user):
    db.query(ResolveSuccessMemory).filter(ResolveSuccessMemory.user_id == user.id).delete()
    db.query(HashAvailabilityMemory).filter(HashAvailabilityMemory.user_id == user.id).delete()
    db.query(ProviderInventorySnapshot).filter(ProviderInventorySnapshot.user_id == user.id).delete()
    db.commit()
    u = db.query(User).filter(User.id == user.id).first()
    if u:
        db.delete(u)
        db.commit()


# ── ResolveSuccessMemory ────────────────────────────────────────────────


class TestResolveSuccessMemory:

    def test_insert_and_query(self, db):
        user = _make_user(db)
        try:
            now = datetime.now(timezone.utc)
            row = ResolveSuccessMemory(
                user_id=user.id,
                content_id="tmdb:12345",
                season=1,
                episode=3,
                provider_type="real_debrid",
                source_key="torrentio:abc123",
                infohash="a" * 40,
                file_name="Show.S01E03.1080p.mkv",
                quality="1080p",
                audio_flags="atmos",
                file_size=2_000_000_000,
                success_count=1,
                last_success_at=now,
                last_device_type="tv",
                expires_at=now + timedelta(days=30),
            )
            db.add(row)
            db.commit()

            # Query by user + content
            results = db.query(ResolveSuccessMemory).filter(
                ResolveSuccessMemory.user_id == user.id,
                ResolveSuccessMemory.content_id == "tmdb:12345",
            ).all()
            assert len(results) == 1
            assert results[0].quality == "1080p"
            assert results[0].success_count == 1
        finally:
            _cleanup(db, user)

    def test_upsert_increments_count(self, db):
        user = _make_user(db)
        try:
            now = datetime.now(timezone.utc)
            row = ResolveSuccessMemory(
                user_id=user.id,
                content_id="tmdb:99999",
                provider_type="real_debrid",
                source_key="src:xyz",
                last_success_at=now,
                expires_at=now + timedelta(days=30),
            )
            db.add(row)
            db.commit()

            # Simulate upsert: find and increment
            existing = db.query(ResolveSuccessMemory).filter(
                ResolveSuccessMemory.user_id == user.id,
                ResolveSuccessMemory.content_id == "tmdb:99999",
                ResolveSuccessMemory.provider_type == "real_debrid",
                ResolveSuccessMemory.source_key == "src:xyz",
            ).first()
            assert existing is not None
            existing.success_count += 1
            existing.last_success_at = datetime.now(timezone.utc)
            db.commit()
            db.refresh(existing)
            assert existing.success_count == 2
        finally:
            _cleanup(db, user)

    def test_expired_rows_queryable(self, db):
        """Expired rows can be found for cleanup."""
        user = _make_user(db)
        try:
            now = datetime.now(timezone.utc)
            # Expired row
            db.add(ResolveSuccessMemory(
                user_id=user.id,
                content_id="tmdb:expired",
                provider_type="rd",
                source_key="src:old",
                last_success_at=now - timedelta(days=60),
                expires_at=now - timedelta(days=1),
            ))
            # Fresh row
            db.add(ResolveSuccessMemory(
                user_id=user.id,
                content_id="tmdb:fresh",
                provider_type="rd",
                source_key="src:new",
                last_success_at=now,
                expires_at=now + timedelta(days=30),
            ))
            db.commit()

            expired = db.query(ResolveSuccessMemory).filter(
                ResolveSuccessMemory.expires_at < now,
            ).all()
            assert len(expired) == 1
            assert expired[0].content_id == "tmdb:expired"

            active = db.query(ResolveSuccessMemory).filter(
                ResolveSuccessMemory.user_id == user.id,
                ResolveSuccessMemory.expires_at >= now,
            ).all()
            assert len(active) == 1
            assert active[0].content_id == "tmdb:fresh"
        finally:
            _cleanup(db, user)

    def test_user_isolation(self, db):
        user_a = _make_user(db)
        user_b = _make_user(db)
        try:
            now = datetime.now(timezone.utc)
            for u in (user_a, user_b):
                db.add(ResolveSuccessMemory(
                    user_id=u.id,
                    content_id="tmdb:shared",
                    provider_type="rd",
                    source_key=f"src:{u.id}",
                    last_success_at=now,
                    expires_at=now + timedelta(days=30),
                ))
            db.commit()

            a_rows = db.query(ResolveSuccessMemory).filter(
                ResolveSuccessMemory.user_id == user_a.id,
            ).all()
            assert len(a_rows) == 1
            assert a_rows[0].source_key == f"src:{user_a.id}"
        finally:
            _cleanup(db, user_a)
            _cleanup(db, user_b)


# ── HashAvailabilityMemory ──────────────────────────────────────────────


class TestHashAvailabilityMemory:

    def test_insert_and_query(self, db):
        user = _make_user(db)
        try:
            now = datetime.now(timezone.utc)
            row = HashAvailabilityMemory(
                user_id=user.id,
                provider_type="real_debrid",
                infohash="b" * 40,
                is_cached=True,
                observed_at=now,
                observation_source="resolve",
                confidence="observed",
                expires_at=now + timedelta(hours=4),
            )
            db.add(row)
            db.commit()

            result = db.query(HashAvailabilityMemory).filter(
                HashAvailabilityMemory.user_id == user.id,
                HashAvailabilityMemory.provider_type == "real_debrid",
                HashAvailabilityMemory.infohash == "b" * 40,
                HashAvailabilityMemory.expires_at > now,
            ).first()
            assert result is not None
            assert result.is_cached is True
            assert result.confidence == "observed"
        finally:
            _cleanup(db, user)

    def test_upsert_updates_cached_status(self, db):
        user = _make_user(db)
        try:
            now = datetime.now(timezone.utc)
            row = HashAvailabilityMemory(
                user_id=user.id,
                provider_type="premiumize",
                infohash="c" * 40,
                is_cached=False,
                observed_at=now - timedelta(hours=2),
                expires_at=now + timedelta(hours=2),
            )
            db.add(row)
            db.commit()

            # Update: now cached
            existing = db.query(HashAvailabilityMemory).filter(
                HashAvailabilityMemory.user_id == user.id,
                HashAvailabilityMemory.provider_type == "premiumize",
                HashAvailabilityMemory.infohash == "c" * 40,
            ).first()
            existing.is_cached = True
            existing.observed_at = now
            existing.expires_at = now + timedelta(hours=4)
            db.commit()
            db.refresh(existing)
            assert existing.is_cached is True
        finally:
            _cleanup(db, user)

    def test_short_ttl_expiry(self, db):
        user = _make_user(db)
        try:
            now = datetime.now(timezone.utc)
            db.add(HashAvailabilityMemory(
                user_id=user.id,
                provider_type="rd",
                infohash="d" * 40,
                is_cached=True,
                observed_at=now - timedelta(hours=5),
                expires_at=now - timedelta(hours=1),  # expired
            ))
            db.commit()

            result = db.query(HashAvailabilityMemory).filter(
                HashAvailabilityMemory.user_id == user.id,
                HashAvailabilityMemory.expires_at > now,
            ).all()
            assert len(result) == 0
        finally:
            _cleanup(db, user)

    def test_batch_cached_lookup(self, db):
        """Batch query: all cached hashes for a user+provider."""
        user = _make_user(db)
        try:
            now = datetime.now(timezone.utc)
            for i, cached in enumerate([True, True, False, True]):
                db.add(HashAvailabilityMemory(
                    user_id=user.id,
                    provider_type="rd",
                    infohash=f"{i}" * 40,
                    is_cached=cached,
                    observed_at=now,
                    expires_at=now + timedelta(hours=4),
                ))
            db.commit()

            cached_hashes = db.query(HashAvailabilityMemory).filter(
                HashAvailabilityMemory.user_id == user.id,
                HashAvailabilityMemory.provider_type == "rd",
                HashAvailabilityMemory.is_cached == True,  # noqa: E712
                HashAvailabilityMemory.expires_at > now,
            ).all()
            assert len(cached_hashes) == 3
        finally:
            _cleanup(db, user)


# ── ProviderInventorySnapshot ───────────────────────────────────────────


class TestProviderInventorySnapshot:

    def test_insert_and_query(self, db):
        user = _make_user(db)
        try:
            now = datetime.now(timezone.utc)
            row = ProviderInventorySnapshot(
                user_id=user.id,
                provider_type="real_debrid",
                remote_item_id="rd:torrent:abc123",
                normalized_title="breaking bad",
                year=2008,
                season=1,
                episode=1,
                infohash="e" * 40,
                file_size=1_500_000_000,
                file_name="Breaking.Bad.S01E01.1080p.mkv",
                display_path="/cloud/Breaking Bad/Season 1/",
                inventory_class="cloud",
                quality="1080p",
                last_seen_at=now,
                expires_at=now + timedelta(hours=24),
            )
            db.add(row)
            db.commit()

            results = db.query(ProviderInventorySnapshot).filter(
                ProviderInventorySnapshot.user_id == user.id,
                ProviderInventorySnapshot.provider_type == "real_debrid",
            ).all()
            assert len(results) == 1
            assert results[0].normalized_title == "breaking bad"
            assert results[0].inventory_class == "cloud"
        finally:
            _cleanup(db, user)

    def test_match_by_infohash(self, db):
        user = _make_user(db)
        try:
            now = datetime.now(timezone.utc)
            target_hash = "f" * 40
            db.add(ProviderInventorySnapshot(
                user_id=user.id,
                provider_type="rd",
                remote_item_id="rd:t:1",
                infohash=target_hash,
                inventory_class="cloud",
                last_seen_at=now,
                expires_at=now + timedelta(hours=24),
            ))
            db.add(ProviderInventorySnapshot(
                user_id=user.id,
                provider_type="rd",
                remote_item_id="rd:t:2",
                infohash="0" * 40,
                inventory_class="cloud",
                last_seen_at=now,
                expires_at=now + timedelta(hours=24),
            ))
            db.commit()

            match = db.query(ProviderInventorySnapshot).filter(
                ProviderInventorySnapshot.user_id == user.id,
                ProviderInventorySnapshot.provider_type == "rd",
                ProviderInventorySnapshot.infohash == target_hash,
                ProviderInventorySnapshot.expires_at > now,
            ).first()
            assert match is not None
            assert match.remote_item_id == "rd:t:1"
        finally:
            _cleanup(db, user)

    def test_match_by_title(self, db):
        user = _make_user(db)
        try:
            now = datetime.now(timezone.utc)
            db.add(ProviderInventorySnapshot(
                user_id=user.id,
                provider_type="rd",
                remote_item_id="rd:t:title",
                normalized_title="the bear",
                season=2,
                episode=1,
                inventory_class="cloud",
                last_seen_at=now,
                expires_at=now + timedelta(hours=24),
            ))
            db.commit()

            match = db.query(ProviderInventorySnapshot).filter(
                ProviderInventorySnapshot.user_id == user.id,
                ProviderInventorySnapshot.provider_type == "rd",
                ProviderInventorySnapshot.normalized_title == "the bear",
                ProviderInventorySnapshot.season == 2,
                ProviderInventorySnapshot.episode == 1,
            ).first()
            assert match is not None
        finally:
            _cleanup(db, user)

    def test_inventory_classes(self, db):
        user = _make_user(db)
        try:
            now = datetime.now(timezone.utc)
            for cls in ("cloud", "download", "history", "library"):
                db.add(ProviderInventorySnapshot(
                    user_id=user.id,
                    provider_type="rd",
                    remote_item_id=f"rd:{cls}",
                    inventory_class=cls,
                    last_seen_at=now,
                    expires_at=now + timedelta(hours=24),
                ))
            db.commit()

            cloud = db.query(ProviderInventorySnapshot).filter(
                ProviderInventorySnapshot.user_id == user.id,
                ProviderInventorySnapshot.inventory_class == "cloud",
            ).all()
            assert len(cloud) == 1
        finally:
            _cleanup(db, user)

    def test_expiry_cleanup(self, db):
        user = _make_user(db)
        try:
            now = datetime.now(timezone.utc)
            db.add(ProviderInventorySnapshot(
                user_id=user.id,
                provider_type="rd",
                remote_item_id="rd:stale",
                inventory_class="cloud",
                last_seen_at=now - timedelta(days=2),
                expires_at=now - timedelta(hours=1),
            ))
            db.add(ProviderInventorySnapshot(
                user_id=user.id,
                provider_type="rd",
                remote_item_id="rd:fresh",
                inventory_class="cloud",
                last_seen_at=now,
                expires_at=now + timedelta(hours=24),
            ))
            db.commit()

            expired = db.query(ProviderInventorySnapshot).filter(
                ProviderInventorySnapshot.expires_at < now,
            ).count()
            assert expired == 1

            active = db.query(ProviderInventorySnapshot).filter(
                ProviderInventorySnapshot.user_id == user.id,
                ProviderInventorySnapshot.expires_at >= now,
            ).count()
            assert active == 1
        finally:
            _cleanup(db, user)


# ── Cascade delete ──────────────────────────────────────────────────────


class TestCascadeDelete:

    def test_user_delete_cascades_all_acceleration_data(self, db):
        user = _make_user(db)
        now = datetime.now(timezone.utc)
        exp = now + timedelta(days=30)

        db.add(ResolveSuccessMemory(
            user_id=user.id, content_id="tmdb:1", provider_type="rd",
            source_key="s:1", last_success_at=now, expires_at=exp,
        ))
        db.add(HashAvailabilityMemory(
            user_id=user.id, provider_type="rd", infohash="a" * 40,
            is_cached=True, observed_at=now, expires_at=exp,
        ))
        db.add(ProviderInventorySnapshot(
            user_id=user.id, provider_type="rd", remote_item_id="rd:1",
            inventory_class="cloud", last_seen_at=now, expires_at=exp,
        ))
        db.commit()

        # Delete user — should cascade
        db.delete(user)
        db.commit()

        assert db.query(ResolveSuccessMemory).filter(
            ResolveSuccessMemory.user_id == user.id).count() == 0
        assert db.query(HashAvailabilityMemory).filter(
            HashAvailabilityMemory.user_id == user.id).count() == 0
        assert db.query(ProviderInventorySnapshot).filter(
            ProviderInventorySnapshot.user_id == user.id).count() == 0
