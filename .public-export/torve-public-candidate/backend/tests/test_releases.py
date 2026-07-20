"""Tests for the desktop release appcast endpoint.

Covers:
  - GET /releases/appcast.xml with no published release → 404
  - GET /releases/appcast.xml with one row → valid Sparkle XML
  - POST /admin/releases without auth → 403
  - POST /admin/releases with auth → row persists, GET reflects it
  - POST /admin/releases duplicate version → 409
  - GET /admin/releases lists rows
  - PATCH /admin/releases/{version} flips is_published
  - Version ordering: GET serves highest semver, not latest by insert order
"""
import logging
import re
from datetime import datetime, timezone

import httpx
import pytest

from app import discord_release_notifier
from app.config import settings
from app.models import DesktopRelease

ADMIN_HEADERS = {"X-Admin-Secret": "test-admin-secret"}

GOOD_SHA = "a" * 64
GOOD_URL = "https://cdn.example.com/Torve-1.0.0.msi"


@pytest.fixture(autouse=True)
def _patch_admin_secret(monkeypatch):
    monkeypatch.setattr(settings, "PADDLE_ADMIN_SECRET", "test-admin-secret")
    monkeypatch.setattr(settings, "DISCORD_RELEASE_WEBHOOK_URL", "")


@pytest.fixture(autouse=True)
def _clean_releases(db):
    yield
    db.query(DesktopRelease).delete()
    db.commit()


def _post_release(client, version="1.0.0", sha=GOOD_SHA, url=GOOD_URL,
                  length=123456, notes="<p>Notes</p>", published=True):
    return client.post("/admin/releases", json={
        "version": version,
        "msi_url": url,
        "sha256": sha,
        "length": length,
        "release_notes_html": notes,
        "is_published": published,
    }, headers=ADMIN_HEADERS)


# ── GET with no rows ──────────────────────────────────────────────────────────

def test_appcast_no_releases_is_404(client):
    r = client.get("/releases/appcast.xml")
    assert r.status_code == 404


# ── POST auth ─────────────────────────────────────────────────────────────────

def test_post_release_no_auth_is_403(client):
    r = client.post("/admin/releases", json={
        "version": "1.0.0", "msi_url": GOOD_URL,
        "sha256": GOOD_SHA, "length": 100,
    })
    assert r.status_code == 403


def test_post_release_wrong_secret_is_403(client):
    r = client.post("/admin/releases", json={
        "version": "1.0.0", "msi_url": GOOD_URL,
        "sha256": GOOD_SHA, "length": 100,
    }, headers={"X-Admin-Secret": "wrong"})
    assert r.status_code == 403


# ── POST validation ───────────────────────────────────────────────────────────

def test_post_release_http_url_rejected(client):
    r = _post_release(client, url="http://cdn.example.com/Torve-1.0.0.msi")
    assert r.status_code == 422


def test_post_release_bad_sha_rejected(client):
    r = _post_release(client, sha="notahexstring")
    assert r.status_code == 422


def test_post_release_zero_length_rejected(client):
    r = _post_release(client, length=0)
    assert r.status_code == 422


# ── POST happy path + GET appcast ─────────────────────────────────────────────

def test_post_release_persists_and_appcast_reflects_it(client):
    r = _post_release(client, version="1.2.3", notes="<p>Hello</p>")
    assert r.status_code == 201
    data = r.json()
    assert data["version"] == "1.2.3"
    assert data["sha256_hex"] == GOOD_SHA
    assert "appcast_url" in data

    xml_r = client.get("/releases/appcast.xml")
    assert xml_r.status_code == 200
    assert xml_r.headers["content-type"].startswith("application/xml")
    assert "max-age=300" in xml_r.headers["cache-control"]

    body = xml_r.text
    assert "1.2.3" in body
    assert GOOD_SHA in body
    assert GOOD_URL in body
    assert "123456" in body
    assert "<![CDATA[<p>Hello</p>]]>" in body
    assert 'sparkle:version="1.2.3"' in body
    assert 'sparkle:installerSha256="' + GOOD_SHA + '"' in body


def test_discord_failure_does_not_fail_release_upload_flow(client, monkeypatch, caplog):
    attempts = []

    def fail_post(*args, **kwargs):
        attempts.append(kwargs.get("json"))
        raise httpx.ConnectError("discord webhook failed token=super-secret")

    monkeypatch.setattr(
        settings,
        "DISCORD_RELEASE_WEBHOOK_URL",
        "https://hooks.example.test/release/super-secret",
    )
    monkeypatch.setattr(discord_release_notifier.httpx, "post", fail_post)
    caplog.set_level(logging.WARNING, logger="app.discord_release_notifier")

    r = _post_release(client, version="1.2.4")

    assert r.status_code == 201
    assert r.json()["version"] == "1.2.4"
    assert len(attempts) == 2
    assert "ConnectError" in caplog.text
    assert "super-secret" not in caplog.text
    assert "token=" not in caplog.text


def test_appcast_xml_structure(client):
    _post_release(client, version="2.0.0")
    body = client.get("/releases/appcast.xml").text
    assert body.startswith('<?xml version="1.0"')
    assert 'xmlns:sparkle=' in body
    assert "<channel>" in body
    assert "<item>" in body
    assert "</item>" in body
    assert "</rss>" in body
    # enclosure must have the mandatory attributes the client parser looks for
    enclosure_match = re.search(r"<enclosure\b[\s\S]*?/>", body)
    assert enclosure_match, "No <enclosure> element found"
    enc = enclosure_match.group(0)
    assert 'url=' in enc
    assert 'sparkle:version=' in enc
    assert 'sparkle:installerSha256=' in enc
    assert 'length=' in enc


# ── duplicate version ─────────────────────────────────────────────────────────

def test_post_duplicate_version_is_409(client):
    _post_release(client, version="1.0.0")
    r = _post_release(client, version="1.0.0")
    assert r.status_code == 409


# ── unpublished rows not served ───────────────────────────────────────────────

def test_unpublished_release_not_served(client):
    _post_release(client, version="1.0.0", published=False)
    r = client.get("/releases/appcast.xml")
    assert r.status_code == 404


# ── highest semver wins, not insertion order ──────────────────────────────────

def test_appcast_serves_highest_semver(client):
    _post_release(client, version="1.0.0")
    _post_release(client, version="2.0.0", url="https://cdn.example.com/Torve-2.0.0.msi")
    _post_release(client, version="1.9.9")
    body = client.get("/releases/appcast.xml").text
    assert 'sparkle:version="2.0.0"' in body
    assert "Torve-2.0.0.msi" in body


def test_appcast_semver_beats_lexicographic(client):
    # "1.10.0" > "1.9.0" numerically but not lexicographically
    _post_release(client, version="1.9.0")
    _post_release(client, version="1.10.0", url="https://cdn.example.com/Torve-1.10.0.msi")
    body = client.get("/releases/appcast.xml").text
    assert 'sparkle:version="1.10.0"' in body


# ── non-UTC published_at normalisation ───────────────────────────────────────

def test_appcast_non_utc_published_at_normalised(client):
    # Regression: format_datetime(..., usegmt=True) raises ValueError for
    # non-UTC tz-aware datetimes.  An admin POST with a +02:00 timestamp
    # must not make GET /releases/appcast.xml 500 forever afterwards.
    r = client.post("/admin/releases", json={
        "version": "3.0.0",
        "msi_url": GOOD_URL,
        "sha256": GOOD_SHA,
        "length": 123456,
        "published_at": "2026-05-01T10:00:00+02:00",
    }, headers=ADMIN_HEADERS)
    assert r.status_code == 201

    xml_r = client.get("/releases/appcast.xml")
    assert xml_r.status_code == 200

    pub_date_match = re.search(r"<pubDate>(.*?)</pubDate>", xml_r.text)
    assert pub_date_match, "<pubDate> not found"
    pub_date = pub_date_match.group(1)
    assert "+0200" not in pub_date, "non-UTC offset leaked into pubDate"
    assert "+0000" in pub_date or "GMT" in pub_date, f"expected UTC pubDate, got: {pub_date}"


# ── GET /admin/releases ───────────────────────────────────────────────────────

def test_admin_list_releases(client):
    _post_release(client, version="1.0.0")
    _post_release(client, version="1.1.0")
    r = client.get("/admin/releases", headers=ADMIN_HEADERS)
    assert r.status_code == 200
    versions = [row["version"] for row in r.json()]
    assert "1.0.0" in versions
    assert "1.1.0" in versions


def test_admin_list_no_auth(client):
    r = client.get("/admin/releases")
    assert r.status_code == 403


# ── PATCH ─────────────────────────────────────────────────────────────────────

def test_patch_unpublish(client):
    _post_release(client, version="1.0.0")
    r = client.patch("/admin/releases/1.0.0",
                     json={"is_published": False},
                     headers=ADMIN_HEADERS)
    assert r.status_code == 200
    assert r.json()["is_published"] is False
    assert client.get("/releases/appcast.xml").status_code == 404


def test_patch_update_notes(client):
    _post_release(client, version="1.0.0", notes="<p>Old</p>")
    r = client.patch("/admin/releases/1.0.0",
                     json={"release_notes_html": "<p>New</p>"},
                     headers=ADMIN_HEADERS)
    assert r.status_code == 200
    assert r.json()["release_notes_html"] == "<p>New</p>"
    body = client.get("/releases/appcast.xml").text
    assert "<![CDATA[<p>New</p>]]>" in body


def test_patch_nonexistent_is_404(client):
    r = client.patch("/admin/releases/9.9.9",
                     json={"is_published": False},
                     headers=ADMIN_HEADERS)
    assert r.status_code == 404


def test_patch_version_with_v_prefix(client):
    _post_release(client, version="1.0.0")
    r = client.patch("/admin/releases/v1.0.0",
                     json={"is_published": False},
                     headers=ADMIN_HEADERS)
    assert r.status_code == 200
