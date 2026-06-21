from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest

from app import discord_beta as beta
from app.config import settings
from app.models import BetaAccessGrant, DiscordAccountLink, UserEntitlement


ADMIN_HEADERS = {"X-Admin-Secret": "test-admin-secret"}
DISCORD_ID = "992000000000001"


class FakeDiscord:
    def __init__(self):
        self.added_roles: list[str] = []
        self.removed_roles: list[str] = []

    def add_beta_role(self, discord_user_id):
        self.added_roles.append(discord_user_id)
        return beta.DiscordApiResult(ok=True, action="add_role", status_code=204)

    def remove_beta_role(self, discord_user_id):
        self.removed_roles.append(discord_user_id)
        return beta.DiscordApiResult(ok=True, action="remove_role", status_code=204)


@pytest.fixture(autouse=True)
def _cleanup_admin_beta_rows(db):
    _delete_admin_beta_rows(db)
    yield
    _delete_admin_beta_rows(db)


def _delete_admin_beta_rows(db):
    db.query(BetaAccessGrant).filter(BetaAccessGrant.discord_user_id.like("992000%")).delete(synchronize_session=False)
    db.query(DiscordAccountLink).filter(DiscordAccountLink.discord_user_id.like("992000%")).delete(synchronize_session=False)
    db.commit()


def _now() -> datetime:
    return datetime.now(timezone.utc).replace(microsecond=0)


def _create_beta_grant(db, user, *, days: int = 10, discord_user_id: str = DISCORD_ID):
    now = _now()
    grant = BetaAccessGrant(
        torve_user_id=user.id,
        discord_user_id=discord_user_id,
        source=beta.BETA_SOURCE_DISCORD,
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


def _link_discord(db, user, discord_user_id: str = DISCORD_ID):
    now = _now()
    link = DiscordAccountLink(
        torve_user_id=user.id,
        discord_user_id=discord_user_id,
        discord_username="admin-beta-test",
        linked_at=now,
        created_at=now,
        updated_at=now,
    )
    db.add(link)
    db.commit()
    return link


def _configure_beta_admin(monkeypatch, *, free_access_days: int = 60):
    now = _now()
    monkeypatch.setattr(settings, "PADDLE_ADMIN_SECRET", "test-admin-secret")
    monkeypatch.setattr(settings, "BETA_SIGNUP_CLOSE_AT", (now + timedelta(days=30)).isoformat())
    monkeypatch.setattr(settings, "BETA_FREE_ACCESS_END_AT", (now + timedelta(days=free_access_days)).isoformat())


def test_admin_user_list_and_detail_include_beta_access(client, db, free_user, monkeypatch):
    _configure_beta_admin(monkeypatch)
    grant = _create_beta_grant(db, free_user)

    list_response = client.get(
        f"/admin/users?q={free_user.email}&limit=10",
        headers=ADMIN_HEADERS,
    )
    assert list_response.status_code == 200, list_response.text
    listed_user = list_response.json()["users"][0]
    listed = listed_user["beta_access"]
    assert listed_user["has_premium_access"] is True
    assert listed_user["access_tier"] == "free"
    assert listed["active"] is True
    assert listed["status"] == "active"
    assert listed["expires_at"] == grant.expires_at.isoformat()

    premium_filter_response = client.get(
        f"/admin/users?q={free_user.email}&has_premium=true&limit=10",
        headers=ADMIN_HEADERS,
    )
    assert premium_filter_response.status_code == 200, premium_filter_response.text
    assert premium_filter_response.json()["total"] == 1

    detail_response = client.get(f"/admin/users/{free_user.id}", headers=ADMIN_HEADERS)
    assert detail_response.status_code == 200, detail_response.text
    detail = detail_response.json()
    assert detail["beta_access"]["active"] is True
    assert detail["beta_access"]["id"] == str(grant.id)
    assert detail["user"]["beta_access"]["id"] == str(grant.id)
    assert detail["user"]["has_premium_access"] is True
    assert detail["user"]["access_tier"] == "free"


def test_admin_can_create_beta_grant_without_paid_entitlement(
    client,
    db,
    free_user,
    free_auth_header,
    monkeypatch,
):
    _configure_beta_admin(monkeypatch)
    _link_discord(db, free_user)
    fake = FakeDiscord()
    monkeypatch.setattr(beta.DiscordBotClient, "from_settings", classmethod(lambda cls: fake))
    paid_entitlements_before = db.query(UserEntitlement).filter(UserEntitlement.user_id == free_user.id).count()

    response = client.post(
        f"/admin/users/{free_user.id}/beta/extend",
        headers=ADMIN_HEADERS,
        json={"duration_days": 14, "reason": "manual beta invite"},
    )

    assert response.status_code == 200, response.text
    data = response.json()
    assert data["action"] == "created"
    assert data["beta_access"]["active"] is True
    assert data["beta_access"]["source"] == beta.BETA_SOURCE_DISCORD
    assert fake.added_roles == [DISCORD_ID]
    grant = db.query(BetaAccessGrant).filter(BetaAccessGrant.torve_user_id == free_user.id).one()
    assert grant.status == "active"
    assert grant.source == beta.BETA_SOURCE_DISCORD
    paid_entitlements_after = db.query(UserEntitlement).filter(UserEntitlement.user_id == free_user.id).count()
    assert paid_entitlements_after == paid_entitlements_before

    access_state = client.get("/me/access-state", headers=free_auth_header)
    assert access_state.status_code == 200
    access_body = access_state.json()
    assert access_body["has_premium_access"] is True
    assert access_body["access_tier"] == "free"
    assert access_body["entitlement_type"] is None
    assert access_body["source"] is None
    assert access_body["beta_access"]["active"] is True
    assert access_body["beta_access"]["source"] == beta.BETA_SOURCE_DISCORD


def test_admin_extends_existing_beta_grant_and_caps_campaign_end(client, db, free_user, monkeypatch):
    _configure_beta_admin(monkeypatch, free_access_days=5)
    _link_discord(db, free_user)
    grant = _create_beta_grant(db, free_user, days=1)
    fake = FakeDiscord()
    monkeypatch.setattr(beta.DiscordBotClient, "from_settings", classmethod(lambda cls: fake))

    response = client.post(
        f"/admin/users/{free_user.id}/beta/extend",
        headers=ADMIN_HEADERS,
        json={"duration_days": 30, "reason": "extend to campaign end"},
    )

    assert response.status_code == 200, response.text
    db.refresh(grant)
    assert response.json()["action"] == "extended"
    assert grant.expires_at.isoformat() == settings.BETA_FREE_ACCESS_END_AT
    assert fake.added_roles == [DISCORD_ID]


def test_admin_can_revoke_beta_access_and_remove_role(client, db, free_user, monkeypatch):
    _configure_beta_admin(monkeypatch)
    grant = _create_beta_grant(db, free_user)
    fake = FakeDiscord()
    monkeypatch.setattr(beta.DiscordBotClient, "from_settings", classmethod(lambda cls: fake))

    response = client.post(
        f"/admin/users/{free_user.id}/beta/revoke",
        headers=ADMIN_HEADERS,
        json={"reason": "no longer eligible"},
    )

    assert response.status_code == 200, response.text
    db.refresh(grant)
    assert grant.status == "revoked"
    assert grant.revoked_at is not None
    assert response.json()["beta_access"]["status"] == "revoked"
    assert fake.removed_roles == [DISCORD_ID]


def test_admin_beta_mutations_require_admin_secret(client, free_user, monkeypatch):
    _configure_beta_admin(monkeypatch)

    response = client.post(
        f"/admin/users/{free_user.id}/beta/extend",
        headers={"X-Admin-Secret": "wrong"},
        json={"duration_days": 14},
    )

    assert response.status_code == 403
