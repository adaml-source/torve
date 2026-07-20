"""Security-focused tests for the Torve backend."""
import json
import uuid as _uuid
from datetime import datetime, timezone

from app.models import Device, StripeLifetimePurchase, User, UserEntitlement, UserIntegration
from app.security import create_access_token, hash_password

def _unique_email(prefix="test"):
    return f"{prefix}_{_uuid.uuid4().hex[:8]}@test.com"


def test_integration_save_does_not_echo_secret(client, test_user, db):
    test_user.has_lifetime_access = True
    db.commit()
    """Saved credentials must never appear in the response."""
    token = create_access_token(str(test_user.id))
    r = client.put(
        "/me/integrations/OMDB_TEST",
        json={
            "integration_type": "OMDB_TEST",
            "credentials": {"api_key": "super_secret_key_12345"},
            "storage_mode": "account",
            "display_identifier": "Test",
        },
        headers={"Authorization": f"Bearer {token}"},
    )
    assert r.status_code == 200
    body = r.json()
    body_str = json.dumps(body)
    assert "super_secret_key_12345" not in body_str
    assert "credentials" not in body
    assert "encrypted_credentials" not in body
    assert body["has_credentials"] is True


def test_integration_list_does_not_echo_secret(client, test_user, db):
    """List endpoint must not include secrets."""
    token = create_access_token(str(test_user.id))
    # Save first
    client.put(
        "/me/integrations/OMDB_LIST_TEST",
        json={
            "integration_type": "OMDB_LIST_TEST",
            "credentials": {"api_key": "another_secret_99"},
            "storage_mode": "account",
        },
        headers={"Authorization": f"Bearer {token}"},
    )
    # List
    r = client.get("/me/integrations", headers={"Authorization": f"Bearer {token}"})
    assert r.status_code == 200
    body_str = json.dumps(r.json())
    assert "another_secret_99" not in body_str
    assert "encrypted_credentials" not in body_str


def test_user_cannot_access_other_user_devices(client, db):
    """IDOR: user A cannot see user B's devices."""
    user_a = User(email=_unique_email("usera"), password_hash=hash_password("pass1234"))
    user_b = User(email=_unique_email("userb"), password_hash=hash_password("pass1234"))
    db.add_all([user_a, user_b])
    db.flush()

    device_b = Device(
        user_id=user_b.id, device_type="phone", platform="android",
        installation_id="dev-b-001",
    )
    db.add(device_b)
    db.commit()

    token_a = create_access_token(str(user_a.id))
    # Try to revoke user B's device using user A's token
    r = client.post(
        f"/me/devices/{device_b.id}/revoke",
        headers={"Authorization": f"Bearer {token_a}"},
    )
    assert r.status_code == 404  # Not found because it's not user A's device


def test_user_cannot_access_other_user_integrations(client, db):
    """IDOR: user A cannot see user B's integrations."""
    user_a = User(email=_unique_email("usera2"), password_hash=hash_password("pass1234"))
    user_b = User(email=_unique_email("userb2"), password_hash=hash_password("pass1234"))
    db.add_all([user_a, user_b])
    db.flush()

    from app.crypto import encrypt_secret
    integ_b = UserIntegration(
        user_id=user_b.id,
        integration_type="SECRET_TEST",
        storage_mode="account",
        encrypted_credentials=encrypt_secret('{"key":"val"}'),
    )
    db.add(integ_b)
    db.commit()

    token_a = create_access_token(str(user_a.id))
    r = client.get(
        "/me/integrations/SECRET_TEST",
        headers={"Authorization": f"Bearer {token_a}"},
    )
    assert r.status_code == 404


def test_invalid_ai_provider_rejected(client, test_user, db):
    """Backend must reject unsupported ai_provider values."""
    token = create_access_token(str(test_user.id))
    r = client.patch(
        "/me/account-settings",
        json={"settings": {"ai_provider": "MALICIOUS_PROVIDER"}},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert r.status_code == 422


def test_protected_endpoints_reject_unauthenticated(client):
    """All /me endpoints must reject unauthenticated requests."""
    endpoints = [
        ("GET", "/me"),
        ("GET", "/me/devices"),
        ("GET", "/me/integrations"),
        ("GET", "/me/account-settings"),
        ("GET", "/me/pairings"),
        ("GET", "/me/playlists"),
    ]
    for method, path in endpoints:
        if method == "GET":
            r = client.get(path)
        assert r.status_code in (401, 403), f"{method} {path} returned {r.status_code}"


def test_playlist_rejects_non_http_url(client, test_user, db):
    test_user.has_lifetime_access = True
    db.commit()
    """URL fields must reject non-http schemes (SSRF defense-in-depth)."""
    token = create_access_token(str(test_user.id))
    for bad_url in ["file:///etc/passwd", "ftp://evil.com", "javascript:alert(1)", "gopher://x"]:
        r = client.put(
            "/me/playlists/ssrf-test",
            json={
                "playlist_id": "ssrf-test",
                "name": "test",
                "playlist_type": "m3u",
                "url": bad_url,
            },
            headers={"Authorization": f"Bearer {token}"},
        )
        assert r.status_code == 400, f"Expected 400 for URL: {bad_url}, got {r.status_code}"


def test_playlist_accepts_valid_http_url(client, test_user, db):
    test_user.has_lifetime_access = True
    db.commit()
    """Valid http/https URLs should be accepted."""
    token = create_access_token(str(test_user.id))
    r = client.put(
        "/me/playlists/valid-url-test",
        json={
            "playlist_id": "valid-url-test",
            "name": "test",
            "playlist_type": "m3u",
            "url": "https://example.com/playlist.m3u",
        },
        headers={"Authorization": f"Bearer {token}"},
    )
    assert r.status_code == 200


def test_encryption_versioned_roundtrip():
    """Encrypted secrets with version prefix should decrypt correctly."""
    from app.crypto import encrypt_secret, decrypt_secret
    original = "test_secret_12345"
    encrypted = encrypt_secret(original)
    assert encrypted.startswith("v1:")
    decrypted = decrypt_secret(encrypted)
    assert decrypted == original


def test_encryption_legacy_compat():
    """Legacy ciphertexts without version prefix should still decrypt."""
    from app.crypto import _get_fernet, decrypt_secret
    from cryptography.fernet import Fernet
    f = _get_fernet()
    if isinstance(f, Fernet):
        legacy = f.encrypt(b"legacy_secret").decode()
    else:
        legacy = f.encrypt(b"legacy_secret").decode()
    # Should not have v1: prefix (simulating legacy)
    assert not legacy.startswith("v1:")
    assert decrypt_secret(legacy) == "legacy_secret"


def test_ai_providers_endpoint_is_public(client):
    """The provider registry must be accessible without auth."""
    r = client.get("/meta/ai-providers")
    assert r.status_code == 200
    providers = r.json()
    assert len(providers) >= 1
    # Must not contain any secret material
    for p in providers:
        assert "api_key" not in p
        assert "secret" not in p
        assert "password" not in p


def test_lifetime_offer_endpoint_is_public_and_deprecated_free(client):
    """Old clients can call the lifetime-offer endpoint without seeing a paid tier."""
    r = client.get("/meta/lifetime-offer")
    assert r.status_code == 200
    body = r.json()
    assert body["tier"] == "free"
    assert body["display_price"] == "$0.00"
    assert body["price_cents"] == 0
    assert body["status"] == "deprecated_free_software"
    assert body["checkout_required"] is False
    assert body["next_tier"] is None
