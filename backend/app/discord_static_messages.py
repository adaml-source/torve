"""Publish official static Discord server messages."""
from __future__ import annotations

import argparse
import json
import logging
import os
import re
import time
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import quote, urlparse, urlunparse

import httpx

try:
    from app.config import settings as _settings
except Exception:  # pragma: no cover - CLI should still fail cleanly without app env
    _settings = None


_log = logging.getLogger(__name__)

_DISCORD_TIMEOUT = httpx.Timeout(connect=2.0, read=3.0, write=2.0, pool=2.0)
_MAX_ATTEMPTS = 2
_MESSAGE_ID_RE = re.compile(r"^\d{5,30}$")
_SPACE_RE = re.compile(r"\s+")
_STORE_VERSION = 1
_FORBIDDEN_PAYLOAD_FRAGMENTS = (
    "webhook",
    "token",
    "password",
    "secret",
    "private key",
    "signed url",
    "/admin",
    "/opt/",
    ".env",
    "localhost",
    "127.0.0.1",
)

_TORVE_GOLD = 0xF5B301
_LEGAL_GREEN = 0x36C275
_GREEN = _LEGAL_GREEN
_DANGER_RED = 0xEF4444
_WARNING_ORANGE = 0xF59E0B
_PURPLE = 0xA855F7
_BLUE = 0x4D8DFF
_GRAY = 0x6B7280
_CYAN = 0x22D3EE
_TEAL = 0x14B8A6


@dataclass(frozen=True)
class PublishResult:
    ok: bool
    action: str
    message_id: str | None = None


@dataclass(frozen=True)
class PageDefinition:
    key: str
    webhook_env: str
    message_id_env: str
    username: str
    payload_builder: Callable[[], dict]
    success_label: str


def build_rules_payload() -> dict:
    """Build the #rules embed payload.

    The payload is static and intentionally contains only public Torve links.
    """
    return {
        "username": "Torve Rules Bot",
        "content": "",
        "allowed_mentions": {"parse": []},
        "embeds": [
            {
                "title": "Torve Community Rules",
                "description": (
                    "Welcome to the Torve community. These rules keep support, bug reports, "
                    "and product discussion useful, legal, and safe for everyone."
                ),
                "color": _TORVE_GOLD,
            },
            {
                "title": "Rule 1: Legal Sources",
                "description": (
                    "Use Torve only with media, services, and content sources you are legally "
                    "allowed to access. You are responsible for following the laws and terms "
                    "that apply to your region and services."
                ),
                "color": _LEGAL_GREEN,
            },
            {
                "title": "Rule 2: No Provider Sourcing",
                "description": (
                    "Do not ask where to buy, find, scrape, or source providers, playlists, "
                    "indexers, streams, or catalogs. Keep provider-specific sourcing and sales "
                    "out of this server."
                ),
                "color": _DANGER_RED,
            },
            {
                "title": "Rule 3: No Piracy Links or Illegal Streams",
                "description": (
                    "Do not post, request, hint at, or troubleshoot piracy links, illegal "
                    "streams, copyrighted files, unauthorized mirrors, or instructions for "
                    "bypassing access controls."
                ),
                "color": _WARNING_ORANGE,
            },
            {
                "title": "Rule 4: No Credential Sharing",
                "description": (
                    "Do not share, sell, trade, request, or expose accounts, invite-only "
                    "access, cookies, login details, or other private access material."
                ),
                "color": _PURPLE,
            },
            {
                "title": "Rule 5: Useful Bug Reports",
                "description": (
                    "When reporting a bug, include the platform, app version, what you expected, "
                    "what happened, and repeatable steps. Screenshots and logs are welcome after "
                    "you remove private details."
                ),
                "color": _BLUE,
            },
            {
                "title": "Rule 6: Respect and Conduct",
                "description": (
                    "Be direct, patient, and respectful. Harassment, hate speech, personal "
                    "attacks, spam, and disruptive behavior are not welcome here."
                ),
                "color": _GRAY,
            },
            {
                "title": "Important Links",
                "description": (
                    "- Website: https://torve.app\n"
                    "- Download: https://torve.app/download.html\n"
                    "- Privacy: https://torve.app/privacy.html\n"
                    "- Account deletion: https://torve.app/account-deletion.html\n"
                    "- Refund help: https://torve.app/refund.html"
                ),
                "color": _CYAN,
            },
        ],
    }


def build_faq_payload() -> dict:
    return _payload(
        "Torve FAQ Bot",
        [
            _embed(
                "Torve FAQ",
                "Frequently asked questions for setup, downloads, integrations, and support.",
                _TORVE_GOLD,
            ),
            _embed(
                "What is Torve?",
                (
                    "Torve is a cross-platform media hub for Android TV, Fire TV, Windows, "
                    "and mobile. It helps users organize and use their own legal sources, "
                    "libraries, subscriptions, and supported integrations."
                ),
                _TEAL,
            ),
            _embed(
                "Where do I download Torve?",
                (
                    "Use the official download page:\n"
                    "https://torve.app/download.html\n\n"
                    "Only use download links posted by Torve Team or shown on the official website."
                ),
                _CYAN,
            ),
            _embed(
                "Why does Windows show a warning?",
                (
                    "The Windows installer may currently be unsigned. Windows can show a warning "
                    "for unsigned installers. This is expected until code signing is added."
                ),
                _WARNING_ORANGE,
            ),
            _embed(
                "How should I set up multiple devices?",
                (
                    "Set up Torve on one device first. After it works, use QR transfer for additional "
                    "devices. After transfer, use Refresh App if catalogs, channels, or sources do not "
                    "appear immediately."
                ),
                _BLUE,
            ),
            _embed(
                "Can I ask for IPTV providers or links?",
                (
                    "No. Do not ask for or share provider names, seller contacts, trials, "
                    "playlists, private access details, piracy links, or illegal content sources."
                ),
                _DANGER_RED,
            ),
            _embed(
                "How do I report a bug?",
                (
                    "Use #bugs. Include device, platform, Torve version, what happened, what you "
                    "expected, steps to reproduce, and screenshots or video if useful."
                ),
                _BLUE,
            ),
            _embed(
                "Where do I request features?",
                (
                    "Use #suggestions. Keep requests specific, explain the use case, and mention "
                    "the platform if it matters."
                ),
                _PURPLE,
            ),
            _embed(
                "How do I join beta testing?",
                (
                    "Beta access is manual. Be active, report issues clearly, and mention what "
                    "devices you can test. Torve Team may assign the Beta Tester role."
                ),
                _GRAY,
            ),
        ],
    )


def build_downloads_payload() -> dict:
    return _payload(
        "Torve Downloads Bot",
        [
            _embed(
                "Torve Downloads",
                "Official Torve download links and setup notes.",
                _TORVE_GOLD,
            ),
            _embed(
                "Official Download Page",
                "Download Torve here:\nhttps://torve.app/download.html\n\nOnly use official Torve links.",
                _CYAN,
            ),
            _embed(
                "Available Builds",
                (
                    "Current direct downloads may include:\n"
                    "- Windows MSI\n"
                    "- Fire TV APK\n"
                    "- Android TV APK\n"
                    "- Optional direct Android mobile APK when available\n\n"
                    "Google Play or store installs may remain the preferred path where available."
                ),
                _GREEN,
            ),
            _embed(
                "Windows Installer Note",
                (
                    "The Windows installer may currently be unsigned. Windows can show a warning "
                    "during installation. This is expected until code signing is added."
                ),
                _WARNING_ORANGE,
            ),
            _embed(
                "Android TV and Fire TV",
                (
                    "Android TV and Fire TV builds may require sideloading if not installed through "
                    "a store. Make sure installation from trusted unknown sources is enabled only "
                    "when needed."
                ),
                _TEAL,
            ),
            _embed(
                "Recommended Setup Flow",
                (
                    "1. Install Torve on one device.\n"
                    "2. Log in and configure your sources.\n"
                    "3. Confirm playback, catalogs, channels, and EPG work.\n"
                    "4. Use QR transfer for additional devices.\n"
                    "5. Use Refresh App if content does not appear immediately."
                ),
                _BLUE,
            ),
            _embed(
                "Checksums",
                (
                    "When SHA-256 checksum links are provided, you can use them to verify downloaded "
                    "files. Only compare against checksums from the official Torve download page."
                ),
                _PURPLE,
            ),
            _embed(
                "Release Announcements",
                (
                    "New public releases are announced in #announcements. Download links remain "
                    "available from the official download page."
                ),
                _GRAY,
            ),
        ],
    )


def build_beta_info_payload() -> dict:
    return _payload(
        "Torve Beta Bot",
        [
            _embed(
                "Torve Beta Program",
                "Information for selected Torve beta testers.",
                _TORVE_GOLD,
            ),
            _embed(
                "What Beta Access Means",
                (
                    "Beta testers may receive early builds, UI experiments, playback changes, "
                    "IPTV/EPG fixes, and integration updates before public release."
                ),
                _TEAL,
            ),
            _embed(
                "Beta Builds Can Break",
                (
                    "Beta builds may contain bugs, regressions, crashes, incomplete UI, or "
                    "experimental behavior. Do not use beta builds if you need maximum stability."
                ),
                _WARNING_ORANGE,
            ),
            _embed(
                "How to Request Beta Access",
                (
                    "Be active in the community, report issues clearly, and mention which devices "
                    "you can test. Beta Tester access is assigned manually by Torve Team."
                ),
                _BLUE,
            ),
            _embed(
                "Good Beta Feedback",
                (
                    "Useful feedback includes device, platform, Torve version, what changed, what "
                    "broke, steps to reproduce, screenshots, videos, and logs if safe."
                ),
                _GREEN,
            ),
            _embed(
                "What Not to Post",
                (
                    "Do not post provider names, playlist links, private access strings, account "
                    "logins, piracy links, illegal content sources, or private user data."
                ),
                _DANGER_RED,
            ),
            _embed(
                "Where to Post",
                (
                    "Use #beta-feedback for beta discussion and beta bugs. Use public #bugs only "
                    "for issues that also affect public builds."
                ),
                _PURPLE,
            ),
            _embed(
                "Privacy and Safety",
                (
                    "Remove private details from screenshots and logs before posting. Do not expose "
                    "account emails, private access strings, device file locations, or source login details."
                ),
                _GRAY,
            ),
        ],
    )


def _payload(username: str, embeds: list[dict]) -> dict:
    return {
        "username": username,
        "content": "",
        "allowed_mentions": {"parse": []},
        "embeds": embeds,
    }


def _embed(title: str, description: str, color: int) -> dict:
    return {
        "title": title,
        "description": description,
        "color": color,
    }


PAGE_DEFINITIONS: dict[str, PageDefinition] = {
    "rules": PageDefinition(
        key="rules",
        webhook_env="DISCORD_RULES_WEBHOOK_URL",
        message_id_env="DISCORD_RULES_MESSAGE_ID",
        username="Torve Rules Bot",
        payload_builder=build_rules_payload,
        success_label="rules",
    ),
    "faq": PageDefinition(
        key="faq",
        webhook_env="DISCORD_FAQ_WEBHOOK_URL",
        message_id_env="DISCORD_FAQ_MESSAGE_ID",
        username="Torve FAQ Bot",
        payload_builder=build_faq_payload,
        success_label="FAQ",
    ),
    "downloads": PageDefinition(
        key="downloads",
        webhook_env="DISCORD_DOWNLOADS_WEBHOOK_URL",
        message_id_env="DISCORD_DOWNLOADS_MESSAGE_ID",
        username="Torve Downloads Bot",
        payload_builder=build_downloads_payload,
        success_label="downloads",
    ),
    "beta-info": PageDefinition(
        key="beta-info",
        webhook_env="DISCORD_BETA_INFO_WEBHOOK_URL",
        message_id_env="DISCORD_BETA_INFO_MESSAGE_ID",
        username="Torve Beta Bot",
        payload_builder=build_beta_info_payload,
        success_label="beta info",
    ),
}


def build_payload(page_key: str) -> dict:
    return _page_definition(page_key).payload_builder()


def build_effective_payload(page_key: str, *, store_path: str | Path | None = None) -> dict:
    """Return the saved admin-editable payload, falling back to the code default."""
    page = _page_definition(page_key)
    record = _page_record(page.key, store_path=store_path)
    payload = record.get("payload")
    if isinstance(payload, dict):
        try:
            return normalize_static_payload(page.key, payload)
        except ValueError:
            _log.warning("Discord static message override is invalid page=%s", page.key)
    return page.payload_builder()


def page_payload_source(page_key: str, *, store_path: str | Path | None = None) -> str:
    page = _page_definition(page_key)
    record = _page_record(page.key, store_path=store_path)
    return "custom" if isinstance(record.get("payload"), dict) else "default"


def page_admin_summary(page_key: str, *, store_path: str | Path | None = None) -> dict:
    page = _page_definition(page_key)
    message_id = _effective_message_id(page, explicit=None, store_path=store_path)
    return {
        "key": page.key,
        "label": page.success_label,
        "username": page.username,
        "webhook_env": page.webhook_env,
        "message_id_env": page.message_id_env,
        "webhook_configured": bool(_clean_setting(None, page.webhook_env)),
        "message_id_configured": bool(message_id),
        "message_id": message_id or None,
        "source": page_payload_source(page.key, store_path=store_path),
    }


def list_admin_pages(*, store_path: str | Path | None = None) -> list[dict]:
    return [
        page_admin_summary(page_key, store_path=store_path)
        for page_key in sorted(PAGE_DEFINITIONS)
    ]


def normalize_static_payload(page_key: str, payload: dict) -> dict:
    """Validate and normalize an admin-edited Discord embed payload."""
    page = _page_definition(page_key)
    if not isinstance(payload, dict):
        raise ValueError("payload must be an object")

    username = _clean_inline(str(payload.get("username") or page.username), max_length=80)
    if not username:
        username = page.username

    raw_embeds = payload.get("embeds")
    if not isinstance(raw_embeds, list) or not raw_embeds:
        raise ValueError("payload.embeds must contain at least one embed")
    if len(raw_embeds) > 10:
        raise ValueError("payload.embeds cannot contain more than 10 embeds")

    embeds: list[dict] = []
    for raw in raw_embeds:
        if not isinstance(raw, dict):
            raise ValueError("each embed must be an object")
        title = _clean_inline(str(raw.get("title") or ""), max_length=256)
        description = str(raw.get("description") or "").strip()
        if not title:
            raise ValueError("each embed needs a title")
        if not description:
            raise ValueError("each embed needs a description")
        if len(description) > 4096:
            raise ValueError("embed descriptions cannot exceed 4096 characters")
        embeds.append(
            _embed(
                title,
                description,
                _normalize_color(raw.get("color", _TORVE_GOLD)),
            )
        )

    normalized = _payload(username, embeds)
    _reject_forbidden_payload_text(normalized)
    return normalized


def save_page_payload(
    page_key: str,
    payload: dict,
    *,
    store_path: str | Path | None = None,
) -> dict:
    page = _page_definition(page_key)
    normalized = normalize_static_payload(page.key, payload)
    store = _read_store(store_path=store_path)
    pages = store.setdefault("pages", {})
    record = pages.setdefault(page.key, {})
    record["payload"] = normalized
    _write_store(store, store_path=store_path)
    return normalized


def reset_page_payload(page_key: str, *, store_path: str | Path | None = None) -> dict:
    page = _page_definition(page_key)
    store = _read_store(store_path=store_path)
    record = store.setdefault("pages", {}).setdefault(page.key, {})
    record.pop("payload", None)
    _write_store(store, store_path=store_path)
    return page.payload_builder()


def publish_rules_message(
    *,
    webhook_url: str | None = None,
    message_id: str | None = None,
    max_attempts: int = _MAX_ATTEMPTS,
    store_path: str | Path | None = None,
) -> PublishResult:
    """Backward-compatible helper for the #rules message."""
    return publish_static_message(
        "rules",
        webhook_url=webhook_url,
        message_id=message_id,
        max_attempts=max_attempts,
        store_path=store_path,
    )


def publish_static_message(
    page_key: str,
    *,
    webhook_url: str | None = None,
    message_id: str | None = None,
    payload: dict | None = None,
    max_attempts: int = _MAX_ATTEMPTS,
    store_path: str | Path | None = None,
    persist_created_message_id: bool = False,
) -> PublishResult:
    """Publish or update a configured static Discord message.

    Missing webhook config is a clean failure, not a traceback.
    """
    page = _page_definition(page_key)
    url = _clean_setting(webhook_url, page.webhook_env)
    if not url:
        _log.warning("Discord static message webhook is not configured page=%s", page.key)
        return PublishResult(ok=False, action="missing_config")

    existing_message_id = _effective_message_id(
        page,
        explicit=message_id,
        store_path=store_path,
    )
    if existing_message_id and not _MESSAGE_ID_RE.match(existing_message_id):
        _log.warning("Discord static message ID is invalid page=%s", page.key)
        return PublishResult(ok=False, action="invalid_message_id")
    try:
        payload = normalize_static_payload(
            page.key,
            payload if payload is not None else build_effective_payload(page.key, store_path=store_path),
        )
    except ValueError:
        _log.warning("Discord static message payload is invalid page=%s", page.key)
        return PublishResult(ok=False, action="invalid_payload")
    action = "update" if existing_message_id else "create"
    target_url = (
        _webhook_message_url(url, existing_message_id)
        if existing_message_id
        else _webhook_wait_url(url)
    )
    method = httpx.patch if existing_message_id else httpx.post

    response = _send_with_retry(method, target_url, payload, max_attempts=max_attempts, action=action)
    if response is None:
        return PublishResult(ok=False, action=action)

    returned_id = _message_id_from_response(response) or existing_message_id
    if action == "create" and persist_created_message_id and returned_id:
        _save_message_id(page.key, returned_id, store_path=store_path)
    _log.info(
        "Discord static message published page=%s action=%s message_id=%s",
        page.key,
        action,
        returned_id or "unknown",
    )
    return PublishResult(ok=True, action=action, message_id=returned_id)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Publish Torve static Discord messages.")
    subparsers = parser.add_subparsers(dest="command", required=True)

    publish_parser = subparsers.add_parser("publish", help="Publish or update a static page")
    publish_parser.add_argument("page", choices=sorted(PAGE_DEFINITIONS))

    args = parser.parse_args(argv)

    if args.command == "publish":
        page = _page_definition(args.page)
        result = publish_static_message(args.page)
        if not result.ok:
            if result.action == "missing_config":
                print(f"{page.webhook_env} is not configured")
                return 2
            if result.action == "invalid_message_id":
                print(f"{page.message_id_env} is invalid")
                return 2
            if result.action == "invalid_payload":
                print(f"Discord {page.success_label} message payload is invalid")
                return 2
            print(f"Discord {page.success_label} message publish failed")
            return 1
        if result.action == "create":
            print(f"Discord {page.success_label} message created message_id={result.message_id or 'unknown'}")
        else:
            print(f"Discord {page.success_label} message updated message_id={result.message_id or 'unknown'}")
        return 0

    parser.error("unsupported command")


def _send_with_retry(
    method,
    url: str,
    payload: dict,
    *,
    max_attempts: int,
    action: str,
) -> httpx.Response | None:
    attempts = max(1, min(max_attempts, 2))
    for attempt in range(1, attempts + 1):
        try:
            response = method(url, json=payload, timeout=_DISCORD_TIMEOUT)
        except Exception as exc:  # noqa: BLE001 - sanitized operational log
            _log.warning(
                "Discord static message webhook failed action=%s error_type=%s attempt=%d/%d",
                action,
                exc.__class__.__name__,
                attempt,
                attempts,
            )
            continue

        if 200 <= response.status_code < 300:
            return response

        retry_after = _retry_after_seconds(response)
        if response.status_code == 429:
            _log.warning(
                "Discord static message webhook rate limited action=%s retry_after_ms=%s attempt=%d/%d",
                action,
                _retry_after_ms(retry_after),
                attempt,
                attempts,
            )
            if attempt < attempts and retry_after is not None and retry_after <= 2.0:
                time.sleep(max(0.0, retry_after))
            continue

        _log.warning(
            "Discord static message webhook failed action=%s status=%s attempt=%d/%d",
            action,
            response.status_code,
            attempt,
            attempts,
        )
    return None


def _retry_after_seconds(response: httpx.Response) -> float | None:
    header_value = response.headers.get("retry-after") if hasattr(response, "headers") else None
    if header_value:
        try:
            return max(0.0, float(header_value))
        except ValueError:
            pass
    try:
        data = response.json()
    except Exception:  # noqa: BLE001
        return None
    if isinstance(data, dict):
        value = data.get("retry_after")
        if isinstance(value, (int, float)):
            return max(0.0, float(value))
    return None


def _retry_after_ms(retry_after: float | None) -> str:
    if retry_after is None:
        return "unknown"
    return str(int(retry_after * 1000))


def _message_id_from_response(response: httpx.Response) -> str | None:
    try:
        data = response.json()
    except Exception:  # noqa: BLE001
        return None
    value = data.get("id") if isinstance(data, dict) else None
    if value is None:
        return None
    return _clean_inline(str(value), max_length=80)


def _store_path(explicit: str | Path | None = None) -> Path:
    if explicit is not None:
        return Path(explicit)
    if _settings is not None:
        configured = str(getattr(_settings, "DISCORD_STATIC_MESSAGES_FILE", "") or "").strip()
        if configured:
            return Path(configured)
    return Path(os.environ.get("DISCORD_STATIC_MESSAGES_FILE", "data/discord_static_messages.json"))


def _empty_store() -> dict:
    return {"version": _STORE_VERSION, "pages": {}}


def _read_store(*, store_path: str | Path | None = None) -> dict:
    path = _store_path(store_path)
    if not path.exists():
        return _empty_store()
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except Exception:  # noqa: BLE001
        _log.warning("Discord static message store could not be read")
        return _empty_store()
    if not isinstance(data, dict):
        return _empty_store()
    pages = data.get("pages")
    if not isinstance(pages, dict):
        data["pages"] = {}
    data["version"] = _STORE_VERSION
    return data


def _write_store(store: dict, *, store_path: str | Path | None = None) -> None:
    path = _store_path(store_path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = path.with_name(f".{path.name}.tmp")
    tmp_path.write_text(
        json.dumps(store, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    tmp_path.replace(path)


def _page_record(page_key: str, *, store_path: str | Path | None = None) -> dict:
    store = _read_store(store_path=store_path)
    pages = store.get("pages")
    if not isinstance(pages, dict):
        return {}
    record = pages.get(page_key)
    return record if isinstance(record, dict) else {}


def _save_message_id(page_key: str, message_id: str, *, store_path: str | Path | None = None) -> None:
    safe_message_id = _clean_inline(message_id, max_length=80)
    if not _MESSAGE_ID_RE.match(safe_message_id):
        return
    store = _read_store(store_path=store_path)
    record = store.setdefault("pages", {}).setdefault(page_key, {})
    record["message_id"] = safe_message_id
    _write_store(store, store_path=store_path)


def _effective_message_id(
    page: PageDefinition,
    *,
    explicit: str | None,
    store_path: str | Path | None = None,
) -> str:
    if explicit is not None:
        return explicit.strip()
    configured = _clean_setting(None, page.message_id_env)
    if configured:
        return configured
    stored = _page_record(page.key, store_path=store_path).get("message_id")
    return str(stored or "").strip()


def _normalize_color(value) -> int:
    if isinstance(value, str):
        raw = value.strip()
        try:
            if raw.startswith("#"):
                color = int(raw[1:], 16)
            elif raw.lower().startswith("0x"):
                color = int(raw, 16)
            else:
                color = int(raw)
        except ValueError as exc:
            raise ValueError("embed color must be a valid integer color") from exc
    elif isinstance(value, int):
        color = value
    else:
        raise ValueError("embed color must be a valid integer color")
    if color < 0 or color > 0xFFFFFF:
        raise ValueError("embed color must be between 0x000000 and 0xFFFFFF")
    return color


def _reject_forbidden_payload_text(payload: dict) -> None:
    text = json.dumps(payload, ensure_ascii=True).lower()
    if any(fragment in text for fragment in _FORBIDDEN_PAYLOAD_FRAGMENTS):
        raise ValueError("payload contains forbidden private or secret-like text")


def _clean_setting(explicit: str | None, name: str) -> str:
    if explicit is not None:
        return explicit.strip()
    if _settings is not None:
        return str(getattr(_settings, name, "") or "").strip()
    return os.environ.get(name, "").strip()


def _page_definition(page_key: str) -> PageDefinition:
    try:
        return PAGE_DEFINITIONS[page_key]
    except KeyError as exc:
        raise ValueError(f"Unknown Discord static page: {page_key}") from exc


def _webhook_base_url(webhook_url: str) -> str:
    parsed = urlparse(webhook_url.strip())
    return urlunparse((parsed.scheme, parsed.netloc, parsed.path.rstrip("/"), "", "", ""))


def _webhook_wait_url(webhook_url: str) -> str:
    return f"{_webhook_base_url(webhook_url)}?wait=true"


def _webhook_message_url(webhook_url: str, message_id: str) -> str:
    safe_message_id = quote(_clean_inline(message_id, max_length=100), safe="")
    return f"{_webhook_base_url(webhook_url)}/messages/{safe_message_id}"


def _clean_inline(value: str, *, max_length: int) -> str:
    cleaned = _SPACE_RE.sub(" ", value).strip()
    if len(cleaned) <= max_length:
        return cleaned
    return cleaned[:max_length]


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
