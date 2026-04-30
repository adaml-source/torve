"""Tests for the Prompt 9B LAN hub registry endpoints.

Covers:
  - publish creates and updates idempotently
  - same-user isolation (a second user can't see, fetch, or delete
    another user's hubs)
  - stale rows are filtered from listings and rejected with 410 on
    secret fetch
  - delete is idempotent
  - listing payload never carries the auth_secret field

In-memory SQLite via the shared `client` fixture. The fixture's lifecycle
(create_all → tests → drop_all) means each test starts with an empty
LAN hub table — no cross-test pollution.
"""
from datetime import timedelta
import pytest
import pytest_asyncio
from httpx import AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import async_sessionmaker, AsyncSession

from app.models import Device, LanHub, User, utcnow
from app.security import create_access_token, hash_password


async def _seed_user(db_engine, email: str) -> tuple[User, str]:
    """Insert a user + an activated device, return (user, access_token).

    Bypasses /auth/register because of an unrelated pre-existing
    baseline bug in `as_user_response` that references a missing
    `User.is_verified` attribute.
    """
    factory = async_sessionmaker(db_engine, class_=AsyncSession, expire_on_commit=False)
    async with factory() as session:
        user = User(email=email, password_hash=hash_password("Pass1234!"))
        session.add(user)
        await session.flush()
        device = Device(
            user_id=user.id,
            installation_id=f"install-{email}",
            device_name="Mac",
            device_type="desktop",
            platform="mac",
            activated_at=utcnow(),
        )
        session.add(device)
        await session.flush()
        access_token, _ = create_access_token(user_id=user.id, device_id=device.id)
        await session.commit()
    return user, access_token


@pytest.mark.asyncio
async def test_publish_inserts_and_updates_idempotently(client: AsyncClient, db_engine):
    _, token = await _seed_user(db_engine, "pub-1@example.com")
    headers = {"Authorization": f"Bearer {token}"}

    body = {
        "publisher_id": "desk-001",
        "device_label": "Adam Mac",
        "lan_host": "192.168.1.10",
        "lan_port": 41122,
        "protocol_version": 1,
        "auth_secret": "secret-AAAA",
    }
    first = await client.post("/me/lan/hubs", json=body, headers=headers)
    assert first.status_code == 200, first.text
    j1 = first.json()
    assert j1["publisher_id"] == "desk-001"
    assert "auth_secret" not in j1, "listing/response shape must not leak the secret"

    # Re-publish updates rather than creates a duplicate row.
    body["device_label"] = "Adam Mac (renamed)"
    body["auth_secret"] = "secret-BBBB"
    second = await client.post("/me/lan/hubs", json=body, headers=headers)
    assert second.status_code == 200
    listed = await client.get("/me/lan/hubs", headers=headers)
    assert listed.status_code == 200
    hubs = listed.json()["hubs"]
    assert len(hubs) == 1
    assert hubs[0]["device_label"] == "Adam Mac (renamed)"


@pytest.mark.asyncio
async def test_listing_does_not_leak_auth_secret(client: AsyncClient, db_engine):
    _, token = await _seed_user(db_engine, "pub-2@example.com")
    headers = {"Authorization": f"Bearer {token}"}
    body = {
        "publisher_id": "desk-002",
        "device_label": "Mac",
        "lan_host": "10.0.0.5",
        "lan_port": 41122,
        "protocol_version": 1,
        "auth_secret": "should-not-leak-deadbeef",
    }
    await client.post("/me/lan/hubs", json=body, headers=headers)
    listed = await client.get("/me/lan/hubs", headers=headers)
    raw = listed.text
    assert "should-not-leak-deadbeef" not in raw, "listing endpoint leaked auth_secret"
    assert "auth_secret" not in raw


@pytest.mark.asyncio
async def test_secret_endpoint_returns_secret_for_same_user(client: AsyncClient, db_engine):
    _, token = await _seed_user(db_engine, "pub-3@example.com")
    headers = {"Authorization": f"Bearer {token}"}
    body = {
        "publisher_id": "desk-003",
        "device_label": "Mac",
        "lan_host": "10.0.0.5",
        "lan_port": 41122,
        "protocol_version": 1,
        "auth_secret": "round-trip-secret",
    }
    await client.post("/me/lan/hubs", json=body, headers=headers)
    secret = await client.get("/me/lan/hubs/desk-003/secret", headers=headers)
    assert secret.status_code == 200
    assert secret.json()["auth_secret"] == "round-trip-secret"


@pytest.mark.asyncio
async def test_cross_user_isolation_for_listing_and_secret(client: AsyncClient, db_engine):
    # User A publishes.
    _, a_token = await _seed_user(db_engine, "user-a@example.com")
    a_headers = {"Authorization": f"Bearer {a_token}"}
    body = {
        "publisher_id": "desk-shared",
        "device_label": "A's Mac",
        "lan_host": "192.168.1.10",
        "lan_port": 41122,
        "protocol_version": 1,
        "auth_secret": "user-a-secret",
    }
    await client.post("/me/lan/hubs", json=body, headers=a_headers)

    # User B sees nothing.
    _, b_token = await _seed_user(db_engine, "user-b@example.com")
    b_headers = {"Authorization": f"Bearer {b_token}"}
    listed = await client.get("/me/lan/hubs", headers=b_headers)
    assert listed.status_code == 200
    assert listed.json()["hubs"] == []

    # User B cannot fetch A's secret.
    secret = await client.get("/me/lan/hubs/desk-shared/secret", headers=b_headers)
    assert secret.status_code == 404

    # User B cannot delete A's hub.
    deleted = await client.delete("/me/lan/hubs/desk-shared", headers=b_headers)
    assert deleted.status_code == 200
    # Confirm A's hub still exists.
    a_listed = await client.get("/me/lan/hubs", headers=a_headers)
    assert len(a_listed.json()["hubs"]) == 1


@pytest.mark.asyncio
async def test_stale_hubs_drop_out_of_listing_and_secret(
    client: AsyncClient,
    db_engine,
):
    _, token = await _seed_user(db_engine, "pub-stale@example.com")
    headers = {"Authorization": f"Bearer {token}"}
    body = {
        "publisher_id": "desk-stale",
        "device_label": "Stale Mac",
        "lan_host": "192.168.1.20",
        "lan_port": 41122,
        "protocol_version": 1,
        "auth_secret": "stale-secret",
    }
    publish = await client.post("/me/lan/hubs", json=body, headers=headers)
    assert publish.status_code == 200

    # Hand-edit last_seen_at to 1 hour ago via the DB directly.
    factory = async_sessionmaker(db_engine, class_=AsyncSession, expire_on_commit=False)
    async with factory() as direct:
        result = await direct.execute(
            select(LanHub).where(LanHub.publisher_id == "desk-stale")
        )
        row = result.scalar_one()
        row.last_seen_at = utcnow() - timedelta(hours=1)
        await direct.flush()
        await direct.commit()

    listed = await client.get("/me/lan/hubs", headers=headers)
    assert listed.status_code == 200
    assert listed.json()["hubs"] == [], "stale hub must drop from listings"

    secret = await client.get("/me/lan/hubs/desk-stale/secret", headers=headers)
    assert secret.status_code == 410, "stale secret fetch must return 410"


@pytest.mark.asyncio
async def test_delete_is_idempotent(client: AsyncClient, db_engine):
    _, token = await _seed_user(db_engine, "pub-del@example.com")
    headers = {"Authorization": f"Bearer {token}"}
    body = {
        "publisher_id": "desk-del",
        "device_label": "Mac",
        "lan_host": "10.0.0.5",
        "lan_port": 41122,
        "protocol_version": 1,
        "auth_secret": "x",
    }
    await client.post("/me/lan/hubs", json=body, headers=headers)
    first = await client.delete("/me/lan/hubs/desk-del", headers=headers)
    assert first.status_code == 200
    second = await client.delete("/me/lan/hubs/desk-del", headers=headers)
    assert second.status_code == 200, "second delete must be a no-op, not 404"
    listed = await client.get("/me/lan/hubs", headers=headers)
    assert listed.json()["hubs"] == []


@pytest.mark.asyncio
async def test_endpoints_require_authentication(client: AsyncClient):
    body = {
        "publisher_id": "x",
        "device_label": "x",
        "lan_host": "x",
        "lan_port": 1,
        "protocol_version": 1,
        "auth_secret": "x",
    }
    p = await client.post("/me/lan/hubs", json=body)
    assert p.status_code == 401
    g = await client.get("/me/lan/hubs")
    assert g.status_code == 401
    s = await client.get("/me/lan/hubs/x/secret")
    assert s.status_code == 401
    d = await client.delete("/me/lan/hubs/x")
    assert d.status_code == 401
