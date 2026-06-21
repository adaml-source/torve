"""Tests for NzbDAV candidate emission through the acceleration pipeline.

Scope: Sprint 2 emission-path contract only.
- Well-formed NzbDAV outcomes round-trip as USENET_NZBDAV candidates
  through /me/acceleration/sources and /me/acceleration/startup.
- Malformed NzbDAV outcomes (missing candidate_id or hash_key) persist
  the baseline provider_type row WITHOUT the NzbDAV-specific payload
  and log NZBDAV_OUTCOME_MALFORMED (write-side guardrail).
- DB rows tagged USENET_NZBDAV but missing required fields are
  suppressed on read (read-side guardrail).
- Non-NzbDAV provider outcomes (torrentio, real_debrid, panda) remain
  unchanged — no provenance_kind, no NzbDAV fields.
"""
import logging
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
        email=f"nzbem-{uuid.uuid4().hex[:8]}@test.com",
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
        source_ref=f"nzbem-test-{user.id}",
        status="active",
    )
    db.add(ent)
    db.commit()
    db.refresh(user)
    return user


def _auth(user):
    return {"Authorization": f"Bearer {create_access_token(str(user.id))}"}


def _cleanup(db, user):
    db.query(ResolveSuccessMemory).filter(
        ResolveSuccessMemory.user_id == user.id
    ).delete()
    db.query(HashAvailabilityMemory).filter(
        HashAvailabilityMemory.user_id == user.id
    ).delete()
    db.query(ProviderInventorySnapshot).filter(
        ProviderInventorySnapshot.user_id == user.id
    ).delete()
    db.query(UserEntitlement).filter(UserEntitlement.user_id == user.id).delete()
    db.commit()
    u = db.query(User).filter(User.id == user.id).first()
    if u:
        db.delete(u)
        db.commit()


# ── Well-formed NzbDAV round-trip ───────────────────────────────────────


class TestNzbDavWellFormedEmission:

    def test_sources_surfaces_nzbdav_fields(self, client, db):
        user = _make_premium_user(db)
        try:
            r = client.post(
                "/me/acceleration/outcome",
                headers=_auth(user),
                json={
                    "content_id": "tmdb:1000",
                    "provider_type": "nzbdav",
                    "source_key": "nzbdav:cand-xyz",
                    "success": True,
                    "quality": "1080p",
                    "file_name": "Movie.2024.1080p.WEB-DL.mkv",
                    "nzbdav_candidate_id": "cand-xyz",
                    "nzbdav_hash_key": "hk-123",
                    "nzbdav_nzb_url": "https://example.com/nzbs/cand-xyz.nzb",
                },
            )
            assert r.status_code == 201
            assert r.json()["recorded"] is True

            r = client.get(
                "/me/acceleration/sources?content_id=tmdb:1000",
                headers=_auth(user),
            )
            assert r.status_code == 200
            body = r.json()
            assert len(body["candidates"]) == 1
            c = body["candidates"][0]
            assert c["provenance"] == "recent_success"
            assert c["provenance_kind"] == "USENET_NZBDAV"
            assert c["candidate_id"] == "cand-xyz"
            assert c["hash_key"] == "hk-123"
            assert c["nzb_url"] == "https://example.com/nzbs/cand-xyz.nzb"
            assert c["provider_type"] == "nzbdav"
        finally:
            _cleanup(db, user)

    def test_startup_surfaces_nzbdav_fields(self, client, db):
        user = _make_premium_user(db)
        try:
            client.post(
                "/me/acceleration/outcome",
                headers=_auth(user),
                json={
                    "content_id": "tmdb:1100",
                    "provider_type": "nzbdav",
                    "source_key": "nzbdav:cand-aaa",
                    "success": True,
                    "quality": "4k",
                    "nzbdav_candidate_id": "cand-aaa",
                    "nzbdav_hash_key": "hk-aaa",
                    "nzbdav_nzb_url": "https://example.com/nzbs/cand-aaa.nzb",
                },
            )

            r = client.get(
                "/me/acceleration/startup?content_id=tmdb:1100&title=Movie",
                headers=_auth(user),
            )
            assert r.status_code == 200
            body = r.json()
            nzb_candidates = [
                c for c in body["candidates"]
                if c.get("provenance_kind") == "USENET_NZBDAV"
            ]
            assert len(nzb_candidates) == 1
            c = nzb_candidates[0]
            assert c["candidate_id"] == "cand-aaa"
            assert c["hash_key"] == "hk-aaa"
            assert c["nzb_url"] == "https://example.com/nzbs/cand-aaa.nzb"
            assert c["provider_type"] == "nzbdav"
            # Scoring still works
            assert c["score"] > 0
            assert "exact_content_match" in c["reasons"]
        finally:
            _cleanup(db, user)


# ── Write-side guardrail: missing candidate_id ──────────────────────────


class TestNzbDavMalformedWrite:

    def test_missing_candidate_id_skips_nzbdav_fields(self, client, db, caplog):
        user = _make_premium_user(db)
        try:
            caplog.set_level(logging.WARNING, logger="app.routers.acceleration")
            r = client.post(
                "/me/acceleration/outcome",
                headers=_auth(user),
                json={
                    "content_id": "tmdb:1200",
                    "provider_type": "nzbdav",
                    "source_key": "nzbdav:broken",
                    "success": True,
                    "nzbdav_candidate_id": "",  # blank!
                    "nzbdav_hash_key": "hk-present",
                    "nzbdav_nzb_url": "https://example.com/x.nzb",
                },
            )
            assert r.status_code == 201  # accepts, doesn't 422
            assert r.json()["recorded"] is True

            # Warning log emitted
            assert any(
                "NZBDAV_OUTCOME_MALFORMED" in rec.getMessage()
                for rec in caplog.records
            ), f"expected NZBDAV_OUTCOME_MALFORMED in logs: {[r.getMessage() for r in caplog.records]}"

            # The row is persisted, but WITHOUT provenance_kind=USENET_NZBDAV
            r = client.get(
                "/me/acceleration/sources?content_id=tmdb:1200",
                headers=_auth(user),
            )
            assert r.status_code == 200
            body = r.json()
            assert len(body["candidates"]) == 1
            c = body["candidates"][0]
            # NO USENET_NZBDAV discriminator — baseline row only
            assert c.get("provenance_kind") is None
            assert c.get("candidate_id") is None
            assert c.get("hash_key") is None
            assert c.get("nzb_url") is None
            # Provider type still preserved
            assert c["provider_type"] == "nzbdav"
        finally:
            _cleanup(db, user)

    def test_missing_hash_key_skips_nzbdav_fields(self, client, db, caplog):
        user = _make_premium_user(db)
        try:
            caplog.set_level(logging.WARNING, logger="app.routers.acceleration")
            r = client.post(
                "/me/acceleration/outcome",
                headers=_auth(user),
                json={
                    "content_id": "tmdb:1201",
                    "provider_type": "nzbdav",
                    "source_key": "nzbdav:broken2",
                    "success": True,
                    "nzbdav_candidate_id": "cand-present",
                    "nzbdav_hash_key": "   ",  # whitespace only
                    "nzbdav_nzb_url": "https://example.com/y.nzb",
                },
            )
            assert r.status_code == 201
            assert any(
                "NZBDAV_OUTCOME_MALFORMED" in rec.getMessage()
                for rec in caplog.records
            )

            r = client.get(
                "/me/acceleration/sources?content_id=tmdb:1201",
                headers=_auth(user),
            )
            body = r.json()
            assert len(body["candidates"]) == 1
            assert body["candidates"][0].get("provenance_kind") is None
        finally:
            _cleanup(db, user)


# ── Read-side guardrail: broken DB row ──────────────────────────────────


class TestNzbDavReadSideSuppression:

    def test_db_row_with_blank_hash_key_suppressed(self, client, db, caplog):
        user = _make_premium_user(db)
        try:
            # Bypass the write-side guard by inserting directly
            from datetime import datetime, timedelta, timezone
            now = datetime.now(timezone.utc)
            row = ResolveSuccessMemory(
                id=uuid.uuid4(),
                user_id=user.id,
                content_id="tmdb:1300",
                provider_type="nzbdav",
                source_key="nzbdav:sneaky",
                success_count=1,
                last_success_at=now,
                expires_at=now + timedelta(days=30),
                provenance_kind="USENET_NZBDAV",
                nzbdav_candidate_id="cand-present",
                nzbdav_hash_key=None,  # broken!
                nzbdav_nzb_url="https://example.com/z.nzb",
            )
            db.add(row)
            db.commit()

            caplog.set_level(logging.WARNING, logger="app.routers.acceleration")

            r = client.get(
                "/me/acceleration/sources?content_id=tmdb:1300",
                headers=_auth(user),
            )
            assert r.status_code == 200
            body = r.json()
            # Row suppressed entirely — no candidate emitted
            assert body["candidates"] == []
            assert any(
                "NZBDAV_EMISSION_SUPPRESSED" in rec.getMessage()
                for rec in caplog.records
            ), f"expected NZBDAV_EMISSION_SUPPRESSED in logs: {[r.getMessage() for r in caplog.records]}"
        finally:
            _cleanup(db, user)

    def test_db_row_with_blank_candidate_id_suppressed_in_startup(self, client, db, caplog):
        user = _make_premium_user(db)
        try:
            from datetime import datetime, timedelta, timezone
            now = datetime.now(timezone.utc)
            row = ResolveSuccessMemory(
                id=uuid.uuid4(),
                user_id=user.id,
                content_id="tmdb:1301",
                provider_type="nzbdav",
                source_key="nzbdav:sneaky2",
                success_count=1,
                last_success_at=now,
                expires_at=now + timedelta(days=30),
                provenance_kind="USENET_NZBDAV",
                nzbdav_candidate_id="",  # blank
                nzbdav_hash_key="hk-present",
                nzbdav_nzb_url=None,
            )
            db.add(row)
            db.commit()

            caplog.set_level(logging.WARNING, logger="app.startup_assembly")

            r = client.get(
                "/me/acceleration/startup?content_id=tmdb:1301",
                headers=_auth(user),
            )
            assert r.status_code == 200
            body = r.json()
            # Startup suppresses the row too
            nzb_candidates = [
                c for c in body["candidates"]
                if c.get("provenance_kind") == "USENET_NZBDAV"
            ]
            assert nzb_candidates == []
        finally:
            _cleanup(db, user)


# ── Non-NzbDAV providers: no regression ─────────────────────────────────


class TestNonNzbDavUnchanged:

    def test_torrentio_outcome_has_no_provenance_kind(self, client, db):
        user = _make_premium_user(db)
        try:
            client.post(
                "/me/acceleration/outcome",
                headers=_auth(user),
                json={
                    "content_id": "tmdb:1400",
                    "provider_type": "torrentio",
                    "source_key": "torrentio:abc",
                    "success": True,
                    "infohash": "a" * 40,
                    "quality": "1080p",
                },
            )

            r = client.get(
                "/me/acceleration/sources?content_id=tmdb:1400",
                headers=_auth(user),
            )
            body = r.json()
            assert len(body["candidates"]) == 1
            c = body["candidates"][0]
            assert c["provider_type"] == "torrentio"
            assert c["provenance"] == "recent_success"
            assert c.get("provenance_kind") is None
            assert c.get("candidate_id") is None
            assert c.get("hash_key") is None
            assert c.get("nzb_url") is None
        finally:
            _cleanup(db, user)

    def test_real_debrid_outcome_unchanged(self, client, db):
        user = _make_premium_user(db)
        try:
            client.post(
                "/me/acceleration/outcome",
                headers=_auth(user),
                json={
                    "content_id": "tmdb:1401",
                    "provider_type": "real_debrid",
                    "source_key": "rd:abc",
                    "success": True,
                    "infohash": "b" * 40,
                },
            )

            r = client.get(
                "/me/acceleration/startup?content_id=tmdb:1401",
                headers=_auth(user),
            )
            body = r.json()
            assert len(body["candidates"]) == 1
            c = body["candidates"][0]
            assert c["provider_type"] == "real_debrid"
            assert c.get("provenance_kind") is None
            assert c.get("candidate_id") is None
            assert c.get("hash_key") is None
            assert c.get("nzb_url") is None
        finally:
            _cleanup(db, user)

    def test_panda_style_addon_unchanged(self, client, db):
        user = _make_premium_user(db)
        try:
            # Panda-style: addon-like provider, no NzbDAV fields at all
            client.post(
                "/me/acceleration/outcome",
                headers=_auth(user),
                json={
                    "content_id": "tmdb:1402",
                    "provider_type": "panda",
                    "source_key": "panda:stream-1",
                    "success": True,
                    "quality": "720p",
                },
            )

            r = client.get(
                "/me/acceleration/sources?content_id=tmdb:1402",
                headers=_auth(user),
            )
            body = r.json()
            assert len(body["candidates"]) == 1
            c = body["candidates"][0]
            assert c["provider_type"] == "panda"
            assert c["quality"] == "720p"
            assert c.get("provenance_kind") is None
            # Original shape preserved — no new required fields
            assert c["provenance"] == "recent_success"

            r = client.get(
                "/me/acceleration/startup?content_id=tmdb:1402",
                headers=_auth(user),
            )
            body = r.json()
            assert len(body["candidates"]) == 1
            c = body["candidates"][0]
            assert c.get("provenance_kind") is None
        finally:
            _cleanup(db, user)

    def test_http_source_key_hidden_when_memory_id_is_emitted(self, client, db, caplog):
        user = _make_premium_user(db)
        try:
            caplog.set_level(logging.INFO, logger="app.routers.acceleration")
            client.post(
                "/me/acceleration/outcome",
                headers=_auth(user),
                json={
                    "content_id": "tmdb:1403",
                    "provider_type": "real_debrid",
                    "source_key": "https://provider.example/video.mp4?token=SECRET_VALUE",
                    "success": True,
                    "infohash": "d" * 40,
                    "quality": "1080p",
                },
            )

            r = client.get(
                "/me/acceleration/sources?content_id=tmdb:1403",
                headers={**_auth(user), "X-Torve-Installation-Id": "install-test-1"},
            )
            assert r.status_code == 200
            body = r.json()
            assert len(body["candidates"]) == 1
            c = body["candidates"][0]
            assert c["memory_id"]
            assert c["source_key"] is None
            assert "provider.example" not in r.text
            assert "SECRET_VALUE" not in r.text

            coverage_logs = [
                rec.getMessage()
                for rec in caplog.records
                if "ACCEL_MEMORY_ID_COVERAGE" in rec.getMessage()
            ]
            assert coverage_logs
            assert any("coverage_pct=100.00" in msg for msg in coverage_logs)
            assert all(str(user.id) not in msg for msg in coverage_logs)
            assert all("user_hash=" in msg and "device_hash=" in msg for msg in coverage_logs)
        finally:
            _cleanup(db, user)

    def test_inventory_match_attaches_existing_memory_id_without_raw_url(self, client, db):
        user = _make_premium_user(db)
        try:
            now = datetime.now(timezone.utc)
            memory = ResolveSuccessMemory(
                user_id=user.id,
                content_id="tmdb:1404",
                provider_type="real_debrid",
                source_key="https://provider.example/inventory.mp4?token=SECRET_VALUE",
                infohash="e" * 40,
                file_name="Inventory.Match.2026.1080p.mkv",
                quality="1080p",
                success_count=2,
                last_success_at=now,
                expires_at=now + timedelta(days=30),
            )
            inv = ProviderInventorySnapshot(
                user_id=user.id,
                provider_type="real_debrid",
                remote_item_id="remote-1404",
                normalized_title="inventory match",
                infohash="e" * 40,
                file_name="Inventory.Match.2026.1080p.mkv",
                inventory_class="cloud",
                quality="1080p",
                last_seen_at=now,
                expires_at=now + timedelta(days=1),
            )
            db.add_all([memory, inv])
            db.commit()
            db.refresh(memory)

            r = client.get(
                "/me/acceleration/inventory?content_id=tmdb:1404&infohash="
                + ("e" * 40)
                + "&provider_type=real_debrid",
                headers=_auth(user),
            )
            assert r.status_code == 200
            body = r.json()
            assert len(body["matches"]) == 1
            match = body["matches"][0]
            assert match["memory_id"] == str(memory.id)
            assert match["source_key"] is None
            assert match["success_count"] == 2
            assert "provider.example" not in r.text
            assert "SECRET_VALUE" not in r.text
        finally:
            _cleanup(db, user)
