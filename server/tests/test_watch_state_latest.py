"""
Tests for the cross-device watch-state read endpoint.

Covers:
- 401 when no auth
- 404 when the user has never reported for this content
- 200 returns the newest row when multiple reports exist (across devices)
- Scoped to the caller: a row belonging to a different user never leaks
"""
from httpx import AsyncClient
import pytest

from tests.conftest import login_user, register_user


@pytest.mark.asyncio
async def test_latest_unauthenticated_returns_401(client: AsyncClient):
    resp = await client.get("/me/watch_state/latest", params={"content_id": "tmdb-42"})
    assert resp.status_code == 401


@pytest.mark.asyncio
async def test_latest_404_when_no_rows(client: AsyncClient):
    tokens = await register_user(client, email="a@example.com")
    headers = {"Authorization": f"Bearer {tokens['tokens']['access_token']}"}

    resp = await client.get(
        "/me/watch_state/latest",
        params={"content_id": "tmdb-never-watched"},
        headers=headers,
    )
    assert resp.status_code == 404


@pytest.mark.asyncio
async def test_latest_returns_newest_row(client: AsyncClient):
    tokens = await register_user(client, email="b@example.com")
    headers = {"Authorization": f"Bearer {tokens['tokens']['access_token']}"}

    # Three reports for the same content, increasing positions.
    for pos in (1000, 5000, 12000):
        resp = await client.post(
            "/watch_state/report",
            json={"content_id": "tmdb-100", "provider": "torve", "position_ms": pos},
            headers=headers,
        )
        assert resp.status_code == 200

    resp = await client.get(
        "/me/watch_state/latest",
        params={"content_id": "tmdb-100"},
        headers=headers,
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["content_id"] == "tmdb-100"
    assert body["position_ms"] == 12000
    assert body["provider"] == "torve"
    assert "reported_at" in body
    assert "device_id" in body


@pytest.mark.asyncio
async def test_latest_is_scoped_to_caller(client: AsyncClient):
    # User A writes a report.
    tokens_a = await register_user(client, email="a2@example.com")
    headers_a = {"Authorization": f"Bearer {tokens_a['tokens']['access_token']}"}
    resp = await client.post(
        "/watch_state/report",
        json={"content_id": "tmdb-200", "provider": "torve", "position_ms": 7777},
        headers=headers_a,
    )
    assert resp.status_code == 200

    # User B — never reported — must not see A's row.
    tokens_b = await register_user(client, email="b2@example.com")
    headers_b = {"Authorization": f"Bearer {tokens_b['tokens']['access_token']}"}
    resp = await client.get(
        "/me/watch_state/latest",
        params={"content_id": "tmdb-200"},
        headers=headers_b,
    )
    assert resp.status_code == 404


@pytest.mark.asyncio
async def test_latest_rejects_blank_content_id(client: AsyncClient):
    tokens = await register_user(client, email="c@example.com")
    headers = {"Authorization": f"Bearer {tokens['tokens']['access_token']}"}

    resp = await client.get(
        "/me/watch_state/latest",
        params={"content_id": ""},
        headers=headers,
    )
    assert resp.status_code == 400
