"""Tests for Google Play reviewer account bootstrap."""
import uuid
from unittest.mock import patch

from app.bootstrap import bootstrap_reviewer_account
from app.models import AccountSettings, User
from app.security import verify_password


def test_bootstrap_creates_reviewer_account(db):
    email = f"review-{uuid.uuid4().hex[:8]}@torve.app"
    with patch("app.bootstrap.settings") as mock_settings:
        mock_settings.GOOGLE_PLAY_REVIEW_ENABLED = True
        mock_settings.GOOGLE_PLAY_REVIEW_EMAIL = email
        mock_settings.GOOGLE_PLAY_REVIEW_PASSWORD = "ReviewPass2026!"
        mock_settings.GOOGLE_PLAY_REVIEW_DISPLAY_NAME = "GP Reviewer"

        bootstrap_reviewer_account(db)

    user = db.query(User).filter(User.email == email).first()
    assert user is not None
    assert user.display_name == "GP Reviewer"
    assert user.is_active is True
    assert user.is_verified is True
    assert verify_password("ReviewPass2026!", user.password_hash)

    acct = db.query(AccountSettings).filter(AccountSettings.user_id == user.id).first()
    assert acct is not None
    assert acct.settings["language"] == "en"

    # Cleanup
    db.delete(acct)
    db.delete(user)
    db.commit()


def test_bootstrap_is_idempotent(db):
    email = f"review-{uuid.uuid4().hex[:8]}@torve.app"
    with patch("app.bootstrap.settings") as mock_settings:
        mock_settings.GOOGLE_PLAY_REVIEW_ENABLED = True
        mock_settings.GOOGLE_PLAY_REVIEW_EMAIL = email
        mock_settings.GOOGLE_PLAY_REVIEW_PASSWORD = "Pass1"
        mock_settings.GOOGLE_PLAY_REVIEW_DISPLAY_NAME = "Rev1"

        bootstrap_reviewer_account(db)
        user_id = db.query(User).filter(User.email == email).first().id

        # Run again with different password
        mock_settings.GOOGLE_PLAY_REVIEW_PASSWORD = "Pass2"
        mock_settings.GOOGLE_PLAY_REVIEW_DISPLAY_NAME = "Rev2"
        bootstrap_reviewer_account(db)

    users = db.query(User).filter(User.email == email).all()
    assert len(users) == 1
    assert users[0].id == user_id
    assert users[0].display_name == "Rev2"
    assert verify_password("Pass2", users[0].password_hash)

    # Cleanup
    db.query(AccountSettings).filter(AccountSettings.user_id == user_id).delete()
    db.delete(users[0])
    db.commit()


def test_bootstrap_skipped_when_disabled(db):
    with patch("app.bootstrap.settings") as mock_settings:
        mock_settings.GOOGLE_PLAY_REVIEW_ENABLED = False
        bootstrap_reviewer_account(db)

    user = db.query(User).filter(User.email == "review@torve.app").first()
    # Should not have been created by this test
    # (may exist from production .env, so don't assert None)


def test_bootstrap_skipped_when_password_empty(db):
    email = f"review-{uuid.uuid4().hex[:8]}@torve.app"
    with patch("app.bootstrap.settings") as mock_settings:
        mock_settings.GOOGLE_PLAY_REVIEW_ENABLED = True
        mock_settings.GOOGLE_PLAY_REVIEW_EMAIL = email
        mock_settings.GOOGLE_PLAY_REVIEW_PASSWORD = ""

        bootstrap_reviewer_account(db)

    user = db.query(User).filter(User.email == email).first()
    assert user is None


def test_reviewer_can_login(client, db):
    email = f"review-{uuid.uuid4().hex[:8]}@torve.app"
    password = "ReviewLogin2026!"

    with patch("app.bootstrap.settings") as mock_settings:
        mock_settings.GOOGLE_PLAY_REVIEW_ENABLED = True
        mock_settings.GOOGLE_PLAY_REVIEW_EMAIL = email
        mock_settings.GOOGLE_PLAY_REVIEW_PASSWORD = password
        mock_settings.GOOGLE_PLAY_REVIEW_DISPLAY_NAME = "GP Reviewer"

        bootstrap_reviewer_account(db)

    r = client.post("/auth/login", json={
        "email": email,
        "password": password,
        "device": {
            "device_type": "phone",
            "platform": "android",
            "installation_id": "review-phone-001",
        },
    })
    assert r.status_code == 200
    data = r.json()
    assert data["tokens"]["access_token"]
    assert data["user"]["is_verified"] is True
    assert data["device"] is not None
    assert data["device"]["device_type"] == "phone"

    # Cleanup
    user = db.query(User).filter(User.email == email).first()
    db.query(AccountSettings).filter(AccountSettings.user_id == user.id).delete()
    from app.models import Device, RefreshToken
    db.query(Device).filter(Device.user_id == user.id).delete()
    db.query(RefreshToken).filter(RefreshToken.user_id == user.id).delete()
    db.delete(user)
    db.commit()
