"""Admin per-device activity rollup tests."""
from __future__ import annotations

import json
import uuid
from datetime import datetime, timedelta, timezone

from app.models import (
    AccountSettings,
    DevicePairing,
    PairingCode,
    PairingSigninCode,
    StreamHandoffReference,
    TransferSession,
    UserEntitlement,
    UserMediaFavorite,
    WatchStateReport,
)


ADMIN_HEADERS = {"X-Admin-Secret": "test-admin-secret"}


def test_admin_device_activity_returns_policy_scoped_redacted_events(
    client,
    db,
    test_user,
    tv_device,
    phone_device,
    monkeypatch,
):
    monkeypatch.setattr("app.config.settings.PADDLE_ADMIN_SECRET", "test-admin-secret")
    now = datetime.now(timezone.utc)

    tv_device.stable_device_id = f"stable-{uuid.uuid4().hex[:8]}"
    db.add(tv_device)
    db.add(
        DevicePairing(
            user_id=test_user.id,
            controller_device_id=phone_device.id,
            target_device_id=tv_device.id,
            status="active",
            created_at=now - timedelta(hours=6),
            updated_at=now - timedelta(hours=6),
        )
    )
    db.add(
        PairingCode(
            user_id=test_user.id,
            target_device_id=tv_device.id,
            code="SECRET11",
            expires_at=now + timedelta(minutes=5),
            claimed_at=now - timedelta(hours=5),
            claimed_by_device_id=phone_device.id,
            created_at=now - timedelta(hours=5, minutes=5),
        )
    )
    db.add(
        PairingSigninCode(
            code="SIGNSECRET",
            device_installation_id=tv_device.installation_id,
            device_name=tv_device.display_name,
            device_type=tv_device.device_type,
            platform=tv_device.platform,
            expires_at=now + timedelta(minutes=5),
            claimed_by_user_id=test_user.id,
            claimed_at=now - timedelta(hours=4),
            consumed_at=now - timedelta(hours=3, minutes=55),
            access_token_encrypted="access-token-secret",
            refresh_token_encrypted="refresh-token-secret",
            access_token_expires_in_s=3600,
            claimed_device_id=tv_device.id,
            created_at=now - timedelta(hours=4, minutes=10),
        )
    )
    db.add(
        UserEntitlement(
            user_id=test_user.id,
            entitlement_type="lifetime_access",
            source="google_play",
            source_ref="gp-secret-source-ref-123456789",
            product_id="premium_lifetime",
            status="active",
            granted_at=now - timedelta(days=1),
            originating_device_id=tv_device.id,
        )
    )
    db.add(
        WatchStateReport(
            user_id=test_user.id,
            device_id=tv_device.id,
            content_id="tmdb:movie:activity-test",
            provider="real_debrid",
            position_ms=123456,
            reported_at=now - timedelta(minutes=45),
        )
    )
    db.add(
        UserMediaFavorite(
            user_id=test_user.id,
            media_key="movie:activity-test",
            media_type="movie",
            tmdb_id=123,
            imdb_id="tt0000123",
            title="Activity Test",
            year=2026,
            source_device_id=tv_device.id,
            added_at=now - timedelta(hours=2),
            updated_at=now - timedelta(hours=2),
        )
    )
    db.add(
        AccountSettings(
            user_id=test_user.id,
            settings={"theme": "midnight", "api_key": "settings-secret"},
            version=3,
            updated_by_device_id=tv_device.id,
            updated_at=now - timedelta(hours=1),
        )
    )
    db.add(
        StreamHandoffReference(
            stream_id="stream-secret-id",
            upstream_url_encrypted="https://provider.example/video?token=secret",
            user_id=test_user.id,
            device_id=tv_device.id,
            content_id="tmdb:stream-activity",
            provider_type="real_debrid",
            source_ref="source-ref-secret-abcdef",
            created_at=now - timedelta(minutes=30),
            expires_at=now + timedelta(minutes=5),
        )
    )
    db.add(
        TransferSession(
            user_id=test_user.id,
            receiver_device_id=tv_device.installation_id,
            receiver_device_name=tv_device.display_name,
            receiver_public_key="public-key-secret",
            envelope_json={"ciphertext": "transfer-secret"},
            expires_at=now + timedelta(minutes=10),
            created_at=now - timedelta(minutes=20),
            delivered_at=now - timedelta(minutes=19),
            consumed_at=now - timedelta(minutes=18),
        )
    )
    db.commit()

    response = client.get(
        f"/admin/users/{test_user.id}/devices/{tv_device.id}/activity",
        headers=ADMIN_HEADERS,
    )

    assert response.status_code == 200, response.text
    body = response.json()
    assert body["device"]["id"] == str(tv_device.id)
    assert {
        "device",
        "pairing",
        "entitlements",
        "watch_state",
        "favorites",
        "settings",
        "stream_handoff",
        "transfers",
    }.issubset(body["categories"].keys())

    assert body["categories"]["watch_state"]["events"][0]["metadata"] == {
        "content_id": "tmdb:movie:activity-test",
        "provider": "real_debrid",
        "position_ms": 123456,
    }
    assert body["categories"]["settings"]["events"][0]["metadata"]["settings_keys"] == [
        "api_key",
        "theme",
    ]

    rendered = json.dumps(body)
    assert "SECRET11" not in rendered
    assert "SIGNSECRET" not in rendered
    assert "access-token-secret" not in rendered
    assert "refresh-token-secret" not in rendered
    assert "transfer-secret" not in rendered
    assert "public-key-secret" not in rendered
    assert "settings-secret" not in rendered
    assert "https://provider.example/video" not in rendered
    assert "gp-secret-source-ref-123456789" not in rendered
    assert "gp-secret-so..." in rendered


def test_admin_device_activity_supports_category_filter(
    client,
    test_user,
    tv_device,
    monkeypatch,
):
    monkeypatch.setattr("app.config.settings.PADDLE_ADMIN_SECRET", "test-admin-secret")

    response = client.get(
        f"/admin/users/{test_user.id}/devices/{tv_device.id}/activity?categories=device,settings",
        headers=ADMIN_HEADERS,
    )

    assert response.status_code == 200, response.text
    body = response.json()
    assert body["selected_categories"] == ["device", "settings"]
    assert set(body["categories"].keys()) == {"device", "settings"}


def test_admin_device_activity_rejects_unknown_category(
    client,
    test_user,
    tv_device,
    monkeypatch,
):
    monkeypatch.setattr("app.config.settings.PADDLE_ADMIN_SECRET", "test-admin-secret")

    response = client.get(
        f"/admin/users/{test_user.id}/devices/{tv_device.id}/activity?categories=device,secrets",
        headers=ADMIN_HEADERS,
    )

    assert response.status_code == 400
    assert "secrets" in response.json()["detail"]


def test_admin_device_activity_requires_admin_secret(client, test_user, tv_device):
    response = client.get(
        f"/admin/users/{test_user.id}/devices/{tv_device.id}/activity",
        headers={"X-Admin-Secret": "wrong"},
    )

    assert response.status_code in (403, 503)
