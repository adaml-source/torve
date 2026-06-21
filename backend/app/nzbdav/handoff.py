"""HMAC-signed handoff tokens for NzbDAV resolved streams.

A handoff token hides the upstream URL from the Torve client. The client
receives a URL like `/resolver/usenet/handoff/{token}` — the token decodes
to signed playback claims and the backend resolves the upstream URL from
its in-process cache.

Tokens are:
- HMAC-SHA256 signed with settings.NZBDAV_HANDOFF_SECRET (auto-generated
  if empty on first use — stable for process lifetime)
- Base64url encoded (payload || "." || signature)
- TTL 5 minutes, scoped to user/device/content/stream when the caller
  provided those claims.

Follow-up TODO: single-use enforcement. Currently we rely on the 5-minute
expiry + user scoping, which is sufficient for this sprint.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import secrets
import threading
import time
from dataclasses import dataclass

from app.config import settings

TOKEN_TTL_SECONDS = 300  # 5 minutes


# Lazily materialized secret. If settings.NZBDAV_HANDOFF_SECRET is empty we
# generate a random secret once per process. In production the operator
# should configure a stable value, but this keeps dev/test unbroken.
_secret_lock = threading.Lock()
_secret_cached: bytes | None = None


def _get_secret() -> bytes:
    global _secret_cached
    with _secret_lock:
        if _secret_cached is not None:
            return _secret_cached
        configured = settings.NZBDAV_HANDOFF_SECRET
        if configured:
            _secret_cached = configured.encode("utf-8")
        else:
            _secret_cached = secrets.token_bytes(32)
        return _secret_cached


def _reset_for_tests() -> None:
    global _secret_cached
    with _secret_lock:
        _secret_cached = None


def _b64url_encode(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode("ascii")


def _b64url_decode(s: str) -> bytes:
    padding = "=" * (-len(s) % 4)
    return base64.urlsafe_b64decode(s + padding)


class HandoffError(Exception):
    pass


@dataclass(frozen=True)
class HandoffClaims:
    user_id: str
    stream_id: str
    exp: int
    device_id: str | None = None
    content_id: str | None = None
    issued_at: int | None = None
    jti: str | None = None


def mint(
    *,
    user_id: str,
    stream_id: str,
    device_id: str | None = None,
    content_id: str | None = None,
    ttl_seconds: int = TOKEN_TTL_SECONDS,
) -> str:
    """Mint a new handoff token. Returns the URL-safe token string."""
    now = int(time.time())
    exp = now + int(ttl_seconds)
    payload = {
        "uid": str(user_id),
        "sid": str(stream_id),
        "exp": exp,
        "iat": now,
        "jti": secrets.token_urlsafe(18),
    }
    if device_id:
        payload["did"] = str(device_id)
    if content_id:
        payload["cid"] = str(content_id)
    payload_bytes = json.dumps(
        payload, separators=(",", ":"), sort_keys=True
    ).encode("utf-8")
    payload_b64 = _b64url_encode(payload_bytes)
    sig = hmac.new(
        _get_secret(), payload_b64.encode("ascii"), hashlib.sha256
    ).digest()
    sig_b64 = _b64url_encode(sig)
    return f"{payload_b64}.{sig_b64}"


def verify(token: str) -> HandoffClaims:
    """Verify a token. Raises HandoffError on any failure."""
    if not token or not isinstance(token, str):
        raise HandoffError("invalid_token")
    parts = token.split(".")
    if len(parts) != 2:
        raise HandoffError("malformed_token")
    payload_b64, sig_b64 = parts
    expected_sig = hmac.new(
        _get_secret(), payload_b64.encode("ascii"), hashlib.sha256
    ).digest()
    try:
        provided_sig = _b64url_decode(sig_b64)
    except Exception as exc:  # noqa: BLE001
        raise HandoffError("bad_signature_encoding") from exc
    if not hmac.compare_digest(expected_sig, provided_sig):
        raise HandoffError("bad_signature")
    try:
        payload = json.loads(_b64url_decode(payload_b64).decode("utf-8"))
    except Exception as exc:  # noqa: BLE001
        raise HandoffError("bad_payload") from exc
    uid = payload.get("uid")
    sid = payload.get("sid")
    exp = payload.get("exp")
    did = payload.get("did")
    cid = payload.get("cid")
    iat = payload.get("iat")
    jti = payload.get("jti")
    if not (isinstance(uid, str) and isinstance(sid, str) and isinstance(exp, int)):
        raise HandoffError("bad_claims")
    if did is not None and not isinstance(did, str):
        raise HandoffError("bad_claims")
    if cid is not None and not isinstance(cid, str):
        raise HandoffError("bad_claims")
    if iat is not None and not isinstance(iat, int):
        raise HandoffError("bad_claims")
    if jti is not None and not isinstance(jti, str):
        raise HandoffError("bad_claims")
    if exp < int(time.time()):
        raise HandoffError("expired")
    return HandoffClaims(
        user_id=uid,
        stream_id=sid,
        exp=exp,
        device_id=did,
        content_id=cid,
        issued_at=iat,
        jti=jti,
    )
