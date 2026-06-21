"""Client trust-signal normalization and Play Integrity verification.

Trust signals are never entitlement proof. They are backend-observed context
for logging, review, rate limits, and stricter handling on sensitive paths.
"""
from __future__ import annotations

import hashlib
import json
import logging
import os
import time
from dataclasses import dataclass
from typing import Any

import httpx
from jose import jwt as jose_jwt

from app.config import settings

_log = logging.getLogger(__name__)

PLAY_INTEGRITY_SCOPE = "https://www.googleapis.com/auth/playintegrity"
PLAY_INTEGRITY_FRESHNESS_SECONDS = 10 * 60
_CACHE_TTL_SECONDS = 5 * 60
_integrity_cache: dict[str, tuple[float, "IntegrityVerdict"]] = {}


@dataclass(frozen=True)
class IntegrityVerdict:
    status: str
    app_recognition_verdict: str | None = None
    package_name: str | None = None
    certificate_match: bool | None = None
    fresh: bool | None = None
    risk_flags: tuple[str, ...] = ()


def normalize_channel(value: str | None) -> str:
    text = (value or "").strip().lower()
    if text in {"google", "google_play", "play", "play_store"}:
        return "google_play"
    if text in {"amazon", "amazon_appstore", "firetv", "fire_tv"}:
        return "amazon"
    if text in {"desktop", "windows", "macos", "linux"}:
        return "desktop"
    if text in {"ios", "app_store", "apple"}:
        return "ios"
    return text or "unknown"


def is_google_play_channel(
    *,
    distribution_channel: str | None,
    installer_package: str | None,
    integrity_provider: str | None,
) -> bool:
    channel = normalize_channel(distribution_channel)
    return (
        channel == "google_play"
        or (installer_package or "").strip() == "com.android.vending"
        or (integrity_provider or "").strip().lower() == "play_integrity"
    )


def normalize_risk_flags(payload: Any) -> list[str]:
    flags: list[str] = []
    if bool(getattr(payload, "is_debuggable", False)):
        flags.append("debuggable")
    if bool(getattr(payload, "is_emulator", False)) or bool(getattr(payload, "likely_virtual_device", False)):
        flags.append("virtual_device")
    if bool(getattr(payload, "has_known_hooking_indicators", False)):
        flags.append("hooking_indicators")
    if bool(getattr(payload, "has_known_root_indicators", False)):
        flags.append("root_indicators")
    channel = normalize_channel(getattr(payload, "distribution_channel", None))
    if channel == "unknown":
        flags.append("unknown_distribution_channel")
    if is_google_play_channel(
        distribution_channel=getattr(payload, "distribution_channel", None),
        installer_package=getattr(payload, "installer_package", None),
        integrity_provider=getattr(payload, "integrity_provider", None),
    ) and not getattr(payload, "integrity_token", None):
        flags.append("integrity_token_missing")
    return flags


def verify_play_integrity_token(
    *,
    integrity_token: str,
    package_name: str | None,
    expected_certificate_sha256: str | None = None,
) -> IntegrityVerdict:
    """Decode and validate a Play Integrity token server-side.

    The return value is intentionally sanitized; raw token payloads are not
    logged or returned to clients.
    """
    token_hash = hashlib.sha256(integrity_token.encode("utf-8")).hexdigest()
    cache_key = f"{package_name or ''}:{token_hash}"
    cached = _integrity_cache.get(cache_key)
    now_monotonic = time.monotonic()
    if cached and now_monotonic - cached[0] <= _CACHE_TTL_SECONDS:
        return cached[1]

    if not settings.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON:
        verdict = IntegrityVerdict(status="config_missing", risk_flags=("integrity_config_missing",))
        _integrity_cache[cache_key] = (now_monotonic, verdict)
        return verdict

    effective_package = package_name or settings.GOOGLE_PLAY_PACKAGE_NAME
    if not effective_package:
        verdict = IntegrityVerdict(status="config_missing", risk_flags=("package_missing",))
        _integrity_cache[cache_key] = (now_monotonic, verdict)
        return verdict

    try:
        access_token = _get_google_access_token(
            settings.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON,
            PLAY_INTEGRITY_SCOPE,
        )
        if not access_token:
            verdict = IntegrityVerdict(status="service_account_failure", risk_flags=("integrity_auth_failed",))
            _integrity_cache[cache_key] = (now_monotonic, verdict)
            return verdict

        url = f"https://playintegrity.googleapis.com/v1/{effective_package}:decodeIntegrityToken"
        resp = httpx.post(
            url,
            headers={"Authorization": f"Bearer {access_token}"},
            json={"integrity_token": integrity_token},
            timeout=15.0,
        )
        if resp.status_code in (401, 403):
            verdict = IntegrityVerdict(status="service_account_failure", risk_flags=("integrity_auth_rejected",))
            _integrity_cache[cache_key] = (now_monotonic, verdict)
            return verdict
        if resp.status_code >= 500:
            verdict = IntegrityVerdict(status="upstream_unreachable", risk_flags=("integrity_upstream_unreachable",))
            _integrity_cache[cache_key] = (now_monotonic, verdict)
            return verdict
        if resp.status_code != 200:
            verdict = IntegrityVerdict(status="rejected", risk_flags=("integrity_decode_rejected",))
            _integrity_cache[cache_key] = (now_monotonic, verdict)
            return verdict

        verdict = _validate_play_integrity_payload(
            resp.json(),
            expected_package_name=effective_package,
            expected_certificate_sha256=expected_certificate_sha256,
        )
        _integrity_cache[cache_key] = (now_monotonic, verdict)
        return verdict
    except httpx.HTTPError:
        verdict = IntegrityVerdict(status="upstream_unreachable", risk_flags=("integrity_upstream_unreachable",))
        _integrity_cache[cache_key] = (now_monotonic, verdict)
        return verdict
    except Exception as exc:  # noqa: BLE001
        _log.warning("PLAY_INTEGRITY_UNEXPECTED class=%s", type(exc).__name__)
        verdict = IntegrityVerdict(status="unknown", risk_flags=("integrity_unknown_error",))
        _integrity_cache[cache_key] = (now_monotonic, verdict)
        return verdict


def _validate_play_integrity_payload(
    payload: dict[str, Any],
    *,
    expected_package_name: str,
    expected_certificate_sha256: str | None,
) -> IntegrityVerdict:
    token_payload = payload.get("tokenPayloadExternal")
    if not isinstance(token_payload, dict):
        return IntegrityVerdict(status="rejected", risk_flags=("integrity_payload_missing",))

    request_details = token_payload.get("requestDetails") or {}
    app_integrity = token_payload.get("appIntegrity") or {}
    risk_flags: list[str] = []

    package_name = app_integrity.get("packageName")
    if package_name != expected_package_name:
        risk_flags.append("package_mismatch")

    app_recognition = app_integrity.get("appRecognitionVerdict")
    if app_recognition != "PLAY_RECOGNIZED":
        risk_flags.append("app_not_play_recognized")

    cert_match: bool | None = None
    expected_cert = _normalize_sha256(expected_certificate_sha256)
    if expected_cert:
        certs = app_integrity.get("certificateSha256Digest") or []
        normalized_certs = {_normalize_sha256(str(c)) for c in certs if c}
        cert_match = expected_cert in normalized_certs
        if not cert_match:
            risk_flags.append("signing_certificate_mismatch")

    fresh: bool | None = None
    timestamp_ms = request_details.get("timestampMillis")
    if timestamp_ms is not None:
        try:
            age_seconds = abs((int(time.time() * 1000) - int(timestamp_ms)) / 1000)
            fresh = age_seconds <= PLAY_INTEGRITY_FRESHNESS_SECONDS
        except (TypeError, ValueError):
            fresh = False
        if not fresh:
            risk_flags.append("integrity_verdict_stale")

    status = "verified" if not risk_flags else "rejected"
    return IntegrityVerdict(
        status=status,
        app_recognition_verdict=app_recognition if isinstance(app_recognition, str) else None,
        package_name=package_name if isinstance(package_name, str) else None,
        certificate_match=cert_match,
        fresh=fresh,
        risk_flags=tuple(risk_flags),
    )


def _normalize_sha256(value: str | None) -> str:
    return (value or "").replace(":", "").replace(" ", "").strip().upper()


def _get_google_access_token(creds_path: str, scope: str) -> str | None:
    try:
        if not os.path.exists(creds_path):
            _log.error("Google service account file missing for Play Integrity")
            return None

        with open(creds_path, "r", encoding="utf-8") as f:
            sa = json.load(f)

        now = int(time.time())
        payload = {
            "iss": sa["client_email"],
            "scope": scope,
            "aud": "https://oauth2.googleapis.com/token",
            "iat": now,
            "exp": now + 3600,
        }
        signed_jwt = jose_jwt.encode(payload, sa["private_key"], algorithm="RS256")
        resp = httpx.post(
            "https://oauth2.googleapis.com/token",
            data={
                "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
                "assertion": signed_jwt,
            },
            timeout=15.0,
        )
        if resp.status_code == 200:
            return resp.json().get("access_token")
        _log.error("Google token exchange failed for Play Integrity status=%d", resp.status_code)
        return None
    except Exception as exc:  # noqa: BLE001
        _log.error("Google auth error for Play Integrity class=%s", type(exc).__name__)
        return None


def reset_integrity_cache_for_tests() -> None:
    _integrity_cache.clear()
