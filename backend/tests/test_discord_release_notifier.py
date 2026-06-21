import logging

import httpx

from app.discord_release_notifier import (
    ReleaseNotification,
    build_discord_release_payload,
    build_discord_test_payload,
    main,
    notify_release_published,
)


def _fields(payload):
    return {
        field["name"]: field["value"]
        for field in payload["embeds"][0]["fields"]
    }


def test_notifier_builds_expected_payload():
    payload = build_discord_release_payload(
        ReleaseNotification(
            platform="android_tv",
            version="1.2.3",
            build_type="stable",
            download_page_url="https://torve.app/download.html",
            release_notes_summary="<p>Faster startup and pairing fixes.</p>",
            file_size_bytes=12_345_678,
            checksum_sha256="a" * 64,
            artifact_filename="torve-android-tv-1.2.3.apk",
        )
    )

    embed = payload["embeds"][0]
    fields = _fields(payload)

    assert payload["allowed_mentions"] == {"parse": []}
    assert embed["title"] == "Torve Android TV / Fire TV 1.2.3 published"
    assert embed["url"] == "https://torve.app/download.html"
    assert embed["description"] == "Faster startup and pairing fixes."
    assert fields["Platform"] == "Android TV / Fire TV"
    assert fields["Version"] == "1.2.3"
    assert fields["Build"] == "public"
    assert fields["Download page"] == "https://torve.app/download.html"
    assert fields["File size"] == "11.8 MB (12345678 bytes)"
    assert fields["SHA-256"] == "a" * 64


def test_missing_webhook_url_disables_notification_cleanly(monkeypatch):
    calls = []
    monkeypatch.setattr(
        "app.discord_release_notifier.httpx.post",
        lambda *args, **kwargs: calls.append((args, kwargs)),
    )

    sent = notify_release_published(
        ReleaseNotification(platform="windows", version="1.2.3"),
        webhook_url="",
    )

    assert sent is False
    assert calls == []


def test_cli_test_message_posts_webhook_only_payload(monkeypatch):
    calls = []

    class FakeResponse:
        status_code = 204

    def fake_post(url, json, timeout):
        calls.append({"url": url, "json": json, "timeout": timeout})
        return FakeResponse()

    monkeypatch.setattr(
        "app.discord_release_notifier._setting",
        lambda _name, _default: "https://hooks.example.test/release/test",
    )
    monkeypatch.setattr("app.discord_release_notifier.httpx.post", fake_post)

    result = main([
        "--test-message",
        "TEST: Torve release webhook is connected. No new release was published.",
    ])

    assert result == 0
    assert len(calls) == 1
    payload = calls[0]["json"]
    assert payload == build_discord_test_payload(
        "TEST: Torve release webhook is connected. No new release was published."
    )
    assert "embeds" not in payload
    assert "platform" not in str(payload).lower()
    assert "version" not in str(payload).lower()


def test_discord_failure_is_sanitized_and_non_throwing(monkeypatch, caplog):
    def fail_post(*args, **kwargs):
        raise httpx.ConnectError(
            "failed posting to https://hooks.example.test/release/secret-token"
        )

    monkeypatch.setattr("app.discord_release_notifier.httpx.post", fail_post)
    caplog.set_level(logging.WARNING, logger="app.discord_release_notifier")

    sent = notify_release_published(
        ReleaseNotification(platform="windows", version="1.2.3"),
        webhook_url="https://hooks.example.test/release/secret-token",
    )

    assert sent is False
    assert "ConnectError" in caplog.text
    assert "secret-token" not in caplog.text
    assert "hooks.example.test" not in caplog.text


def test_windows_release_includes_unsigned_warning():
    payload = build_discord_release_payload(
        ReleaseNotification(
            platform="windows",
            version="1.2.3",
            artifact_filename="torve-windows-1.2.3.msi",
        )
    )

    fields = _fields(payload)
    assert "Windows unsigned warning" in fields
    assert "unknown publisher warning" in fields["Windows unsigned warning"]


def test_payload_excludes_private_urls_tokens_and_webhook_values():
    payload = build_discord_release_payload(
        ReleaseNotification(
            platform="windows",
            version="1.2.3",
            download_page_url="https://torve.app/download.html?token=download-secret",
            release_notes_summary=(
                "Notes at https://storage.internal/private.msi?token=abc "
                "signature=abc123 password=hunter2"
            ),
            checksum_sha256="b" * 64,
            artifact_filename="torve-windows-1.2.3.exe",
        )
    )

    payload_text = str(payload)
    assert "storage.internal" not in payload_text
    assert "download-secret" not in payload_text
    assert "token" not in payload_text.lower()
    assert "signature" not in payload_text.lower()
    assert "hunter2" not in payload_text
    assert "hooks.example.test" not in payload_text
    assert _fields(payload)["Download page"] == "https://torve.app/download.html"
