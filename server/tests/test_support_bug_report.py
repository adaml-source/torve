import uuid

from app.models import User
from app.security import create_access_token, hash_password


def test_verified_user_can_send_bug_report(client, test_user, monkeypatch):
    sent = {}

    def fake_send_support_bug_report_email(**kwargs):
        sent.update(kwargs)
        return True

    monkeypatch.setattr(
        "app.routers.support.send_support_bug_report_email",
        fake_send_support_bug_report_email,
    )

    token = create_access_token(str(test_user.id))
    r = client.post(
        "/me/support/bug-report",
        json={
            "issue_type": "Android TV",
            "platform": "Fire TV",
            "appVersion": "1.0.71 (20081)",
            "buildNumber": "20081",
            "distributionChannel": "amazon",
            "message": "Focus got stuck near token=SECRET",
            "device": {
                "platform": "Android",
                "model": "Fire TV",
            },
            "diagnostics": {
                "app": {"currentScreen": "settings"},
                "network": {"connected": True},
                "integrations": {"trakt": {"status": "GREEN"}},
                "addons": {"installedCount": 1},
                "performance": {"appNotRespondingSuspected": False},
                "focus": {"currentFocusedElement": "settings:trakt"},
                "playback": {"recentPlaybackErrors": []},
            },
            "logs": [
                "Authorization: Bearer LOGSECRET",
                "stream_url=https://user:pass@example.test/movie",
            ],
            "report": "Playback failed\nAuthorization: Bearer SECRET\napi_key=abc123",
        },
        headers={
            "Authorization": f"Bearer {token}",
            "X-Torve-Installation-Id": "install-test",
        },
    )

    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "sent"
    assert body["report_id"] in sent["subject"]
    assert sent["to"] == "support@torve.app"
    assert sent["reply_to"] == test_user.email
    assert sent["subject"].startswith("[BUG report] Torve Fire TV ")
    assert "## Structured diagnostic payload" in sent["report_text"]
    assert '"distributionChannel": "amazon"' in sent["report_text"]
    assert '"currentScreen": "settings"' in sent["report_text"]
    assert "Bearer SECRET" not in sent["report_text"]
    assert "Bearer LOGSECRET" not in sent["report_text"]
    assert "api_key=abc123" not in sent["report_text"]
    assert "token=SECRET" not in sent["report_text"]
    assert "user:pass@example.test" not in sent["report_text"]
    assert "[REDACTED]" in sent["report_text"]


def test_unverified_user_cannot_send_bug_report(client, db, monkeypatch):
    called = False

    def fake_send_support_bug_report_email(**kwargs):
        nonlocal called
        called = True
        return True

    monkeypatch.setattr(
        "app.routers.support.send_support_bug_report_email",
        fake_send_support_bug_report_email,
    )

    user = User(
        id=uuid.uuid4(),
        email=f"support-unverified-{uuid.uuid4().hex[:8]}@test.com",
        password_hash=hash_password("TestPass123!"),
        is_verified=False,
    )
    db.add(user)
    db.commit()
    try:
        token = create_access_token(str(user.id))
        r = client.post(
            "/me/support/bug-report",
            json={"issue_type": "TV", "report": "Something broke"},
            headers={"Authorization": f"Bearer {token}"},
        )
        assert r.status_code == 403
        assert called is False
    finally:
        db.delete(user)
        db.commit()


def test_bug_report_send_failure_returns_503(client, test_user, monkeypatch):
    monkeypatch.setattr(
        "app.routers.support.send_support_bug_report_email",
        lambda **kwargs: False,
    )

    token = create_access_token(str(test_user.id))
    r = client.post(
        "/me/support/bug-report",
        json={"issue_type": "TV", "report": "Something broke"},
        headers={"Authorization": f"Bearer {token}"},
    )

    assert r.status_code == 503
