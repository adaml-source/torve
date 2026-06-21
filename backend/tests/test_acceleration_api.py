"""Tests for the source acceleration API endpoints.

Covers:
- GET /me/acceleration/sources — recent successful sources
- GET /me/acceleration/hashes — hash availability check
- POST /me/acceleration/hashes — hash availability write
- GET /me/acceleration/inventory — inventory matches
- POST /me/acceleration/inventory/ingest — inventory ingestion
- GET /me/acceleration/startup — merged startup candidates
- POST /me/acceleration/outcome — resolve outcome write-back
- Auth/premium gating
- Empty state graceful degradation
- Cross-user isolation
"""
import uuid
from datetime import datetime, timedelta, timezone

from app.models import (
    HashAvailabilityMemory,
    ProviderInventorySnapshot,
    ResolveSuccessMemory,
    User,
    UserEntitlement,
)
from app.security import create_access_token, hash_password


def _make_premium_user(db):
    user = User(
        id=uuid.uuid4(),
        email=f"accel-{uuid.uuid4().hex[:8]}@test.com",
        password_hash=hash_password("TestPass123!"),
        is_verified=True,
        has_lifetime_access=True,
        has_premium_access=True,
    )
    db.add(user)
    db.flush()
    ent = UserEntitlement(
        user_id=user.id,
        entitlement_type="lifetime_access",
        source="admin_grant",
        source_ref=f"accel-test-{user.id}",
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


# ── Auth / gating ───────────────────────────────────────────────────────


class TestAccelerationAuth:

    def test_requires_auth(self, client):
        r = client.get("/me/acceleration/sources?content_id=tmdb:1")
        assert r.status_code in (401, 403)

    def test_authenticated_free_account_can_use_acceleration(self, client, db):
        user = User(
            id=uuid.uuid4(),
            email=f"free-accel-{uuid.uuid4().hex[:8]}@test.com",
            password_hash=hash_password("TestPass123!"),
            is_verified=True,
        )
        db.add(user)
        db.commit()
        try:
            headers = {"Authorization": f"Bearer {create_access_token(str(user.id))}"}
            r = client.get("/me/acceleration/sources?content_id=tmdb:1", headers=headers)
            assert r.status_code == 200
        finally:
            db.delete(user)
            db.commit()


# ── GET /me/acceleration/sources ────────────────────────────────────────


class TestRecentSources:

    def test_empty_returns_empty(self, client, db):
        user = _make_premium_user(db)
        try:
            r = client.get("/me/acceleration/sources?content_id=tmdb:999",
                           headers=_auth(user))
            assert r.status_code == 200
            body = r.json()
            assert body["content_id"] == "tmdb:999"
            assert body["candidates"] == []
            assert body["carryforward"] == []
        finally:
            _cleanup(db, user)

    def test_returns_recent_success(self, client, db):
        user = _make_premium_user(db)
        try:
            # Record a success via outcome endpoint
            client.post("/me/acceleration/outcome", headers=_auth(user), json={
                "content_id": "tmdb:100", "provider_type": "real_debrid",
                "source_key": "torrentio:abc", "success": True,
                "infohash": "a" * 40, "quality": "1080p",
            })

            r = client.get("/me/acceleration/sources?content_id=tmdb:100",
                           headers=_auth(user))
            assert r.status_code == 200
            body = r.json()
            assert len(body["candidates"]) == 1
            assert body["candidates"][0]["provenance"] == "recent_success"
            assert body["candidates"][0]["quality"] == "1080p"
        finally:
            _cleanup(db, user)

    def test_episodic_carryforward(self, client, db):
        user = _make_premium_user(db)
        try:
            # Record success for S01E01 and S01E02
            for ep in (1, 2):
                client.post("/me/acceleration/outcome", headers=_auth(user), json={
                    "content_id": "tmdb:200", "provider_type": "rd",
                    "source_key": "src:pack", "season": 1, "episode": ep,
                    "success": True,
                })

            # Query for S01E03 — no exact match, should carryforward
            r = client.get(
                "/me/acceleration/sources?content_id=tmdb:200&season=1&episode=3",
                headers=_auth(user),
            )
            assert r.status_code == 200
            body = r.json()
            assert body["candidates"] == []
            assert len(body["carryforward"]) >= 1
            assert body["carryforward"][0]["provenance"] == "carryforward"
        finally:
            _cleanup(db, user)


# ── Hash availability ───────────────────────────────────────────────────


class TestHashAvailability:

    def test_write_and_read(self, client, db):
        user = _make_premium_user(db)
        try:
            # Write
            r = client.post("/me/acceleration/hashes", headers=_auth(user), json={
                "provider_type": "real_debrid",
                "observations": [
                    {"infohash": "a" * 40, "is_cached": True},
                    {"infohash": "b" * 40, "is_cached": False},
                ],
            })
            assert r.status_code == 201
            assert r.json()["recorded"] == 2

            # Read
            hashes = f"{'a' * 40},{'b' * 40},{'z' * 40}"
            r = client.get(
                f"/me/acceleration/hashes?provider_type=real_debrid&infohashes={hashes}",
                headers=_auth(user),
            )
            assert r.status_code == 200
            obs = r.json()["observations"]
            assert len(obs) == 2
            cached = {o["infohash"]: o["is_cached"] for o in obs}
            assert cached["a" * 40] is True
            assert cached["b" * 40] is False
        finally:
            _cleanup(db, user)

    def test_empty_hashes(self, client, db):
        user = _make_premium_user(db)
        try:
            r = client.get(
                "/me/acceleration/hashes?provider_type=rd&infohashes=",
                headers=_auth(user),
            )
            assert r.status_code == 200
            assert r.json()["observations"] == []
        finally:
            _cleanup(db, user)


# ── Inventory ───────────────────────────────────────────────────────────


class TestInventoryAPI:

    def test_ingest_and_match(self, client, db):
        user = _make_premium_user(db)
        try:
            # Ingest
            r = client.post("/me/acceleration/inventory/ingest", headers=_auth(user), json={
                "provider_type": "real_debrid",
                "items": [
                    {"id": "t1", "filename": "Breaking.Bad.S05E16.1080p.BluRay.mkv",
                     "hash": "a" * 40, "bytes": 2_000_000_000},
                ],
            })
            assert r.status_code == 201
            assert r.json()["upserted"] == 1
            assert r.json()["supported"] is True

            # Match by hash
            r = client.get(
                f"/me/acceleration/inventory?content_id=tmdb:300&infohash={'a' * 40}",
                headers=_auth(user),
            )
            assert r.status_code == 200
            body = r.json()
            assert len(body["matches"]) == 1
            assert body["matches"][0]["provenance"] == "inventory_match"
        finally:
            _cleanup(db, user)

    def test_unsupported_provider(self, client, db):
        user = _make_premium_user(db)
        try:
            r = client.post("/me/acceleration/inventory/ingest", headers=_auth(user), json={
                "provider_type": "trakt",
                "items": [{"id": "1", "name": "test"}],
            })
            assert r.status_code == 201
            assert r.json()["supported"] is False
            assert r.json()["upserted"] == 0
        finally:
            _cleanup(db, user)

    def test_match_by_title(self, client, db):
        user = _make_premium_user(db)
        try:
            client.post("/me/acceleration/inventory/ingest", headers=_auth(user), json={
                "provider_type": "real_debrid",
                "items": [
                    {"id": "t2", "filename": "The.Matrix.1999.4K.UHD.mkv",
                     "hash": "b" * 40, "bytes": 5_000_000_000},
                ],
            })

            r = client.get(
                "/me/acceleration/inventory?content_id=tmdb:400&title=the%20matrix&year=1999",
                headers=_auth(user),
            )
            assert r.status_code == 200
            assert len(r.json()["matches"]) == 1
        finally:
            _cleanup(db, user)

    def test_empty_inventory(self, client, db):
        user = _make_premium_user(db)
        try:
            r = client.get(
                "/me/acceleration/inventory?content_id=tmdb:999",
                headers=_auth(user),
            )
            assert r.status_code == 200
            assert r.json()["matches"] == []
        finally:
            _cleanup(db, user)


# ── Startup candidates ─────────────────────────────────────────────────


class TestStartupCandidates:

    def test_empty_state(self, client, db):
        user = _make_premium_user(db)
        try:
            r = client.get(
                "/me/acceleration/startup?content_id=tmdb:000",
                headers=_auth(user),
            )
            assert r.status_code == 200
            body = r.json()
            assert body["candidates"] == []
            assert body["sources_used"] == []
        finally:
            _cleanup(db, user)

    def test_merges_sources(self, client, db):
        user = _make_premium_user(db)
        try:
            # 1. Record a success
            client.post("/me/acceleration/outcome", headers=_auth(user), json={
                "content_id": "tmdb:500", "provider_type": "real_debrid",
                "source_key": "src:merged", "success": True,
                "infohash": "d" * 40, "quality": "1080p",
            })

            # 2. Ingest inventory with same hash
            client.post("/me/acceleration/inventory/ingest", headers=_auth(user), json={
                "provider_type": "real_debrid",
                "items": [
                    {"id": "t3", "filename": "Movie.2024.1080p.mkv",
                     "hash": "d" * 40, "bytes": 2_000_000_000},
                ],
            })

            # 3. Record hash as cached
            client.post("/me/acceleration/hashes", headers=_auth(user), json={
                "provider_type": "real_debrid",
                "observations": [{"infohash": "d" * 40, "is_cached": True}],
            })

            # Get startup candidates
            r = client.get(
                "/me/acceleration/startup?content_id=tmdb:500",
                headers=_auth(user),
            )
            assert r.status_code == 200
            body = r.json()
            assert len(body["candidates"]) >= 1
            assert "recent_success" in body["sources_used"]
            # The candidate should have is_cached enriched
            cached_candidates = [c for c in body["candidates"] if c.get("is_cached")]
            assert len(cached_candidates) >= 1
        finally:
            _cleanup(db, user)

    def test_deduplicates(self, client, db):
        user = _make_premium_user(db)
        try:
            # Same hash from success and inventory
            client.post("/me/acceleration/outcome", headers=_auth(user), json={
                "content_id": "tmdb:600", "provider_type": "rd",
                "source_key": "src:dup", "success": True,
                "infohash": "e" * 40,
            })
            client.post("/me/acceleration/inventory/ingest", headers=_auth(user), json={
                "provider_type": "rd",
                "items": [{"id": "t4", "filename": "test.mkv", "hash": "e" * 40}],
            })

            r = client.get(
                f"/me/acceleration/startup?content_id=tmdb:600&infohash={'e' * 40}",
                headers=_auth(user),
            )
            body = r.json()
            # Should be deduped — same provider + same hash = one candidate
            hashes = [c["infohash"] for c in body["candidates"]]
            assert hashes.count("e" * 40) == 1
        finally:
            _cleanup(db, user)


# ── Resolve outcome ────────────────────────────────────────────────────


class TestResolveOutcome:

    def test_record_success(self, client, db):
        user = _make_premium_user(db)
        try:
            r = client.post("/me/acceleration/outcome", headers=_auth(user), json={
                "content_id": "tmdb:700", "provider_type": "rd",
                "source_key": "src:test", "success": True,
                "quality": "4k", "device_type": "tv",
            })
            assert r.status_code == 201
            assert r.json()["recorded"] is True
            assert r.json()["success_count"] == 1

            # Second success increments
            r = client.post("/me/acceleration/outcome", headers=_auth(user), json={
                "content_id": "tmdb:700", "provider_type": "rd",
                "source_key": "src:test", "success": True,
            })
            assert r.json()["success_count"] == 2
        finally:
            _cleanup(db, user)

    def test_record_success_accepts_long_source_key(self, client, db):
        user = _make_premium_user(db)
        long_source_key = (
            "https://stremio.torbox.app/example/new-stream-url/"
            + ("a" * 900)
            + "/Movie.2026.1080p.WEBRip.mkv"
        )
        try:
            r = client.post("/me/acceleration/outcome", headers=_auth(user), json={
                "content_id": "tmdb:701", "provider_type": "real_debrid",
                "source_key": long_source_key, "success": True,
                "quality": "1080p",
            })
            assert r.status_code == 201
            assert r.json()["recorded"] is True

            row = db.query(ResolveSuccessMemory).filter(
                ResolveSuccessMemory.user_id == user.id,
                ResolveSuccessMemory.content_id == "tmdb:701",
            ).one()
            assert row.source_key == long_source_key
            assert len(row.source_key_hash) == 64
        finally:
            _cleanup(db, user)

    def test_record_failure_unknown(self, client, db):
        user = _make_premium_user(db)
        try:
            r = client.post("/me/acceleration/outcome", headers=_auth(user), json={
                "content_id": "tmdb:800", "provider_type": "rd",
                "source_key": "src:never", "success": False,
            })
            assert r.status_code == 201
            assert r.json()["recorded"] is False
            assert r.json()["was_known"] is False
        finally:
            _cleanup(db, user)

    def test_record_failure_known(self, client, db):
        user = _make_premium_user(db)
        try:
            # First succeed
            client.post("/me/acceleration/outcome", headers=_auth(user), json={
                "content_id": "tmdb:810", "provider_type": "rd",
                "source_key": "src:flaky", "success": True,
            })
            # Then fail
            r = client.post("/me/acceleration/outcome", headers=_auth(user), json={
                "content_id": "tmdb:810", "provider_type": "rd",
                "source_key": "src:flaky", "success": False,
            })
            assert r.json()["recorded"] is True
            assert r.json()["was_known"] is True
        finally:
            _cleanup(db, user)


# ── Cross-user isolation ───────────────────────────────────────────────


class TestAccelerationIsolation:

    def test_user_a_cannot_see_user_b(self, client, db):
        user_a = _make_premium_user(db)
        user_b = _make_premium_user(db)
        try:
            client.post("/me/acceleration/outcome", headers=_auth(user_a), json={
                "content_id": "tmdb:shared", "provider_type": "rd",
                "source_key": "src:a_only", "success": True,
            })

            r = client.get("/me/acceleration/sources?content_id=tmdb:shared",
                           headers=_auth(user_b))
            assert r.json()["candidates"] == []
        finally:
            _cleanup(db, user_a)
            _cleanup(db, user_b)
