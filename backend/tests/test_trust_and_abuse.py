"""Trust signal, integrity-risk, and honeytoken tests."""
from __future__ import annotations

import logging
import time

import pytest

from app.rate_limits import reset_rate_limits_for_tests


@pytest.fixture(autouse=True)
def _reset_rate_limits():
    reset_rate_limits_for_tests()
    yield
    reset_rate_limits_for_tests()


def test_desktop_trust_signal_does_not_require_play_integrity(client, auth_header):
    r = client.post(
        "/me/trust-signals",
        headers=auth_header,
        json={
            "platform": "desktop",
            "appVersion": "1.2.3",
            "buildNumber": "123",
            "distributionChannel": "desktop",
            "isDebuggable": False,
            "generatedAtEpochMillis": int(time.time() * 1000),
        },
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["accepted"] is True
    assert body["channel"] == "desktop"
    assert "integrity_token_missing" not in body["risk_flags"]
    assert body["integrity_status"] is None


def test_google_play_missing_integrity_is_risk_signal_not_premium_grant(
    client, free_user, free_auth_header,
):
    r = client.post(
        "/me/trust-signals",
        headers=free_auth_header,
        json={
            "platform": "android",
            "distributionChannel": "google_play",
            "packageName": "com.torve.app",
            "installerPackage": "com.android.vending",
            "isDebuggable": False,
            "generatedAtEpochMillis": int(time.time() * 1000),
        },
    )
    assert r.status_code == 200, r.text
    assert "integrity_token_missing" in r.json()["risk_flags"]

    state = client.get("/me/access-state", headers=free_auth_header)
    assert state.status_code == 200
    assert state.json()["has_premium_access"] is True


def test_honeytoken_premium_activate_logs_and_does_not_grant(client, caplog):
    caplog.set_level(logging.WARNING)
    r = client.post("/v1/premium/activate", json={"hasPremium": True})
    assert r.status_code == 404
    assert r.json()["detail"] == "not_found"
    assert any("HONEYTOKEN_SUBMITTED" in rec.getMessage() for rec in caplog.records)
