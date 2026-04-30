"""Account deletion cascade + data export end-to-end (Prompt 12 hardening)."""
from __future__ import annotations

import pytest
from httpx import AsyncClient

from app import main as main_module
from app.models import (
    AccountSettings,
    Device,
    Entitlement,
    EventOutbox,
    LanHub,
    Purchase,
    Session,
    User,
    UserPlaylist,
    WatchStateReport,
)
from sqlalchemy import select


async def _register_and_login(client: AsyncClient, email: str = "del@example.com") -> str:
    payload = {
        "email": email,
        "password": "Hunter2-Test",
        "device": {
            "installation_id": f"iid-{email}",
            "device_name": "Test Phone",
            "device_type": "phone",
            "platform": "google_play_mobile",
        },
    }
    resp = await client.post("/auth/register", json=payload)
    assert resp.status_code == 200, resp.text
    return resp.json()["tokens"]["access_token"]


async def _seed_per_user_rows(session, user_id: str, device_id: str) -> None:
    """Drop one row per user-scoped table. Cascade test is meaningful only
    when there's something to cascade.
    """
    session.add_all([
        WatchStateReport(
            user_id=user_id,
            device_id=device_id,
            content_id="movie:42",
            provider="debrid",
            position_ms=12345,
        ),
        UserPlaylist(
            user_id=user_id,
            name="My IPTV",
            url="http://example/m3u",
        ),
        LanHub(
            user_id=user_id,
            publisher_id="pub-1",
            device_label="Desktop",
            lan_host="192.168.1.10",
            lan_port=41122,
            auth_secret="fernet:v1:placeholder",
        ),
    ])
    await session.commit()


@pytest.mark.asyncio
async def test_delete_account_cascades_all_user_rows(client: AsyncClient, db_session) -> None:
    token = await _register_and_login(client)
    headers = {"Authorization": f"Bearer {token}"}

    # Find the seeded user + device so we can attach per-user rows.
    user = (await db_session.execute(select(User).where(User.email == "del@example.com"))).scalar_one()
    device = (await db_session.execute(select(Device).where(Device.user_id == user.id))).scalar_one()
    await _seed_per_user_rows(db_session, user.id, device.id)

    # Delete the account.
    resp = await client.delete("/auth/account", headers=headers)
    assert resp.status_code == 200, resp.text

    # Every per-user table must be empty for this user_id.
    async def _count(model) -> int:
        rows = (await db_session.execute(
            select(model).where(model.user_id == user.id)
        )).scalars().all()
        return len(rows)

    assert await _count(WatchStateReport) == 0
    assert await _count(UserPlaylist) == 0
    assert await _count(LanHub) == 0
    assert await _count(Purchase) == 0
    assert await _count(Entitlement) == 0
    assert await _count(AccountSettings) == 0
    assert await _count(EventOutbox) == 0
    assert await _count(Device) == 0
    assert await _count(Session) == 0
    # User row gone too.
    user_after = (await db_session.execute(select(User).where(User.id == user.id))).scalar_one_or_none()
    assert user_after is None


@pytest.mark.asyncio
async def test_delete_account_revokes_active_sessions_first(client: AsyncClient, db_session) -> None:
    token = await _register_and_login(client, email="del2@example.com")
    headers = {"Authorization": f"Bearer {token}"}
    resp = await client.delete("/auth/account", headers=headers)
    assert resp.status_code == 200
    # The same token must no longer authenticate any further request.
    me = await client.get("/me", headers=headers)
    assert me.status_code == 401


@pytest.mark.asyncio
async def test_export_returns_user_envelope_with_no_secrets(client: AsyncClient, db_session) -> None:
    token = await _register_and_login(client, email="exp@example.com")
    headers = {"Authorization": f"Bearer {token}"}

    user = (await db_session.execute(select(User).where(User.email == "exp@example.com"))).scalar_one()
    device = (await db_session.execute(select(Device).where(Device.user_id == user.id))).scalar_one()
    db_session.add_all([
        UserPlaylist(
            user_id=user.id,
            name="Pl",
            url="http://x/m3u",
            username="bob",
            password_enc="ENC-DO-NOT-LEAK",
        ),
        LanHub(
            user_id=user.id,
            publisher_id="pub-x",
            device_label="Desk",
            lan_host="1.2.3.4",
            lan_port=41122,
            auth_secret="fernet:v1:WRAP-DO-NOT-LEAK",
        ),
    ])
    await db_session.commit()

    resp = await client.get("/me/export", headers=headers)
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["export_format_version"] == 1
    assert body["user"]["email"] == "exp@example.com"
    assert any(p["name"] == "Pl" for p in body["playlists"])
    assert any(h["publisher_id"] == "pub-x" for h in body["lan_hubs"])

    # Crucial: secrets must not appear in the export. The user can fetch
    # their LAN secret separately via the dedicated endpoint per session.
    blob = resp.text
    assert "ENC-DO-NOT-LEAK" not in blob, "playlist password ciphertext leaked into export"
    assert "WRAP-DO-NOT-LEAK" not in blob, "LAN auth_secret leaked into export"


@pytest.mark.asyncio
async def test_export_requires_auth(client: AsyncClient) -> None:
    resp = await client.get("/me/export")
    assert resp.status_code == 401
