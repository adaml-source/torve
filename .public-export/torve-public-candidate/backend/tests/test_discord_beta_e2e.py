import json
import logging
import uuid
from datetime import datetime, timedelta, timezone

import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from fastapi.testclient import TestClient
from sqlalchemy.orm import sessionmaker

from app import discord_beta as beta
from app.beta_campaign import beta_free_access_end_at
from app.billing import grant_entitlement
from app.config import settings
from app.deps import get_db
from app.main import app
from app.models import (
    BetaAccessGrant,
    DiscordAccountLink,
    DiscordBetaApplication,
    DiscordBetaApplicationDraft,
    DiscordBetaLinkCode,
    User,
    UserEntitlement,
)
from app.rate_limits import reset_rate_limits_for_tests
from app.security import create_access_token, hash_password


E2E_DISCORD_PREFIX = "991000"
E2E_EMAIL_PREFIX = "discord-beta-e2e"


class DiscordSigner:
    def __init__(self, private_key: Ed25519PrivateKey):
        self.private_key = private_key
        self.public_key_hex = private_key.public_key().public_bytes(
            encoding=serialization.Encoding.Raw,
            format=serialization.PublicFormat.Raw,
        ).hex()

    def body_and_headers(self, payload: dict) -> tuple[bytes, dict[str, str]]:
        body = json.dumps(payload, separators=(",", ":"), sort_keys=True).encode("utf-8")
        timestamp = str(int(beta.utcnow().timestamp()))
        signature = self.private_key.sign(timestamp.encode("utf-8") + body).hex()
        return body, {
            "content-type": "application/json",
            "x-signature-ed25519": signature,
            "x-signature-timestamp": timestamp,
        }

    def post(self, client: TestClient, payload: dict):
        body, headers = self.body_and_headers(payload)
        return client.post("/discord/interactions", content=body, headers=headers)


class FakeDiscord:
    def __init__(self, *, add_ok: bool = True, remove_ok: bool = True, dm_ok: bool = True):
        self.add_ok = add_ok
        self.remove_ok = remove_ok
        self.dm_ok = dm_ok
        self.added_roles: list[str] = []
        self.removed_roles: list[str] = []
        self.staff_reviews: list[dict] = []
        self.dms: list[tuple[str, str]] = []

    def add_beta_role(self, discord_user_id):
        self.added_roles.append(discord_user_id)
        return beta.DiscordApiResult(ok=self.add_ok, action="add_role", status_code=204 if self.add_ok else 500)

    def remove_beta_role(self, discord_user_id):
        self.removed_roles.append(discord_user_id)
        return beta.DiscordApiResult(ok=self.remove_ok, action="remove_role", status_code=204 if self.remove_ok else 500)

    def post_staff_review(self, application):
        payload = beta.build_staff_review_payload(application)
        self.staff_reviews.append({"application_id": str(application.id), "payload": payload})
        return beta.DiscordApiResult(ok=True, action="post_message", status_code=200, message_id=f"{E2E_DISCORD_PREFIX}999")

    def dm_user(self, discord_user_id, content):
        self.dms.append((discord_user_id, content))
        return beta.DiscordApiResult(ok=self.dm_ok, action="dm", status_code=200 if self.dm_ok else 500)


class DbProxy:
    def __init__(self, db):
        self._db = db

    def __getattr__(self, name):
        return getattr(self._db, name)

    def close(self):
        pass


@pytest.fixture(autouse=True)
def _discord_beta_e2e_cleanup(db, monkeypatch):
    reset_rate_limits_for_tests()
    _cleanup_e2e_rows(db)
    now = beta.utcnow().replace(microsecond=0)
    monkeypatch.setattr(settings, "BETA_SIGNUP_CLOSE_AT", (now + timedelta(days=30)).isoformat())
    monkeypatch.setattr(settings, "BETA_FREE_ACCESS_END_AT", (now + timedelta(days=60)).isoformat())
    monkeypatch.setattr(settings, "DISCORD_BETA_AUTO_APPROVE", False)
    yield
    _cleanup_e2e_rows(db)


@pytest.fixture
def e2e_client(db):
    TestSession = sessionmaker(autocommit=False, autoflush=False, bind=db.get_bind())

    def _override_db():
        session = TestSession()
        try:
            yield session
        finally:
            session.close()

    app.dependency_overrides[get_db] = _override_db
    with TestClient(app) as client:
        yield client
    app.dependency_overrides.clear()


@pytest.fixture
def discord_signer(monkeypatch):
    signer = DiscordSigner(Ed25519PrivateKey.generate())
    monkeypatch.setattr(settings, "DISCORD_PUBLIC_KEY", signer.public_key_hex)
    return signer


@pytest.fixture
def fake_discord(monkeypatch):
    fake = FakeDiscord()
    monkeypatch.setattr(beta.DiscordBotClient, "from_settings", classmethod(lambda cls: fake))
    return fake


def _cleanup_e2e_rows(db):
    user_ids = [
        row[0]
        for row in db.query(User.id)
        .filter(User.email.like(f"{E2E_EMAIL_PREFIX}-%@test.com"))
        .all()
    ]
    if user_ids:
        db.query(BetaAccessGrant).filter(BetaAccessGrant.torve_user_id.in_(user_ids)).delete(synchronize_session=False)
        db.query(DiscordAccountLink).filter(DiscordAccountLink.torve_user_id.in_(user_ids)).delete(synchronize_session=False)
        db.query(DiscordBetaApplication).filter(DiscordBetaApplication.torve_user_id.in_(user_ids)).delete(synchronize_session=False)
        db.query(DiscordBetaLinkCode).filter(DiscordBetaLinkCode.torve_user_id.in_(user_ids)).delete(synchronize_session=False)
        db.query(UserEntitlement).filter(UserEntitlement.user_id.in_(user_ids)).delete(synchronize_session=False)
        db.query(User).filter(User.id.in_(user_ids)).delete(synchronize_session=False)
    db.query(DiscordBetaApplicationDraft).filter(
        DiscordBetaApplicationDraft.discord_user_id.like(f"{E2E_DISCORD_PREFIX}%")
    ).delete(synchronize_session=False)
    db.commit()


def _set_campaign_window(monkeypatch, *, signup_close_at: datetime, free_access_end_at: datetime):
    monkeypatch.setattr(settings, "BETA_SIGNUP_CLOSE_AT", signup_close_at.isoformat())
    monkeypatch.setattr(settings, "BETA_FREE_ACCESS_END_AT", free_access_end_at.isoformat())


def _auth_header(user: User) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(str(user.id))}"}


def _make_user(db, *, verified: bool = True, premium: bool = False) -> User:
    user = User(
        id=uuid.uuid4(),
        email=f"{E2E_EMAIL_PREFIX}-{uuid.uuid4().hex[:10]}@test.com",
        password_hash=hash_password("TestPass123!"),
        display_name="Discord Beta E2E",
        is_active=True,
        is_verified=verified,
        has_lifetime_access=False,
        has_premium_access=False,
    )
    db.add(user)
    db.flush()
    if premium:
        grant_entitlement(
            db,
            user.id,
            source="admin_grant",
            source_ref=f"discord-beta-e2e-{user.id}",
            entitlement_type="lifetime_access",
        )
        user.has_lifetime_access = True
        user.has_premium_access = True
    db.commit()
    db.refresh(user)
    return user


def _discord_user(suffix: str = "001") -> dict:
    return {
        "id": f"{E2E_DISCORD_PREFIX}{suffix.zfill(12)}",
        "username": f"e2e-tester-{suffix}",
        "global_name": f"E2E Tester {suffix}",
    }


def _apply_payload(discord_user: dict) -> dict:
    return {
        "type": 3,
        "member": {"user": discord_user},
        "data": {"custom_id": beta.APPLICATION_BUTTON_CUSTOM_ID},
    }


def _component_payload(discord_user: dict, custom_id: str, values: list[str]) -> dict:
    return {
        "type": 3,
        "member": {"user": discord_user},
        "data": {"custom_id": custom_id, "values": values},
    }


def _modal_payload(
    discord_user: dict,
    flow_id: str,
    code: str,
    *,
    notes: str = "E2E can test playback, recordings, and setup.",
    confirmation: str = "I UNDERSTAND",
) -> dict:
    return {
        "type": 5,
        "member": {"user": discord_user},
        "data": {
            "custom_id": f"{beta.APPLICATION_MODAL_CUSTOM_ID}:{flow_id}",
            "components": [
                {"components": [{"custom_id": beta.FIELD_LINK_CODE, "value": code}]},
                {"components": [{"custom_id": beta.FIELD_MOTIVATION, "value": notes}]},
                {"components": [{"custom_id": beta.FIELD_CONFIRMATION, "value": confirmation}]},
            ],
        },
    }


def _staff_component_payload(custom_id: str, *, reviewer_id: str | None = None) -> dict:
    return {
        "type": 3,
        "member": {
            "permissions": str(1 << 28),
            "user": {"id": reviewer_id or f"{E2E_DISCORD_PREFIX}999999999", "username": "e2e-reviewer"},
        },
        "data": {"custom_id": custom_id},
    }


def _reject_modal_payload(application_id: uuid.UUID, *, reason: str = "Not a match for this round.") -> dict:
    return {
        "type": 5,
        "member": {
            "permissions": str(1 << 28),
            "user": {"id": f"{E2E_DISCORD_PREFIX}999999999", "username": "e2e-reviewer"},
        },
        "data": {
            "custom_id": f"{beta.REJECT_MODAL_PREFIX}{application_id}",
            "components": [
                {"components": [{"custom_id": beta.FIELD_REJECTION_REASON, "value": reason}]},
            ],
        },
    }


def _select_custom_id(response_json: dict) -> str:
    return response_json["data"]["components"][0]["components"][0]["custom_id"]


def _flow_id_from_select(custom_id: str, prefix: str) -> str:
    assert custom_id.startswith(prefix)
    return custom_id[len(prefix):]


def _create_link_code(client: TestClient, user: User) -> str:
    response = client.post("/me/beta/discord-link-code", headers=_auth_header(user))
    assert response.status_code == 200
    return response.json()["code"]


def _submit_application_via_discord(
    client: TestClient,
    signer: DiscordSigner,
    *,
    code: str,
    discord_user: dict,
    devices: list[str] | None = None,
    features: list[str] | None = None,
    stability: str = "unstable_ok",
) -> dict:
    apply_response = signer.post(client, _apply_payload(discord_user))
    assert apply_response.status_code == 200
    device_custom_id = _select_custom_id(apply_response.json())
    flow_id = _flow_id_from_select(device_custom_id, beta.DEVICE_SELECT_PREFIX)

    device_response = signer.post(
        client,
        _component_payload(discord_user, device_custom_id, devices or ["fire_tv", "windows"]),
    )
    assert device_response.status_code == 200
    feature_custom_id = _select_custom_id(device_response.json())

    feature_response = signer.post(
        client,
        _component_payload(discord_user, feature_custom_id, features or ["playback", "iptv_epg", "recordings"]),
    )
    assert feature_response.status_code == 200
    stability_custom_id = _select_custom_id(feature_response.json())

    stability_response = signer.post(
        client,
        _component_payload(discord_user, stability_custom_id, [stability]),
    )
    assert stability_response.status_code == 200
    modal_custom_id = stability_response.json()["data"]["custom_id"]
    flow_id = modal_custom_id.split(":", 1)[1]

    modal_response = signer.post(client, _modal_payload(discord_user, flow_id, code))
    assert modal_response.status_code == 200
    return {"flow_id": flow_id, "response": modal_response.json()}


def _seed_submitted_application(db, user: User, *, discord_user: dict | None = None) -> DiscordBetaApplication:
    discord_user = discord_user or _discord_user("777")
    application = DiscordBetaApplication(
        torve_user_id=user.id,
        discord_user_id=discord_user["id"],
        discord_username=discord_user["username"],
        status="submitted",
        devices_json=["fire_tv", "windows"],
        integrations_json=["playback", "iptv_epg"],
        stability_preference="unstable_ok",
        motivation="Seeded e2e application.",
        accepted_beta_terms=True,
        accepted_no_credentials=True,
        created_at=beta.utcnow(),
        updated_at=beta.utcnow(),
    )
    db.add(application)
    db.commit()
    db.refresh(application)
    return application


def _grant_for_user(db, user: User) -> BetaAccessGrant:
    grant = db.query(BetaAccessGrant).filter(BetaAccessGrant.torve_user_id == user.id).one()
    db.refresh(grant)
    return grant


def test_discord_interactions_signature_validation(e2e_client, discord_signer):
    unsigned = e2e_client.post("/discord/interactions", json={"type": 1})
    assert unsigned.status_code == 401

    body = b'{"type":1}'
    invalid = e2e_client.post(
        "/discord/interactions",
        content=body,
        headers={
            "content-type": "application/json",
            "x-signature-ed25519": "00" * 64,
            "x-signature-timestamp": "1",
        },
    )
    assert invalid.status_code == 401

    valid = discord_signer.post(e2e_client, {"type": 1})
    assert valid.status_code == 200
    assert valid.json() == {"type": 1}


def test_e2e_verified_non_premium_happy_path(e2e_client, db, discord_signer, fake_discord):
    user = _make_user(db, verified=True, premium=False)
    code = _create_link_code(e2e_client, user)
    row = db.query(DiscordBetaLinkCode).filter(DiscordBetaLinkCode.torve_user_id == user.id).one()
    assert row.code_hash != code

    discord_user = _discord_user("101")
    result = _submit_application_via_discord(e2e_client, discord_signer, code=code, discord_user=discord_user)
    assert result["response"]["data"]["content"] == beta.APPLICATION_SUBMITTED_MESSAGE
    assert result["response"]["data"]["flags"] == beta.EPHEMERAL_MESSAGE_FLAG
    assert result["response"]["data"]["components"] == []
    assert fake_discord.staff_reviews

    application = db.query(DiscordBetaApplication).filter(DiscordBetaApplication.torve_user_id == user.id).one()
    assert application.devices_json == ["fire_tv", "windows"]
    assert application.integrations_json == ["playback", "iptv_epg", "recordings"]
    assert application.stability_preference == "unstable_ok"

    approve = discord_signer.post(
        e2e_client,
        _staff_component_payload(f"{beta.APPROVE_BUTTON_PREFIX}{application.id}"),
    )
    assert approve.status_code == 200
    grant = _grant_for_user(db, user)
    assert grant.status == "active"
    assert grant.expires_at <= beta_free_access_end_at()
    assert fake_discord.added_roles == [discord_user["id"]]

    status = e2e_client.get("/me/beta/status", headers=_auth_header(user)).json()
    access_state = e2e_client.get("/me/access-state", headers=_auth_header(user)).json()
    assert status["beta_application_status"] == "approved"
    assert status["beta_access_active"] is True
    assert access_state["beta_access"]["active"] is True
    assert access_state["beta_access"]["source"] == "discord_beta"
    assert access_state["has_premium_access"] is True
    assert access_state["access_tier"] == "free"


def test_e2e_unverified_email_blocked(e2e_client, db):
    user = _make_user(db, verified=False)

    response = e2e_client.post("/me/beta/discord-link-code", headers=_auth_header(user))
    status = e2e_client.get("/me/beta/status", headers=_auth_header(user))

    assert response.status_code == 403
    assert response.json()["detail"]["error_code"] == "email_not_verified"
    assert db.query(DiscordBetaLinkCode).filter(DiscordBetaLinkCode.torve_user_id == user.id).count() == 0
    assert status.json()["can_apply"] is False
    assert status.json()["blocked_reason"] == "email_not_verified"


def test_e2e_free_premium_signup_closed_still_allows_discord_beta_opt_in(
    e2e_client,
    db,
    discord_signer,
    monkeypatch,
    fake_discord,
):
    user = _make_user(db, verified=True)
    _create_link_code(e2e_client, user)
    now = beta.utcnow().replace(microsecond=0)
    _set_campaign_window(
        monkeypatch,
        signup_close_at=now - timedelta(seconds=1),
        free_access_end_at=now + timedelta(days=10),
    )

    closed = e2e_client.post("/me/beta/discord-link-code", headers=_auth_header(user))
    assert closed.status_code == 200, closed.text
    assert db.query(DiscordBetaLinkCode).filter(DiscordBetaLinkCode.torve_user_id == user.id).count() == 2

    discord_user = _discord_user("102")
    code = closed.json()["code"]
    result = _submit_application_via_discord(e2e_client, discord_signer, code=code, discord_user=discord_user)
    assert result["response"]["data"]["content"] == beta.APPLICATION_SUBMITTED_MESSAGE
    assert db.query(DiscordBetaLinkCode).filter(
        DiscordBetaLinkCode.torve_user_id == user.id,
        DiscordBetaLinkCode.consumed_by_discord_user_id == discord_user["id"],
    ).count() == 1
    application = db.query(DiscordBetaApplication).filter(DiscordBetaApplication.torve_user_id == user.id).one()
    assert application.status == "submitted"
    assert fake_discord.staff_reviews


def test_e2e_free_access_ended_approval_is_discord_only_and_cleanup_expires_old_grants(
    e2e_client,
    db,
    discord_signer,
    fake_discord,
    monkeypatch,
):
    user = _make_user(db, verified=True)
    application = _seed_submitted_application(db, user, discord_user=_discord_user("103"))
    now = beta.utcnow().replace(microsecond=0)
    _set_campaign_window(
        monkeypatch,
        signup_close_at=now + timedelta(days=1),
        free_access_end_at=now - timedelta(seconds=1),
    )

    approved = discord_signer.post(
        e2e_client,
        _staff_component_payload(f"{beta.APPROVE_BUTTON_PREFIX}{application.id}"),
    )
    assert approved.status_code == 200
    assert approved.json()["type"] == 7
    assert db.query(BetaAccessGrant).filter(BetaAccessGrant.torve_user_id == user.id).count() == 0
    db.refresh(application)
    assert application.status == "approved"
    assert fake_discord.added_roles == [application.discord_user_id]
    assert e2e_client.get("/me/access-state", headers=_auth_header(user)).json()["beta_access"]["active"] is False

    grant = BetaAccessGrant(
        torve_user_id=user.id,
        discord_user_id=application.discord_user_id,
        source="discord_beta",
        status="active",
        starts_at=now - timedelta(days=1),
        expires_at=now + timedelta(days=10),
        created_at=now,
        updated_at=now,
    )
    application.status = "approved"
    db.add(grant)
    db.commit()
    assert e2e_client.get("/me/access-state", headers=_auth_header(user)).json()["beta_access"]["active"] is False

    result = beta.expire_due_beta_grants(db, discord=fake_discord, torve_user_ids=[user.id])
    db.commit()
    assert result["expired"] == 1
    assert fake_discord.removed_roles == [application.discord_user_id]
    assert grant.status == "expired"


def test_e2e_grant_expiry_is_capped_near_campaign_end(e2e_client, db, discord_signer, monkeypatch):
    user = _make_user(db, verified=True)
    application = _seed_submitted_application(db, user, discord_user=_discord_user("104"))
    now = datetime(2026, 7, 25, 12, 0, tzinfo=timezone.utc)
    end = datetime(2026, 7, 31, 21, 59, 59, tzinfo=timezone.utc)
    monkeypatch.setattr(beta, "utcnow", lambda: now)
    _set_campaign_window(
        monkeypatch,
        signup_close_at=datetime(2026, 7, 1, 21, 59, 59, tzinfo=timezone.utc),
        free_access_end_at=end,
    )

    response = discord_signer.post(
        e2e_client,
        _staff_component_payload(f"{beta.APPROVE_BUTTON_PREFIX}{application.id}"),
    )

    assert response.status_code == 200
    assert _grant_for_user(db, user).expires_at == end


def test_e2e_rejection_flow(e2e_client, db, discord_signer, fake_discord):
    user = _make_user(db, verified=True)
    code = _create_link_code(e2e_client, user)
    _submit_application_via_discord(e2e_client, discord_signer, code=code, discord_user=_discord_user("105"))
    application = db.query(DiscordBetaApplication).filter(DiscordBetaApplication.torve_user_id == user.id).one()

    reject_button = discord_signer.post(
        e2e_client,
        _staff_component_payload(f"{beta.REJECT_BUTTON_PREFIX}{application.id}"),
    )
    assert reject_button.status_code == 200
    assert reject_button.json()["type"] == 9

    reject_modal = discord_signer.post(e2e_client, _reject_modal_payload(application.id))
    db.refresh(application)
    status = e2e_client.get("/me/beta/status", headers=_auth_header(user)).json()
    assert reject_modal.status_code == 200
    assert application.status == "rejected"
    assert status["beta_application_status"] == "rejected"
    assert db.query(BetaAccessGrant).filter(BetaAccessGrant.torve_user_id == user.id).count() == 0
    assert fake_discord.dms


def test_e2e_draft_expiry_returns_sanitized_copy(e2e_client, db, discord_signer, monkeypatch):
    user = _make_user(db, verified=True)
    code = _create_link_code(e2e_client, user)
    discord_user = _discord_user("106")
    apply_response = discord_signer.post(e2e_client, _apply_payload(discord_user))
    device_custom_id = _select_custom_id(apply_response.json())
    flow_id = _flow_id_from_select(device_custom_id, beta.DEVICE_SELECT_PREFIX)
    devices = discord_signer.post(
        e2e_client,
        _component_payload(discord_user, device_custom_id, ["fire_tv", "windows"]),
    )
    assert devices.status_code == 200
    future = beta.utcnow() + timedelta(minutes=16)
    monkeypatch.setattr(beta, "utcnow", lambda: future)

    expired = discord_signer.post(
        e2e_client,
        _component_payload(discord_user, _select_custom_id(devices.json()), ["playback"]),
    )

    assert expired.status_code == 200
    assert expired.json()["data"]["content"] == "Your beta application session expired. Please click Apply for Beta again."
    assert db.query(DiscordBetaApplication).filter(DiscordBetaApplication.torve_user_id == user.id).count() == 0
    assert db.query(DiscordBetaLinkCode).filter(DiscordBetaLinkCode.torve_user_id == user.id).one().consumed_at is None


def test_e2e_multi_worker_session_safety(e2e_client, db, discord_signer):
    user = _make_user(db, verified=True)
    code = _create_link_code(e2e_client, user)
    discord_user = _discord_user("107")

    result = _submit_application_via_discord(e2e_client, discord_signer, code=code, discord_user=discord_user)

    assert result["response"]["data"]["content"] == beta.APPLICATION_SUBMITTED_MESSAGE
    application = db.query(DiscordBetaApplication).filter(DiscordBetaApplication.torve_user_id == user.id).one()
    assert application.devices_json == ["fire_tv", "windows"]
    assert application.integrations_json == ["playback", "iptv_epg", "recordings"]


def test_e2e_duplicate_modal_and_approve_are_idempotent(e2e_client, db, discord_signer):
    user = _make_user(db, verified=True)
    code = _create_link_code(e2e_client, user)
    discord_user = _discord_user("108")
    result = _submit_application_via_discord(e2e_client, discord_signer, code=code, discord_user=discord_user)
    duplicate_modal = discord_signer.post(e2e_client, _modal_payload(discord_user, result["flow_id"], code))
    assert duplicate_modal.status_code == 200
    assert "expired" in duplicate_modal.json()["data"]["content"].lower()
    assert db.query(DiscordBetaApplication).filter(DiscordBetaApplication.torve_user_id == user.id).count() == 1

    application = db.query(DiscordBetaApplication).filter(DiscordBetaApplication.torve_user_id == user.id).one()
    first = discord_signer.post(e2e_client, _staff_component_payload(f"{beta.APPROVE_BUTTON_PREFIX}{application.id}"))
    second = discord_signer.post(e2e_client, _staff_component_payload(f"{beta.APPROVE_BUTTON_PREFIX}{application.id}"))
    assert first.status_code == 200
    assert second.status_code == 200
    assert db.query(BetaAccessGrant).filter(BetaAccessGrant.torve_user_id == user.id).count() == 1


def test_e2e_paid_premium_user_beta_does_not_override_paid_access(e2e_client, db, discord_signer, monkeypatch):
    user = _make_user(db, verified=True, premium=True)
    code = _create_link_code(e2e_client, user)
    _submit_application_via_discord(e2e_client, discord_signer, code=code, discord_user=_discord_user("109"))
    application = db.query(DiscordBetaApplication).filter(DiscordBetaApplication.torve_user_id == user.id).one()
    approved = discord_signer.post(e2e_client, _staff_component_payload(f"{beta.APPROVE_BUTTON_PREFIX}{application.id}"))
    assert approved.status_code == 200
    access = e2e_client.get("/me/access-state", headers=_auth_header(user)).json()
    assert access["has_premium_access"] is True
    assert access["access_tier"] == "free"
    assert access["beta_access"]["active"] is True

    now = beta.utcnow().replace(microsecond=0)
    _set_campaign_window(
        monkeypatch,
        signup_close_at=now + timedelta(days=1),
        free_access_end_at=now - timedelta(seconds=1),
    )
    ended_access = e2e_client.get("/me/access-state", headers=_auth_header(user)).json()
    assert ended_access["has_premium_access"] is True
    assert ended_access["access_tier"] == "free"
    assert ended_access["beta_access"]["active"] is False


@pytest.mark.parametrize(
    ("step", "bad_value", "expected_text"),
    [
        ("device", "sideways_toaster", "device"),
        ("feature", "raw_provider_name", "beta area"),
        ("stability", "chaos_channel", "stability"),
    ],
)
def test_e2e_controlled_values_reject_unknowns(e2e_client, db, discord_signer, step, bad_value, expected_text):
    discord_user = _discord_user(f"11{len(step)}")
    apply_response = discord_signer.post(e2e_client, _apply_payload(discord_user))
    device_custom_id = _select_custom_id(apply_response.json())

    if step == "device":
        response = discord_signer.post(
            e2e_client,
            _component_payload(discord_user, device_custom_id, ["fire_tv", bad_value]),
        )
    else:
        device_response = discord_signer.post(
            e2e_client,
            _component_payload(discord_user, device_custom_id, ["fire_tv"]),
        )
        feature_custom_id = _select_custom_id(device_response.json())
        if step == "feature":
            response = discord_signer.post(
                e2e_client,
                _component_payload(discord_user, feature_custom_id, ["playback", bad_value]),
            )
        else:
            feature_response = discord_signer.post(
                e2e_client,
                _component_payload(discord_user, feature_custom_id, ["playback"]),
            )
            response = discord_signer.post(
                e2e_client,
                _component_payload(discord_user, _select_custom_id(feature_response.json()), [bad_value]),
            )

    assert response.status_code == 200
    assert expected_text in response.json()["data"]["content"].lower()
    assert db.query(DiscordBetaApplication).filter(DiscordBetaApplication.discord_user_id == discord_user["id"]).count() == 0


def test_e2e_stats_command_outputs_counts_without_secrets(db, monkeypatch, capsys):
    user = _make_user(db, verified=True)
    application = _seed_submitted_application(db, user, discord_user=_discord_user("120"))
    beta.approve_beta_application(db, application_id=application.id, reviewer_discord_user_id=f"{E2E_DISCORD_PREFIX}999")
    db.commit()
    monkeypatch.setattr(beta, "SessionLocal", lambda: DbProxy(db))
    monkeypatch.setattr(settings, "DISCORD_BOT_TOKEN", "e2e-raw-bot-token")

    code = beta.main(["stats"])
    captured = capsys.readouterr()
    stats = json.loads(captured.out)

    assert code == 0
    assert stats["applications_by_status"]["approved"] >= 1
    assert stats["selected_devices"]["fire_tv"] >= 1
    assert stats["selected_features_integrations"]["playback"] >= 1
    assert "e2e-raw-bot-token" not in captured.out
    assert "password" not in captured.out.lower()
    assert "secret" not in captured.out.lower()


def test_e2e_expiry_callable_expires_grants_and_removes_role(db, fake_discord):
    user = _make_user(db, verified=True)
    application = _seed_submitted_application(db, user, discord_user=_discord_user("121"))
    approval = beta.approve_beta_application(db, application_id=application.id, reviewer_discord_user_id=f"{E2E_DISCORD_PREFIX}999")
    approval.grant.expires_at = beta.utcnow() - timedelta(seconds=1)
    db.commit()

    result = beta.expire_due_beta_grants(db, discord=fake_discord, torve_user_ids=[user.id])
    db.commit()
    db.refresh(application)

    assert result["expired"] == 1
    assert fake_discord.removed_roles == [application.discord_user_id]
    assert approval.grant.status == "expired"
    assert application.status == "expired"


def test_e2e_sanitization_no_secret_payloads_or_corrupt_grants(db, discord_signer, e2e_client, monkeypatch, caplog):
    fake = FakeDiscord(add_ok=False, dm_ok=False)
    monkeypatch.setattr(beta.DiscordBotClient, "from_settings", classmethod(lambda cls: fake))
    monkeypatch.setattr(settings, "DISCORD_BOT_TOKEN", "e2e-raw-bot-token")
    user_access_token = create_access_token(str(uuid.uuid4()))
    user = _make_user(db, verified=True)
    application = DiscordBetaApplication(
        torve_user_id=user.id,
        discord_user_id=_discord_user("122")["id"],
        discord_username="payload-sanitize",
        status="submitted",
        devices_json=["fire_tv", "password=hunter2", "/opt/private"],
        integrations_json=["playback", "https://example.test/list.m3u?token=abc"],
        stability_preference="unstable_ok",
        motivation="signed URL https://example.test/file?token=abc\nprivate key nope\n.env /opt/app",
        accepted_beta_terms=True,
        accepted_no_credentials=True,
        created_at=beta.utcnow(),
        updated_at=beta.utcnow(),
    )
    db.add(application)
    db.commit()
    payload_text = json.dumps(beta.build_staff_review_payload(application)).lower()
    caplog.set_level(logging.WARNING, logger="app.discord_beta")

    response = discord_signer.post(e2e_client, _staff_component_payload(f"{beta.APPROVE_BUTTON_PREFIX}{application.id}"))
    grant = _grant_for_user(db, user)
    combined = f"{payload_text}\n{caplog.text}".lower()

    assert response.status_code == 200
    assert grant.status == "active"
    assert fake.added_roles == [application.discord_user_id]
    for forbidden in [
        "e2e-raw-bot-token",
        "webhook",
        "token",
        "password",
        "secret",
        "private key",
        ".env",
        "/opt/",
        "signed url",
        "traceback",
        user_access_token.lower(),
        "code_hash",
    ]:
        assert forbidden not in combined
