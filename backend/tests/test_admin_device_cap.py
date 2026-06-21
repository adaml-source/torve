"""Admin account-level device cap override tests."""
from __future__ import annotations

from app.security import create_access_token


ADMIN_HEADERS = {"X-Admin-Secret": "test-admin-secret"}


def _auth(user):
    return {"Authorization": f"Bearer {create_access_token(str(user.id))}"}


def test_admin_can_set_and_clear_user_device_cap(client, db, test_user, monkeypatch):
    monkeypatch.setattr("app.config.settings.PADDLE_ADMIN_SECRET", "test-admin-secret")

    r = client.patch(
        f"/admin/users/{test_user.id}/device-cap",
        headers=ADMIN_HEADERS,
        json={"device_cap_override": 20},
    )
    assert r.status_code == 200, r.text
    assert r.json()["device_cap_override"] == 20
    assert r.json()["device_limit"] == 20

    db.refresh(test_user)
    assert test_user.device_cap_override == 20

    state = client.get("/me/access-state", headers=_auth(test_user))
    assert state.status_code == 200
    assert state.json()["device_cap_override"] is None
    assert state.json()["device_limit"] is None

    r = client.patch(
        f"/admin/users/{test_user.id}/device-cap",
        headers=ADMIN_HEADERS,
        json={"device_cap_override": None},
    )
    assert r.status_code == 200, r.text
    assert r.json()["device_cap_override"] is None

    db.refresh(test_user)
    assert test_user.device_cap_override is None


def test_admin_device_cap_rejects_invalid_values(client, test_user, monkeypatch):
    monkeypatch.setattr("app.config.settings.PADDLE_ADMIN_SECRET", "test-admin-secret")

    r = client.patch(
        f"/admin/users/{test_user.id}/device-cap",
        headers=ADMIN_HEADERS,
        json={"device_cap_override": 0},
    )
    assert r.status_code == 422


def test_admin_device_cap_requires_admin_secret(client, test_user):
    r = client.patch(
        f"/admin/users/{test_user.id}/device-cap",
        headers={"X-Admin-Secret": "wrong"},
        json={"device_cap_override": 20},
    )
    assert r.status_code in (403, 503)
