import logging

import httpx
import pytest

from app import discord_static_messages as dsm


def _payload_text(payload):
    return str(payload)


def test_builds_expected_rules_payload():
    payload = dsm.build_rules_payload()

    assert payload["username"] == "Torve Rules Bot"
    assert payload["allowed_mentions"] == {"parse": []}
    assert payload["content"] == ""
    titles = [embed["title"] for embed in payload["embeds"]]
    colors = [embed["color"] for embed in payload["embeds"]]

    assert titles == [
        "Torve Community Rules",
        "Rule 1: Legal Sources",
        "Rule 2: No Provider Sourcing",
        "Rule 3: No Piracy Links or Illegal Streams",
        "Rule 4: No Credential Sharing",
        "Rule 5: Useful Bug Reports",
        "Rule 6: Respect and Conduct",
        "Important Links",
    ]
    assert colors == [
        0xF5B301,
        0x36C275,
        0xEF4444,
        0xF59E0B,
        0xA855F7,
        0x4D8DFF,
        0x6B7280,
        0x22D3EE,
    ]


def test_page_registry_contains_expected_pages():
    assert set(dsm.PAGE_DEFINITIONS) == {"rules", "faq", "downloads", "beta-info"}


@pytest.mark.parametrize("page_key", ["rules", "faq", "downloads", "beta-info"])
def test_each_page_builds_valid_discord_payload(page_key):
    page = dsm.PAGE_DEFINITIONS[page_key]
    payload = dsm.build_payload(page_key)

    assert payload["username"] == page.username
    assert payload["allowed_mentions"] == {"parse": []}
    assert isinstance(payload["embeds"], list)
    assert len(payload["embeds"]) >= 2
    for embed in payload["embeds"]:
        assert embed["title"]
        assert embed["description"]
        assert isinstance(embed["color"], int)


def test_each_page_uses_correct_env_vars():
    assert dsm.PAGE_DEFINITIONS["rules"].webhook_env == "DISCORD_RULES_WEBHOOK_URL"
    assert dsm.PAGE_DEFINITIONS["rules"].message_id_env == "DISCORD_RULES_MESSAGE_ID"
    assert dsm.PAGE_DEFINITIONS["faq"].webhook_env == "DISCORD_FAQ_WEBHOOK_URL"
    assert dsm.PAGE_DEFINITIONS["faq"].message_id_env == "DISCORD_FAQ_MESSAGE_ID"
    assert dsm.PAGE_DEFINITIONS["downloads"].webhook_env == "DISCORD_DOWNLOADS_WEBHOOK_URL"
    assert dsm.PAGE_DEFINITIONS["downloads"].message_id_env == "DISCORD_DOWNLOADS_MESSAGE_ID"
    assert dsm.PAGE_DEFINITIONS["beta-info"].webhook_env == "DISCORD_BETA_INFO_WEBHOOK_URL"
    assert dsm.PAGE_DEFINITIONS["beta-info"].message_id_env == "DISCORD_BETA_INFO_MESSAGE_ID"


def test_admin_editable_payload_can_be_saved_and_reset(tmp_path):
    store_path = tmp_path / "discord.json"
    payload = dsm.build_payload("faq")
    payload["embeds"][1]["description"] = "Edited FAQ text for Discord."

    saved = dsm.save_page_payload("faq", payload, store_path=store_path)

    assert saved["embeds"][1]["description"] == "Edited FAQ text for Discord."
    assert dsm.page_payload_source("faq", store_path=store_path) == "custom"
    assert dsm.build_effective_payload("faq", store_path=store_path)["embeds"][1]["description"] == (
        "Edited FAQ text for Discord."
    )

    reset = dsm.reset_page_payload("faq", store_path=store_path)

    assert reset == dsm.build_payload("faq")
    assert dsm.page_payload_source("faq", store_path=store_path) == "default"


def test_admin_payload_rejects_secret_like_text(tmp_path):
    payload = dsm.build_payload("downloads")
    payload["embeds"][0]["description"] = "Use this webhook during setup."

    with pytest.raises(ValueError):
        dsm.save_page_payload("downloads", payload, store_path=tmp_path / "discord.json")


def test_created_message_id_can_be_persisted_to_store(monkeypatch, tmp_path):
    calls = []

    class FakeResponse:
        status_code = 200

        def json(self):
            return {"id": "5555555555"}

    def fake_post(url, json, timeout):
        calls.append({"url": url, "json": json})
        return FakeResponse()

    monkeypatch.setattr(dsm.httpx, "post", fake_post)
    monkeypatch.setattr(dsm._settings, "DISCORD_RULES_MESSAGE_ID", "")
    store_path = tmp_path / "discord.json"

    result = dsm.publish_static_message(
        "rules",
        webhook_url="https://hooks.example.test/webhook/secret",
        message_id="",
        store_path=store_path,
        persist_created_message_id=True,
    )

    assert result.ok is True
    assert result.action == "create"
    assert dsm.page_admin_summary("rules", store_path=store_path)["message_id"] == "5555555555"
    assert calls[0]["json"] == dsm.build_payload("rules")


def test_missing_webhook_url_exits_cleanly(monkeypatch, capsys):
    calls = []
    monkeypatch.setattr(dsm.httpx, "post", lambda *args, **kwargs: calls.append((args, kwargs)))
    monkeypatch.setattr(dsm.httpx, "patch", lambda *args, **kwargs: calls.append((args, kwargs)))
    monkeypatch.setattr(dsm, "_clean_setting", lambda _explicit, _name: "")

    code = dsm.main(["publish", "rules"])

    captured = capsys.readouterr()
    assert code == 2
    assert "DISCORD_RULES_WEBHOOK_URL is not configured" in captured.out
    assert "http" not in captured.out.lower()
    assert calls == []


@pytest.mark.parametrize("page_key", ["rules", "faq", "downloads", "beta-info"])
def test_missing_message_id_uses_post_with_wait_true(page_key, monkeypatch, tmp_path):
    calls = []

    class FakeResponse:
        status_code = 200

        def json(self):
            return {"id": "9876543210"}

    def fake_post(url, json, timeout):
        calls.append({"method": "POST", "url": url, "json": json, "timeout": timeout})
        return FakeResponse()

    def fake_patch(*args, **kwargs):
        raise AssertionError("PATCH should not be used for first publish")

    monkeypatch.setattr(dsm.httpx, "post", fake_post)
    monkeypatch.setattr(dsm.httpx, "patch", fake_patch)

    result = dsm.publish_static_message(
        page_key,
        webhook_url="https://hooks.example.test/webhook/secret?ignored=true",
        message_id="",
        store_path=tmp_path / "discord.json",
    )

    assert result.ok is True
    assert result.action == "create"
    assert result.message_id == "9876543210"
    assert len(calls) == 1
    assert calls[0]["url"] == "https://hooks.example.test/webhook/secret?wait=true"
    assert calls[0]["json"] == dsm.build_payload(page_key)


@pytest.mark.parametrize("page_key", ["rules", "faq", "downloads", "beta-info"])
def test_configured_message_id_uses_patch_update(page_key, monkeypatch, tmp_path):
    calls = []

    class FakeResponse:
        status_code = 200

        def json(self):
            return {"id": "1234567890"}

    def fake_post(*args, **kwargs):
        raise AssertionError("POST should not be used when message ID is configured")

    def fake_patch(url, json, timeout):
        calls.append({"method": "PATCH", "url": url, "json": json, "timeout": timeout})
        return FakeResponse()

    monkeypatch.setattr(dsm.httpx, "post", fake_post)
    monkeypatch.setattr(dsm.httpx, "patch", fake_patch)

    result = dsm.publish_static_message(
        page_key,
        webhook_url="https://hooks.example.test/webhook/secret",
        message_id="1234567890",
        store_path=tmp_path / "discord.json",
    )

    assert result.ok is True
    assert result.action == "update"
    assert result.message_id == "1234567890"
    assert len(calls) == 1
    assert calls[0]["url"] == "https://hooks.example.test/webhook/secret/messages/1234567890"
    assert calls[0]["json"] == dsm.build_payload(page_key)


def test_invalid_message_id_fails_before_webhook_call(monkeypatch, capsys):
    calls = []
    monkeypatch.setattr(dsm.httpx, "post", lambda *args, **kwargs: calls.append((args, kwargs)))
    monkeypatch.setattr(dsm.httpx, "patch", lambda *args, **kwargs: calls.append((args, kwargs)))
    monkeypatch.setattr(
        dsm,
        "_clean_setting",
        lambda _explicit, name: (
            "https://hooks.example.test/release/test"
            if name == "DISCORD_RULES_WEBHOOK_URL"
            else "not-a-snowflake"
        ),
    )

    code = dsm.main(["publish", "rules"])

    captured = capsys.readouterr()
    assert code == 2
    assert "DISCORD_RULES_MESSAGE_ID is invalid" in captured.out
    assert calls == []


def test_webhook_url_is_never_included_in_logs_or_payload(monkeypatch, caplog):
    def fail_post(*args, **kwargs):
        raise httpx.ConnectError("failed for https://hooks.example.test/webhook/secret")

    monkeypatch.setattr(dsm.httpx, "post", fail_post)
    caplog.set_level(logging.WARNING, logger="app.discord_static_messages")

    result = dsm.publish_rules_message(
        webhook_url="https://hooks.example.test/webhook/secret",
        message_id="",
    )

    assert result.ok is False
    assert "hooks.example.test" not in caplog.text
    assert "secret" not in caplog.text
    assert "hooks.example.test" not in _payload_text(dsm.build_rules_payload())


@pytest.mark.parametrize("page_key", ["rules", "faq", "downloads", "beta-info"])
def test_payload_contains_no_forbidden_secret_like_fields(page_key):
    text = _payload_text(dsm.build_payload(page_key)).lower()

    forbidden = [
        "webhook",
        "token",
        "password",
        "secret",
        "private key",
        "/admin",
        "/opt/",
        ".env",
        "localhost",
        "127.0.0.1",
        "signed url",
        "hooks.example.test",
    ]
    assert not any(item in text for item in forbidden)


def test_main_output_never_prints_webhook_url(monkeypatch, capsys):
    class FakeResponse:
        status_code = 200
        headers = {}

        def json(self):
            return {"id": "24680"}

    monkeypatch.setattr(dsm.httpx, "post", lambda *args, **kwargs: FakeResponse())
    monkeypatch.setattr(
        dsm,
        "_clean_setting",
        lambda _explicit, name: (
            "https://hooks.example.test/webhook/secret"
            if name == "DISCORD_FAQ_WEBHOOK_URL"
            else ""
        ),
    )

    code = dsm.main(["publish", "faq"])

    captured = capsys.readouterr()
    assert code == 0
    assert "24680" in captured.out
    assert "hooks.example.test" not in captured.out
    assert "secret" not in captured.out


def test_retry_behavior_is_bounded(monkeypatch, tmp_path):
    calls = []

    class FakeResponse:
        status_code = 500
        headers = {}

        def json(self):
            return {}

    def fake_post(url, json, timeout):
        calls.append(url)
        return FakeResponse()

    monkeypatch.setattr(dsm.httpx, "post", fake_post)

    result = dsm.publish_static_message(
        "downloads",
        webhook_url="https://hooks.example.test/webhook/secret",
        message_id="",
        max_attempts=10,
        store_path=tmp_path / "discord.json",
    )

    assert result.ok is False
    assert len(calls) == 2


def test_rate_limit_handling_uses_response_retry_after(monkeypatch, caplog, tmp_path):
    calls = []
    sleeps = []

    class FakeResponse:
        status_code = 429
        headers = {}

        def json(self):
            return {"retry_after": 0.25}

    def fake_post(url, json, timeout):
        calls.append(url)
        return FakeResponse()

    monkeypatch.setattr(dsm.httpx, "post", fake_post)
    monkeypatch.setattr(dsm.time, "sleep", lambda seconds: sleeps.append(seconds))
    caplog.set_level(logging.WARNING, logger="app.discord_static_messages")

    result = dsm.publish_static_message(
        "faq",
        webhook_url="https://hooks.example.test/webhook/secret",
        message_id="",
        store_path=tmp_path / "discord.json",
    )

    assert result.ok is False
    assert len(calls) == 2
    assert sleeps == [0.25]
    assert "rate limited" in caplog.text
    assert "retry_after_ms=250" in caplog.text
    assert "hooks.example.test" not in caplog.text


def test_discord_failure_does_not_dump_raw_secrets(monkeypatch, caplog, tmp_path):
    def fail_post(*args, **kwargs):
        raise httpx.ConnectError("failed token=raw-secret-value")

    monkeypatch.setattr(dsm.httpx, "post", fail_post)
    caplog.set_level(logging.WARNING, logger="app.discord_static_messages")

    result = dsm.publish_static_message(
        "beta-info",
        webhook_url="https://hooks.example.test/webhook/secret",
        message_id="",
        store_path=tmp_path / "discord.json",
    )

    assert result.ok is False
    assert "ConnectError" in caplog.text
    assert "raw-secret-value" not in caplog.text
    assert "token=" not in caplog.text
