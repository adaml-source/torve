"""Tests for SSE event stream and email verification push notification."""
import uuid
from datetime import datetime, timedelta, timezone
from unittest.mock import patch, MagicMock

from app.events import UserEvent, event_bus
from app.models import EmailVerificationToken, User
from app.security import create_access_token, generate_refresh_token, hash_password, hash_refresh_token


# ── Unit tests for UserEvent ───────────────────────────────────────────────


def test_user_event_to_sse_data():
    uid = uuid.uuid4()
    event = UserEvent(event_type="EMAIL_VERIFIED", user_id=uid)
    data = event.to_sse_data()
    assert '"event": "EMAIL_VERIFIED"' in data or '"event":"EMAIL_VERIFIED"' in data
    assert str(uid) in data


def test_user_event_roundtrip_notify_payload():
    uid = uuid.uuid4()
    event = UserEvent(event_type="EMAIL_VERIFIED", user_id=uid)
    payload = event.to_notify_payload()
    restored = UserEvent.from_notify_payload(payload)
    assert restored.event_type == event.event_type
    assert restored.user_id == event.user_id


# ── Integration tests: SSE endpoint ────────────────────────────────────────


def test_sse_endpoint_requires_auth(client):
    r = client.get("/me/events")
    assert r.status_code == 403


def test_sse_route_exists(client, test_user):
    """SSE route is mounted (401 with bad token, not 404)."""
    r = client.get("/me/events", headers={"Authorization": "Bearer bad"})
    assert r.status_code == 401


# ── Integration test: verify-email handler calls event_bus.emit ────────────


def test_verify_email_calls_event_bus_emit(client, db):
    """Verify that the verify-email handler emits an EMAIL_VERIFIED event."""
    user = User(
        id=uuid.uuid4(),
        email=f"sse-emit-{uuid.uuid4().hex[:8]}@test.com",
        password_hash=hash_password("TestPass123!"),
        is_verified=False,
    )
    db.add(user)
    db.flush()

    raw_token = generate_refresh_token()
    vtoken = EmailVerificationToken(
        user_id=user.id,
        token_hash=hash_refresh_token(raw_token),
        expires_at=datetime.now(timezone.utc) + timedelta(hours=24),
    )
    db.add(vtoken)
    db.commit()

    with patch("app.routers.auth.event_bus") as mock_bus:
        r = client.get(f"/auth/verify-email?token={raw_token}", follow_redirects=False)
        assert r.status_code == 307

        mock_bus.emit.assert_called_once()
        emitted_event = mock_bus.emit.call_args[0][0]
        assert emitted_event.event_type == "EMAIL_VERIFIED"
        assert emitted_event.user_id == user.id

    db.refresh(user)
    assert user.is_verified is True

    db.delete(user)
    db.commit()


def test_verify_email_does_not_emit_on_invalid_token(client, db):
    """Invalid verification token should not emit any event."""
    with patch("app.routers.auth.event_bus") as mock_bus:
        r = client.get("/auth/verify-email?token=bogus", follow_redirects=False)
        assert r.status_code == 307
        mock_bus.emit.assert_not_called()


def test_verify_email_does_not_emit_on_expired_token(client, db):
    """Expired verification token should not emit any event."""
    user = User(
        id=uuid.uuid4(),
        email=f"sse-expired-{uuid.uuid4().hex[:8]}@test.com",
        password_hash=hash_password("TestPass123!"),
        is_verified=False,
    )
    db.add(user)
    db.flush()

    raw_token = generate_refresh_token()
    vtoken = EmailVerificationToken(
        user_id=user.id,
        token_hash=hash_refresh_token(raw_token),
        expires_at=datetime.now(timezone.utc) - timedelta(hours=1),
    )
    db.add(vtoken)
    db.commit()

    with patch("app.routers.auth.event_bus") as mock_bus:
        r = client.get(f"/auth/verify-email?token={raw_token}", follow_redirects=False)
        assert r.status_code == 307
        mock_bus.emit.assert_not_called()

    db.delete(user)
    db.commit()


# ── EventBus pg NOTIFY integration ─────────────────────────────────────────


def test_event_bus_emit_sends_pg_notify():
    """emit() issues a pg NOTIFY without error."""
    uid = uuid.uuid4()
    event = UserEvent(event_type="EMAIL_VERIFIED", user_id=uid)
    # Should not raise even with no listeners
    event_bus.emit(event)
