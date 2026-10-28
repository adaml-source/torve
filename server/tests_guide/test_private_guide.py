"""Isolated guide contracts; never load server/tests production DB fixtures."""
import os
os.environ["DATABASE_URL"] = "sqlite:///unused-guide-test.sqlite"
os.environ["JWT_SECRET"] = "guide-tests-only-not-a-production-key"
os.environ["APP_ENV"] = "test"

from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import MagicMock, patch
import uuid

from fastapi import FastAPI
from fastapi.testclient import TestClient
from app.deps import get_db
from app.guide_catalog import load_guide
from app.routers import web_session
from app.security import create_access_token


class PrivateGuideTests(unittest.TestCase):
    def setUp(self):
        self.db = MagicMock()
        app = FastAPI()
        app.include_router(web_session.router)
        app.dependency_overrides[get_db] = lambda: self.db
        self.client = TestClient(app, base_url="https://testserver")
        self.addCleanup(self.client.close)
        self.uid = uuid.uuid4()
        self.user = SimpleNamespace(id=self.uid, email="guide@isolated.invalid", display_name=None,
            is_active=True, is_verified=True, has_lifetime_access=False, has_premium_access=False,
            created_at=datetime.now(timezone.utc))
        self.refresh = SimpleNamespace(user_id=self.uid, is_revoked=False,
            expires_at=datetime.now(timezone.utc) + timedelta(days=1))

    def cookies(self, access=None):
        self.client.cookies.set("torve_web_refresh", "isolated-refresh")
        if access:
            self.client.cookies.set("torve_web_access", access)

    def rows(self, *rows):
        self.db.query.return_value.filter.return_value.first.side_effect = rows

    def assert_private(self, response):
        self.assertEqual(response.headers["cache-control"], "private, no-store")
        self.assertEqual(response.headers["vary"], "Cookie")
        self.assertEqual(response.headers["x-robots-tag"], "noindex")

    def test_direct_request_without_session_cannot_read_articles(self):
        with patch("app.guide_catalog.load_guide") as load:
            response = self.client.get("/web/auth/guide")
            self.assertEqual(response.status_code, 401)
            self.assertNotIn("articles", response.json())
            load.assert_not_called()
        self.db.query.assert_not_called()
        self.assert_private(response)

    def test_access_cookie_alone_does_not_bypass_logout(self):
        self.client.cookies.set("torve_web_access", create_access_token(str(self.uid)))
        response = self.client.get("/web/auth/guide")
        self.assertEqual(response.status_code, 401)
        self.assertIn("Max-Age=0", response.headers["set-cookie"])

    def test_expired_refresh_rejects_even_valid_access_token(self):
        self.cookies(create_access_token(str(self.uid)))
        self.refresh.expires_at = datetime.now(timezone.utc) - timedelta(seconds=1)
        self.rows(self.refresh)
        response = self.client.get("/web/auth/guide")
        self.assertEqual(response.status_code, 401)
        self.assertNotIn("articles", response.json())
        self.assert_private(response)

    def test_unknown_refresh_is_rejected(self):
        self.cookies(create_access_token(str(self.uid)))
        self.rows(None)
        self.assertEqual(self.client.get("/web/auth/guide").status_code, 401)

    def test_revoked_refresh_is_rejected(self):
        self.cookies(create_access_token(str(self.uid)))
        self.refresh.is_revoked = True
        self.rows(self.refresh)
        with patch.object(web_session, "_handle_refresh_token_reuse") as reused:
            self.assertEqual(self.client.get("/web/auth/guide").status_code, 401)
            reused.assert_called_once_with(self.db, self.refresh)

    def test_valid_session_returns_current_edition_without_account_data(self):
        self.cookies(create_access_token(str(self.uid)))
        self.rows(self.refresh, self.user)
        response = self.client.get("/web/auth/guide")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["revision"], load_guide()["revision"])
        self.assertGreater(len(response.json()["articles"]), 60)
        self.assertNotIn(self.user.email, response.text)
        self.assert_private(response)

    def test_expired_access_can_refresh_and_preserves_both_new_cookies(self):
        self.cookies("expired-access")
        self.rows(self.refresh, self.user)
        with patch.object(web_session, "_rotate_refresh_token", return_value=("new-refresh", None)):
            response = self.client.get("/web/auth/guide")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(len(response.headers.get_list("set-cookie")), 2)
        self.assertIn("new-refresh", response.headers["set-cookie"])
        self.assertIn("HttpOnly", response.headers["set-cookie"])

    def test_inactive_user_cannot_read_guide(self):
        self.cookies()
        self.user.is_active = False
        self.rows(self.refresh, self.user)
        self.assertEqual(self.client.get("/web/auth/guide").status_code, 401)


class GuideContentTests(unittest.TestCase):
    def test_legacy_bookmarks_resolve(self):
        guide = load_guide()
        articles = {a["id"] for a in guide["articles"]} | set(guide["aliases"])
        legacy = "welcome how-torve-works setup-order setup-checklist install-panda what-is-panda panda-management-token usenet-overview usenet-easynews usenet-nzb-indexer what-is-streaming-service setup-rd setup-ad setup-pm setup-tb url-import-vs-service-login add-url-import add-service-login epg-explained connect-trakt connect-simkl setup-omdb setup-mdblist setup-jellyfin setup-plex activated-devices manage-devices pair-phone-tv tv-qr-signin transfer-credentials sync-settings-to-tv what-syncs create-account sign-in-out verify-email reset-password delete-account streaming-troubleshooting channels-troubleshooting device-limit-troubleshooting pairing-troubleshooting playback-troubleshooting signin-troubleshooting what-are-extensions how-to-install-extension catalog-vs-stream managing-extensions no-streams-troubleshooting".split()
        self.assertTrue(set(legacy) <= articles)
        self.assertEqual(guide["categoryAliases"]["multi-device"], "devices")

    def test_links_platforms_and_aliases_have_valid_targets(self):
        guide = load_guide()
        ids = {a["id"] for a in guide["articles"]}
        self.assertEqual(len(ids), len(guide["articles"]))
        platforms = {p["id"] for p in guide["platforms"]}
        categories = {c["id"] for c in guide["categories"]}
        self.assertEqual(platforms, {"ios", "tvos", "android", "firetv", "windows"})
        for article in guide["articles"]:
            self.assertIn(article["category"], categories)
            self.assertTrue(set(article["platforms"]) <= platforms)
            self.assertTrue(set(article["related"]) <= ids)
            self.assertGreater(len(article["sections"]), 1)
        self.assertTrue(set(guide["aliases"].values()) <= ids)
        self.assertTrue(set(guide["categoryAliases"].values()) <= categories)
        self.assertTrue({p["article"] for p in guide["platforms"]} <= ids)

    def test_current_topic_coverage_and_no_public_article_bundles(self):
        guide = load_guide()
        ids = {a["id"] for a in guide["articles"]}
        required = {"audio-language", "track-inspection", "source-preparation", "usenet-nzb-indexer",
            "sports", "downloads", "profiles", "calendar-alerts", "handoff", "localisation",
            "activated-devices", "recordings", "ai-connection", "streaming-services", "tvos-start"}
        self.assertTrue(required <= ids)
        web = Path(__file__).resolve().parents[2] / "web"
        for bundle in (web / "assets/app").glob("help-articles*.js"):
            text = bundle.read_text(encoding="utf-8")
            self.assertIn("Retired public article bundle", text)
            self.assertLess(len(text), 200)
        page = (web / "app/help.html").read_text(encoding="utf-8")
        self.assertNotIn("help-articles", page)
        self.assertIn('content="noindex, nofollow"', page)
        self.assertNotIn("/documentation.html</loc>", (web / "sitemap.xml").read_text(encoding="utf-8"))
        self.assertFalse((web / "assets/app/catalog.json").exists())


if __name__ == "__main__":
    unittest.main()
