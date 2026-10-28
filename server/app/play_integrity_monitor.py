"""Observation-only Standard Play Integrity verification; never an access gate."""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import secrets
import time
from pathlib import Path

import httpx
from jose import jwt

from app.config import settings

PURPOSE = "torve-play-integrity-monitor-v1"
PACKAGE = "com.torve.app"
EVENTS = {"login", "foreground", "credential_transfer"}
TTL_SECONDS = 300


def enabled() -> bool:
    return bool(settings.PLAY_INTEGRITY_MONITOR_ENABLED and
                settings.PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER > 0 and
                settings.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON)


def request_hash(challenge: str) -> str:
    return hashlib.sha256(challenge.encode()).hexdigest()


def create_challenge(user_id: str, installation_id: str | None, event: str, now: int | None = None) -> dict:
    now = int(time.time()) if now is None else now
    challenge = jwt.encode(dict(sub=user_id, installation=installation_id or "", event=event,
                                aud=PURPOSE, iat=now, exp=now + TTL_SECONDS, jti=secrets.token_urlsafe(24)),
                           settings.JWT_SECRET, algorithm="HS256")
    return dict(enabled=True, challenge=challenge, request_hash=request_hash(challenge),
                cloud_project_number=settings.PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER)


def verify_challenge(challenge: str, user_id: str, installation_id: str | None) -> dict:
    payload = jwt.decode(challenge, settings.JWT_SECRET, algorithms=["HS256"], audience=PURPOSE,
                         options={"require_exp": True, "require_iat": True, "require_sub": True,
                                  "require_aud": True, "require_jti": True})
    if (payload["sub"] != user_id or payload.get("installation") != (installation_id or "") or
            payload.get("event") not in EVENTS or payload["iat"] > time.time() + 30 or
            payload["exp"] - payload["iat"] > TTL_SECONDS):
        raise ValueError("challenge binding invalid")
    return payload


def normalized_certificate(value: str) -> str:
    # Google verdicts use base64url certificate digests; configuration uses hex.
    hex_value = value.replace(":", "").lower()
    if len(hex_value) == 64 and all(c in "0123456789abcdef" for c in hex_value):
        return hex_value
    try:
        return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4)).hex()
    except (ValueError, TypeError):
        return ""


def evaluate_verdict(payload: dict, expected_hash: str, now_ms: int | None = None) -> dict:
    """Only trust Google-decoded data after binding it to this server-issued request."""
    now_ms = int(time.time() * 1000) if now_ms is None else now_ms
    data = payload.get("tokenPayloadExternal") or {}
    request = data.get("requestDetails") or {}
    try:
        age = now_ms - int(request.get("timestampMillis", 0))
    except (ValueError, TypeError):
        age = TTL_SECONDS * 1000 + 1
    if (request.get("requestPackageName") != PACKAGE or
            not hmac.compare_digest(str(request.get("requestHash", "")), expected_hash) or
            age < -30_000 or age > TTL_SECONDS * 1000):
        return dict(status="invalid_request_binding")

    app = data.get("appIntegrity") or {}
    app_verdict = app.get("appRecognitionVerdict")
    app_verdict = app_verdict if app_verdict in {"PLAY_RECOGNIZED", "UNRECOGNIZED_VERSION", "UNEVALUATED"} else "UNEVALUATED"
    certificates = app.get("certificateSha256Digest") or []
    expected = normalized_certificate(settings.PLAY_INTEGRITY_SIGNING_CERT_SHA256)
    certificate_match = bool(expected) and any(normalized_certificate(str(c)) == expected for c in certificates)
    if app_verdict == "PLAY_RECOGNIZED" and (app.get("packageName") != PACKAGE or not certificate_match):
        return dict(status="invalid_app_identity")
    license_verdict = (data.get("accountDetails") or {}).get("appLicensingVerdict")
    license_verdict = license_verdict if license_verdict in {"LICENSED", "UNLICENSED", "UNEVALUATED"} else "UNEVALUATED"
    device = (data.get("deviceIntegrity") or {}).get("deviceRecognitionVerdict") or []
    allowed_labels = {"MEETS_BASIC_INTEGRITY", "MEETS_DEVICE_INTEGRITY", "MEETS_STRONG_INTEGRITY", "MEETS_VIRTUAL_INTEGRITY"}
    return dict(status="observed", app_integrity=app_verdict, play_install=license_verdict,
                device_integrity=sorted(set(device) & allowed_labels), certificate_match=certificate_match)


def decode_token(token: str) -> dict:
    """No token/verdict cache: Standard Integrity provides replay protection."""
    credentials = json.loads(Path(settings.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON).read_text())
    now = int(time.time())
    assertion = jwt.encode(dict(iss=credentials["client_email"],
        scope="https://www.googleapis.com/auth/playintegrity", aud="https://oauth2.googleapis.com/token",
        iat=now, exp=now+3600), credentials["private_key"], algorithm="RS256")
    with httpx.Client(timeout=httpx.Timeout(12.0, connect=5.0)) as client:
        auth = client.post("https://oauth2.googleapis.com/token", data={
            "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer", "assertion": assertion})
        auth.raise_for_status()
        response = client.post(f"https://playintegrity.googleapis.com/v1/{PACKAGE}:decodeIntegrityToken",
            headers={"Authorization": "Bearer " + auth.json()["access_token"]},
            json={"integrity_token": token})
        response.raise_for_status()
        return response.json()


def observe(challenge: str, token: str | None, user_id: str, installation_id: str | None) -> dict:
    if not enabled(): return dict(status="disabled")
    try:
        verify_challenge(challenge, user_id, installation_id)
    except Exception:
        return dict(status="invalid_challenge")
    if not token: return dict(status="token_unavailable")
    try:
        return evaluate_verdict(decode_token(token), request_hash(challenge))
    except httpx.HTTPStatusError as exc:
        return dict(status="google_rejected", http_status=exc.response.status_code)
    except Exception:
        return dict(status="verification_unavailable")
