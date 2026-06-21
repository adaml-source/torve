"""Tests for playlist backup/restore endpoints."""
import gzip
from datetime import datetime, timedelta, timezone
from unittest.mock import patch

from fastapi import HTTPException

from app.models import BetaAccessGrant, UserPlaylist
from app.routers import playlists as playlists_router
from app.security import create_access_token


def _device_headers(headers, device):
    return {**headers, "X-Torve-Installation-Id": device.installation_id}


def _detail_code(response):
    detail = response.json().get("detail")
    if isinstance(detail, dict):
        return detail.get("code") or detail.get("error_code")
    return detail


def _grant_beta_access(db, user, *, days: int = 30) -> BetaAccessGrant:
    now = datetime.now(timezone.utc).replace(microsecond=0)
    grant = BetaAccessGrant(
        torve_user_id=user.id,
        discord_user_id=None,
        source="discord_beta",
        status="active",
        starts_at=now,
        expires_at=now + timedelta(days=days),
        created_at=now,
        updated_at=now,
    )
    db.add(grant)
    db.commit()
    db.refresh(grant)
    return grant


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
        "epg_url": "https://guide.example.com/xmltv.xml",
        "username": "myuser",
        "password": "mysecretpass",
    })
    assert r.status_code == 200
    data = r.json()
    assert data["playlist_type"] == "xtream"
    assert data["server"] == "http://xtream.example.com"
    assert data["epg_url"] == "https://guide.example.com/xmltv.xml"
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
        "epg_url": "http://example.com/guide.xml",
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
    m3u = next(p for p in r.json() if p["playlist_id"] == "list-1")
    assert m3u["epg_url"] == "http://example.com/guide.xml"


def test_m3u_playlist_survives_cold_hydration_after_reauth(client, test_user):
    first_token = create_access_token(str(test_user.id))
    first_headers = {"Authorization": f"Bearer {first_token}"}
    save = client.put("/me/playlists/cold-m3u", headers=first_headers, json={
        "playlist_id": "cold-m3u",
        "name": "Cold M3U",
        "playlist_type": "m3u",
        "url": "http://example.com/cold.m3u",
        "epg_url": "http://example.com/cold.xml",
    })
    assert save.status_code == 200, save.text

    second_token = create_access_token(str(test_user.id))
    response = client.get(
        "/me/playlists",
        headers={"Authorization": f"Bearer {second_token}"},
    )

    assert response.status_code == 200
    playlists = {item["playlist_id"]: item for item in response.json()}
    assert playlists["cold-m3u"]["playlist_type"] == "m3u"
    assert playlists["cold-m3u"]["url"] == "http://example.com/cold.m3u"


def test_xtream_playlist_survives_cold_hydration_after_reauth(client, test_user):
    first_token = create_access_token(str(test_user.id))
    first_headers = {"Authorization": f"Bearer {first_token}"}
    save = client.put("/me/playlists/cold-xtream", headers=first_headers, json={
        "playlist_id": "cold-xtream",
        "name": "Cold Xtream",
        "playlist_type": "xtream",
        "server": "http://xtream.example.com",
        "username": "xt-user",
        "password": "xt-password",
    })
    assert save.status_code == 200, save.text

    second_token = create_access_token(str(test_user.id))
    response = client.get(
        "/me/playlists",
        headers={"Authorization": f"Bearer {second_token}"},
    )

    assert response.status_code == 200
    playlists = {item["playlist_id"]: item for item in response.json()}
    assert playlists["cold-xtream"]["playlist_type"] == "xtream"
    assert playlists["cold-xtream"]["server"] == "http://xtream.example.com"
    assert playlists["cold-xtream"]["username"] == "xt-user"
    assert playlists["cold-xtream"]["has_password"] is True


def test_startup_hydration_does_not_clear_valid_playlist_rows(client, db, test_user, auth_header):
    client.put("/me/playlists/hydrate-m3u", headers=auth_header, json={
        "playlist_id": "hydrate-m3u",
        "name": "Hydrate M3U",
        "playlist_type": "m3u",
        "url": "http://example.com/hydrate.m3u",
    })
    client.put("/me/playlists/hydrate-xtream", headers=auth_header, json={
        "playlist_id": "hydrate-xtream",
        "name": "Hydrate Xtream",
        "playlist_type": "xtream",
        "server": "http://xtream.example.com",
        "username": "hydrate",
        "password": "secret",
    })
    before_count = db.query(UserPlaylist).filter(UserPlaylist.user_id == test_user.id).count()

    first = client.get("/me/playlists", headers=auth_header)
    second = client.get("/me/playlists", headers=auth_header)
    after_count = db.query(UserPlaylist).filter(UserPlaylist.user_id == test_user.id).count()

    assert first.status_code == 200
    assert second.status_code == 200
    assert before_count == 2
    assert after_count == before_count
    assert {item["playlist_id"] for item in second.json()} == {"hydrate-m3u", "hydrate-xtream"}


def test_playlist_hydration_reports_no_filtering_for_valid_rows(client, test_user, auth_header, caplog):
    client.put("/me/playlists/filter-m3u", headers=auth_header, json={
        "playlist_id": "filter-m3u",
        "name": "Filter M3U",
        "playlist_type": "m3u",
        "url": "http://example.com/filter.m3u",
    })
    caplog.set_level("INFO", logger="app.routers.playlists")

    response = client.get("/me/playlists", headers=auth_header)

    assert response.status_code == 200
    assert any(item["playlist_id"] == "filter-m3u" for item in response.json())
    assert "PLAYLIST_HYDRATION" in caplog.text
    assert "filtered_count=0" in caplog.text
    assert "filter_reason=none" in caplog.text


def test_xtream_epg_url_is_preserved_on_list_and_update(client, test_user, auth_header):
    client.put("/me/playlists/list-xt", headers=auth_header, json={
        "playlist_id": "list-xt",
        "name": "Xtream",
        "playlist_type": "xtream",
        "server": "http://xtream.example.com",
        "username": "user",
        "password": "pass",
        "epg_url": "https://guide.example.com/one.xml",
    })
    r = client.get("/me/playlists", headers=auth_header)
    assert r.status_code == 200
    xtream = next(p for p in r.json() if p["playlist_id"] == "list-xt")
    assert xtream["epg_url"] == "https://guide.example.com/one.xml"

    updated = client.put("/me/playlists/list-xt", headers=auth_header, json={
        "playlist_id": "list-xt",
        "name": "Xtream",
        "playlist_type": "xtream",
        "server": "http://xtream.example.com",
        "username": "user",
        "password": "pass",
        "epg_url": "https://guide.example.com/two.xml",
    })
    assert updated.status_code == 200
    assert updated.json()["epg_url"] == "https://guide.example.com/two.xml"


def test_save_playlist_emits_update_event(client, test_user, auth_header):
    with patch("app.routers.playlists.event_bus") as mock_bus:
        r = client.put("/me/playlists/event-1", headers=auth_header, json={
            "playlist_id": "event-1",
            "name": "Evented",
            "playlist_type": "xtream",
            "server": "http://xtream.example.com",
            "username": "user",
            "password": "pass",
            "epg_url": "https://guide.example.com/live.xml",
        })
        assert r.status_code == 200, r.text
        mock_bus.emit.assert_called_once()
        emitted = mock_bus.emit.call_args[0][0]
        assert emitted.event_type == "PLAYLISTS_UPDATED"
        assert emitted.user_id == test_user.id


def test_delete_playlist_emits_update_event(client, test_user, auth_header):
    client.put("/me/playlists/delete-event", headers=auth_header, json={
        "playlist_id": "delete-event",
        "name": "Delete Event",
        "playlist_type": "m3u",
        "url": "http://example.com/delete.m3u",
    })

    with patch("app.routers.playlists.event_bus") as mock_bus:
        r = client.delete("/me/playlists/delete-event", headers=auth_header)
        assert r.status_code == 204, r.text
        mock_bus.emit.assert_called_once()
        emitted = mock_bus.emit.call_args[0][0]
        assert emitted.event_type == "PLAYLISTS_UPDATED"
        assert emitted.user_id == test_user.id


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
        "epg_url": "http://new.com/epg.xml",
    })
    assert r.status_code == 200
    assert r.json()["name"] == "Updated"
    assert r.json()["url"] == "http://new.com/pl.m3u"
    assert r.json()["epg_url"] == "http://new.com/epg.xml"

    # Only one entry, not two
    r2 = client.get("/me/playlists", headers=auth_header)
    matches = [p for p in r2.json() if p["playlist_id"] == "up-1"]
    assert len(matches) == 1
    assert matches[0]["epg_url"] == "http://new.com/epg.xml"


class _FakeStreamResponse:
    def __init__(self, status_code=200, body=b"", headers=None):
        self.status_code = status_code
        self._body = body
        self.headers = headers or {"content-type": "application/xml"}

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def iter_bytes(self):
        yield self._body


class _FakeHttpClient:
    response = _FakeStreamResponse()

    def __init__(self, *args, **kwargs):
        pass

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def stream(self, *args, **kwargs):
        return self.response


def _install_fake_epg_fetch(monkeypatch, body, status_code=200, headers=None):
    _FakeHttpClient.response = _FakeStreamResponse(
        status_code=status_code,
        body=body,
        headers=headers or {"content-type": "application/xml"},
    )
    monkeypatch.setattr(playlists_router, "_assert_safe_fetch_url", lambda url: url)
    monkeypatch.setattr(playlists_router.httpx, "Client", _FakeHttpClient)


def test_validate_epg_url_detects_xmltv_programmes(client, test_user, auth_header, monkeypatch):
    body = (
        b'<?xml version="1.0"?><tv>'
        b'<channel id="bbc"><display-name>BBC</display-name></channel>'
        b'<programme channel="bbc" start="20260516000000 +0000" stop="20260516010000 +0000">'
        b'<title>News</title></programme></tv>'
    )
    _install_fake_epg_fetch(monkeypatch, body)

    r = client.post(
        "/me/playlists/validate-epg",
        headers=auth_header,
        json={"epg_url": "https://epg.example.com/guide.xml"},
    )
    assert r.status_code == 200
    data = r.json()
    assert data["success"] is True
    assert data["status"] == "ok"
    assert data["channel_count"] == 1
    assert data["programme_count"] == 1


def test_validate_epg_url_handles_gzipped_xmltv(client, test_user, auth_header, monkeypatch):
    body = gzip.compress(
        b'<tv><channel id="c"></channel>'
        b'<programme channel="c" start="20260516000000 +0000"></programme></tv>'
    )
    _install_fake_epg_fetch(
        monkeypatch,
        body,
        headers={"content-type": "application/gzip"},
    )

    r = client.post(
        "/me/playlists/validate-epg",
        headers=auth_header,
        json={"epg_url": "https://epg.example.com/guide.xml.gz"},
    )
    assert r.status_code == 200
    assert r.json()["success"] is True


def test_validate_epg_url_reports_non_epg_payload(client, test_user, auth_header, monkeypatch):
    _install_fake_epg_fetch(monkeypatch, b"not an xmltv guide")

    r = client.post(
        "/me/playlists/validate-epg",
        headers=auth_header,
        json={"epg_url": "https://epg.example.com/guide.xml"},
    )
    assert r.status_code == 200
    assert r.json()["success"] is False
    assert r.json()["status"] == "not_epg"


def test_validate_epg_url_rejects_private_hosts(client, test_user, auth_header):
    r = client.post(
        "/me/playlists/validate-epg",
        headers=auth_header,
        json={"epg_url": "http://127.0.0.1/guide.xml"},
    )
    assert r.status_code == 200
    assert r.json()["success"] is False
    assert r.json()["status"] == "invalid_url"


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


def test_get_xtream_credentials(client, test_user, auth_header, tv_device):
    client.put("/me/playlists/cred-1", headers=auth_header, json={
        "playlist_id": "cred-1",
        "name": "Xtream",
        "playlist_type": "xtream",
        "server": "http://s.com",
        "username": "user1",
        "password": "secret123",
    })
    r = client.get(
        "/me/playlists/cred-1/credentials",
        headers=_device_headers(auth_header, tv_device),
    )
    assert r.status_code == 200
    assert r.json()["playlist_id"] == "cred-1"
    assert r.json()["password"] == "secret123"


def test_m3u_credentials_returns_null_password(client, test_user, auth_header, tv_device):
    client.put("/me/playlists/m3u-cred", headers=auth_header, json={
        "playlist_id": "m3u-cred",
        "name": "M3U",
        "playlist_type": "m3u",
        "url": "http://x.com/m.m3u",
    })
    r = client.get(
        "/me/playlists/m3u-cred/credentials",
        headers=_device_headers(auth_header, tv_device),
    )
    assert r.status_code == 200
    assert r.json()["password"] is None


def test_playlist_credentials_restore_requires_registered_device(client, test_user, auth_header):
    client.put("/me/playlists/cred-device-required", headers=auth_header, json={
        "playlist_id": "cred-device-required",
        "name": "Xtream",
        "playlist_type": "xtream",
        "server": "http://s.com",
        "username": "user1",
        "password": "stored_secret",
    })
    r = client.get("/me/playlists/cred-device-required/credentials", headers=auth_header)
    assert r.status_code == 403
    assert _detail_code(r) == "device_required"
    assert "stored_secret" not in r.text


def test_playlist_credentials_restore_rejects_other_user_device(
    client, test_user, test_user_b, auth_header, phone_device_b
):
    client.put("/me/playlists/cred-other-device", headers=auth_header, json={
        "playlist_id": "cred-other-device",
        "name": "Xtream",
        "playlist_type": "xtream",
        "server": "http://s.com",
        "username": "user1",
        "password": "stored_secret",
    })
    r = client.get(
        "/me/playlists/cred-other-device/credentials",
        headers=_device_headers(auth_header, phone_device_b),
    )
    assert r.status_code == 403
    assert _detail_code(r) == "device_not_authorized"
    assert "stored_secret" not in r.text


def test_playlist_credentials_restore_rate_limited(
    client, test_user, auth_header, tv_device, monkeypatch
):
    client.put("/me/playlists/cred-rate-limit", headers=auth_header, json={
        "playlist_id": "cred-rate-limit",
        "name": "Xtream",
        "playlist_type": "xtream",
        "server": "http://s.com",
        "username": "user1",
        "password": "stored_secret",
    })

    def fake_limit(**kwargs):
        assert kwargs["category"] == "credential_restore_playlist"
        raise HTTPException(
            status_code=429,
            detail={
                "error_code": "rate_limited",
                "message": "Too many requests. Please try again later.",
            },
        )

    monkeypatch.setattr("app.routers.playlists.enforce_rate_limit", fake_limit)
    r = client.get(
        "/me/playlists/cred-rate-limit/credentials",
        headers=_device_headers(auth_header, tv_device),
    )
    assert r.status_code == 429
    assert _detail_code(r) == "rate_limited"
    assert "stored_secret" not in r.text


def test_playlist_credentials_restore_logs_are_sanitized(
    client, test_user, auth_header, tv_device, caplog
):
    secret = "do_not_log_playlist_secret"
    client.put("/me/playlists/cred-log", headers=auth_header, json={
        "playlist_id": "cred-log",
        "name": "Xtream",
        "playlist_type": "xtream",
        "server": "http://s.com",
        "username": "user1",
        "password": secret,
    })
    caplog.set_level("INFO", logger="app.routers.playlists")

    r = client.get(
        "/me/playlists/cred-log/credentials",
        headers=_device_headers(auth_header, tv_device),
    )
    assert r.status_code == 200
    assert r.json()["password"] == secret
    assert "CREDENTIAL_RESTORE kind=playlist" in caplog.text
    assert secret not in caplog.text


def test_playlist_credentials_restore_decrypt_failure_is_sanitized(
    client, test_user, auth_header, tv_device, db
):
    from app.models import UserPlaylist

    db.add(UserPlaylist(
        user_id=test_user.id,
        playlist_id="cred-broken",
        name="Broken Xtream",
        playlist_type="xtream",
        server="http://s.com",
        username="user1",
        encrypted_password="not-a-valid-encrypted-secret",
    ))
    db.commit()

    r = client.get(
        "/me/playlists/cred-broken/credentials",
        headers=_device_headers(auth_header, tv_device),
    )
    assert r.status_code == 500
    assert _detail_code(r) == "credential_restore_failed"
    assert "not-a-valid-encrypted-secret" not in r.text


def test_user_isolation(client, test_user, test_user_b, auth_header, auth_header_b, phone_device_b):
    client.put("/me/playlists/iso-1", headers=auth_header, json={
        "playlist_id": "iso-1",
        "name": "User A Playlist",
        "playlist_type": "m3u",
        "url": "http://a.com/pl.m3u",
    })
    r = client.get("/me/playlists", headers=auth_header_b)
    assert len(r.json()) == 0

    r2 = client.get(
        "/me/playlists/iso-1/credentials",
        headers=_device_headers(auth_header_b, phone_device_b),
    )
    assert r2.status_code == 404


def test_playlist_hydration_survives_installation_id_change(client, test_user, auth_header, phone_device):
    save = client.put(
        "/me/playlists/install-shift",
        headers=_device_headers(auth_header, phone_device),
        json={
            "playlist_id": "install-shift",
            "name": "Install Shift",
            "playlist_type": "m3u",
            "url": "http://example.com/install-shift.m3u",
        },
    )
    assert save.status_code == 200, save.text

    response = client.get(
        "/me/playlists",
        headers={**auth_header, "X-Torve-Installation-Id": "pixel-9-pro-after-restart"},
    )

    assert response.status_code == 200
    assert any(item["playlist_id"] == "install-shift" for item in response.json())


def test_beta_user_can_save_and_hydrate_playlist(client, db, free_user, free_auth_header):
    _grant_beta_access(db, free_user)

    save = client.put("/me/playlists/beta-m3u", headers=free_auth_header, json={
        "playlist_id": "beta-m3u",
        "name": "Beta M3U",
        "playlist_type": "m3u",
        "url": "http://example.com/beta.m3u",
    })
    hydrate = client.get("/me/playlists", headers=free_auth_header)

    assert save.status_code == 200, save.text
    assert hydrate.status_code == 200
    assert any(item["playlist_id"] == "beta-m3u" for item in hydrate.json())


def test_survives_reauth(client, test_user, tv_device):
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

    r2 = client.get(
        "/me/playlists/reauth-1/credentials",
        headers=_device_headers(h2, tv_device),
    )
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
    assert client.get("/me/playlists/x/credentials").status_code == 403
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
