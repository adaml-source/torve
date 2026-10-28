"""Tests for the content policy classification engine and policy contract.

Covers:
1. Classifier: safe, sensitive, blocked, review cases
2. Filename normalization (separators, leetspeak, punctuation)
3. Multilingual keywords
4. Addon classification
5. Documentary/artistic override
6. Age band and enablement validation
7. Policy contract API (read, DOB, enable/disable, mode)
8. Acceleration endpoint filtering (blocked never returns, sensitive gated)
9. Blocked_illegal never returned in Google Play mode
10. Account settings validation for unsafe configs
"""
import uuid
from datetime import date, datetime, timedelta, timezone

import pytest

from app.content_policy import (
    AccessContext,
    AddonClassification,
    AgeBand,
    ContentClassification,
    ContentPolicyMode,
    PolicyDecision,
    classify_addon,
    classify_text,
    decide,
    normalize_text,
)
from app.content_policy_service import (
    disable_sensitive_material,
    enable_sensitive_material,
    get_or_create_policy,
    get_policy_state,
    set_date_of_birth,
    upsert_override,
)
from app.models import (
    ContentOverride,
    HashAvailabilityMemory,
    ProviderInventorySnapshot,
    ResolveSuccessMemory,
    User,
    UserContentPolicy,
    UserEntitlement,
)
from app.security import create_access_token, hash_password


def _make_user(db, *, premium=True):
    user = User(
        id=uuid.uuid4(),
        email=f"policy-{uuid.uuid4().hex[:8]}@test.com",
        password_hash=hash_password("TestPass123!"),
        is_verified=True,
        has_lifetime_access=premium,
        has_premium_access=premium,
    )
    db.add(user)
    db.flush()
    if premium:
        ent = UserEntitlement(
            user_id=user.id, entitlement_type="lifetime_access",
            source="admin_grant", source_ref=f"policy-{user.id}", status="active",
        )
        db.add(ent)
    db.commit()
    db.refresh(user)
    return user


def _auth(user):
    return {"Authorization": f"Bearer {create_access_token(str(user.id))}"}


def _gplay_auth(user):
    """Auth headers with Google Play channel."""
    return {
        "Authorization": f"Bearer {create_access_token(str(user.id))}",
        "X-Torve-Channel": "google_play",
    }


def _open_auth(user):
    """Auth headers with open/desktop channel."""
    return {
        "Authorization": f"Bearer {create_access_token(str(user.id))}",
        "X-Torve-Channel": "open",
    }


def _cleanup(db, user):
    for model in (ResolveSuccessMemory, HashAvailabilityMemory,
                  ProviderInventorySnapshot, UserContentPolicy,
                  UserEntitlement, ContentOverride):
        db.query(model).filter(
            model.user_id == user.id if hasattr(model, "user_id") else True
        ).delete()
    db.commit()
    u = db.query(User).filter(User.id == user.id).first()
    if u:
        db.delete(u)
        db.commit()


# ── 1. Classifier unit tests ───────────────────────────────────────────


class TestClassifyText:

    def test_safe_movie_title(self):
        assert classify_text("The.Matrix.1999.1080p.BluRay.mkv") == ContentClassification.SAFE

    def test_safe_show_title(self):
        assert classify_text("Breaking.Bad.S05E16.720p.WEB-DL") == ContentClassification.SAFE

    def test_blocked_porn(self):
        assert classify_text("XXX.Movie.2024.1080p.mkv") == ContentClassification.BLOCKED_ILLEGAL

    def test_blocked_explicit_terms(self):
        for term in ["porn", "pornography", "hardcore sex", "hentai"]:
            assert classify_text(f"Some.{term}.Video.mkv") == ContentClassification.BLOCKED_ILLEGAL, f"Failed for: {term}"

    def test_blocked_illegal_content(self):
        assert classify_text("child porn anything") == ContentClassification.BLOCKED_ILLEGAL
        assert classify_text("bestiality video") == ContentClassification.BLOCKED_ILLEGAL

    def test_sensitive_erotic(self):
        assert classify_text("Erotic.Thriller.2024.1080p.mkv") == ContentClassification.SENSITIVE_CATALOG

    def test_sensitive_adult_content(self):
        assert classify_text("Adults.Only.Film.2024") == ContentClassification.SENSITIVE_CATALOG

    def test_sensitive_nudity(self):
        assert classify_text("Full.Frontal.Nudity.Documentary.mkv") == ContentClassification.SAFE  # documentary override

    def test_empty_string(self):
        assert classify_text("") == ContentClassification.SAFE

    def test_none_safe(self):
        assert classify_text(None) == ContentClassification.SAFE

    def test_documentary_override_nude(self):
        """Documentary with nude term should be SAFE, not SENSITIVE."""
        assert classify_text("Nude Art Documentary 2024") == ContentClassification.SAFE

    def test_documentary_override_not_for_blocked(self):
        """Documentary cannot override BLOCKED terms."""
        assert classify_text("Documentary about pornography industry") == ContentClassification.BLOCKED_ILLEGAL

    def test_manual_denylist(self):
        assert classify_text(
            "Normal Movie", external_id="tmdb:evil",
            denylist_ids=frozenset({"tmdb:evil"}),
        ) == ContentClassification.BLOCKED_ILLEGAL

    def test_manual_allowlist(self):
        assert classify_text(
            "Erotic Film", external_id="tmdb:art",
            allowlist_ids=frozenset({"tmdb:art"}),
        ) == ContentClassification.SAFE


# ── 2. Normalization ───────────────────────────────────────────────────


class TestNormalization:

    def test_dots_and_underscores(self):
        assert normalize_text("The.Matrix_1999") == "the matrix 1999"

    def test_brackets(self):
        assert normalize_text("[Movie](2024)-1080p") == "movie 2024 1080p"

    def test_leetspeak_detection(self):
        """Leetspeak evasion is caught by the classifier, not normalize_text."""
        assert normalize_text("p0rn") == "p0rn"  # normalize_text doesn't apply leetspeak
        # But the classifier catches it via leetspeak variant checking
        assert classify_text("p0rn video") == ContentClassification.BLOCKED_ILLEGAL

    def test_empty(self):
        assert normalize_text("") == ""

    def test_unicode_preserved(self):
        n = normalize_text("日本語タイトル")
        assert "日本語タイトル" in n


# ── 3. Multilingual keywords ──────────────────────────────────────────


class TestMultilingual:

    def test_german_porn(self):
        assert classify_text("Pornofilm.2024.mkv") == ContentClassification.BLOCKED_ILLEGAL

    def test_french_porn(self):
        assert classify_text("Film.Porno.Francais.mkv") == ContentClassification.BLOCKED_ILLEGAL

    def test_german_erotic(self):
        assert classify_text("Erotikfilm.2024.mkv") == ContentClassification.SENSITIVE_CATALOG

    def test_turkish_porn(self):
        assert classify_text("Porno.Izle.2024") == ContentClassification.BLOCKED_ILLEGAL

    def test_spanish_erotic(self):
        assert classify_text("Pelicula.Erotica.2024") == ContentClassification.SENSITIVE_CATALOG


# ── 4. Addon classification ───────────────────────────────────────────


class TestAddonClassification:

    def test_safe_addon(self):
        assert classify_addon(
            manifest_url="https://v3-cinemeta.strem.io/manifest.json",
            name="Cinemeta", description="Movie and TV catalog",
        ) == AddonClassification.SAFE

    def test_blocked_addon_url(self):
        assert classify_addon(
            manifest_url="https://pornhub-addon.example.com/manifest.json",
            name="Test",
        ) == AddonClassification.BLOCKED_ILLEGAL

    def test_blocked_addon_name(self):
        assert classify_addon(name="XXX Adult Streams") == AddonClassification.BLOCKED_ILLEGAL

    def test_sensitive_addon(self):
        assert classify_addon(
            name="Erotic Films", description="Adult erotic movie catalog",
        ) == AddonClassification.SENSITIVE_CATALOG

    def test_addon_url_denylist(self):
        assert classify_addon(
            manifest_url="https://safe.example.com/manifest.json",
            denylist_urls=frozenset({"https://safe.example.com/manifest.json"}),
        ) == AddonClassification.BLOCKED_ILLEGAL

    def test_addon_url_allowlist(self):
        assert classify_addon(
            manifest_url="https://adult-but-allowed.example.com/manifest.json",
            name="Erotic Catalog",
            allowlist_urls=frozenset({"https://adult-but-allowed.example.com/manifest.json"}),
        ) == AddonClassification.SAFE


# ── 5. Policy decision engine ─────────────────────────────────────────


class TestPolicyDecision:

    def test_blocked_always_blocks(self):
        for mode in ContentPolicyMode:
            for band in AgeBand:
                assert decide(
                    policy_mode=mode, classification=ContentClassification.BLOCKED_ILLEGAL,
                    age_band=band, sensitive_enabled=True, context=AccessContext.DIRECT_SEARCH,
                ) == PolicyDecision.BLOCK

    def test_safe_always_allowed(self):
        assert decide(
            policy_mode=ContentPolicyMode.GOOGLE_PLAY_SAFE_DEFAULT,
            classification=ContentClassification.SAFE,
            age_band=AgeBand.UNKNOWN, sensitive_enabled=False,
            context=AccessContext.DEFAULT_DISCOVERY,
        ) == PolicyDecision.ALLOW_FULL

    def test_open_mode_sensitive_still_requires_adult(self):
        """Open mode hides sensitive for non-adult."""
        assert decide(
            policy_mode=ContentPolicyMode.OPEN,
            classification=ContentClassification.SENSITIVE_CATALOG,
            age_band=AgeBand.UNKNOWN, sensitive_enabled=False,
            context=AccessContext.DEFAULT_DISCOVERY,
        ) == PolicyDecision.HIDE

    def test_open_mode_allows_sensitive_for_adult(self):
        """Open mode allows sensitive for adult+enabled."""
        assert decide(
            policy_mode=ContentPolicyMode.OPEN,
            classification=ContentClassification.SENSITIVE_CATALOG,
            age_band=AgeBand.ADULT, sensitive_enabled=True,
            context=AccessContext.DEFAULT_DISCOVERY,
        ) == PolicyDecision.ALLOW_FULL

    def test_gplay_unknown_age_hides_sensitive(self):
        assert decide(
            policy_mode=ContentPolicyMode.GOOGLE_PLAY_SAFE_DEFAULT,
            classification=ContentClassification.SENSITIVE_CATALOG,
            age_band=AgeBand.UNKNOWN, sensitive_enabled=False,
            context=AccessContext.DIRECT_SEARCH,
        ) == PolicyDecision.HIDE

    def test_gplay_adult_disabled_hides(self):
        assert decide(
            policy_mode=ContentPolicyMode.GOOGLE_PLAY_SAFE_DEFAULT,
            classification=ContentClassification.SENSITIVE_CATALOG,
            age_band=AgeBand.ADULT, sensitive_enabled=False,
            context=AccessContext.DIRECT_SEARCH,
        ) == PolicyDecision.HIDE

    def test_gplay_adult_enabled_search_allows(self):
        assert decide(
            policy_mode=ContentPolicyMode.GOOGLE_PLAY_SAFE_DEFAULT,
            classification=ContentClassification.SENSITIVE_CATALOG,
            age_band=AgeBand.ADULT, sensitive_enabled=True,
            context=AccessContext.DIRECT_SEARCH,
        ) == PolicyDecision.ALLOW_FULL

    def test_gplay_adult_enabled_discovery_hides(self):
        """Even adult-unlocked users don't see sensitive in default discovery."""
        assert decide(
            policy_mode=ContentPolicyMode.GOOGLE_PLAY_SAFE_DEFAULT,
            classification=ContentClassification.SENSITIVE_CATALOG,
            age_band=AgeBand.ADULT, sensitive_enabled=True,
            context=AccessContext.DEFAULT_DISCOVERY,
        ) == PolicyDecision.HIDE

    def test_review_required_fails_closed_gplay(self):
        assert decide(
            policy_mode=ContentPolicyMode.GOOGLE_PLAY_SAFE_DEFAULT,
            classification=ContentClassification.REVIEW_REQUIRED,
            age_band=AgeBand.ADULT, sensitive_enabled=True,
            context=AccessContext.DEFAULT_DISCOVERY,
        ) == PolicyDecision.HIDE

    def test_review_required_fails_closed_open_mode(self):
        """REVIEW_REQUIRED fails closed even in open mode."""
        assert decide(
            policy_mode=ContentPolicyMode.OPEN,
            classification=ContentClassification.REVIEW_REQUIRED,
            age_band=AgeBand.ADULT, sensitive_enabled=True,
            context=AccessContext.DIRECT_SEARCH,
        ) == PolicyDecision.HIDE

    def test_null_classification_fails_closed(self):
        """Null classification treated as REVIEW_REQUIRED = HIDE."""
        assert decide(
            policy_mode=ContentPolicyMode.GOOGLE_PLAY_SAFE_DEFAULT,
            classification=None,
            age_band=AgeBand.ADULT, sensitive_enabled=True,
            context=AccessContext.DIRECT_SEARCH,
        ) == PolicyDecision.HIDE


# ── 6. Age band and enablement ────────────────────────────────────────


class TestAgeBandAndEnablement:

    def test_set_adult_dob(self, db):
        user = _make_user(db)
        try:
            policy = set_date_of_birth(db, user.id, date(1990, 1, 1))
            db.commit()
            assert policy.age_band == "adult"
            assert policy.adult_eligible is True
        finally:
            _cleanup(db, user)

    def test_set_minor_dob(self, db):
        user = _make_user(db)
        try:
            policy = set_date_of_birth(db, user.id, date(2015, 1, 1))
            db.commit()
            assert policy.age_band == "under_18"
            assert policy.adult_eligible is False
        finally:
            _cleanup(db, user)

    def test_unknown_age_default(self, db):
        user = _make_user(db)
        try:
            policy = get_or_create_policy(db, user.id)
            db.commit()
            assert policy.age_band == "unknown"
            assert policy.adult_eligible is False
            assert policy.sensitive_material_enabled is False
        finally:
            _cleanup(db, user)

    def test_enable_sensitive_requires_adult(self, db):
        user = _make_user(db)
        try:
            with pytest.raises(ValueError, match="not adult-eligible"):
                enable_sensitive_material(db, user.id, "v1")
        finally:
            _cleanup(db, user)

    def test_enable_sensitive_adult(self, db):
        user = _make_user(db)
        try:
            set_date_of_birth(db, user.id, date(1990, 1, 1))
            policy = enable_sensitive_material(db, user.id, "v1")
            db.commit()
            assert policy.sensitive_material_enabled is True
            assert policy.sensitive_material_policy_version == "v1"
        finally:
            _cleanup(db, user)

    def test_disable_sensitive(self, db):
        user = _make_user(db)
        try:
            set_date_of_birth(db, user.id, date(1990, 1, 1))
            enable_sensitive_material(db, user.id, "v1")
            policy = disable_sensitive_material(db, user.id)
            db.commit()
            assert policy.sensitive_material_enabled is False
        finally:
            _cleanup(db, user)

    def test_minor_dob_disables_sensitive(self, db):
        """Changing DOB to minor disables sensitive material."""
        user = _make_user(db)
        try:
            set_date_of_birth(db, user.id, date(1990, 1, 1))
            enable_sensitive_material(db, user.id, "v1")
            db.commit()
            # Now become minor
            policy = set_date_of_birth(db, user.id, date(2015, 1, 1))
            db.commit()
            assert policy.sensitive_material_enabled is False
        finally:
            _cleanup(db, user)


# ── 7. Policy contract API ────────────────────────────────────────────


class TestPolicyAPI:

    def test_get_default_policy(self, client, db):
        user = _make_user(db, premium=False)
        try:
            r = client.get("/me/content-policy", headers=_auth(user))
            assert r.status_code == 200
            body = r.json()
            assert body["age_band"] == "unknown"
            assert body["adult_eligible"] is False
            assert body["sensitive_material_enabled"] is False
            assert body["can_enable_sensitive_material"] is False
        finally:
            _cleanup(db, user)

    def test_set_dob_api(self, client, db):
        user = _make_user(db, premium=False)
        try:
            r = client.patch("/me/content-policy/dob", headers=_auth(user),
                             json={"date_of_birth": "1990-05-15"})
            assert r.status_code == 200
            assert r.json()["age_band"] == "adult"
            assert r.json()["adult_eligible"] is True
        finally:
            _cleanup(db, user)

    def test_enable_sensitive_api_requires_adult(self, client, db):
        user = _make_user(db, premium=False)
        try:
            r = client.post("/me/content-policy/enable-sensitive", headers=_auth(user),
                            json={"policy_version": "v1"})
            assert r.status_code == 403
        finally:
            _cleanup(db, user)

    def test_enable_disable_sensitive_flow(self, client, db):
        user = _make_user(db, premium=False)
        try:
            client.patch("/me/content-policy/dob", headers=_auth(user),
                         json={"date_of_birth": "1990-01-01"})
            r = client.post("/me/content-policy/enable-sensitive", headers=_auth(user),
                            json={"policy_version": "v1"})
            assert r.status_code == 200
            assert r.json()["sensitive_material_enabled"] is True

            r = client.post("/me/content-policy/disable-sensitive", headers=_auth(user))
            assert r.status_code == 200
            assert r.json()["sensitive_material_enabled"] is False
        finally:
            _cleanup(db, user)

    def test_policy_mode_from_header(self, client, db):
        """Policy mode is resolved from X-Torve-Channel header, not stored."""
        user = _make_user(db, premium=False)
        try:
            r = client.get("/me/content-policy", headers=_gplay_auth(user))
            assert r.json()["content_policy_mode"] == "google_play"

            r = client.get("/me/content-policy", headers=_open_auth(user))
            assert r.json()["content_policy_mode"] == "open"
        finally:
            _cleanup(db, user)

    def test_no_mode_endpoint(self, client, db):
        """PATCH /me/content-policy/mode no longer exists."""
        user = _make_user(db, premium=False)
        try:
            r = client.patch("/me/content-policy/mode", headers=_auth(user),
                             json={"content_policy_mode": "open"})
            assert r.status_code in (404, 405)
        finally:
            _cleanup(db, user)

    def test_policy_state_version_in_response(self, client, db):
        user = _make_user(db, premium=False)
        try:
            r = client.get("/me/content-policy", headers=_auth(user))
            assert "policy_state_version" in r.json()
            v1 = r.json()["policy_state_version"]

            # Change DOB — version should increment
            client.patch("/me/content-policy/dob", headers=_auth(user),
                         json={"date_of_birth": "1990-01-01"})
            r = client.get("/me/content-policy", headers=_auth(user))
            assert r.json()["policy_state_version"] > v1
        finally:
            _cleanup(db, user)


# ── 8. Acceleration endpoint filtering ────────────────────────────────


class TestAccelerationFiltering:

    def test_blocked_filename_never_returned(self, client, db):
        """A resolve success with a porn filename must not appear in startup."""
        user = _make_user(db)
        try:
            # Record a success with a blocked filename
            client.post("/me/acceleration/outcome", headers=_auth(user), json={
                "content_id": "tmdb:blocked",
                "provider_type": "rd",
                "source_key": "src:blocked",
                "success": True,
                "file_name": "XXX.Porn.Movie.2024.1080p.mkv",
            })

            r = client.get("/me/acceleration/sources?content_id=tmdb:blocked",
                           headers=_gplay_auth(user))
            assert r.status_code == 200
            assert len(r.json()["candidates"]) == 0
        finally:
            _cleanup(db, user)

    def test_sensitive_hidden_for_locked_user(self, client, db):
        """Sensitive filenames hidden for non-adult-unlocked users."""
        user = _make_user(db)
        try:
            client.post("/me/acceleration/outcome", headers=_auth(user), json={
                "content_id": "tmdb:sensitive",
                "provider_type": "rd",
                "source_key": "src:sensitive",
                "success": True,
                "file_name": "Erotic.Thriller.2024.1080p.mkv",
            })

            r = client.get("/me/acceleration/sources?content_id=tmdb:sensitive",
                           headers=_gplay_auth(user))
            assert r.status_code == 200
            # Sensitive hidden because user is unknown age
            assert len(r.json()["candidates"]) == 0
        finally:
            _cleanup(db, user)

    def test_sensitive_visible_for_unlocked_adult(self, client, db):
        """Sensitive filenames returned for adult-unlocked users in history context."""
        user = _make_user(db)
        try:
            set_date_of_birth(db, user.id, date(1990, 1, 1))
            enable_sensitive_material(db, user.id, "v1")
            db.commit()

            client.post("/me/acceleration/outcome", headers=_auth(user), json={
                "content_id": "tmdb:sensitive2",
                "provider_type": "rd",
                "source_key": "src:sensitive2",
                "success": True,
                "file_name": "Erotic.Movie.2024.1080p.mkv",
            })

            r = client.get("/me/acceleration/sources?content_id=tmdb:sensitive2",
                           headers=_gplay_auth(user))
            assert r.status_code == 200
            assert len(r.json()["candidates"]) == 1
        finally:
            _cleanup(db, user)

    def test_safe_always_returned(self, client, db):
        """Safe content always returned regardless of policy state."""
        user = _make_user(db)
        try:
            client.post("/me/acceleration/outcome", headers=_auth(user), json={
                "content_id": "tmdb:safe",
                "provider_type": "rd",
                "source_key": "src:safe",
                "success": True,
                "file_name": "The.Matrix.1999.1080p.BluRay.mkv",
            })

            r = client.get("/me/acceleration/sources?content_id=tmdb:safe",
                           headers=_gplay_auth(user))
            assert r.status_code == 200
            assert len(r.json()["candidates"]) == 1
        finally:
            _cleanup(db, user)

    def test_startup_filters_blocked(self, client, db):
        """Startup candidates never include blocked content."""
        user = _make_user(db)
        try:
            client.post("/me/acceleration/outcome", headers=_auth(user), json={
                "content_id": "tmdb:mixed",
                "provider_type": "rd",
                "source_key": "src:clean",
                "success": True,
                "file_name": "Normal.Movie.2024.1080p.mkv",
            })
            client.post("/me/acceleration/outcome", headers=_auth(user), json={
                "content_id": "tmdb:mixed",
                "provider_type": "rd",
                "source_key": "src:porn",
                "success": True,
                "file_name": "XXX.Porn.Version.2024.mkv",
            })

            r = client.get("/me/acceleration/startup?content_id=tmdb:mixed",
                           headers=_gplay_auth(user))
            assert r.status_code == 200
            candidates = r.json()["candidates"]
            filenames = [c["file_name"] for c in candidates]
            assert not any("XXX" in (f or "") for f in filenames)
            assert any("Normal" in (f or "") for f in filenames)
        finally:
            _cleanup(db, user)


# ── 9. Blocked never returned regression ──────────────────────────────


class TestBlockedNeverReturned:

    def test_blocked_not_in_inventory(self, client, db):
        user = _make_user(db)
        try:
            client.post("/me/acceleration/inventory/ingest", headers=_auth(user), json={
                "provider_type": "real_debrid",
                "items": [
                    {"id": "safe1", "filename": "Normal.Movie.2024.mkv", "hash": "a" * 40},
                    {"id": "porn1", "filename": "XXX.Hardcore.2024.mkv", "hash": "b" * 40},
                ],
            })

            r = client.get(
                f"/me/acceleration/inventory?content_id=test&infohash={'b' * 40}",
                headers=_gplay_auth(user),
            )
            assert r.status_code == 200
            assert len(r.json()["matches"]) == 0
        finally:
            _cleanup(db, user)


# ── 10. Manual overrides ──────────────────────────────────────────────


class TestManualOverrides:

    def test_upsert_override(self, db):
        user = _make_user(db)
        try:
            override = upsert_override(
                db, override_type="content", external_key="tmdb:12345",
                action="deny", note="Known adult content", updated_by="admin",
            )
            db.commit()
            assert override.action == "deny"

            # Update it
            override = upsert_override(
                db, override_type="content", external_key="tmdb:12345",
                action="allow", note="Reviewed and safe",
            )
            db.commit()
            assert override.action == "allow"
        finally:
            db.query(ContentOverride).delete()
            db.commit()
            _cleanup(db, user)


# ── 11. Hardening patch tests ─────────────────────────────────────────


class TestHardeningPatches:

    def test_gplay_client_cannot_switch_to_open(self, client, db):
        """PATCH /me/content-policy/mode endpoint is removed."""
        user = _make_user(db, premium=False)
        try:
            r = client.patch("/me/content-policy/mode",
                             headers=_gplay_auth(user),
                             json={"content_policy_mode": "open"})
            assert r.status_code in (404, 405)
        finally:
            _cleanup(db, user)

    def test_desktop_channel_does_not_affect_play(self, client, db):
        """Open channel header on one request doesn't affect GPlay on next."""
        user = _make_user(db, premium=False)
        try:
            # Request with open channel
            r1 = client.get("/me/content-policy", headers=_open_auth(user))
            assert r1.json()["content_policy_mode"] == "open"

            # Same user, GPlay channel — must still be google_play
            r2 = client.get("/me/content-policy", headers=_gplay_auth(user))
            assert r2.json()["content_policy_mode"] == "google_play"
        finally:
            _cleanup(db, user)

    def test_missing_channel_header_defaults_to_gplay(self, client, db):
        """No X-Torve-Channel header = defaults to google_play (fail safe)."""
        user = _make_user(db, premium=False)
        try:
            r = client.get("/me/content-policy", headers=_auth(user))
            assert r.json()["content_policy_mode"] == "google_play"
        finally:
            _cleanup(db, user)

    def test_policy_version_increments_on_dob(self, client, db):
        user = _make_user(db, premium=False)
        try:
            r = client.get("/me/content-policy", headers=_auth(user))
            v1 = r.json()["policy_state_version"]

            client.patch("/me/content-policy/dob", headers=_auth(user),
                         json={"date_of_birth": "1990-01-01"})
            r = client.get("/me/content-policy", headers=_auth(user))
            v2 = r.json()["policy_state_version"]
            assert v2 > v1
        finally:
            _cleanup(db, user)

    def test_policy_version_increments_on_enable(self, client, db):
        user = _make_user(db, premium=False)
        try:
            client.patch("/me/content-policy/dob", headers=_auth(user),
                         json={"date_of_birth": "1990-01-01"})
            r = client.get("/me/content-policy", headers=_auth(user))
            v1 = r.json()["policy_state_version"]

            client.post("/me/content-policy/enable-sensitive", headers=_auth(user),
                        json={"policy_version": "v1"})
            r = client.get("/me/content-policy", headers=_auth(user))
            assert r.json()["policy_state_version"] > v1
        finally:
            _cleanup(db, user)

    def test_policy_version_increments_on_disable(self, client, db):
        user = _make_user(db, premium=False)
        try:
            client.patch("/me/content-policy/dob", headers=_auth(user),
                         json={"date_of_birth": "1990-01-01"})
            client.post("/me/content-policy/enable-sensitive", headers=_auth(user),
                        json={"policy_version": "v1"})
            r = client.get("/me/content-policy", headers=_auth(user))
            v1 = r.json()["policy_state_version"]

            client.post("/me/content-policy/disable-sensitive", headers=_auth(user))
            r = client.get("/me/content-policy", headers=_auth(user))
            assert r.json()["policy_state_version"] > v1
        finally:
            _cleanup(db, user)

    def test_unknown_age_startup_no_sensitive_leak(self, client, db):
        """Unknown-age user gets no sensitive content in startup."""
        user = _make_user(db)
        try:
            client.post("/me/acceleration/outcome", headers=_auth(user), json={
                "content_id": "tmdb:leak", "provider_type": "rd",
                "source_key": "src:leak", "success": True,
                "file_name": "Erotic.Movie.2024.mkv",
            })
            # Default channel = google_play, unknown age
            r = client.get("/me/acceleration/startup?content_id=tmdb:leak",
                           headers=_auth(user))
            assert r.status_code == 200
            assert len(r.json()["candidates"]) == 0
        finally:
            _cleanup(db, user)
