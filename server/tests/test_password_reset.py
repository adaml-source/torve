from datetime import datetime, timedelta, timezone
from urllib.parse import parse_qs, urlparse

from app.models import RefreshToken
from app.security import generate_refresh_token, hash_refresh_token, verify_password


def test_web_password_reset_round_trip(client, db, test_user, monkeypatch):
    sent = {}

    refresh = RefreshToken(
        user_id=test_user.id,
        token_hash=hash_refresh_token(generate_refresh_token()),
        expires_at=datetime.now(timezone.utc) + timedelta(days=30),
    )
    db.add(refresh)
    db.commit()

    def capture_reset_email(*, to: str, reset_url: str) -> bool:
        sent.update(to=to, reset_url=reset_url)
        return True

    monkeypatch.setattr("app.routers.auth.send_password_reset_email", capture_reset_email)

    response = client.post(
        "/web/auth/password-reset/request",
        json={"email": test_user.email},
    )
    assert response.status_code == 200
    assert response.json()["message"] == "If that email is registered, a reset link has been sent."
    assert sent["to"] == test_user.email

    parsed = urlparse(sent["reset_url"])
    assert parsed.path == "/reset-password"
    token = parse_qs(parsed.query)["token"][0]

    assert db.query(RefreshToken).filter(
        RefreshToken.user_id == test_user.id,
        RefreshToken.is_revoked.is_(False),
    ).count() == 1

    response = client.post(
        "/web/auth/password-reset/confirm",
        json={"token": token, "new_password": "NewTestPass456!"},
    )
    assert response.status_code == 200
    db.refresh(test_user)
    assert verify_password("NewTestPass456!", test_user.password_hash)
    assert not verify_password("TestPass123!", test_user.password_hash)

    assert db.query(RefreshToken).filter(
        RefreshToken.user_id == test_user.id,
        RefreshToken.is_revoked.is_(False),
    ).count() == 0


def test_web_password_reset_request_does_not_reveal_unknown_email(client, monkeypatch):
    send_attempted = False

    def capture_reset_email(*, to: str, reset_url: str) -> bool:
        nonlocal send_attempted
        send_attempted = True
        return True

    monkeypatch.setattr("app.routers.auth.send_password_reset_email", capture_reset_email)

    response = client.post(
        "/web/auth/password-reset/request",
        json={"email": "missing-password-reset-user@test.com"},
    )
    assert response.status_code == 200
    assert response.json()["message"] == "If that email is registered, a reset link has been sent."
    assert send_attempted is False


def test_password_reset_website_assets_are_wired():
    from pathlib import Path

    root = Path(__file__).resolve().parents[2]
    signin = (root / "web" / "signin.html").read_text(encoding="utf-8")
    reset_page = (root / "web" / "reset-password.html").read_text(encoding="utf-8")
    reset_script = (root / "web" / "assets" / "password-reset.js").read_text(encoding="utf-8")

    assert 'href="/reset-password"' in signin
    assert 'data-password-reset-request' in reset_page
    assert 'data-password-reset-confirm' in reset_page
    assert '<meta name="referrer" content="no-referrer"' in reset_page
    assert 'src="assets/password-reset.js"' in reset_page
    assert '"/web/auth/password-reset/request"' in reset_script
    assert '"/web/auth/password-reset/confirm"' in reset_script
    assert 'window.history.replaceState' in reset_script
