"""
Tests for app.google_play_readiness.

Covers:
  - not_configured when nothing is set (intentional opt-out)
  - misconfigured when partial config or unreadable SA file
  - misconfigured when SA JSON is malformed or wrong shape
  - ready when everything is set correctly
  - /me/health/integrations surfaces the global readiness entry
  - verify_google_play handler returns error_code="config_missing"
    when readiness gate fails (and does not leak the SA path)
"""
from __future__ import annotations

import json
import os
import uuid
from pathlib import Path
from unittest.mock import patch

import pytest

from app.config import settings
from app.google_play_readiness import (
    R_MALFORMED,
    R_PACKAGE_MISSING,
    R_PATH_EMPTY,
    R_PRODUCT_IDS_MISSING,
    R_UNREADABLE,
    R_WRONG_SHAPE,
    assess_readiness,
)
from app.security import create_access_token


def _auth(user):
    return {"Authorization": f"Bearer {create_access_token(str(user.id))}"}


@pytest.fixture(autouse=True)
def _reset_health_cache():
    from app.routers.health_integrations import _CACHE
    _CACHE.clear()
    yield
    _CACHE.clear()


@pytest.fixture
def _gp_env():
    """Snapshot + restore the Google Play-related settings so tests
    can mutate them without leaking state across cases.
    """
    snap = {
        "path": settings.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON,
        "package": settings.GOOGLE_PLAY_PACKAGE_NAME,
        "lifetime": settings.GOOGLE_PLAY_LIFETIME_PRODUCT_ID,
        "subscription": settings.GOOGLE_PLAY_SUBSCRIPTION_ID,
        "legacy": settings.GOOGLE_PLAY_PRODUCT_ID,
    }
    # Wipe to baseline.
    settings.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON = ""
    settings.GOOGLE_PLAY_PACKAGE_NAME = ""
    settings.GOOGLE_PLAY_LIFETIME_PRODUCT_ID = ""
    settings.GOOGLE_PLAY_SUBSCRIPTION_ID = ""
    settings.GOOGLE_PLAY_PRODUCT_ID = ""
    yield
    # Restore.
    settings.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON = snap["path"]
    settings.GOOGLE_PLAY_PACKAGE_NAME = snap["package"]
    settings.GOOGLE_PLAY_LIFETIME_PRODUCT_ID = snap["lifetime"]
    settings.GOOGLE_PLAY_SUBSCRIPTION_ID = snap["subscription"]
    settings.GOOGLE_PLAY_PRODUCT_ID = snap["legacy"]


def _write_sa_json(tmp_path: Path, **overrides) -> Path:
    """Produce a fake but well-shaped service account JSON file."""
    sa = {
        "type": "service_account",
        "project_id": "fake",
        "private_key_id": "fake",
        "private_key": "-----BEGIN PRIVATE KEY-----\nFAKE\n-----END PRIVATE KEY-----\n",
        "client_email": "fake@fake.iam.gserviceaccount.com",
        "client_id": "0",
        "token_uri": "https://oauth2.googleapis.com/token",
    }
    sa.update(overrides)
    path = tmp_path / "sa.json"
    path.write_text(json.dumps(sa))
    return path


# ── readiness unit tests ───────────────────────────────────────────────


def test_nothing_configured_is_not_configured(_gp_env):
    r = assess_readiness()
    assert r.ready is False
    assert r.status == "not_configured"


def test_path_only_no_file_is_misconfigured(_gp_env, tmp_path):
    settings.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON = str(tmp_path / "does-not-exist.json")
    r = assess_readiness()
    assert r.ready is False
    assert r.status == "misconfigured"
    assert r.reason == R_UNREADABLE


def test_path_points_at_dir_is_misconfigured(_gp_env, tmp_path):
    settings.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON = str(tmp_path)
    r = assess_readiness()
    assert r.status == "misconfigured"
    assert r.reason == R_UNREADABLE


def test_malformed_json_is_misconfigured(_gp_env, tmp_path):
    p = tmp_path / "sa.json"
    p.write_text("{not valid json")
    settings.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON = str(p)
    r = assess_readiness()
    assert r.status == "misconfigured"
    assert r.reason == R_MALFORMED


def test_wrong_shape_is_misconfigured(_gp_env, tmp_path):
    p = tmp_path / "sa.json"
    p.write_text(json.dumps({"type": "other", "foo": "bar"}))
    settings.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON = str(p)
    r = assess_readiness()
    assert r.status == "misconfigured"
    assert r.reason == R_WRONG_SHAPE


def test_missing_private_key_is_wrong_shape(_gp_env, tmp_path):
    p = _write_sa_json(tmp_path, private_key="")
    settings.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON = str(p)
    r = assess_readiness()
    assert r.reason == R_WRONG_SHAPE


def test_sa_ok_but_package_missing(_gp_env, tmp_path):
    p = _write_sa_json(tmp_path)
    settings.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON = str(p)
    r = assess_readiness()
    assert r.status == "misconfigured"
    assert r.reason == R_PACKAGE_MISSING


def test_sa_and_package_ok_but_no_product_ids(_gp_env, tmp_path):
    p = _write_sa_json(tmp_path)
    settings.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON = str(p)
    settings.GOOGLE_PLAY_PACKAGE_NAME = "com.torve.app"
    r = assess_readiness()
    assert r.status == "misconfigured"
    assert r.reason == R_PRODUCT_IDS_MISSING


def test_fully_configured_is_ready(_gp_env, tmp_path):
    p = _write_sa_json(tmp_path)
    settings.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON = str(p)
    settings.GOOGLE_PLAY_PACKAGE_NAME = "com.torve.app"
    settings.GOOGLE_PLAY_LIFETIME_PRODUCT_ID = "com.torve.pro.lifetime"
    settings.GOOGLE_PLAY_SUBSCRIPTION_ID = "com.torve.pro.subscription"
    r = assess_readiness()
    assert r.ready is True
    assert r.status == "ready"
    assert r.reason is None


# ── secret-leak invariants ────────────────────────────────────────────


def test_readiness_never_returns_path_or_json_contents(_gp_env, tmp_path):
    p = _write_sa_json(tmp_path)
    settings.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON = str(p)
    settings.GOOGLE_PLAY_PACKAGE_NAME = "com.torve.app"
    r = assess_readiness()
    joined = f"{r.status} {r.reason}"
    assert str(p) not in joined
    assert "fake@fake.iam.gserviceaccount.com" not in joined
    assert "PRIVATE KEY" not in joined


# ── /me/health/integrations integration ───────────────────────────────


def test_health_endpoint_surfaces_not_configured(_gp_env, client, test_user):
    r = client.get("/me/health/integrations", headers=_auth(test_user))
    assert r.status_code == 200
    body = r.json()
    gp = next(i for i in body["integrations"] if i["kind"] == "google_play")
    assert gp["status"] == "not_configured"
    assert gp["detail"] == R_PATH_EMPTY


def test_health_endpoint_surfaces_misconfigured(_gp_env, client, test_user, tmp_path):
    # Path set but file missing → misconfigured / unreadable.
    settings.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON = str(tmp_path / "missing.json")
    r = client.get("/me/health/integrations", headers=_auth(test_user))
    body = r.json()
    gp = next(i for i in body["integrations"] if i["kind"] == "google_play")
    assert gp["status"] == "degraded"
    assert gp["detail"] == R_UNREADABLE


def test_health_endpoint_never_exposes_sa_path(_gp_env, client, test_user, tmp_path):
    p = _write_sa_json(tmp_path)
    settings.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON = str(p)
    settings.GOOGLE_PLAY_PACKAGE_NAME = "com.torve.app"
    settings.GOOGLE_PLAY_LIFETIME_PRODUCT_ID = "com.torve.pro.lifetime"
    r = client.get("/me/health/integrations", headers=_auth(test_user))
    # Full response body — no SA file path, no client_email, no private key.
    text = r.text
    assert str(p) not in text
    assert "fake@fake.iam.gserviceaccount.com" not in text
    assert "PRIVATE KEY" not in text


# ── purchase_verify handler gate ──────────────────────────────────────


def test_verify_returns_deprecated_free_response_when_not_ready(_gp_env, client, test_user):
    # Purchase verification endpoints no longer call Google Play readiness;
    # they return a free-software compatibility response.
    settings.GOOGLE_PLAY_LIFETIME_PRODUCT_ID = "com.torve.pro.lifetime"
    r = client.post(
        "/me/purchases/google-play/verify",
        headers=_auth(test_user),
        json={
            "purchase_token": "fake",
            "product_id": "com.torve.pro.lifetime",
        },
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["verified"] is True
    assert body["entitlement_granted"] is False
    assert body["error_code"] == "deprecated_free_software"
    assert "access is free" in body["message"].lower()
