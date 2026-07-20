"""Generic short-lived playback handoff reference storage.

This module intentionally reuses the NzbDAV signed handoff token format so
generic debrid/addon streams do not get a second token system. The upstream
URL itself is kept in shared short-lived database storage and is never encoded
into the token or returned to the client.
"""
from __future__ import annotations

import ipaddress
import logging
import secrets
import time
import uuid
from datetime import datetime, timedelta, timezone
from urllib.parse import urlparse

from sqlalchemy.orm import Session

from app.crypto import decrypt_secret, encrypt_secret
from app.models import StreamHandoffReference
from app.nzbdav.handoff import TOKEN_TTL_SECONDS, mint


_log = logging.getLogger(__name__)


def is_safe_public_stream_url(value: str) -> bool:
    """Return True for public HTTP(S) playback URLs.

    The generic handoff path can only wrap URLs that are already present in
    backend memory, but those memories can originate from legacy client
    reports. Keep a low-cost SSRF guard here: no local schemes, no userinfo,
    and no literal private/link-local/loopback IP hosts.
    """
    try:
        parsed = urlparse(value)
    except Exception:  # noqa: BLE001
        return False
    if parsed.scheme not in {"http", "https"}:
        return False
    if not parsed.hostname:
        return False
    if parsed.username or parsed.password:
        return False
    host = parsed.hostname.strip().lower().rstrip(".")
    if host in {"localhost", "metadata.google.internal"}:
        return False
    try:
        ip = ipaddress.ip_address(host)
    except ValueError:
        return True
    return not (
        ip.is_private
        or ip.is_loopback
        or ip.is_link_local
        or ip.is_multicast
        or ip.is_reserved
        or ip.is_unspecified
    )


def put_handoff(
    db: Session,
    *,
    upstream_url: str,
    user_id: str,
    device_id: str,
    content_id: str,
    provider_type: str,
    source_ref: str,
    ttl_seconds: int = TOKEN_TTL_SECONDS,
) -> tuple[str, str, int]:
    """Persist a generic stream handoff and return ``(token, stream_id, ttl)``.

    The row is shared by all uvicorn workers. No single-use replay protection is
    applied so video range requests, player retries, and reconnects keep working
    until the short TTL expires.
    """
    stream_id = f"generic_{secrets.token_urlsafe(18)}"
    now = datetime.now(timezone.utc)
    cleanup_expired(db)
    db.add(
        StreamHandoffReference(
            stream_id=stream_id,
            upstream_url_encrypted=encrypt_secret(upstream_url),
            user_id=uuid.UUID(str(user_id)),
            device_id=uuid.UUID(str(device_id)),
            content_id=str(content_id),
            provider_type=str(provider_type),
            source_ref=str(source_ref),
            created_at=now,
            expires_at=now + timedelta(seconds=ttl_seconds),
        )
    )
    db.flush()
    token = mint(
        user_id=str(user_id),
        device_id=str(device_id),
        content_id=str(content_id),
        stream_id=stream_id,
        ttl_seconds=ttl_seconds,
    )
    return token, stream_id, ttl_seconds


def get_handoff(db: Session, stream_id: str) -> dict | None:
    now = datetime.now(timezone.utc)
    row = db.get(StreamHandoffReference, stream_id)
    if row is None:
        return None
    if row.expires_at <= now:
        db.delete(row)
        db.flush()
        return None
    try:
        upstream_url = decrypt_secret(row.upstream_url_encrypted)
    except ValueError:
        _log.warning("STREAM_HANDOFF_REF_UNREADABLE stream_id=%s", stream_id[:24])
        db.delete(row)
        db.flush()
        return None
    return {
        "stream_id": row.stream_id,
        "upstream_url": upstream_url,
        "user_id": str(row.user_id),
        "device_id": str(row.device_id),
        "content_id": row.content_id,
        "provider_type": row.provider_type,
        "source_ref": row.source_ref,
        "created_at": int(row.created_at.timestamp()) if row.created_at else int(time.time()),
    }


def cleanup_expired(db: Session) -> int:
    now = datetime.now(timezone.utc)
    count = (
        db.query(StreamHandoffReference)
        .filter(StreamHandoffReference.expires_at <= now)
        .delete(synchronize_session=False)
    )
    return int(count or 0)


def reset_for_tests(db: Session | None = None) -> None:
    if db is not None:
        db.query(StreamHandoffReference).delete(synchronize_session=False)
        db.commit()
