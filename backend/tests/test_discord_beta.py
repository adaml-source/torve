import logging
import uuid
from datetime import timedelta

import httpx
import pytest
from sqlalchemy.orm import sessionmaker

from app import discord_beta as beta
from app.config import Settings
from app.models import (
    BetaAccessGrant,
    DiscordAccountLink,
    DiscordBetaApplication,
    DiscordBetaApplicationDraft,
    DiscordBetaLinkCode,
    User,
)
from app.rate_limits import reset_rate_limits_for_tests
from app.security import create_access_token, hash_password


class FakeDiscord:
    def __init__(self, *, add_ok=True, remove_ok=True):
        self.add_ok = add_ok
        self.remove_ok = remove_ok
        self.added_roles = []
        self.removed_roles = []
        self.staff_reviews = []
        self.dms = []

    def add_beta_role(self, discord_user_id):
        self.added_roles.append(discord_user_id)
        return beta.DiscordApiResult(ok=self.add_ok, action="add_role", status_code=204 if self.add_ok else 500)

    def remove_beta_role(self, discord_user_id):
        self.removed_roles.append(discord_user_id)
        return beta.DiscordApiResult(ok=self.remove_ok, action="remove_role", status_code=204 if self.remove_ok else 500)

    def post_staff_review(self, application):
        self.staff_reviews.append(str(application.id))
        return beta.DiscordApiResult(ok=True, action="post_message", status_code=200, message_id="990000000000001")

    def dm_user(self, discord_user_id, content):
        self.dms.append((discord_user_id, content))
        return beta.DiscordApiResult(ok=True, action="dm", status_code=200)


class FakeCliDb:
    def commit(self):
        pass

    def rollback(self):
        pass

    def close(self):
        pass


class FakePublishDiscord:
    def __init__(self, result):
        self.result = result
        self.message_ids = []

    def publish_application_message(self, *, message_id=None):
        self.message_ids.append(message_id)
        return self.result


@pytest.fixture(autouse=True)
def _beta_test_cleanup(db):
    reset_rate_limits_for_tests()
    yield
    db.query(BetaAccessGrant).filter(BetaAccessGrant.discord_user_id.like("990000%")).delete(synchronize_session=False)
    db.query(DiscordAccountLink).filter(DiscordAccountLink.discord_user_id.like("990000%")).delete(synchronize_session=False)
    db.query(DiscordBetaApplication).filter(DiscordBetaApplication.discord_user_id.like("990000%")).delete(synchronize_session=False)
    db.query(DiscordBetaApplicationDraft).filter(DiscordBetaApplicationDraft.discord_user_id.like("990000%")).delete(synchronize_session=False)
    db.query(DiscordBetaLinkCode).filter(DiscordBetaLinkCode.consumed_by_discord_user_id.like("990000%")).delete(synchronize_session=False)
    db.commit()


def _discord_user(suffix="1"):
    return beta.DiscordUserIdentity(
        user_id=f"99000000000000{suffix}",
        username=f"tester-{suffix}",
        discriminator_or_global_name=f"Tester {suffix}",
    )


def _flow_for_user(
    db,
    discord_user=None,
    *,
    devices=None,
    features=None,
    stability_preference="unstable_ok",
):
    discord_user = discord_user or _discord_user()
    flow_id = beta.create_application_flow(db, discord_user)
    beta.update_application_flow(
        db,
        flow_id,
        discord_user.user_id,
        devices=devices or ["fire_tv", "windows"],
        features=features or ["playback", "iptv_epg"],
        stability_preference=stability_preference,
    )
    return flow_id


def _final_modal_payload(code, *, flow_id, discord_user=None, motivation="I can test setup and playback regressions."):
    discord_user = discord_user or _discord_user()
    return {
        "type": 5,
        "member": {
            "user": {
                "id": discord_user.user_id,
                "username": discord_user.username,
                "global_name": discord_user.discriminator_or_global_name,
            }
        },
        "data": {
            "custom_id": f"{beta.APPLICATION_MODAL_CUSTOM_ID}:{flow_id}",
            "components": [
                {"components": [{"custom_id": beta.FIELD_LINK_CODE, "value": code}]},
                {"components": [{"custom_id": beta.FIELD_MOTIVATION, "value": motivation}]},
                {"components": [{"custom_id": beta.FIELD_CONFIRMATION, "value": "I UNDERSTAND"}]},
            ],
        },
    }


def _staff_payload(custom_id, reviewer_id="990000000099999"):
    return {
        "type": 3,
        "member": {
            "permissions": str(1 << 28),
            "user": {"id": reviewer_id, "username": "reviewer"},
        },
        "data": {"custom_id": custom_id},
    }


def _component_payload(custom_id, values, *, discord_user=None):
    discord_user = discord_user or _discord_user()
    return {
        "type": 3,
        "member": {
            "user": {
                "id": discord_user.user_id,
                "username": discord_user.username,
                "global_name": discord_user.discriminator_or_global_name,
            }
        },
        "data": {
            "custom_id": custom_id,
            "values": values,
        },
    }


def _response_select_custom_id(response):
    return response["data"]["components"][0]["components"][0]["custom_id"]


def _create_submitted_application(db, user, *, discord_user=None):
    discord_user = discord_user or _discord_user()
    code = beta.create_beta_link_code(db, user.id).code
    submit = beta.submit_beta_application(
        db,
        link_code=code,
        discord_user=discord_user,
        devices=["fire_tv", "windows"],
        integrations=["playback", "iptv_epg"],
        stability_preference="unstable_ok",
        motivation="I can test startup and playback.",
        confirmation_text="I UNDERSTAND",
    )
    db.commit()
    return submit.application


def _auth_header(user):
    return {"Authorization": f"Bearer {create_access_token(str(user.id))}"}


def _make_unverified_user(db):
    user = User(
        email=f"discord-beta-unverified-{uuid.uuid4().hex[:8]}@test.com",
        password_hash=hash_password("TestPass123!"),
        display_name="Unverified Beta User",
        is_active=True,
        is_verified=False,
        has_lifetime_access=False,
        has_premium_access=False,
    )
    db.add(user)
    db.commit()
    db.refresh(user)
    return user


def _set_campaign_window(monkeypatch, *, signup_close_delta, free_access_end_delta):
    now = beta.utcnow().replace(microsecond=0)
    signup_close_at = now + signup_close_delta
    free_access_end_at = now + free_access_end_delta
    monkeypatch.setattr(beta.settings, "BETA_SIGNUP_CLOSE_AT", signup_close_at.isoformat())
    monkeypatch.setattr(beta.settings, "BETA_FREE_ACCESS_END_AT", free_access_end_at.isoformat())
    return signup_close_at, free_access_end_at


def test_apply_start_creates_database_draft(db):
    discord_user = _discord_user("21")
    payload = {
        "type": 3,
        "member": {
            "user": {
                "id": discord_user.user_id,
                "username": discord_user.username,
                "global_name": discord_user.discriminator_or_global_name,
            }
        },
        "data": {"custom_id": beta.APPLICATION_BUTTON_CUSTOM_ID},
    }

    response = beta.handle_application_button(db, payload)

    custom_id = _response_select_custom_id(response)
    draft_id = custom_id.removeprefix(beta.DEVICE_SELECT_PREFIX)
    draft = db.query(DiscordBetaApplicationDraft).filter(DiscordBetaApplicationDraft.id == uuid.UUID(draft_id)).one()
    assert response["type"] == 4
    assert response["data"]["flags"] == beta.EPHEMERAL_MESSAGE_FLAG
    assert draft.discord_user_id == discord_user.user_id
    assert draft.selected_devices_json == []
    assert draft.consumed_at is None


def test_device_selection_is_persisted(db):
    discord_user = _discord_user("22")
    flow_id = beta.create_application_flow(db, discord_user)
    payload = _component_payload(
        f"{beta.DEVICE_SELECT_PREFIX}{flow_id}",
        ["fire_tv", "windows"],
        discord_user=discord_user,
    )

    response = beta.handle_device_select_interaction(db, payload, f"{beta.DEVICE_SELECT_PREFIX}{flow_id}")

    draft = db.query(DiscordBetaApplicationDraft).filter(DiscordBetaApplicationDraft.id == uuid.UUID(flow_id)).one()
    assert response["type"] == 7
    assert response["data"]["flags"] == beta.EPHEMERAL_MESSAGE_FLAG
    assert draft.selected_devices_json == ["fire_tv", "windows"]
    assert draft.current_step == "features"


def test_integration_selection_is_persisted(db):
    discord_user = _discord_user("23")
    flow_id = beta.create_application_flow(db, discord_user)
    beta.update_application_flow(db, flow_id, discord_user.user_id, devices=["fire_tv"])
    payload = _component_payload(
        f"{beta.FEATURE_SELECT_PREFIX}{flow_id}",
        ["playback", "iptv_epg"],
        discord_user=discord_user,
    )

    response = beta.handle_feature_select_interaction(db, payload, f"{beta.FEATURE_SELECT_PREFIX}{flow_id}")

    draft = db.query(DiscordBetaApplicationDraft).filter(DiscordBetaApplicationDraft.id == uuid.UUID(flow_id)).one()
    assert response["type"] == 7
    assert response["data"]["flags"] == beta.EPHEMERAL_MESSAGE_FLAG
    assert draft.selected_integrations_json == ["playback", "iptv_epg"]
    assert draft.current_step == "stability"


def test_stability_selection_is_persisted(db):
    discord_user = _discord_user("24")
    flow_id = beta.create_application_flow(db, discord_user)
    beta.update_application_flow(db, flow_id, discord_user.user_id, devices=["fire_tv"], features=["playback"])
    payload = _component_payload(
        f"{beta.STABILITY_SELECT_PREFIX}{flow_id}",
        ["mostly_stable"],
        discord_user=discord_user,
    )

    response = beta.handle_stability_select_interaction(db, payload, f"{beta.STABILITY_SELECT_PREFIX}{flow_id}")

    draft = db.query(DiscordBetaApplicationDraft).filter(DiscordBetaApplicationDraft.id == uuid.UUID(flow_id)).one()
    assert response["type"] == 9
    assert "flags" not in response["data"]
    assert draft.stability_preference == "mostly_stable"
    assert draft.current_step == "modal"


def test_application_steps_do_not_create_public_channel_messages(db):
    discord_user = _discord_user("241")
    apply_payload = {
        "type": 3,
        "member": {
            "user": {
                "id": discord_user.user_id,
                "username": discord_user.username,
                "global_name": discord_user.discriminator_or_global_name,
            }
        },
        "data": {"custom_id": beta.APPLICATION_BUTTON_CUSTOM_ID},
    }

    apply_response = beta.handle_application_button(db, apply_payload)
    device_custom_id = _response_select_custom_id(apply_response)
    device_response = beta.handle_device_select_interaction(
        db,
        _component_payload(device_custom_id, ["fire_tv"], discord_user=discord_user),
        device_custom_id,
    )
    feature_custom_id = _response_select_custom_id(device_response)
    feature_response = beta.handle_feature_select_interaction(
        db,
        _component_payload(feature_custom_id, ["playback"], discord_user=discord_user),
        feature_custom_id,
    )
    stability_custom_id = _response_select_custom_id(feature_response)
    stability_response = beta.handle_stability_select_interaction(
        db,
        _component_payload(stability_custom_id, ["unstable_ok"], discord_user=discord_user),
        stability_custom_id,
    )

    assert apply_response["type"] == 4
    assert apply_response["data"]["flags"] == beta.EPHEMERAL_MESSAGE_FLAG
    assert device_response["type"] == 7
    assert device_response["data"]["flags"] == beta.EPHEMERAL_MESSAGE_FLAG
    assert feature_response["type"] == 7
    assert feature_response["data"]["flags"] == beta.EPHEMERAL_MESSAGE_FLAG
    assert stability_response["type"] == 9


def test_starting_new_flow_invalidates_old_draft(db):
    discord_user = _discord_user("25")
    first_flow_id = beta.create_application_flow(db, discord_user)
    second_flow_id = beta.create_application_flow(db, discord_user)

    first = db.query(DiscordBetaApplicationDraft).filter(DiscordBetaApplicationDraft.id == uuid.UUID(first_flow_id)).one()
    second = db.query(DiscordBetaApplicationDraft).filter(DiscordBetaApplicationDraft.id == uuid.UUID(second_flow_id)).one()
    assert first.consumed_at is not None
    assert second.consumed_at is None


def test_unknown_stability_preference_is_rejected(db):
    discord_user = _discord_user("26")
    flow_id = beta.create_application_flow(db, discord_user)
    beta.update_application_flow(db, flow_id, discord_user.user_id, devices=["fire_tv"], features=["playback"])
    db.commit()
    payload = _component_payload(
        f"{beta.STABILITY_SELECT_PREFIX}{flow_id}",
        ["chaos_channel"],
        discord_user=discord_user,
    )

    response = beta.handle_stability_select_interaction(db, payload, f"{beta.STABILITY_SELECT_PREFIX}{flow_id}")

    draft = db.query(DiscordBetaApplicationDraft).filter(DiscordBetaApplicationDraft.id == uuid.UUID(flow_id)).one()
    assert response["type"] == 4
    assert "stability" in response["data"]["content"].lower()
    assert draft.stability_preference is None


def test_link_code_creation_hash_expiry_and_single_use(db, free_user):
    result = beta.create_beta_link_code(db, free_user.id)
    db.commit()

    row = db.query(DiscordBetaLinkCode).filter(DiscordBetaLinkCode.torve_user_id == free_user.id).one()
    assert result.code.startswith("BETA-")
    assert row.code_hash != result.code
    assert len(row.code_hash) == 64
    assert row.expires_at > beta.utcnow()

    consumed = beta.consume_beta_link_code(db, code=result.code, discord_user_id=_discord_user().user_id)
    assert consumed.consumed_at is not None
    with pytest.raises(beta.BetaFlowError) as exc:
        beta.consume_beta_link_code(db, code=result.code, discord_user_id=_discord_user("2").user_id)
    assert exc.value.code == "invalid_link_code"


def test_unverified_user_cannot_create_beta_link_code(client, db):
    user = _make_unverified_user(db)

    response = client.post("/me/beta/discord-link-code", headers=_auth_header(user))

    assert response.status_code == 403
    body = response.json()["detail"]
    assert body["error_code"] == "email_not_verified"
    assert body["message"] == "Verify your email address before applying for beta access."
    assert "is_verified" not in response.text
    assert "password" not in response.text.lower()


def test_unverified_user_does_not_create_link_code_row(client, db):
    user = _make_unverified_user(db)

    client.post("/me/beta/discord-link-code", headers=_auth_header(user))

    assert db.query(DiscordBetaLinkCode).filter(DiscordBetaLinkCode.torve_user_id == user.id).count() == 0


def test_verified_user_can_create_beta_link_code(client, db, free_user, free_auth_header, monkeypatch):
    _set_campaign_window(
        monkeypatch,
        signup_close_delta=timedelta(days=1),
        free_access_end_delta=timedelta(days=30),
    )
    free_user.is_verified = True
    db.commit()

    response = client.post("/me/beta/discord-link-code", headers=free_auth_header)

    assert response.status_code == 200
    assert response.json()["code"].startswith("BETA-")


def test_link_code_generation_after_free_premium_signup_close_still_allows_beta_opt_in(
    client,
    db,
    free_user,
    free_auth_header,
    monkeypatch,
):
    _set_campaign_window(
        monkeypatch,
        signup_close_delta=timedelta(seconds=-1),
        free_access_end_delta=timedelta(days=30),
    )

    response = client.post("/me/beta/discord-link-code", headers=free_auth_header)

    assert response.status_code == 200, response.text
    assert response.json()["code"].startswith("BETA-")
    assert db.query(DiscordBetaLinkCode).filter(DiscordBetaLinkCode.torve_user_id == free_user.id).count() == 1


def test_beta_status_blocks_unverified_user(client, db):
    user = _make_unverified_user(db)

    response = client.get("/me/beta/status", headers=_auth_header(user))

    assert response.status_code == 200
    body = response.json()
    assert body["can_apply"] is False
    assert body["blocked_reason"] == "email_not_verified"
    assert body["beta_application_status"] == "none"
    assert body["beta_access_active"] is False


def test_beta_status_allows_eligible_verified_user(client, db, free_user, free_auth_header):
    free_user.is_verified = True
    db.commit()

    response = client.get("/me/beta/status", headers=free_auth_header)

    assert response.status_code == 200
    body = response.json()
    assert body["can_apply"] is True
    assert body["blocked_reason"] is None
    assert body["beta_application_status"] == "none"
    assert body["beta_signup_close_at"].endswith("+02:00")
    assert body["beta_free_access_end_at"].endswith("+02:00")


def test_paid_premium_user_can_apply_for_beta(client, db, test_user, auth_header):
    response = client.get("/me/beta/status", headers=auth_header)

    assert response.status_code == 200
    body = response.json()
    assert body["can_apply"] is True
    assert body["blocked_reason"] is None

    code_response = client.post("/me/beta/discord-link-code", headers=auth_header)

    assert code_response.status_code == 200, code_response.text
    assert code_response.json()["code"].startswith("BETA-")
    assert db.query(DiscordBetaLinkCode).filter(DiscordBetaLinkCode.torve_user_id == test_user.id).count() == 1


def test_beta_status_allows_opt_in_after_free_premium_signup_deadline(client, free_auth_header, monkeypatch):
    _set_campaign_window(
        monkeypatch,
        signup_close_delta=timedelta(seconds=-1),
        free_access_end_delta=timedelta(days=30),
    )

    response = client.get("/me/beta/status", headers=free_auth_header)

    assert response.status_code == 200
    body = response.json()
    assert body["can_apply"] is True
    assert body["blocked_reason"] is None


def test_beta_status_allows_discord_opt_in_after_free_access_window(client, free_auth_header, monkeypatch):
    _set_campaign_window(
        monkeypatch,
        signup_close_delta=timedelta(days=30),
        free_access_end_delta=timedelta(seconds=-1),
    )

    response = client.get("/me/beta/status", headers=free_auth_header)

    assert response.status_code == 200
    body = response.json()
    assert body["can_apply"] is True
    assert body["blocked_reason"] is None


def test_stale_link_code_for_unverified_user_cannot_submit_modal(db):
    user = _make_unverified_user(db)
    raw_code = "BETA-ABC234"
    row = DiscordBetaLinkCode(
        torve_user_id=user.id,
        code_hash=beta.code_hash(raw_code),
        expires_at=beta.utcnow() + timedelta(minutes=10),
        created_at=beta.utcnow(),
        updated_at=beta.utcnow(),
    )
    db.add(row)
    db.commit()
    discord_user = _discord_user("88")
    flow_id = _flow_for_user(db, discord_user)
    payload = _final_modal_payload(raw_code, flow_id=flow_id, discord_user=discord_user)

    response = beta.handle_application_modal_submit(db, payload, f"{beta.APPLICATION_MODAL_CUSTOM_ID}:{flow_id}")

    db.refresh(row)
    assert response["type"] == 4
    assert "Verify your email address" in response["data"]["content"]
    assert row.consumed_at is None
    assert db.query(DiscordBetaApplication).filter(DiscordBetaApplication.torve_user_id == user.id).count() == 0


def test_modal_submission_after_free_premium_signup_close_still_submits_application(db, free_user, monkeypatch):
    monkeypatch.setattr(beta.settings, "DISCORD_BETA_AUTO_APPROVE", False)
    _set_campaign_window(
        monkeypatch,
        signup_close_delta=timedelta(days=1),
        free_access_end_delta=timedelta(days=30),
    )
    result = beta.create_beta_link_code(db, free_user.id)
    row = db.query(DiscordBetaLinkCode).filter(DiscordBetaLinkCode.torve_user_id == free_user.id).one()
    db.commit()
    _set_campaign_window(
        monkeypatch,
        signup_close_delta=timedelta(seconds=-1),
        free_access_end_delta=timedelta(days=30),
    )
    discord_user = _discord_user("89")
    flow_id = _flow_for_user(db, discord_user)
    payload = _final_modal_payload(result.code, flow_id=flow_id, discord_user=discord_user)

    response = beta.handle_application_modal_submit(db, payload, f"{beta.APPLICATION_MODAL_CUSTOM_ID}:{flow_id}")

    db.refresh(row)
    assert response["type"] == 4
    assert response["data"]["content"] == beta.APPLICATION_SUBMITTED_MESSAGE
    assert row.consumed_at is not None
    assert db.query(DiscordBetaApplication).filter(DiscordBetaApplication.torve_user_id == free_user.id).count() == 1


def test_expired_link_code_fails_safely(db, free_user):
    result = beta.create_beta_link_code(db, free_user.id)
    row = db.query(DiscordBetaLinkCode).filter(DiscordBetaLinkCode.torve_user_id == free_user.id).one()
    row.expires_at = beta.utcnow() - timedelta(seconds=1)
    db.commit()
    flow_id = _flow_for_user(db)
    payload = _final_modal_payload(result.code, flow_id=flow_id)

    response = beta.handle_application_modal_submit(db, payload, f"{beta.APPLICATION_MODAL_CUSTOM_ID}:{flow_id}")

    assert response["type"] == 4
    assert "invalid or expired" in response["data"]["content"]
    assert db.query(DiscordBetaApplication).filter(DiscordBetaApplication.discord_user_id == _discord_user().user_id).count() == 0


def test_modal_submission_creates_link_and_application(db, free_user, monkeypatch):
    fake = FakeDiscord()
    monkeypatch.setattr(beta.DiscordBotClient, "from_settings", classmethod(lambda cls: fake))
    monkeypatch.setattr(beta.settings, "DISCORD_BETA_AUTO_APPROVE", False)
    code = beta.create_beta_link_code(db, free_user.id).code
    db.commit()
    flow_id = _flow_for_user(db)
    payload = _final_modal_payload(code, flow_id=flow_id)

    response = beta.handle_application_modal_submit(db, payload, f"{beta.APPLICATION_MODAL_CUSTOM_ID}:{flow_id}")

    assert response["type"] == 4
    assert response["data"]["flags"] == beta.EPHEMERAL_MESSAGE_FLAG
    assert response["data"]["content"] == beta.APPLICATION_SUBMITTED_MESSAGE
    assert response["data"]["components"] == []
    assert fake.staff_reviews
    link = db.query(DiscordAccountLink).filter(DiscordAccountLink.torve_user_id == free_user.id).one()
    app = db.query(DiscordBetaApplication).filter(DiscordBetaApplication.torve_user_id == free_user.id).one()
    assert link.discord_user_id == _discord_user().user_id
    assert app.status == "submitted"
    assert app.accepted_beta_terms is True
    draft = db.query(DiscordBetaApplicationDraft).filter(DiscordBetaApplicationDraft.id == uuid.UUID(flow_id)).one()
    assert draft.consumed_at is not None


def test_final_modal_reads_persisted_draft_from_new_session(db, free_user, monkeypatch):
    fake = FakeDiscord()
    monkeypatch.setattr(beta.DiscordBotClient, "from_settings", classmethod(lambda cls: fake))
    monkeypatch.setattr(beta.settings, "DISCORD_BETA_AUTO_APPROVE", False)
    discord_user = _discord_user("27")
    code = beta.create_beta_link_code(db, free_user.id).code
    flow_id = _flow_for_user(db, discord_user)
    db.commit()
    payload = _final_modal_payload(code, flow_id=flow_id, discord_user=discord_user)
    TestSession = sessionmaker(bind=db.get_bind())
    other_db = TestSession()
    try:
        response = beta.handle_application_modal_submit(
            other_db,
            payload,
            f"{beta.APPLICATION_MODAL_CUSTOM_ID}:{flow_id}",
        )
    finally:
        other_db.close()

    assert response["type"] == 4
    assert response["data"]["content"] == beta.APPLICATION_SUBMITTED_MESSAGE
    assert (
        db.query(DiscordBetaApplication)
        .filter(DiscordBetaApplication.discord_user_id == discord_user.user_id)
        .count()
        == 1
    )


def test_missing_draft_returns_expired_session_message(db, free_user):
    code = beta.create_beta_link_code(db, free_user.id).code
    missing_flow_id = str(uuid.uuid4())
    payload = _final_modal_payload(code, flow_id=missing_flow_id, discord_user=_discord_user("28"))

    response = beta.handle_application_modal_submit(
        db,
        payload,
        f"{beta.APPLICATION_MODAL_CUSTOM_ID}:{missing_flow_id}",
    )

    assert response["type"] == 4
    assert response["data"]["content"] == "Your beta application session expired. Please click Apply for Beta again."


def test_expired_draft_returns_expired_session_message(db, free_user):
    discord_user = _discord_user("29")
    code = beta.create_beta_link_code(db, free_user.id).code
    flow_id = _flow_for_user(db, discord_user)
    draft = db.query(DiscordBetaApplicationDraft).filter(DiscordBetaApplicationDraft.id == uuid.UUID(flow_id)).one()
    draft.expires_at = beta.utcnow() - timedelta(seconds=1)
    db.commit()
    payload = _final_modal_payload(code, flow_id=flow_id, discord_user=discord_user)

    response = beta.handle_application_modal_submit(
        db,
        payload,
        f"{beta.APPLICATION_MODAL_CUSTOM_ID}:{flow_id}",
    )

    db.refresh(draft)
    assert response["type"] == 4
    assert response["data"]["content"] == "Your beta application session expired. Please click Apply for Beta again."
    assert draft.consumed_at is not None


def test_duplicate_application_updates_existing_submission(db, free_user):
    discord_user = _discord_user("3")
    first_code = beta.create_beta_link_code(db, free_user.id).code
    first = beta.submit_beta_application(
        db,
        link_code=first_code,
        discord_user=discord_user,
        devices=["fire_tv"],
        integrations=["playback"],
        stability_preference="mostly_stable",
        motivation="First answer",
        confirmation_text="I UNDERSTAND",
    ).application
    second_code = beta.create_beta_link_code(db, free_user.id).code
    second = beta.submit_beta_application(
        db,
        link_code=second_code,
        discord_user=discord_user,
        devices=["windows"],
        integrations=["library"],
        stability_preference="release_candidate",
        motivation="Updated answer",
        confirmation_text="I UNDERSTAND",
    ).application
    db.commit()

    assert second.id == first.id
    assert db.query(DiscordBetaApplication).filter(DiscordBetaApplication.torve_user_id == free_user.id).count() == 1
    assert second.devices_json == ["windows"]
    assert second.integrations_json == ["library"]
    assert second.stability_preference == "release_candidate"
    assert second.motivation == "Updated answer"


def test_structured_selections_are_stored_as_canonical_values(db, free_user):
    discord_user = _discord_user("31")
    code = beta.create_beta_link_code(db, free_user.id).code
    flow_id = _flow_for_user(
        db,
        discord_user,
        devices=["android_tv", "fire_tv"],
        features=["playback", "recordings"],
        stability_preference="mostly_stable",
    )
    payload = _final_modal_payload(code, flow_id=flow_id, discord_user=discord_user, motivation="")

    response = beta.handle_application_modal_submit(
        db,
        payload,
        f"{beta.APPLICATION_MODAL_CUSTOM_ID}:{flow_id}",
    )

    application = (
        db.query(DiscordBetaApplication)
        .filter(DiscordBetaApplication.discord_user_id == discord_user.user_id)
        .one()
    )
    assert response["type"] == 4
    assert application.devices_json == ["android_tv", "fire_tv"]
    assert application.integrations_json == ["playback", "recordings"]
    assert application.stability_preference == "mostly_stable"
    assert application.motivation == ""


def test_unknown_device_value_is_rejected(db):
    flow_id = beta.create_application_flow(db, _discord_user("32"))
    db.commit()
    payload = _component_payload(
        f"{beta.DEVICE_SELECT_PREFIX}{flow_id}",
        ["fire_tv", "sideways_toaster"],
        discord_user=_discord_user("32"),
    )

    response = beta.handle_device_select_interaction(db, payload, f"{beta.DEVICE_SELECT_PREFIX}{flow_id}")

    assert response["type"] == 4
    assert "device" in response["data"]["content"].lower()
    draft = db.query(DiscordBetaApplicationDraft).filter(DiscordBetaApplicationDraft.id == uuid.UUID(flow_id)).one()
    assert draft.selected_devices_json == []


def test_unknown_feature_value_is_rejected(db):
    flow_id = beta.create_application_flow(db, _discord_user("33"))
    beta.update_application_flow(db, flow_id, _discord_user("33").user_id, devices=["fire_tv"])
    db.commit()
    payload = _component_payload(
        f"{beta.FEATURE_SELECT_PREFIX}{flow_id}",
        ["playback", "raw_provider_name"],
        discord_user=_discord_user("33"),
    )

    response = beta.handle_feature_select_interaction(db, payload, f"{beta.FEATURE_SELECT_PREFIX}{flow_id}")

    assert response["type"] == 4
    assert "beta area" in response["data"]["content"].lower()
    draft = db.query(DiscordBetaApplicationDraft).filter(DiscordBetaApplicationDraft.id == uuid.UUID(flow_id)).one()
    assert draft.selected_integrations_json == []


def test_missing_required_selections_are_rejected(db, free_user):
    code = beta.create_beta_link_code(db, free_user.id).code

    with pytest.raises(beta.BetaFlowError) as exc:
        beta.submit_beta_application(
            db,
            link_code=code,
            discord_user=_discord_user("34"),
            devices=[],
            integrations=["playback"],
            stability_preference="unstable_ok",
            motivation="",
            confirmation_text="I UNDERSTAND",
        )

    assert exc.value.code in {"missing_devices", "missing_selections"}


def test_staff_review_card_displays_controlled_labels(db, free_user):
    app = DiscordBetaApplication(
        torve_user_id=free_user.id,
        discord_user_id="990000000000035",
        discord_username="structured",
        status="submitted",
        devices_json=["fire_tv", "windows"],
        integrations_json=["iptv_epg", "desktop_app"],
        stability_preference="release_candidate",
        motivation="Can test RC builds.",
        accepted_beta_terms=True,
        accepted_no_credentials=True,
        created_at=beta.utcnow(),
        updated_at=beta.utcnow(),
    )

    payload_text = str(beta.build_staff_review_payload(app))

    assert "Fire TV" in payload_text
    assert "Windows" in payload_text
    assert "IPTV / EPG" in payload_text
    assert "Desktop App" in payload_text
    assert "I only want release-candidate builds" in payload_text
    assert "fire_tv" not in payload_text
    assert "iptv_epg" not in payload_text


def test_beta_stats_counts_structured_values(db, free_user):
    app = _create_submitted_application(db, free_user, discord_user=_discord_user("36"))
    beta.approve_beta_application(db, application_id=app.id, reviewer_discord_user_id="990000000099999")
    db.commit()

    stats = beta.beta_stats(db)

    assert stats["beta_signup_close_at"].endswith("+02:00")
    assert stats["beta_free_access_end_at"].endswith("+02:00")
    assert stats["signup_closed"] is False
    assert stats["free_access_ended"] is False
    assert stats["applications_by_status"]["approved"] >= 1
    assert stats["active_grants"] >= 1
    assert "pending_applications" in stats
    assert stats["selected_devices"]["fire_tv"] >= 1
    assert stats["selected_devices"]["windows"] >= 1
    assert stats["selected_features_integrations"]["playback"] >= 1
    assert stats["selected_features_integrations"]["iptv_epg"] >= 1
    assert stats["stability_preferences"]["unstable_ok"] >= 1


def test_approval_creates_30_day_grant_and_adds_role(db, free_user, monkeypatch):
    fake = FakeDiscord()
    monkeypatch.setattr(beta.DiscordBotClient, "from_settings", classmethod(lambda cls: fake))
    app = _create_submitted_application(db, free_user)

    response = beta.handle_approve_interaction(
        db,
        _staff_payload(f"{beta.APPROVE_BUTTON_PREFIX}{app.id}"),
        f"{beta.APPROVE_BUTTON_PREFIX}{app.id}",
    )

    grant = db.query(BetaAccessGrant).filter(BetaAccessGrant.torve_user_id == free_user.id).one()
    assert response["type"] == 7
    assert response["data"]["components"] == []
    assert grant.status == "active"
    assert 29 <= (grant.expires_at - grant.starts_at).days <= 30
    assert fake.added_roles == [app.discord_user_id]


def test_approval_expiry_is_capped_at_beta_free_access_end(db, free_user, monkeypatch):
    app = _create_submitted_application(db, free_user, discord_user=_discord_user("41"))
    _signup_close_at, free_access_end_at = _set_campaign_window(
        monkeypatch,
        signup_close_delta=timedelta(days=1),
        free_access_end_delta=timedelta(days=5),
    )
    monkeypatch.setattr(beta.settings, "TORVE_BETA_GRANT_DAYS", 30)

    approval = beta.approve_beta_application(
        db,
        application_id=app.id,
        reviewer_discord_user_id="990000000099999",
    )
    db.commit()

    assert approval.grant is not None
    assert approval.grant.expires_at == free_access_end_at


def test_approval_after_beta_free_access_end_is_discord_only(db, free_user, monkeypatch):
    app = _create_submitted_application(db, free_user, discord_user=_discord_user("42"))
    _set_campaign_window(
        monkeypatch,
        signup_close_delta=timedelta(days=1),
        free_access_end_delta=timedelta(seconds=-1),
    )

    approval = beta.approve_beta_application(
        db,
        application_id=app.id,
        reviewer_discord_user_id="990000000099999",
    )

    assert approval.action == "approved_discord_only"
    assert approval.grant is None
    assert app.status == "approved"


def test_reapproval_does_not_create_duplicate_active_grant(db, free_user, monkeypatch):
    fake = FakeDiscord()
    monkeypatch.setattr(beta.DiscordBotClient, "from_settings", classmethod(lambda cls: fake))
    app = _create_submitted_application(db, free_user)
    payload = _staff_payload(f"{beta.APPROVE_BUTTON_PREFIX}{app.id}")

    beta.handle_approve_interaction(db, payload, f"{beta.APPROVE_BUTTON_PREFIX}{app.id}")
    beta.handle_approve_interaction(db, payload, f"{beta.APPROVE_BUTTON_PREFIX}{app.id}")

    grants = db.query(BetaAccessGrant).filter(BetaAccessGrant.torve_user_id == free_user.id).all()
    assert len(grants) == 1


def test_rejection_does_not_grant_access(db, free_user):
    app = _create_submitted_application(db, free_user, discord_user=_discord_user("4"))

    rejected = beta.reject_beta_application(
        db,
        application_id=app.id,
        reviewer_discord_user_id="990000000099999",
        reason="Need more device details",
    )
    db.commit()

    assert rejected.status == "rejected"
    assert db.query(BetaAccessGrant).filter(BetaAccessGrant.torve_user_id == free_user.id).count() == 0


def test_reject_modal_edits_review_message_in_place(db, free_user, monkeypatch):
    fake = FakeDiscord()
    monkeypatch.setattr(beta.DiscordBotClient, "from_settings", classmethod(lambda cls: fake))
    app = _create_submitted_application(db, free_user, discord_user=_discord_user("44"))
    payload = {
        "type": 5,
        "member": {
            "permissions": str(1 << 28),
            "user": {"id": "990000000099999", "username": "reviewer"},
        },
        "data": {
            "custom_id": f"{beta.REJECT_MODAL_PREFIX}{app.id}",
            "components": [
                {"components": [{"custom_id": beta.FIELD_REJECTION_REASON, "value": "Not this round"}]},
            ],
        },
    }

    response = beta.handle_reject_modal_submit(db, payload, f"{beta.REJECT_MODAL_PREFIX}{app.id}")

    db.refresh(app)
    assert response["type"] == 7
    assert response["data"]["components"] == []
    assert app.status == "rejected"
    assert db.query(BetaAccessGrant).filter(BetaAccessGrant.torve_user_id == free_user.id).count() == 0


def test_expiry_job_marks_grant_expired_and_attempts_role_removal(db, free_user):
    app = _create_submitted_application(db, free_user, discord_user=_discord_user("5"))
    approval = beta.approve_beta_application(db, application_id=app.id, reviewer_discord_user_id="990000000099999")
    approval.grant.expires_at = beta.utcnow() - timedelta(seconds=1)
    draft_id = beta.create_application_flow(db, _discord_user("55"))
    draft = db.query(DiscordBetaApplicationDraft).filter(DiscordBetaApplicationDraft.id == uuid.UUID(draft_id)).one()
    draft.expires_at = beta.utcnow() - timedelta(seconds=1)
    db.commit()
    fake = FakeDiscord()

    result = beta.expire_due_beta_grants(db, discord=fake, torve_user_ids=[free_user.id])
    db.commit()

    assert result["expired"] == 1
    assert result["expired_drafts"] == 1
    assert result["role_remove_attempted"] == 1
    assert fake.removed_roles == [app.discord_user_id]
    assert approval.grant.status == "expired"
    assert draft.consumed_at is not None


def test_expiry_job_expires_active_grants_after_beta_free_access_end(db, free_user, monkeypatch):
    app = _create_submitted_application(db, free_user, discord_user=_discord_user("56"))
    approval = beta.approve_beta_application(db, application_id=app.id, reviewer_discord_user_id="990000000099999")
    approval.grant.expires_at = beta.utcnow() + timedelta(days=30)
    db.commit()
    _set_campaign_window(
        monkeypatch,
        signup_close_delta=timedelta(days=1),
        free_access_end_delta=timedelta(seconds=-1),
    )
    fake = FakeDiscord()

    result = beta.expire_due_beta_grants(db, discord=fake, torve_user_ids=[free_user.id])
    db.commit()

    assert result["expired"] == 1
    assert result["role_remove_attempted"] == 1
    assert app.discord_user_id in fake.removed_roles
    assert approval.grant.status == "expired"


def test_approval_after_free_premium_signup_deadline_is_discord_only(db, free_user, monkeypatch):
    app = _create_submitted_application(db, free_user, discord_user=_discord_user("57"))
    _set_campaign_window(
        monkeypatch,
        signup_close_delta=timedelta(seconds=-1),
        free_access_end_delta=timedelta(days=30),
    )

    approval = beta.approve_beta_application(db, application_id=app.id, reviewer_discord_user_id="990000000099999")
    db.commit()
    db.refresh(app)

    assert approval.action == "approved_discord_only"
    assert approval.grant is None
    assert app.status == "approved"
    assert db.query(BetaAccessGrant).filter(BetaAccessGrant.torve_user_id == free_user.id).count() == 0


def test_beta_status_and_access_state_include_beta_access(client, db, free_user, free_auth_header):
    app = _create_submitted_application(db, free_user, discord_user=_discord_user("6"))
    beta.approve_beta_application(db, application_id=app.id, reviewer_discord_user_id="990000000099999")
    db.commit()

    status_response = client.get("/me/beta/status", headers=free_auth_header)
    access_response = client.get("/me/access-state", headers=free_auth_header)

    assert status_response.status_code == 200
    assert status_response.json()["discord_linked"] is True
    assert status_response.json()["beta_application_status"] == "approved"
    assert status_response.json()["beta_access_active"] is True
    assert status_response.json()["can_apply"] is False
    assert status_response.json()["blocked_reason"] == "already_active"
    assert access_response.status_code == 200
    access_body = access_response.json()
    assert access_body["has_premium_access"] is True
    assert access_body["access_tier"] == "free"
    assert access_body["entitlement_type"] is None
    assert access_body["source"] is None
    assert access_body["beta_access"]["active"] is True
    assert access_body["beta_access"]["source"] == "discord_beta"


def test_beta_access_state_requires_same_torve_user(client, db, free_user, free_auth_header):
    other_user = User(
        email=f"discord-beta-other-{uuid.uuid4().hex[:8]}@test.com",
        password_hash=hash_password("TestPass123!"),
        display_name="Other Beta User",
        is_active=True,
        is_verified=True,
        has_lifetime_access=False,
        has_premium_access=False,
    )
    db.add(other_user)
    db.commit()
    db.refresh(other_user)
    app = _create_submitted_application(db, free_user, discord_user=_discord_user("61"))
    beta.approve_beta_application(db, application_id=app.id, reviewer_discord_user_id="990000000099999")
    db.commit()

    owner_response = client.get("/me/access-state", headers=free_auth_header)
    other_response = client.get("/me/access-state", headers=_auth_header(other_user))

    assert owner_response.status_code == 200
    assert owner_response.json()["access_tier"] == "free"
    assert owner_response.json()["has_premium_access"] is True
    assert other_response.status_code == 200
    assert other_response.json()["access_tier"] == "free"
    assert other_response.json()["has_premium_access"] is True
    assert other_response.json()["beta_access"]["active"] is False


def test_revoked_beta_grant_does_not_entitle(client, db, free_user, free_auth_header):
    app = _create_submitted_application(db, free_user, discord_user=_discord_user("62"))
    approval = beta.approve_beta_application(
        db,
        application_id=app.id,
        reviewer_discord_user_id="990000000099999",
    )
    approval.grant.status = "revoked"
    approval.grant.revoked_at = beta.utcnow()
    db.commit()

    response = client.get("/me/access-state", headers=free_auth_header)

    assert response.status_code == 200
    body = response.json()
    assert body["has_premium_access"] is True
    assert body["access_tier"] == "free"
    assert body["beta_access"]["active"] is False
    assert body["beta_access"]["status"] == "revoked"


def test_beta_access_state_reports_active_device(client, db, free_user, free_auth_header, free_tv_device):
    app = _create_submitted_application(db, free_user, discord_user=_discord_user("63"))
    beta.approve_beta_application(db, application_id=app.id, reviewer_discord_user_id="990000000099999")
    db.commit()

    response = client.get(
        f"/me/access-state?installation_id={free_tv_device.installation_id}",
        headers=free_auth_header,
    )

    assert response.status_code == 200
    body = response.json()
    assert body["has_premium_access"] is True
    assert body["access_tier"] == "free"
    assert body["is_device_activated"] is True
    assert body["device_block_reason"] is None


def test_beta_access_state_reports_unregistered_device_without_losing_entitlement(
    client,
    db,
    free_user,
    free_auth_header,
):
    app = _create_submitted_application(db, free_user, discord_user=_discord_user("64"))
    beta.approve_beta_application(db, application_id=app.id, reviewer_discord_user_id="990000000099999")
    db.commit()

    response = client.get(
        "/me/access-state?installation_id=missing-beta-device",
        headers=free_auth_header,
    )

    assert response.status_code == 200
    body = response.json()
    assert body["has_premium_access"] is True
    assert body["access_tier"] == "free"
    assert body["is_device_activated"] is False
    assert body["device_block_reason"] == "device_not_registered"


def test_auto_approved_beta_login_and_refresh_report_premium(client, db, free_user, monkeypatch):
    fake = FakeDiscord()
    monkeypatch.setattr(beta.DiscordBotClient, "from_settings", classmethod(lambda cls: fake))
    monkeypatch.setattr(beta.settings, "DISCORD_BETA_AUTO_APPROVE", True)
    code = beta.create_beta_link_code(db, free_user.id).code
    discord_user = _discord_user("65")
    flow_id = _flow_for_user(db, discord_user)
    db.commit()
    payload = _final_modal_payload(code, flow_id=flow_id, discord_user=discord_user)

    response = beta.handle_application_modal_submit(
        db,
        payload,
        f"{beta.APPLICATION_MODAL_CUSTOM_ID}:{flow_id}",
    )

    assert response["type"] == 4
    assert "Beta access approved" in response["data"]["content"]
    assert fake.added_roles == [discord_user.user_id]
    assert db.query(BetaAccessGrant).filter(BetaAccessGrant.torve_user_id == free_user.id).count() == 1

    login = client.post(
        "/auth/login",
        json={"email": free_user.email, "password": "TestPass123!"},
    )
    assert login.status_code == 200
    assert login.json()["user"]["has_premium_access"] is True

    refresh = client.post(
        "/auth/refresh",
        json={"refresh_token": login.json()["tokens"]["refresh_token"]},
    )
    assert refresh.status_code == 200
    assert refresh.json()["user"]["has_premium_access"] is True

    web_login = client.post(
        "/web/auth/login",
        json={"email": free_user.email, "password": "TestPass123!"},
    )
    assert web_login.status_code == 200
    assert web_login.json()["user"]["has_premium_access"] is True

    web_session = client.get("https://testserver/web/auth/session")
    assert web_session.status_code == 200
    assert web_session.json()["authenticated"] is True
    assert web_session.json()["user"]["has_premium_access"] is True


def test_access_state_does_not_report_beta_active_after_free_access_end(client, db, free_user, free_auth_header, monkeypatch):
    app = _create_submitted_application(db, free_user, discord_user=_discord_user("57"))
    approval = beta.approve_beta_application(db, application_id=app.id, reviewer_discord_user_id="990000000099999")
    approval.grant.expires_at = beta.utcnow() + timedelta(days=30)
    db.commit()
    _set_campaign_window(
        monkeypatch,
        signup_close_delta=timedelta(days=1),
        free_access_end_delta=timedelta(seconds=-1),
    )

    response = client.get("/me/access-state", headers=free_auth_header)

    assert response.status_code == 200
    body = response.json()
    assert body["has_premium_access"] is True
    assert body["access_tier"] == "free"
    assert body["beta_access"]["active"] is False
    assert body["beta_access"]["status"] == "expired"


def test_paid_premium_entitlement_unaffected_by_beta_campaign_end(client, auth_header, monkeypatch):
    _set_campaign_window(
        monkeypatch,
        signup_close_delta=timedelta(days=1),
        free_access_end_delta=timedelta(seconds=-1),
    )

    response = client.get("/me/access-state", headers=auth_header)

    assert response.status_code == 200
    body = response.json()
    assert body["has_premium_access"] is True
    assert body["access_tier"] == "free"
    assert body["beta_access"]["active"] is False


def test_link_code_endpoint_returns_plain_code_without_hash(client, db, free_user, free_auth_header):
    response = client.post("/me/beta/discord-link-code", headers=free_auth_header)

    assert response.status_code == 200
    body = response.json()
    assert body["code"].startswith("BETA-")
    assert "code_hash" not in body
    row = db.query(DiscordBetaLinkCode).filter(DiscordBetaLinkCode.torve_user_id == free_user.id).one()
    assert row.code_hash != body["code"]


def test_missing_discord_env_disables_bot_actions_cleanly():
    client = beta.DiscordBotClient(
        bot_token="",
        guild_id="",
        beta_role_id="",
        application_channel_id="",
        review_channel_id="",
    )

    assert client.add_beta_role("990000000000001").action == "missing_config"
    assert client.publish_application_message().action == "missing_config"


def test_staff_review_posts_only_to_review_channel(monkeypatch, db, free_user):
    calls = []

    class FakeResponse:
        status_code = 200
        headers = {}

        def json(self):
            return {"id": "990000000000777"}

    def fake_request(method, url, headers, json, timeout):
        calls.append({"method": method, "url": url, "json": json})
        return FakeResponse()

    monkeypatch.setattr(beta.httpx, "request", fake_request)
    app = _create_submitted_application(db, free_user, discord_user=_discord_user("77"))
    client = beta.DiscordBotClient(
        bot_token="bot-token",
        guild_id="990000000000099",
        beta_role_id="990000000000098",
        application_channel_id="990000000000097",
        review_channel_id="990000000000096",
    )

    result = client.post_staff_review(app)

    assert result.ok is True
    assert len(calls) == 1
    assert calls[0]["method"] == "POST"
    assert calls[0]["url"].endswith("/channels/990000000000096/messages")
    assert "/channels/990000000000097/messages" not in calls[0]["url"]


def test_publish_application_message_does_not_use_static_beta_info_message_id(monkeypatch, capsys):
    fake = FakePublishDiscord(
        beta.DiscordApiResult(ok=True, action="post_message", status_code=200, message_id="990000000000111")
    )
    monkeypatch.setattr(beta.settings, "DISCORD_BETA_INFO_MESSAGE_ID", "990000000000222")
    monkeypatch.setattr(beta.settings, "DISCORD_BETA_APPLICATION_MESSAGE_ID", "")
    monkeypatch.setattr(beta, "SessionLocal", lambda: FakeCliDb())
    monkeypatch.setattr(beta.DiscordBotClient, "from_settings", classmethod(lambda cls: fake))

    code = beta.main(["publish-application-message"])

    captured = capsys.readouterr()
    assert code == 0
    assert fake.message_ids == [None]
    assert "990000000000111" in captured.out
    assert "990000000000222" not in captured.out


def test_blank_application_message_id_creates_new_bot_message(monkeypatch):
    calls = []

    class FakeResponse:
        status_code = 200
        headers = {}

        def json(self):
            return {"id": "990000000000333"}

    def fake_request(method, url, headers, json, timeout):
        calls.append({"method": method, "url": url, "headers": headers, "json": json})
        return FakeResponse()

    monkeypatch.setattr(beta.httpx, "request", fake_request)
    client = beta.DiscordBotClient(
        bot_token="bot-token",
        guild_id="990000000000099",
        beta_role_id="990000000000098",
        application_channel_id="990000000000097",
        review_channel_id="990000000000096",
    )

    result = client.publish_application_message(message_id="")

    assert result.ok is True
    assert result.message_id == "990000000000333"
    assert calls[0]["method"] == "POST"
    assert calls[0]["url"].endswith("/channels/990000000000097/messages")
    assert calls[0]["json"] == beta.build_application_message_payload()


def test_configured_application_message_id_edits_existing_bot_message(monkeypatch):
    calls = []

    class FakeResponse:
        status_code = 200
        headers = {}

        def json(self):
            return {"id": "990000000000444"}

    def fake_request(method, url, headers, json, timeout):
        calls.append({"method": method, "url": url, "headers": headers, "json": json})
        return FakeResponse()

    monkeypatch.setattr(beta.httpx, "request", fake_request)
    client = beta.DiscordBotClient(
        bot_token="bot-token",
        guild_id="990000000000099",
        beta_role_id="990000000000098",
        application_channel_id="990000000000097",
        review_channel_id="990000000000096",
    )

    result = client.publish_application_message(message_id="990000000000444")

    assert result.ok is True
    assert result.message_id == "990000000000444"
    assert calls[0]["method"] == "PATCH"
    assert calls[0]["url"].endswith("/channels/990000000000097/messages/990000000000444")
    assert calls[0]["json"] == beta.build_application_message_payload()


def test_publish_application_message_edit_403_prints_sanitized_actionable_output(monkeypatch, capsys):
    fake = FakePublishDiscord(
        beta.DiscordApiResult(ok=False, action="edit_message", status_code=403)
    )
    monkeypatch.setattr(beta.settings, "DISCORD_BETA_APPLICATION_MESSAGE_ID", "990000000000555")
    monkeypatch.setattr(beta.settings, "DISCORD_BOT_TOKEN", "raw-bot-token")
    monkeypatch.setattr(beta, "SessionLocal", lambda: FakeCliDb())
    monkeypatch.setattr(beta.DiscordBotClient, "from_settings", classmethod(lambda cls: fake))

    code = beta.main(["publish-application-message"])

    captured = capsys.readouterr()
    assert code == 2
    assert "Could not edit the configured beta application message." in captured.out
    assert "Clear DISCORD_BETA_APPLICATION_MESSAGE_ID and republish." in captured.out
    assert "raw-bot-token" not in captured.out
    assert ".env" not in captured.out
    assert "Traceback" not in captured.out


def test_application_message_id_setting_is_optional():
    config = Settings(
        DATABASE_URL="postgresql://torve_user:change-me@localhost:5432/torve",
        JWT_SECRET="test-secret",
        _env_file=None,
    )

    assert config.DISCORD_BETA_APPLICATION_MESSAGE_ID == ""


def test_discord_api_failure_does_not_corrupt_beta_grant_state(db, free_user, monkeypatch):
    fake = FakeDiscord(add_ok=False)
    monkeypatch.setattr(beta.DiscordBotClient, "from_settings", classmethod(lambda cls: fake))
    app = _create_submitted_application(db, free_user, discord_user=_discord_user("7"))

    beta.handle_approve_interaction(
        db,
        _staff_payload(f"{beta.APPROVE_BUTTON_PREFIX}{app.id}"),
        f"{beta.APPROVE_BUTTON_PREFIX}{app.id}",
    )

    grant = db.query(BetaAccessGrant).filter(BetaAccessGrant.torve_user_id == free_user.id).one()
    assert grant.status == "active"
    assert fake.added_roles == [app.discord_user_id]


def test_staff_review_payload_and_failure_logs_are_sanitized(db, free_user, monkeypatch, caplog):
    app = DiscordBetaApplication(
        torve_user_id=free_user.id,
        discord_user_id="990000000000008",
        discord_username="tester",
        status="submitted",
        devices_json=["Windows password=hunter2", "/opt/private"],
        integrations_json=["https://example.test/list.m3u?token=secret"],
        motivation="signed URL https://example.test/file?token=abc\nprivate key nope\n.env /opt/app",
        accepted_beta_terms=True,
        accepted_no_credentials=True,
        created_at=beta.utcnow(),
        updated_at=beta.utcnow(),
    )
    payload_text = str(beta.build_staff_review_payload(app)).lower()
    forbidden = [
        "token",
        "webhook",
        "password",
        "secret",
        "private key",
        ".env",
        "/opt/",
        "signed url",
        "traceback",
    ]
    assert not any(item in payload_text for item in forbidden)

    def fail_request(*args, **kwargs):
        raise httpx.ConnectError("failed token=raw-secret-value /opt/private .env")

    monkeypatch.setattr(beta.httpx, "request", fail_request)
    caplog.set_level(logging.WARNING, logger="app.discord_beta")
    result = beta.DiscordBotClient(
        bot_token="bot-token",
        guild_id="990000000000099",
        beta_role_id="990000000000098",
        application_channel_id="990000000000097",
        review_channel_id="990000000000096",
    ).add_beta_role("990000000000008")

    assert result.ok is False
    assert "ConnectError" in caplog.text
    assert "raw-secret-value" not in caplog.text
    assert "/opt/" not in caplog.text
    assert ".env" not in caplog.text
