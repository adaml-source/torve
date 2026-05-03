"""Tests for playlist backup/restore endpoints."""
import uuid

from app.security import create_access_token


def test_list_playlists_empty(client, test_user, auth_header):
    r = client.get("/me/playlists", headers=auth_header)
    assert r.status_code == 200
    assert r.json() == []


def test_save_m3u_playlist(client, test_user, auth_header):
    r = client.put("/me/playlists/pl-001", headers=auth_header, json={
        "playlist_id": "pl-001",
        "name": "My IPTV",
        "playlist_type": "m3u",
        "url": "http://example.com/playlist.m3u",
        "epg_url": "http://example.com/epg.xml",
    })
    assert r.status_code == 200
    data = r.json()
    assert data["playlist_id"] == "pl-001"
    assert data["name"] == "My IPTV"
    assert data["playlist_type"] == "m3u"
    assert data["url"] == "http://example.com/playlist.m3u"
    assert data["epg_url"] == "http://example.com/epg.xml"
    assert data["has_password"] is False
    # Must not contain password or encrypted fields
    assert "password" not in data
    assert "encrypted_password" not in data


def test_save_xtream_playlist(client, test_user, auth_header):
    r = client.put("/me/playlists/xt-001", headers=auth_header, json={
        "playlist_id": "xt-001",
        "name": "Xtream Service",
        "playlist_type": "xtream",
        "server": "http://xtream.example.com",
        "username": "myuser",
        "password": "mysecretpass",
    })
    assert r.status_code == 200
    data = r.json()
    assert data["playlist_type"] == "xtream"
    assert data["server"] == "http://xtream.example.com"
    assert data["username"] == "myuser"
    assert data["has_password"] is True
    assert "password" not in data
    assert "encrypted_password" not in data


def test_list_after_save(client, test_user, auth_header):
    client.put("/me/playlists/list-1", headers=auth_header, json={
        "playlist_id": "list-1",
        "name": "PL1",
        "playlist_type": "m3u",
        "url": "http://example.com/1.m3u",
    })
    client.put("/me/playlists/list-2", headers=auth_header, json={
        "playlist_id": "list-2",
        "name": "PL2",
        "playlist_type": "xtream",
        "server": "http://x.com",
        "username": "u",
        "password": "p",
    })
    r = client.get("/me/playlists", headers=auth_header)
    assert r.status_code == 200
    ids = [p["playlist_id"] for p in r.json()]
    assert "list-1" in ids
    assert "list-2" in ids


def test_upsert_playlist(client, test_user, auth_header):
    client.put("/me/playlists/up-1", headers=auth_header, json={
        "playlist_id": "up-1",
        "name": "Original",
        "playlist_type": "m3u",
        "url": "http://old.com/pl.m3u",
    })
    r = client.put("/me/playlists/up-1", headers=auth_header, json={
        "playlist_id": "up-1",
        "name": "Updated",
        "playlist_type": "m3u",
        "url": "http://new.com/pl.m3u",
    })
    assert r.status_code == 200
    assert r.json()["name"] == "Updated"
    assert r.json()["url"] == "http://new.com/pl.m3u"

    # Only one entry, not two
    r2 = client.get("/me/playlists", headers=auth_header)
    matches = [p for p in r2.json() if p["playlist_id"] == "up-1"]
    assert len(matches) == 1


def test_delete_playlist(client, test_user, auth_header):
    client.put("/me/playlists/del-1", headers=auth_header, json={
        "playlist_id": "del-1",
        "name": "To Delete",
        "playlist_type": "m3u",
        "url": "http://x.com/d.m3u",
    })
    r = client.delete("/me/playlists/del-1", headers=auth_header)
    assert r.status_code == 204

    r2 = client.get("/me/playlists", headers=auth_header)
    ids = [p["playlist_id"] for p in r2.json()]
    assert "del-1" not in ids


def test_get_xtream_credentials(client, test_user, auth_header):
    client.put("/me/playlists/cred-1", headers=auth_header, json={
        "playlist_id": "cred-1",
        "name": "Xtream",
        "playlist_type": "xtream",
        "server": "http://s.com",
        "username": "user1",
        "password": "secret123",
    })
    r = client.get("/me/playlists/cred-1/credentials", headers=auth_header)
    assert r.status_code == 200
    assert r.json()["playlist_id"] == "cred-1"
    assert r.json()["password"] == "secret123"


def test_m3u_credentials_returns_null_password(client, test_user, auth_header):
    client.put("/me/playlists/m3u-cred", headers=auth_header, json={
        "playlist_id": "m3u-cred",
        "name": "M3U",
        "playlist_type": "m3u",
        "url": "http://x.com/m.m3u",
    })
    r = client.get("/me/playlists/m3u-cred/credentials", headers=auth_header)
    assert r.status_code == 200
    assert r.json()["password"] is None


def test_user_isolation(client, test_user, test_user_b, auth_header, auth_header_b):
    client.put("/me/playlists/iso-1", headers=auth_header, json={
        "playlist_id": "iso-1",
        "name": "User A Playlist",
        "playlist_type": "m3u",
        "url": "http://a.com/pl.m3u",
    })
    r = client.get("/me/playlists", headers=auth_header_b)
    assert len(r.json()) == 0

    r2 = client.get("/me/playlists/iso-1/credentials", headers=auth_header_b)
    assert r2.status_code == 404


def test_survives_reauth(client, test_user):
    t1 = create_access_token(str(test_user.id))
    h1 = {"Authorization": f"Bearer {t1}"}

    client.put("/me/playlists/reauth-1", headers=h1, json={
        "playlist_id": "reauth-1",
        "name": "Persist Test",
        "playlist_type": "xtream",
        "server": "http://s.com",
        "username": "u",
        "password": "p",
    })

    t2 = create_access_token(str(test_user.id))
    h2 = {"Authorization": f"Bearer {t2}"}

    r = client.get("/me/playlists", headers=h2)
    assert any(p["playlist_id"] == "reauth-1" for p in r.json())

    r2 = client.get("/me/playlists/reauth-1/credentials", headers=h2)
    assert r2.json()["password"] == "p"


def test_m3u_requires_url(client, test_user, auth_header):
    r = client.put("/me/playlists/bad-m3u", headers=auth_header, json={
        "playlist_id": "bad-m3u",
        "name": "No URL",
        "playlist_type": "m3u",
    })
    assert r.status_code == 400


def test_xtream_requires_server_and_username(client, test_user, auth_header):
    r = client.put("/me/playlists/bad-xt", headers=auth_header, json={
        "playlist_id": "bad-xt",
        "name": "No Server",
        "playlist_type": "xtream",
    })
    assert r.status_code == 400


def test_requires_auth(client):
    assert client.get("/me/playlists").status_code == 403
    assert client.put("/me/playlists/x", json={
        "playlist_id": "x", "name": "x", "playlist_type": "m3u", "url": "http://x.com"
    }).status_code == 403


def test_id_mismatch_rejected(client, test_user, auth_header):
    r = client.put("/me/playlists/id-a", headers=auth_header, json={
        "playlist_id": "id-b",
        "name": "Mismatch",
        "playlist_type": "m3u",
        "url": "http://x.com",
    })
    assert r.status_code == 400
