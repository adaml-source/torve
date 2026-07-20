from app import discord_static_messages as dsm
from app.config import settings

ADMIN_HEADERS = {"X-Admin-Secret": "test-admin-secret"}


def _configure_admin(monkeypatch, tmp_path):
    monkeypatch.setattr(settings, "PADDLE_ADMIN_SECRET", "test-admin-secret")
    monkeypatch.setattr(settings, "DISCORD_STATIC_MESSAGES_FILE", str(tmp_path / "discord.json"))
    monkeypatch.setattr(settings, "DISCORD_FAQ_WEBHOOK_URL", "")
    monkeypatch.setattr(settings, "DISCORD_FAQ_MESSAGE_ID", "")


def test_admin_discord_pages_require_admin_secret(client, monkeypatch, tmp_path):
    _configure_admin(monkeypatch, tmp_path)

    assert client.get("/admin/discord-static-messages/pages").status_code == 403
    assert (
        client.get(
            "/admin/discord-static-messages/pages",
            headers={"X-Admin-Secret": "wrong"},
        ).status_code
        == 403
    )


def test_admin_discord_pages_list_supported_pages(client, monkeypatch, tmp_path):
    _configure_admin(monkeypatch, tmp_path)

    response = client.get("/admin/discord-static-messages/pages", headers=ADMIN_HEADERS)

    assert response.status_code == 200
    keys = {page["key"] for page in response.json()["pages"]}
    assert keys == {"rules", "faq", "downloads", "beta-info"}
    assert "discord.com/api/webhooks" not in response.text


def test_admin_can_save_and_reload_faq_payload(client, monkeypatch, tmp_path):
    _configure_admin(monkeypatch, tmp_path)
    payload = dsm.build_payload("faq")
    payload["embeds"][1]["description"] = "Admin edited FAQ answer."

    saved = client.put(
        "/admin/discord-static-messages/faq",
        headers=ADMIN_HEADERS,
        json={"payload": payload},
    )
    loaded = client.get("/admin/discord-static-messages/faq", headers=ADMIN_HEADERS)

    assert saved.status_code == 200
    assert saved.json()["page"]["source"] == "custom"
    assert loaded.status_code == 200
    assert loaded.json()["payload"]["embeds"][1]["description"] == "Admin edited FAQ answer."


def test_admin_rejects_payload_with_secret_like_text(client, monkeypatch, tmp_path):
    _configure_admin(monkeypatch, tmp_path)
    payload = dsm.build_payload("faq")
    payload["embeds"][0]["description"] = "Paste this token in the channel."

    response = client.put(
        "/admin/discord-static-messages/faq",
        headers=ADMIN_HEADERS,
        json={"payload": payload},
    )

    assert response.status_code == 400
    assert response.json()["detail"]["error_code"] == "invalid_payload"


def test_admin_publish_uses_saved_payload_and_persists_created_message_id(
    client,
    monkeypatch,
    tmp_path,
):
    _configure_admin(monkeypatch, tmp_path)
    monkeypatch.setattr(
        settings,
        "DISCORD_FAQ_WEBHOOK_URL",
        "https://hooks.example.test/webhook/secret",
    )
    calls = []

    class FakeResponse:
        status_code = 200

        def json(self):
            return {"id": "7777777777"}

    def fake_post(url, json, timeout):
        calls.append({"url": url, "json": json})
        return FakeResponse()

    monkeypatch.setattr(dsm.httpx, "post", fake_post)
    payload = dsm.build_payload("faq")
    payload["embeds"][1]["description"] = "Saved then published FAQ answer."

    client.put(
        "/admin/discord-static-messages/faq",
        headers=ADMIN_HEADERS,
        json={"payload": payload},
    )
    response = client.post("/admin/discord-static-messages/faq/publish", headers=ADMIN_HEADERS)

    assert response.status_code == 200
    body = response.json()
    assert body["action"] == "create"
    assert body["message_id"] == "7777777777"
    assert body["page"]["message_id"] == "7777777777"
    assert calls[0]["url"] == "https://hooks.example.test/webhook/secret?wait=true"
    assert calls[0]["json"]["embeds"][1]["description"] == "Saved then published FAQ answer."
    assert "hooks.example.test" not in response.text
    assert "secret" not in response.text


def test_admin_publish_missing_webhook_is_clean(client, monkeypatch, tmp_path):
    _configure_admin(monkeypatch, tmp_path)

    response = client.post("/admin/discord-static-messages/faq/publish", headers=ADMIN_HEADERS)

    assert response.status_code == 503
    assert response.json()["detail"]["error_code"] == "webhook_not_configured"
    assert "discord.com/api/webhooks" not in response.text
