"""Discord notifications for published Torve downloadable releases."""
from __future__ import annotations

import argparse
import html
import logging
import os
import re
from dataclasses import dataclass
from urllib.parse import urlparse, urlunparse

import httpx

try:
    from app.config import settings as _settings
except Exception:  # pragma: no cover - CLI should still no-op cleanly without app env
    _settings = None


_log = logging.getLogger(__name__)

_DEFAULT_DOWNLOAD_URL = "https://torve.app/download.html"
_DEFAULT_TEST_MESSAGE = "TEST: Torve release webhook is connected. No new release was published."
_DISCORD_TIMEOUT = httpx.Timeout(connect=2.0, read=3.0, write=2.0, pool=2.0)
_MAX_ATTEMPTS = 2

_HTML_TAG_RE = re.compile(r"<[^>]+>")
_URL_RE = re.compile(r"https?://[^\s<>()]+", re.IGNORECASE)
_SECRET_PAIR_RE = re.compile(
    r"\b(?:authorization|credential|password|secret|signature|token|key|sig)"
    r"[A-Za-z0-9_-]*\s*[:=]\s*\S+",
    re.IGNORECASE,
)
_SPACE_RE = re.compile(r"\s+")
_SHA256_RE = re.compile(r"^[0-9a-fA-F]{64}$")


@dataclass(frozen=True)
class ReleaseNotification:
    platform: str
    version: str
    build_type: str | None = None
    download_page_url: str | None = None
    release_notes_summary: str | None = None
    file_size_bytes: int | None = None
    checksum_sha256: str | None = None
    artifact_filename: str | None = None


def build_discord_release_payload(release: ReleaseNotification) -> dict:
    """Build the JSON payload sent to Discord.

    Deliberately includes the public download page only, never direct artifact,
    admin, storage, signed, or webhook URLs.
    """
    platform = _platform_label(release.platform)
    version = _clean_inline(release.version, fallback="unknown")
    build_type = _normalize_build_type(release.build_type)
    download_page_url = _safe_download_page_url(release.download_page_url)

    fields = [
        {"name": "Platform", "value": platform, "inline": True},
        {"name": "Version", "value": version, "inline": True},
        {"name": "Build", "value": build_type, "inline": True},
        {"name": "Download page", "value": download_page_url, "inline": False},
    ]

    if release.file_size_bytes and release.file_size_bytes > 0:
        fields.append({
            "name": "File size",
            "value": _format_file_size(release.file_size_bytes),
            "inline": True,
        })

    checksum = _clean_sha256(release.checksum_sha256)
    if checksum:
        fields.append({"name": "SHA-256", "value": checksum, "inline": False})

    if _is_windows_installer(release):
        fields.append({
            "name": "Windows unsigned warning",
            "value": (
                "This Windows installer is currently unsigned. Windows may show "
                "an unknown publisher warning."
            ),
            "inline": False,
        })

    embed = {
        "title": f"Torve {platform} {version} published",
        "url": download_page_url,
        "color": 0x2F855A if build_type == "public" else 0xB7791F,
        "fields": fields,
    }

    notes = _clean_multiline(release.release_notes_summary, max_length=700)
    if notes:
        embed["description"] = notes

    return {
        "allowed_mentions": {"parse": []},
        "embeds": [embed],
    }


def build_discord_test_payload(message: str | None = None) -> dict:
    return {
        "content": _clean_inline(
            message,
            fallback=_DEFAULT_TEST_MESSAGE,
            max_length=1800,
        ),
        "allowed_mentions": {"parse": []},
    }


def notify_release_published(
    release: ReleaseNotification,
    *,
    webhook_url: str | None = None,
    max_attempts: int = _MAX_ATTEMPTS,
) -> bool:
    """Send a Discord notification and never raise to the release caller."""
    try:
        payload = build_discord_release_payload(release)
        sent = _post_discord_payload(
            payload,
            webhook_url=webhook_url,
            max_attempts=max_attempts,
            success_context="release",
        )
        if sent:
            _log.info(
                "Discord release notification sent platform=%s version=%s",
                _clean_inline(release.platform, fallback="unknown"),
                _clean_inline(release.version, fallback="unknown"),
            )
        return sent
    except Exception as exc:  # noqa: BLE001 - never fail the release flow
        _log.warning(
            "Discord release notification skipped error_type=%s",
            exc.__class__.__name__,
        )
        return False


def notify_webhook_test(
    message: str | None = None,
    *,
    webhook_url: str | None = None,
    max_attempts: int = _MAX_ATTEMPTS,
) -> bool:
    """Send a clearly marked webhook-only test notification."""
    try:
        return _post_discord_payload(
            build_discord_test_payload(message),
            webhook_url=webhook_url,
            max_attempts=max_attempts,
            success_context="test",
        )
    except Exception as exc:  # noqa: BLE001 - never fail operational callers
        _log.warning(
            "Discord release webhook test skipped error_type=%s",
            exc.__class__.__name__,
        )
        return False


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Send a Torve release notification to Discord.")
    parser.add_argument("--test-message", default=None)
    parser.add_argument("--platform", default=None)
    parser.add_argument("--version", default=None)
    parser.add_argument("--build-type", default=None)
    parser.add_argument("--download-page-url", default=None)
    parser.add_argument("--release-notes-summary", default=None)
    parser.add_argument("--file-size-bytes", type=int, default=None)
    parser.add_argument("--checksum-sha256", default=None)
    parser.add_argument("--artifact-filename", default=None)
    args = parser.parse_args(argv)

    if args.test_message is not None:
        sent = notify_webhook_test(args.test_message)
        print("Discord release webhook test sent" if sent else "Discord release webhook test not sent")
        return 0

    if not args.platform or not args.version:
        parser.error("--platform and --version are required unless --test-message is used")

    notify_release_published(
        ReleaseNotification(
            platform=args.platform,
            version=args.version,
            build_type=args.build_type,
            download_page_url=args.download_page_url,
            release_notes_summary=args.release_notes_summary,
            file_size_bytes=args.file_size_bytes,
            checksum_sha256=args.checksum_sha256,
            artifact_filename=args.artifact_filename,
        )
    )
    return 0


def _post_discord_payload(
    payload: dict,
    *,
    webhook_url: str | None,
    max_attempts: int,
    success_context: str,
) -> bool:
    url = (webhook_url if webhook_url is not None else _setting("DISCORD_RELEASE_WEBHOOK_URL", "")).strip()
    if not url:
        _log.info("Discord release webhook disabled")
        return False

    attempts = max(1, min(max_attempts, 3))

    for attempt in range(1, attempts + 1):
        try:
            resp = httpx.post(url, json=payload, timeout=_DISCORD_TIMEOUT)
        except Exception as exc:  # noqa: BLE001 - webhook failures are non-blocking
            _log.warning(
                "Discord release webhook failed error_type=%s attempt=%d/%d",
                exc.__class__.__name__,
                attempt,
                attempts,
            )
            continue

        status = getattr(resp, "status_code", 0)
        if 200 <= status < 300:
            _log.info("Discord release webhook sent context=%s", success_context)
            return True

        _log.warning(
            "Discord release webhook failed status=%s attempt=%d/%d",
            status,
            attempt,
            attempts,
        )

    return False


def _setting(name: str, default: str) -> str:
    if _settings is not None:
        return str(getattr(_settings, name, default) or default)
    return os.environ.get(name, default)


def _platform_label(platform: str | None) -> str:
    cleaned = _clean_inline(platform, fallback="unknown").lower().replace("-", "_").replace(" ", "_")
    labels = {
        "windows": "Windows",
        "win": "Windows",
        "android_tv": "Android TV / Fire TV",
        "fire_tv": "Android TV / Fire TV",
        "android_mobile": "Android mobile",
        "android": "Android",
    }
    return labels.get(cleaned, _clean_inline(platform, fallback="unknown"))


def _normalize_build_type(build_type: str | None) -> str:
    value = _clean_inline(build_type, fallback="public").lower()
    if value in {"stable", "release", "production", "prod", "public"}:
        return "public"
    if "beta" in value:
        return "beta"
    return value[:60] or "public"


def _safe_download_page_url(value: str | None) -> str:
    raw = (value or _setting("TORVE_PUBLIC_DOWNLOAD_URL", _DEFAULT_DOWNLOAD_URL) or _DEFAULT_DOWNLOAD_URL).strip()
    parsed = urlparse(raw)
    if parsed.scheme != "https" or not parsed.netloc:
        return _DEFAULT_DOWNLOAD_URL
    host = parsed.netloc.lower()
    path = parsed.path or "/"
    if "admin" in host or "/admin" in path.lower():
        return _DEFAULT_DOWNLOAD_URL
    return urlunparse((parsed.scheme, parsed.netloc, path, "", "", ""))


def _clean_inline(value: str | None, *, fallback: str, max_length: int = 120) -> str:
    cleaned = _clean_multiline(value, max_length=max_length)
    return cleaned.replace("\n", " ") if cleaned else fallback


def _clean_multiline(value: str | None, *, max_length: int) -> str:
    if not value:
        return ""
    text = html.unescape(str(value))
    text = _HTML_TAG_RE.sub(" ", text)
    text = _URL_RE.sub("[link redacted]", text)
    text = _SECRET_PAIR_RE.sub("[secret redacted]", text)
    text = _SPACE_RE.sub(" ", text).strip()
    if len(text) <= max_length:
        return text
    return text[: max_length - 3].rstrip() + "..."


def _clean_sha256(value: str | None) -> str | None:
    if not value:
        return None
    text = str(value).strip()
    if not _SHA256_RE.match(text):
        return None
    return text.lower()


def _format_file_size(size_bytes: int) -> str:
    size = float(size_bytes)
    unit = "bytes"
    for next_unit in ("KB", "MB", "GB", "TB"):
        if size < 1024:
            break
        size /= 1024
        unit = next_unit
    if unit == "bytes":
        return f"{size_bytes} bytes"
    return f"{size:.1f} {unit} ({size_bytes} bytes)"


def _is_windows_installer(release: ReleaseNotification) -> bool:
    text = f"{release.platform or ''} {release.artifact_filename or ''}".lower()
    return "windows" in text or text.endswith(".msi") or text.endswith(".exe")


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
