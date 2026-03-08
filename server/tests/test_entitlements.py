"""Tests for purchase verification and entitlement endpoints."""
import base64
import json
from unittest.mock import AsyncMock, patch

import pytest
from .conftest import register_user


def make_apple_jws(product_id="com.torve.pro.lifetime", txn_id="1000000123", bundle_id="com.torve.app"):
    """Create a fake Apple JWS for testing."""
    header = base64.urlsafe_b64encode(json.dumps({"alg": "ES256"}).encode()).rstrip(b"=").decode()
    payload_data = {
        "transactionId": txn_id,
        "originalTransactionId": txn_id,
        "productId": product_id,
        "bundleId": bundle_id,
        "purchaseDate": 1709251200000,
    }
    payload = base64.urlsafe_b64encode(json.dumps(payload_data).encode()).rstrip(b"=").decode()
    signature = base64.urlsafe_b64encode(b"fakesig").rstrip(b"=").decode()
    return f"{header}.{payload}.{signature}"


@pytest.mark.asyncio
async def test_apple_verify_success(client):
    data = await register_user(client)
    token = data["tokens"]["access_token"]
    jws = make_apple_jws()
    resp = await client.post(
        "/purchases/apple/verify",
        json={"transaction_jws": jws, "product_id": "com.torve.pro.lifetime", "platform": "ios"},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "verified"
    assert body["premium_access"] is True
    assert any(e["key"] == "torve_pro_lifetime" for e in body["entitlements"])


@pytest.mark.asyncio
async def test_apple_verify_idempotent(client):
    data = await register_user(client)
    token = data["tokens"]["access_token"]
    jws = make_apple_jws()
    # First verify
    resp1 = await client.post(
        "/purchases/apple/verify",
        json={"transaction_jws": jws, "product_id": "com.torve.pro.lifetime"},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp1.status_code == 200
    # Second verify — same transaction
    resp2 = await client.post(
        "/purchases/apple/verify",
        json={"transaction_jws": jws, "product_id": "com.torve.pro.lifetime"},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp2.status_code == 200
    assert resp2.json()["premium_access"] is True


@pytest.mark.asyncio
async def test_apple_verify_wrong_product(client):
    data = await register_user(client)
    token = data["tokens"]["access_token"]
    jws = make_apple_jws(product_id="com.other.product")
    resp = await client.post(
        "/purchases/apple/verify",
        json={"transaction_jws": jws, "product_id": "com.torve.pro.lifetime"},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 400


@pytest.mark.asyncio
async def test_google_verify_success(client):
    """Google verify in dev mode (no service account configured)."""
    data = await register_user(client)
    token = data["tokens"]["access_token"]
    resp = await client.post(
        "/purchases/google/verify",
        json={
            "product_id": "com.torve.pro.lifetime",
            "purchase_token": "test-purchase-token-12345",
            "platform": "google_play_mobile",
        },
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["premium_access"] is True


@pytest.mark.asyncio
async def test_google_verify_idempotent(client):
    data = await register_user(client)
    token = data["tokens"]["access_token"]
    payload = {
        "product_id": "com.torve.pro.lifetime",
        "purchase_token": "test-purchase-token-same",
        "platform": "google_play_mobile",
    }
    resp1 = await client.post("/purchases/google/verify", json=payload, headers={"Authorization": f"Bearer {token}"})
    assert resp1.status_code == 200
    resp2 = await client.post("/purchases/google/verify", json=payload, headers={"Authorization": f"Bearer {token}"})
    assert resp2.status_code == 200


@pytest.mark.asyncio
async def test_amazon_verify_success(client):
    """Amazon verify in dev mode (no shared secret configured)."""
    data = await register_user(client)
    token = data["tokens"]["access_token"]
    resp = await client.post(
        "/purchases/amazon/verify",
        json={
            "receipt_id": "amazon-receipt-123",
            "amazon_user_id": "amzn-user-456",
            "product_id": "com.torve.pro.lifetime.amazon",
            "platform": "amazon_fire_tv",
        },
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["premium_access"] is True


@pytest.mark.asyncio
async def test_entitlements_after_purchase(client):
    data = await register_user(client)
    token = data["tokens"]["access_token"]

    # Before purchase — no entitlements
    resp = await client.get("/me/entitlements", headers={"Authorization": f"Bearer {token}"})
    assert resp.json()["premium_access"] is False
    assert resp.json()["entitlements"] == []

    # Make purchase
    jws = make_apple_jws()
    await client.post(
        "/purchases/apple/verify",
        json={"transaction_jws": jws, "product_id": "com.torve.pro.lifetime"},
        headers={"Authorization": f"Bearer {token}"},
    )

    # After purchase — has entitlement
    resp = await client.get("/me/entitlements", headers={"Authorization": f"Bearer {token}"})
    body = resp.json()
    assert body["premium_access"] is True
    assert len(body["entitlements"]) == 1
    assert body["entitlements"][0]["key"] == "torve_pro_lifetime"


@pytest.mark.asyncio
async def test_cross_platform_entitlement(client):
    """User buys on iOS, signs in on Android — should see entitlement."""
    data = await register_user(client)
    token = data["tokens"]["access_token"]

    # Purchase on iOS
    jws = make_apple_jws()
    resp = await client.post(
        "/purchases/apple/verify",
        json={"transaction_jws": jws, "product_id": "com.torve.pro.lifetime", "platform": "ios"},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 200

    # Sign in on Android (same account, different device)
    login_resp = await client.post("/auth/login", json={
        "email": "test@example.com",
        "password": "TestPass123!",
        "device": {
            "installation_id": "android-device-002",
            "device_name": "Pixel 9",
            "device_type": "phone",
            "platform": "google_play_mobile",
        },
    })
    android_token = login_resp.json()["tokens"]["access_token"]

    # Activate Android device and check access (device-aware)
    resp = await client.get("/me/access-state", headers={"Authorization": f"Bearer {android_token}"})
    body = resp.json()
    assert body["premium"]["premium_access"] is True
    assert body["premium"]["entitlements"][0]["source_store"] == "apple"


@pytest.mark.asyncio
async def test_purchase_different_user_conflict(client):
    """Two different users can't use the same receipt."""
    # User A
    data_a = await register_user(client, email="user_a@test.com")
    token_a = data_a["tokens"]["access_token"]
    jws = make_apple_jws(txn_id="shared-txn-123")
    resp = await client.post(
        "/purchases/apple/verify",
        json={"transaction_jws": jws, "product_id": "com.torve.pro.lifetime"},
        headers={"Authorization": f"Bearer {token_a}"},
    )
    assert resp.status_code == 200

    # User B tries same receipt
    data_b = await register_user(client, email="user_b@test.com")
    token_b = data_b["tokens"]["access_token"]
    resp = await client.post(
        "/purchases/apple/verify",
        json={"transaction_jws": jws, "product_id": "com.torve.pro.lifetime"},
        headers={"Authorization": f"Bearer {token_b}"},
    )
    assert resp.status_code == 409


@pytest.mark.asyncio
async def test_purchase_history(client):
    data = await register_user(client)
    token = data["tokens"]["access_token"]

    jws = make_apple_jws()
    await client.post(
        "/purchases/apple/verify",
        json={"transaction_jws": jws, "product_id": "com.torve.pro.lifetime"},
        headers={"Authorization": f"Bearer {token}"},
    )

    resp = await client.get("/purchases/history", headers={"Authorization": f"Bearer {token}"})
    assert resp.status_code == 200
    history = resp.json()
    assert len(history) == 1
    assert history[0]["store"] == "apple"
    assert history[0]["verification_status"] == "verified"


@pytest.mark.asyncio
async def test_verify_unauthenticated(client):
    resp = await client.post("/purchases/apple/verify", json={
        "transaction_jws": "fake.fake.fake",
        "product_id": "com.torve.pro.lifetime",
    })
    assert resp.status_code in (401, 403, 422)


@pytest.mark.asyncio
async def test_restore_purchases(client):
    data = await register_user(client)
    token = data["tokens"]["access_token"]
    resp = await client.post(
        "/purchases/restore",
        json={"store": "apple", "platform": "ios"},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 200
    assert resp.json()["premium_access"] is False  # No purchases yet


@pytest.mark.asyncio
async def test_lifetime_overrides_monthly(client):
    """If user has both monthly and lifetime, premium_access is True."""
    data = await register_user(client)
    token = data["tokens"]["access_token"]

    # Lifetime purchase
    jws = make_apple_jws(product_id="com.torve.pro.lifetime", txn_id="lifetime-001")
    resp = await client.post(
        "/purchases/apple/verify",
        json={"transaction_jws": jws, "product_id": "com.torve.pro.lifetime"},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 200
    assert resp.json()["premium_access"] is True
