"""Tests for account-backed /me/media-favorites sync."""
from __future__ import annotations

import uuid
from unittest.mock import patch

from app.models import Device, UserMediaFavorite
from app.security import create_access_token


def _auth(user):
    return {"Authorization": f"Bearer {create_access_token(str(user.id))}"}


def _payload(**overrides):
    body = {
        "media_type": "movie",
        "tmdb_id": 550,
        "imdb_id": "tt0137523",
        "title": "Fight Club",
        "poster_url": "https://image.example/poster.jpg",
        "backdrop_url": "https://image.example/backdrop.jpg",
        "rating": 8.4,
        "year": 1999,
    }
    body.update(overrides)
    return body


def test_media_favorites_auth_required(client):
    assert client.get("/me/media-favorites").status_code in (401, 403)
    assert client.put("/me/media-favorites/tt0137523", json=_payload()).status_code in (401, 403)
    assert client.delete("/me/media-favorites/tt0137523").status_code in (401, 403)


def test_put_then_get_media_favorite(client, test_user):
    r = client.put(
        "/me/media-favorites/tt0137523",
        headers=_auth(test_user),
        json=_payload(),
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["version"]
    assert body["updated_at"]
    assert body["favorite"]["media_key"] == "tt0137523"
    assert body["favorite"]["media_type"] == "movie"
    assert body["favorite"]["title"] == "Fight Club"

    r2 = client.get("/me/media-favorites", headers=_auth(test_user))
    assert r2.status_code == 200, r2.text
    data = r2.json()
    assert data["version"]
    assert len(data["favorites"]) == 1
    assert data["favorites"][0]["media_key"] == "tt0137523"


def test_put_idempotent_and_unique_per_user(client, test_user, test_user_b, db):
    with patch("app.routers.media_favorites.event_bus") as mock_bus:
        r1 = client.put(
            "/me/media-favorites/shared-key",
            headers=_auth(test_user),
            json=_payload(title="First Save"),
        )
        assert r1.status_code == 200, r1.text
        item1 = r1.json()["favorite"]

        r2 = client.put(
            "/me/media-favorites/shared-key",
            headers=_auth(test_user),
            json=_payload(title="First Save"),
        )
        assert r2.status_code == 200, r2.text
        item2 = r2.json()["favorite"]

        assert item2["id"] == item1["id"]
        assert item2["updated_at"] == item1["updated_at"]
        assert mock_bus.emit.call_count == 1

    count_a = (
        db.query(UserMediaFavorite)
        .filter(UserMediaFavorite.user_id == test_user.id, UserMediaFavorite.media_key == "shared-key")
        .count()
    )
    assert count_a == 1

    r3 = client.put(
        "/me/media-favorites/shared-key",
        headers=_auth(test_user_b),
        json=_payload(title="Other User Save"),
    )
    assert r3.status_code == 200, r3.text
    assert r3.json()["favorite"]["id"] != item1["id"]

    count_total = (
        db.query(UserMediaFavorite)
        .filter(UserMediaFavorite.media_key == "shared-key")
        .count()
    )
    assert count_total == 2


def test_cross_user_isolation_for_list_and_delete(client, test_user, test_user_b):
    r = client.put(
        "/me/media-favorites/private-key",
        headers=_auth(test_user),
        json=_payload(title="Private Favorite"),
    )
    assert r.status_code == 200

    b_list = client.get("/me/media-favorites", headers=_auth(test_user_b))
    assert b_list.status_code == 200
    assert b_list.json()["favorites"] == []

    b_delete = client.delete("/me/media-favorites/private-key", headers=_auth(test_user_b))
    assert b_delete.status_code == 200
    assert b_delete.json()["deleted"] is False

    a_list = client.get("/me/media-favorites", headers=_auth(test_user))
    assert [f["media_key"] for f in a_list.json()["favorites"]] == ["private-key"]


def test_delete_idempotent_and_emits_only_on_real_mutation(client, test_user):
    client.put(
        "/me/media-favorites/delete-key",
        headers=_auth(test_user),
        json=_payload(title="Delete Me"),
    )

    with patch("app.routers.media_favorites.event_bus") as mock_bus:
        r1 = client.delete("/me/media-favorites/delete-key", headers=_auth(test_user))
        assert r1.status_code == 200, r1.text
        assert r1.json()["deleted"] is True
        assert mock_bus.emit.call_count == 1
        emitted = mock_bus.emit.call_args[0][0]
        assert emitted.event_type == "MEDIA_FAVORITES_UPDATED"
        assert emitted.user_id == test_user.id

        r2 = client.delete("/me/media-favorites/delete-key", headers=_auth(test_user))
        assert r2.status_code == 200, r2.text
        assert r2.json()["deleted"] is False
        assert mock_bus.emit.call_count == 1


def test_update_emits_media_favorites_event(client, test_user):
    client.put(
        "/me/media-favorites/update-key",
        headers=_auth(test_user),
        json=_payload(title="Before"),
    )

    with patch("app.routers.media_favorites.event_bus") as mock_bus:
        r = client.put(
            "/me/media-favorites/update-key",
            headers=_auth(test_user),
            json=_payload(title="After"),
        )
        assert r.status_code == 200, r.text
        mock_bus.emit.assert_called_once()
        emitted = mock_bus.emit.call_args[0][0]
        assert emitted.event_type == "MEDIA_FAVORITES_UPDATED"
        assert emitted.user_id == test_user.id


def test_malformed_media_type_rejected(client, test_user):
    r = client.put(
        "/me/media-favorites/bad-type",
        headers=_auth(test_user),
        json=_payload(media_type="episode"),
    )
    assert r.status_code == 422


def test_foreign_source_device_rejected(client, test_user, test_user_b, db):
    foreign = Device(
        user_id=test_user_b.id,
        device_type="phone",
        platform="android",
        display_name="Other phone",
        installation_id=f"fav-foreign-{uuid.uuid4().hex[:8]}",
    )
    db.add(foreign)
    db.commit()
    db.refresh(foreign)

    try:
        r = client.put(
            "/me/media-favorites/foreign-device",
            headers=_auth(test_user),
            json=_payload(source_device_id=str(foreign.id)),
        )
        assert r.status_code == 400
    finally:
        db.delete(foreign)
        db.commit()
