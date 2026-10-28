"""Tests for provider inventory snapshot service.

Covers:
- Title normalization (movies, shows, edge cases)
- Provider adapters (real_debrid, premiumize, alldebrid, torbox)
- Snapshot ingestion (full replace, partial/incremental)
- Content matching (by infohash, batch infohash, by title)
- Freshness checks and summary
- User isolation
- Expiry and cleanup
- Unsupported provider returns None adapter
"""
import uuid
from datetime import datetime, timedelta, timezone

from app.inventory_service import (
    InventoryItem,
    adapt_alldebrid_magnets,
    adapt_premiumize_transfers,
    adapt_real_debrid_torrents,
    adapt_torbox_torrents,
    cleanup_expired,
    get_adapter,
    get_provider_snapshot_summary,
    ingest_snapshot,
    is_snapshot_fresh,
    match_by_infohash,
    match_by_infohashes,
    match_by_title,
    normalize_title,
)
from app.models import ProviderInventorySnapshot, User
from app.security import hash_password


def _make_user(db):
    user = User(
        id=uuid.uuid4(),
        email=f"inv-{uuid.uuid4().hex[:8]}@test.com",
        password_hash=hash_password("TestPass123!"),
        is_verified=True,
    )
    db.add(user)
    db.commit()
    db.refresh(user)
    return user


def _cleanup(db, user):
    db.query(ProviderInventorySnapshot).filter(
        ProviderInventorySnapshot.user_id == user.id
    ).delete()
    db.commit()
    u = db.query(User).filter(User.id == user.id).first()
    if u:
        db.delete(u)
        db.commit()


# ── Title normalization ─────────────────────────────────────────────────


class TestNormalizeTitle:

    def test_movie_with_year_and_quality(self):
        title, year, season, episode, quality = normalize_title(
            "The.Matrix.1999.2160p.UHD.BluRay.x265-GROUP"
        )
        assert title == "the matrix"
        assert year == 1999
        assert season is None
        assert episode is None
        assert quality == "4k"

    def test_show_with_season_episode(self):
        title, year, season, episode, quality = normalize_title(
            "Breaking.Bad.S05E16.1080p.BluRay.x264-DEMAND"
        )
        assert title == "breaking bad"
        assert season == 5
        assert episode == 16
        assert quality == "1080p"

    def test_show_season_only(self):
        title, year, season, episode, quality = normalize_title(
            "The.Bear.S02.Complete.720p.WEB-DL"
        )
        assert title == "the bear"
        assert season == 2
        assert episode is None
        assert quality == "720p"

    def test_movie_4k_variants(self):
        for variant in ("4K", "2160p", "UHD"):
            _, _, _, _, quality = normalize_title(f"Movie.2024.{variant}.BluRay")
            assert quality == "4k", f"Failed for {variant}"

    def test_empty_string(self):
        assert normalize_title("") == (None, None, None, None, None)

    def test_no_metadata(self):
        title, year, season, episode, quality = normalize_title("random_file.mkv")
        assert title is not None  # Something extracted
        assert quality is None


# ── Provider adapters ───────────────────────────────────────────────────


class TestAdapters:

    def test_real_debrid(self):
        raw = [
            {"id": "ABC123", "filename": "Movie.2024.1080p.BluRay.mkv",
             "hash": "a" * 40, "bytes": 2_000_000_000},
            {"id": "DEF456", "filename": "Show.S01E03.720p.WEB-DL.mkv",
             "hash": "b" * 40, "bytes": 500_000_000},
        ]
        items = adapt_real_debrid_torrents(raw)
        assert len(items) == 2
        assert items[0].remote_item_id == "rd:torrent:ABC123"
        assert items[0].infohash == "a" * 40
        assert items[0].quality == "1080p"
        assert items[1].season == 1
        assert items[1].episode == 3

    def test_premiumize(self):
        raw = [
            {"id": "pm1", "name": "The.Bear.S02E01.1080p.mkv",
             "size": 1_000_000_000,
             "src": f"magnet:?xt=urn:btih:{'c' * 40}&dn=test"},
        ]
        items = adapt_premiumize_transfers(raw)
        assert len(items) == 1
        assert items[0].remote_item_id == "pm:transfer:pm1"
        assert items[0].infohash == "c" * 40
        assert items[0].season == 2
        assert items[0].episode == 1

    def test_alldebrid(self):
        raw = [
            {"id": 99, "filename": "Dune.2021.4K.UHD.mkv",
             "hash": "d" * 40, "size": 4_000_000_000},
        ]
        items = adapt_alldebrid_magnets(raw)
        assert len(items) == 1
        assert items[0].remote_item_id == "ad:magnet:99"
        assert items[0].quality == "4k"
        assert items[0].year == 2021

    def test_torbox(self):
        raw = [
            {"id": 42, "name": "Oppenheimer.2023.REMUX.mkv",
             "hash": "e" * 40, "size": 50_000_000_000},
        ]
        items = adapt_torbox_torrents(raw)
        assert len(items) == 1
        assert items[0].remote_item_id == "tb:torrent:42"
        assert items[0].year == 2023

    def test_unsupported_provider(self):
        assert get_adapter("trakt") is None
        assert get_adapter("tmdb") is None
        assert get_adapter("unknown") is None

    def test_supported_providers(self):
        for p in ("real_debrid", "premiumize", "alldebrid", "torbox"):
            assert get_adapter(p) is not None, f"Missing adapter for {p}"

    def test_empty_input(self):
        assert adapt_real_debrid_torrents([]) == []
        assert adapt_premiumize_transfers([]) == []
        assert adapt_alldebrid_magnets([]) == []
        assert adapt_torbox_torrents([]) == []

    def test_missing_id_skipped(self):
        raw = [{"filename": "no_id.mkv"}, {"id": "ok", "filename": "has_id.mkv"}]
        items = adapt_real_debrid_torrents(raw)
        assert len(items) == 1
        assert items[0].remote_item_id == "rd:torrent:ok"

    def test_invalid_hash_cleaned(self):
        raw = [{"id": "1", "filename": "test.mkv", "hash": "not-a-valid-hash"}]
        items = adapt_real_debrid_torrents(raw)
        assert items[0].infohash is None


# ── Snapshot ingestion ──────────────────────────────────────────────────


class TestIngestSnapshot:

    def test_full_replace(self, db):
        user = _make_user(db)
        try:
            items_v1 = [
                InventoryItem(remote_item_id="rd:1", file_name="A.mkv", inventory_class="cloud"),
                InventoryItem(remote_item_id="rd:2", file_name="B.mkv", inventory_class="cloud"),
            ]
            result = ingest_snapshot(db, user_id=user.id, provider_type="real_debrid",
                                    items=items_v1)
            db.commit()
            assert result["upserted"] == 2
            assert result["expired"] == 0

            # V2: only rd:2 and rd:3. rd:1 should be expired.
            items_v2 = [
                InventoryItem(remote_item_id="rd:2", file_name="B_updated.mkv", inventory_class="cloud"),
                InventoryItem(remote_item_id="rd:3", file_name="C.mkv", inventory_class="cloud"),
            ]
            result = ingest_snapshot(db, user_id=user.id, provider_type="real_debrid",
                                    items=items_v2, replace=True)
            db.commit()
            assert result["upserted"] == 2
            assert result["expired"] == 1  # rd:1 expired

            # rd:1 should be expired (expires_at in the past or at now)
            now = datetime.now(timezone.utc)
            active = db.query(ProviderInventorySnapshot).filter(
                ProviderInventorySnapshot.user_id == user.id,
                ProviderInventorySnapshot.expires_at > now,
            ).count()
            assert active == 2  # rd:2 and rd:3
        finally:
            _cleanup(db, user)

    def test_incremental_no_expire(self, db):
        user = _make_user(db)
        try:
            items_v1 = [
                InventoryItem(remote_item_id="rd:1", inventory_class="cloud"),
            ]
            ingest_snapshot(db, user_id=user.id, provider_type="rd", items=items_v1)
            db.commit()

            items_v2 = [
                InventoryItem(remote_item_id="rd:2", inventory_class="cloud"),
            ]
            result = ingest_snapshot(db, user_id=user.id, provider_type="rd",
                                    items=items_v2, replace=False)
            db.commit()
            assert result["expired"] == 0

            now = datetime.now(timezone.utc)
            total = db.query(ProviderInventorySnapshot).filter(
                ProviderInventorySnapshot.user_id == user.id,
                ProviderInventorySnapshot.expires_at > now,
            ).count()
            assert total == 2  # Both remain active
        finally:
            _cleanup(db, user)

    def test_upsert_updates_metadata(self, db):
        user = _make_user(db)
        try:
            items = [InventoryItem(remote_item_id="rd:up", file_name="v1.mkv",
                                   inventory_class="cloud")]
            ingest_snapshot(db, user_id=user.id, provider_type="rd", items=items)
            db.commit()

            items2 = [InventoryItem(remote_item_id="rd:up", file_name="v2_updated.mkv",
                                    quality="1080p", inventory_class="cloud")]
            ingest_snapshot(db, user_id=user.id, provider_type="rd", items=items2)
            db.commit()

            row = db.query(ProviderInventorySnapshot).filter(
                ProviderInventorySnapshot.user_id == user.id,
                ProviderInventorySnapshot.remote_item_id == "rd:up",
            ).first()
            assert row.file_name == "v2_updated.mkv"
            assert row.quality == "1080p"
        finally:
            _cleanup(db, user)


# ── Content matching ────────────────────────────────────────────────────


class TestMatching:

    def _seed(self, db, user):
        items = [
            InventoryItem(remote_item_id="rd:hash1", infohash="a" * 40,
                          normalized_title="breaking bad", season=5, episode=16,
                          quality="1080p", inventory_class="cloud"),
            InventoryItem(remote_item_id="rd:hash2", infohash="b" * 40,
                          normalized_title="the matrix", year=1999,
                          quality="4k", inventory_class="cloud"),
            InventoryItem(remote_item_id="rd:hash3", infohash="c" * 40,
                          normalized_title="breaking bad", season=5, episode=15,
                          quality="720p", inventory_class="cloud"),
        ]
        ingest_snapshot(db, user_id=user.id, provider_type="real_debrid", items=items)
        db.commit()

    def test_match_by_infohash(self, db):
        user = _make_user(db)
        try:
            self._seed(db, user)
            results = match_by_infohash(db, user_id=user.id, infohash="a" * 40)
            assert len(results) == 1
            assert results[0].normalized_title == "breaking bad"
        finally:
            _cleanup(db, user)

    def test_match_by_infohashes_batch(self, db):
        user = _make_user(db)
        try:
            self._seed(db, user)
            results = match_by_infohashes(
                db, user_id=user.id,
                infohashes=["a" * 40, "b" * 40, "z" * 40],
            )
            assert len(results) == 2
        finally:
            _cleanup(db, user)

    def test_match_by_infohashes_empty(self, db):
        user = _make_user(db)
        try:
            results = match_by_infohashes(db, user_id=user.id, infohashes=[])
            assert results == []
        finally:
            _cleanup(db, user)

    def test_match_by_title_movie(self, db):
        user = _make_user(db)
        try:
            self._seed(db, user)
            results = match_by_title(
                db, user_id=user.id, title="the matrix", year=1999,
            )
            assert len(results) == 1
            assert results[0].quality == "4k"
        finally:
            _cleanup(db, user)

    def test_match_by_title_episode(self, db):
        user = _make_user(db)
        try:
            self._seed(db, user)
            results = match_by_title(
                db, user_id=user.id, title="breaking bad",
                season=5, episode=16,
            )
            assert len(results) == 1
            assert results[0].quality == "1080p"
        finally:
            _cleanup(db, user)

    def test_match_by_title_all_episodes(self, db):
        user = _make_user(db)
        try:
            self._seed(db, user)
            results = match_by_title(
                db, user_id=user.id, title="breaking bad",
            )
            # Both BB episodes match (no season/episode filter)
            assert len(results) == 2
        finally:
            _cleanup(db, user)

    def test_no_match_returns_empty(self, db):
        user = _make_user(db)
        try:
            results = match_by_infohash(db, user_id=user.id, infohash="z" * 40)
            assert results == []
            results = match_by_title(db, user_id=user.id, title="nonexistent")
            assert results == []
        finally:
            _cleanup(db, user)

    def test_provider_filter(self, db):
        user = _make_user(db)
        try:
            self._seed(db, user)
            # Seed for different provider
            ingest_snapshot(db, user_id=user.id, provider_type="premiumize", items=[
                InventoryItem(remote_item_id="pm:1", infohash="a" * 40,
                              inventory_class="cloud"),
            ])
            db.commit()

            results = match_by_infohash(
                db, user_id=user.id, infohash="a" * 40,
                provider_type="premiumize",
            )
            assert len(results) == 1
            assert results[0].provider_type == "premiumize"
        finally:
            _cleanup(db, user)


# ── Freshness ───────────────────────────────────────────────────────────


class TestFreshness:

    def test_is_fresh(self, db):
        user = _make_user(db)
        try:
            ingest_snapshot(db, user_id=user.id, provider_type="rd", items=[
                InventoryItem(remote_item_id="rd:fresh", inventory_class="cloud"),
            ])
            db.commit()
            assert is_snapshot_fresh(db, user_id=user.id, provider_type="rd") is True
        finally:
            _cleanup(db, user)

    def test_is_not_fresh_empty(self, db):
        user = _make_user(db)
        try:
            assert is_snapshot_fresh(db, user_id=user.id, provider_type="rd") is False
        finally:
            _cleanup(db, user)

    def test_summary(self, db):
        user = _make_user(db)
        try:
            for i in range(5):
                ingest_snapshot(db, user_id=user.id, provider_type="rd", items=[
                    InventoryItem(remote_item_id=f"rd:s{i}", inventory_class="cloud"),
                ], replace=False)
            db.commit()

            summary = get_provider_snapshot_summary(db, user_id=user.id)
            assert len(summary) == 1
            assert summary[0]["provider_type"] == "rd"
            assert summary[0]["item_count"] == 5
        finally:
            _cleanup(db, user)


# ── User isolation ──────────────────────────────────────────────────────


class TestInventoryUserIsolation:

    def test_no_cross_user_leakage(self, db):
        user_a = _make_user(db)
        user_b = _make_user(db)
        try:
            ingest_snapshot(db, user_id=user_a.id, provider_type="rd", items=[
                InventoryItem(remote_item_id="rd:a", infohash="a" * 40,
                              inventory_class="cloud"),
            ])
            ingest_snapshot(db, user_id=user_b.id, provider_type="rd", items=[
                InventoryItem(remote_item_id="rd:b", infohash="b" * 40,
                              inventory_class="cloud"),
            ])
            db.commit()

            a_results = match_by_infohash(db, user_id=user_a.id, infohash="b" * 40)
            assert a_results == []  # User A can't see user B's inventory

            b_results = match_by_infohash(db, user_id=user_b.id, infohash="a" * 40)
            assert b_results == []
        finally:
            _cleanup(db, user_a)
            _cleanup(db, user_b)


# ── Expiry / cleanup ───────────────────────────────────────────────────


class TestInventoryExpiry:

    def test_expired_excluded_from_matches(self, db):
        user = _make_user(db)
        try:
            ingest_snapshot(db, user_id=user.id, provider_type="rd", items=[
                InventoryItem(remote_item_id="rd:stale", infohash="x" * 40,
                              inventory_class="cloud"),
            ], ttl_hours=0)
            db.commit()
            # Force expiry into past
            db.query(ProviderInventorySnapshot).filter(
                ProviderInventorySnapshot.user_id == user.id,
            ).update({"expires_at": datetime.now(timezone.utc) - timedelta(hours=1)})
            db.commit()

            results = match_by_infohash(db, user_id=user.id, infohash="x" * 40)
            assert results == []
        finally:
            _cleanup(db, user)

    def test_cleanup_expired(self, db):
        user = _make_user(db)
        try:
            ingest_snapshot(db, user_id=user.id, provider_type="rd", items=[
                InventoryItem(remote_item_id="rd:old", inventory_class="cloud"),
            ])
            db.commit()
            db.query(ProviderInventorySnapshot).filter(
                ProviderInventorySnapshot.user_id == user.id,
            ).update({"expires_at": datetime.now(timezone.utc) - timedelta(days=1)})
            db.commit()

            deleted = cleanup_expired(db)
            assert deleted >= 1
        finally:
            _cleanup(db, user)
