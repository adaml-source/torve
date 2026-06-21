"""Tests for POST /me/support/bug-report."""
from __future__ import annotations

import json
import logging
import uuid

import pytest

from app.mail import send_bug_report_email
from app.models import User
from app.rate_limits import reset_rate_limits_for_tests
from app.security import create_access_token, hash_password


@pytest.fixture(autouse=True)
def _support_test_config(monkeypatch):
    reset_rate_limits_for_tests()
    monkeypatch.setattr("app.config.settings.SUPPORT_EMAIL", "support@torve.app")
    monkeypatch.setattr("app.config.settings.RESEND_API_KEY", "re_test")
    yield
    reset_rate_limits_for_tests()


def _bug_payload() -> dict:
    return {
        "platform": "google_tv",
        "appVersion": "1.2.3",
        "message": "Playback failed after selecting a stream.",
        "authorization": "Bearer report-secret-token",
        "diagnostics": {
            "api_key": "sk_live_report_secret",
            "url": "https://provider.example/watch?token=query-secret&ok=1",
            "nested": {"refreshToken": "refresh-secret"},
            "app": {
                "sessionId": "diagnostic-session-id",
                "sessionStartedAtEpochMs": 1779809907240,
            },
            "integrations": {
                "credentialTransfer": {
                    "cryptoEngineAvailable": True,
                    "signedIn": True,
                    "relayReachable": "UNKNOWN",
                },
            },
        },
        "logs": [
            "GET /watch authorization=Bearer inline-secret",
            "safe line",
        ],
    }


def _android_tv_report_text() -> str:
    return """# Torve bug report
Generated at epoch_ms=1779794190540

## Issue type
Android TV

## What happened
Report generated from Android TV.

## Pasted logs
(none pasted)

## Diagnostics
# Torve diagnostics
Generated at epoch_ms=1779794190509

## App
- versionName: 1.0.71
- versionCode: 20081
- store: amazon
- active player engine: ExoPlayer

## Device
- platform: Android
- model: Amazon AFTGAZL
- OS: Android 9 / API 28
- locale: en-US

## Provider status
- Real-Debrid [GREEN] lastChecked=1779794188155 msg=Real-Debrid is connected. next=(none)
- IPTV playlist [GREEN] lastChecked=1779794189658 msg="8K": 92228 channels loaded. next=(none)

## Transfer (credential transfer / automatic transfer)
- crypto engine available: true
- signed in: true
- relay reachable: unknown
- last attempt: none recorded on this device

## Last failure
(no failure recorded)

## Redaction
This report was automatically redacted before sending.
"""


def test_bug_report_sends_redacted_email_and_returns_report_id(
    client,
    auth_header,
    test_user,
    monkeypatch,
    caplog,
):
    captured = {}

    def fake_send_bug_report_email(**kwargs):
        captured.update(kwargs)
        return True

    monkeypatch.setattr("app.mail.send_bug_report_email", fake_send_bug_report_email)
    caplog.set_level(logging.INFO, logger="app.routers.support")

    response = client.post(
        "/me/support/bug-report",
        headers=auth_header,
        json=_bug_payload(),
    )

    assert response.status_code == 200, response.text
    body = response.json()
    assert body["status"] == "sent"
    assert body["support_email"] == "support@torve.app"
    assert body["report_id"]

    assert captured["to"] == "support@torve.app"
    assert captured["report_id"] == body["report_id"]
    assert captured["user_email"] == test_user.email
    assert captured["platform"] == "google_tv"

    rendered = json.dumps(captured["report_payload"])
    assert "report-secret-token" not in rendered
    assert "sk_live_report_secret" not in rendered
    assert "query-secret" not in rendered
    assert "refresh-secret" not in rendered
    assert "inline-secret" not in rendered
    assert "[REDACTED]" in rendered
    assert "credentialTransfer" in rendered
    assert "cryptoEngineAvailable" in rendered
    assert "diagnostic-session-id" in rendered
    assert "sessionStartedAtEpochMs" in rendered

    assert "BUG_REPORT_SENT" in caplog.text
    assert body["report_id"] in caplog.text
    assert "report-secret-token" not in caplog.text
    assert "sk_live_report_secret" not in caplog.text


def test_bug_report_enriches_null_device_and_diagnostics_from_report_text(
    client,
    auth_header,
    monkeypatch,
):
    captured = {}

    def fake_send_bug_report_email(**kwargs):
        captured.update(kwargs)
        return True

    monkeypatch.setattr("app.mail.send_bug_report_email", fake_send_bug_report_email)
    response = client.post(
        "/me/support/bug-report",
        headers=auth_header,
        json={
            "platform": "Android TV",
            "appVersion": "1.0.71 (20081)",
            "buildNumber": None,
            "distributionChannel": None,
            "device": None,
            "diagnostics": None,
            "message": None,
            "issue_type": "Android TV",
            "report": _android_tv_report_text(),
        },
    )

    assert response.status_code == 200, response.text
    report = captured["report_payload"]["report"]
    assert report["device"] == {
        "platform": "Android",
        "model": "Amazon AFTGAZL",
        "os": "Android 9 / API 28",
        "locale": "en-US",
    }
    assert report["diagnostics"]["generated_at_epoch_ms"] == 1779794190509
    assert report["diagnostics"]["app"] == {
        "version_name": "1.0.71",
        "version_code": "20081",
        "store": "amazon",
        "active_player_engine": "ExoPlayer",
    }
    assert report["diagnostics"]["provider_status"][0] == {
        "name": "Real-Debrid",
        "status": "GREEN",
        "last_checked": 1779794188155,
        "message": "Real-Debrid is connected.",
        "next": None,
    }
    assert report["diagnostics"]["transfer"]["crypto_engine_available"] is True
    assert report["distributionChannel"] == "amazon"
    assert report["buildNumber"] == "20081"
    assert report["message"] == "Report generated from Android TV."


def test_bug_report_mail_subject_and_reply_to(monkeypatch):
    captured = {}

    def fake_send_email(**kwargs):
        captured.update(kwargs)
        return True

    monkeypatch.setattr("app.mail.send_email", fake_send_email)

    assert send_bug_report_email(
        to="support@torve.app",
        report_id="bug-123",
        user_id="user-123",
        user_email="verified@test.com",
        platform="fire_tv",
        report_payload={"message": "safe detail"},
    )

    assert captured["to"] == "support@torve.app"
    assert captured["subject"] == "[BUG report] Torve fire_tv bug-123"
    assert captured["reply_to"] == "verified@test.com"
    assert "safe detail" in captured["text"]
    assert "safe detail" in captured["html"]


def test_bug_report_email_starts_with_human_triage_summary(monkeypatch):
    captured = {}

    def fake_send_email(**kwargs):
        captured.update(kwargs)
        return True

    monkeypatch.setattr("app.mail.send_email", fake_send_email)

    assert send_bug_report_email(
        to="support@torve.app",
        report_id="bug-readable",
        user_id="user-readable",
        user_email="verified@test.com",
        platform="Fire TV",
        report_payload={
            "account": {
                "email": "verified@test.com",
                "is_verified": True,
                "user_id": "user-readable",
            },
            "device": {"installation_id": "install-123"},
            "received_at": "2026-05-26T15:41:12+00:00",
            "report": {
                "platform": "Fire TV",
                "appVersion": "1.0.71 (20081)",
                "buildNumber": "20081",
                "distributionChannel": "amazon",
                "issue_type": "Crash",
                "message": "(not provided)",
                "device": {
                    "manufacturer": "Amazon",
                    "brand": "Amazon",
                    "model": "AFTGAZL",
                    "os": "Android 9 / API 28",
                    "memory": {"availableMb": 741},
                },
                "diagnostics": {
                    "account": {
                        "accessTier": "LIFETIME",
                        "hasPremiumAccess": True,
                        "deviceActivationState": "active",
                    },
                    "app": {
                        "versionName": "1.0.71",
                        "versionCode": "20081",
                        "store": "amazon",
                    },
                    "network": {
                        "backendReachable": True,
                        "transport": "wifi",
                        "vpnActive": False,
                        "proxyActive": False,
                    },
                    "performance": {
                        "appNotRespondingSuspected": False,
                        "slowFrames": 0,
                        "frozenFrameEvents": 0,
                    },
                    "playback": {
                        "lastPlaybackAttempt": {
                            "errorCode": None,
                            "errorMessage": None,
                            "bufferCount": 0,
                            "totalBufferMs": 0,
                        },
                    },
                    "focus": {
                        "focusStuckSuspected": False,
                        "currentScreen": "bug_report_tv",
                        "currentFocusedElement": "unknown",
                    },
                    "crashes": {"lastCrash": None, "recentCrashes": []},
                    "integrations": {
                        "realDebrid": {
                            "status": "GREEN",
                            "message": "Real-Debrid is connected.",
                            "nextAction": None,
                        },
                        "allDebrid": {
                            "status": "UNCONFIGURED",
                            "message": "Not connected.",
                            "nextAction": "Set up AllDebrid via Panda",
                        },
                    },
                    "recentActions": [
                        {
                            "timestampEpochMs": 1779809941522,
                            "screen": "bug_report_tv",
                            "action": "open_screen",
                            "target": "bug_report_tv",
                            "result": "success",
                        }
                    ],
                },
                "logs": ["1779809941529 INFO/screen open_screen screen=bug_report_tv"],
            },
            "report_id": "bug-readable",
        },
    )

    assert "At a Glance" in captured["html"]
    assert "Triage Signals" in captured["html"]
    assert "Integration Status" in captured["html"]
    assert "Recent Logs" in captured["html"]
    assert "Full Redacted JSON" in captured["html"]
    assert "AFTGAZL" in captured["html"]
    assert "Android 9 / API 28" in captured["html"]
    assert "Issue type is Crash, but no crash stack was captured" in captured["text"]
    assert "Network: backend reachable=yes" in captured["text"]
    assert "realDebrid: GREEN" in captured["text"]
    assert "allDebrid: UNCONFIGURED" in captured["text"]
    assert '"report_id": "bug-readable"' in captured["text"]


def test_bug_report_rejects_unverified_user(client, db, monkeypatch):
    user = User(
        id=uuid.uuid4(),
        email=f"unverified-{uuid.uuid4().hex[:8]}@test.com",
        password_hash=hash_password("TestPass123!"),
        is_verified=False,
    )
    db.add(user)
    db.commit()

    def fail_send_bug_report_email(**_kwargs):
        raise AssertionError("unverified users must not send support email")

    monkeypatch.setattr("app.mail.send_bug_report_email", fail_send_bug_report_email)

    response = client.post(
        "/me/support/bug-report",
        headers={"Authorization": f"Bearer {create_access_token(str(user.id))}"},
        json=_bug_payload(),
    )

    assert response.status_code == 403

    db.delete(user)
    db.commit()


def test_bug_report_missing_and_invalid_auth_get_401(client):
    missing = client.post("/me/support/bug-report", json=_bug_payload())
    invalid = client.post(
        "/me/support/bug-report",
        headers={"Authorization": "Bearer not-a-valid-token"},
        json=_bug_payload(),
    )

    assert missing.status_code == 401
    assert invalid.status_code == 401


def test_bug_report_missing_resend_key_returns_503_without_logging_body(
    client,
    auth_header,
    monkeypatch,
    caplog,
):
    monkeypatch.setattr("app.config.settings.RESEND_API_KEY", "")
    caplog.set_level(logging.INFO)
    payload = _bug_payload()

    response = client.post(
        "/me/support/bug-report",
        headers=auth_header,
        json=payload,
    )

    assert response.status_code == 503
    assert "BUG_REPORT_SEND_FAILED" in caplog.text
    assert "report-secret-token" not in caplog.text
    assert "sk_live_report_secret" not in caplog.text
