"""
Account-scoped playlist backup and restore.

Playlists (M3U and Xtream) are stored under the authenticated user's
account so they can be restored on sign-in. Xtream passwords are
encrypted at rest. Passwords are never returned in list/metadata responses.
"""

import logging
import ipaddress
import re
import socket
import uuid
import zlib
from collections import Counter
from datetime import datetime, timezone
from urllib.parse import urljoin, urlparse

import httpx
from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy.orm import Session

from app.crypto import decrypt_secret, encrypt_secret
from app.deps import (
    AuthenticatedDeviceContext,
    get_calling_installation_id,
    get_current_user_id,
    get_db,
    require_active_device,
    require_account_access,
)
from app.events import UserEvent, event_bus
from app.models import Device, UserPlaylist
from app.rate_limits import enforce_rate_limit
from app.schemas import EpgValidateRequest, EpgValidateResult, PlaylistOut, PlaylistSaveRequest

_log = logging.getLogger(__name__)

router = APIRouter(prefix="/me/playlists", tags=["playlists"])

_HTTP_SCHEMES = {"http", "https"}
_EPG_FETCH_TIMEOUT = httpx.Timeout(connect=4.0, read=6.0, write=4.0, pool=4.0)
_EPG_FETCH_MAX_BYTES = 2 * 1024 * 1024
_EPG_DECOMPRESS_MAX_BYTES = 4 * 1024 * 1024
_EPG_MAX_REDIRECTS = 3
_RESTORE_RATE_LIMIT = 20
_RESTORE_RATE_WINDOW_SECONDS = 60


def _to_out(row: UserPlaylist) -> PlaylistOut:
    return PlaylistOut(
        id=row.id,
        playlist_id=row.playlist_id,
        name=row.name,
        playlist_type=row.playlist_type,
        url=row.url,
        epg_url=row.epg_url,
        server=row.server,
        username=row.username,
        has_password=bool(row.encrypted_password),
        created_at=row.created_at,
        updated_at=row.updated_at,
    )


def _clean_optional(value: str | None) -> str | None:
    if value is None:
        return None
    cleaned = value.strip()
    return cleaned or None


def _validate_http_url(field_name: str, field_val: str) -> None:
    parsed = urlparse(field_val)
    if parsed.scheme not in _HTTP_SCHEMES or not parsed.hostname:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"{field_name} must use http:// or https:// scheme.",
        )
    if len(field_val) > 2048:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"{field_name} exceeds maximum length of 2048 characters.",
        )


@router.get("", response_model=list[PlaylistOut])
def list_playlists(
    user_id: str = Depends(get_current_user_id),
    installation_id: str | None = Depends(get_calling_installation_id),
    db: Session = Depends(get_db),
) -> list[PlaylistOut]:
    """List all backed-up playlists for the authenticated user.

    Returns metadata only. Xtream passwords are not included.
    """
    uid = uuid.UUID(user_id)
    rows = (
        db.query(UserPlaylist)
        .filter(UserPlaylist.user_id == uid)
        .order_by(UserPlaylist.name)
        .all()
    )
    type_counts = dict(sorted(Counter(row.playlist_type for row in rows).items()))
    device_id = _active_device_id_for_installation(db, uid, installation_id)
    _log.info(
        "PLAYLIST_HYDRATION user=%s installation=%s device=%s loaded_count=%d "
        "playlist_types=%s filtered_count=0 filter_reason=none",
        uid,
        installation_id,
        device_id,
        len(rows),
        type_counts,
    )
    return [_to_out(r) for r in rows]


@router.put("/{playlist_id}", response_model=PlaylistOut)
def save_playlist(
    playlist_id: str,
    body: PlaylistSaveRequest,
    user_id: str = Depends(require_account_access),
    installation_id: str | None = Depends(get_calling_installation_id),
    db: Session = Depends(get_db),
) -> PlaylistOut:
    """Save or update a playlist backup. Upserts by user + playlist_id.

    For Xtream playlists, the password is encrypted before storage.
    The response never includes the raw password.
    """
    uid = uuid.UUID(user_id)

    if body.playlist_id != playlist_id:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="playlist_id in body must match URL path.",
        )

    name = body.name.strip()
    url = _clean_optional(body.url)
    epg_url = _clean_optional(body.epg_url)
    server = _clean_optional(body.server)
    username = _clean_optional(body.username)

    # Validate required fields per type
    if body.playlist_type == "m3u" and not url:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="url is required for m3u playlists.",
        )
    if body.playlist_type == "xtream":
        if not server or not username:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="server and username are required for xtream playlists.",
            )

    # Validate URL schemes (defense-in-depth — backend stores but does not fetch)
    for field_name, field_val in [("url", url), ("epg_url", epg_url), ("server", server)]:
        if field_val:
            _validate_http_url(field_name, field_val)

    m3u_url = url if body.playlist_type == "m3u" else None
    saved_epg_url = epg_url
    xtream_server = server if body.playlist_type == "xtream" else None
    xtream_username = username if body.playlist_type == "xtream" else None

    # Encrypt password if provided
    enc_password = None
    if body.password:
        enc_password = encrypt_secret(body.password)

    # Upsert
    existing = (
        db.query(UserPlaylist)
        .filter(
            UserPlaylist.user_id == uid,
            UserPlaylist.playlist_id == playlist_id,
        )
        .first()
    )

    if existing:
        existing.name = name
        existing.playlist_type = body.playlist_type
        existing.url = m3u_url
        existing.epg_url = saved_epg_url
        existing.server = xtream_server
        existing.username = xtream_username
        if body.playlist_type != "xtream":
            existing.encrypted_password = None
        elif enc_password is not None:
            existing.encrypted_password = enc_password
        existing.updated_at = datetime.now(timezone.utc)
        db.commit()
        db.refresh(existing)
        _log_playlist_save(
            db,
            uid=uid,
            playlist_id=playlist_id,
            playlist_type=body.playlist_type,
            installation_id=installation_id,
            action="updated",
        )
        event_bus.emit(UserEvent("PLAYLISTS_UPDATED", uid))
        return _to_out(existing)

    row = UserPlaylist(
        user_id=uid,
        playlist_id=playlist_id,
        name=name,
        playlist_type=body.playlist_type,
        url=m3u_url,
        epg_url=saved_epg_url,
        server=xtream_server,
        username=xtream_username,
        encrypted_password=enc_password,
    )
    db.add(row)
    db.commit()
    db.refresh(row)
    _log_playlist_save(
        db,
        uid=uid,
        playlist_id=playlist_id,
        playlist_type=body.playlist_type,
        installation_id=installation_id,
        action="created",
    )
    event_bus.emit(UserEvent("PLAYLISTS_UPDATED", uid))
    return _to_out(row)


@router.post("/validate-epg", response_model=EpgValidateResult)
def validate_epg_url(
    body: EpgValidateRequest,
    user_id: str = Depends(require_account_access),
) -> EpgValidateResult:
    """Check whether an EPG URL is reachable and contains XMLTV programme data."""
    _log.info("Validating EPG URL for user %s", user_id)
    return _probe_epg_url(body.epg_url)


@router.delete("/{playlist_id}", status_code=status.HTTP_204_NO_CONTENT)
def remove_playlist(
    playlist_id: str,
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> None:
    """Remove a backed-up playlist."""
    row = _get_own_playlist(db, user_id, playlist_id)
    uid = uuid.UUID(user_id)
    db.delete(row)
    db.commit()
    _log.info("Removed playlist %s for user %s", playlist_id, user_id)
    event_bus.emit(UserEvent("PLAYLISTS_UPDATED", uid))


@router.get("/{playlist_id}/credentials", include_in_schema=False)
def get_playlist_credentials(
    playlist_id: str,
    request: Request,
    caller: AuthenticatedDeviceContext = Depends(require_active_device),
    _account_user_id: str = Depends(require_account_access),
    db: Session = Depends(get_db),
) -> dict:
    """Return decrypted Xtream password for restore.

    Only meaningful for xtream playlists. M3U playlists return
    empty password. Hidden from public API docs. Requires account access plus
    an active registered device because this endpoint releases account
    secrets back to a client install.
    """
    enforce_rate_limit(
        category="credential_restore_playlist",
        request=request,
        user_id=caller.user_id,
        device_id=str(caller.device_id),
        limit=_RESTORE_RATE_LIMIT,
        window_seconds=_RESTORE_RATE_WINDOW_SECONDS,
    )

    row = _get_own_playlist(db, caller.user_id, playlist_id)

    if row.playlist_type != "xtream" or not row.encrypted_password:
        _log.info(
            "CREDENTIAL_RESTORE kind=playlist user=%s device=%s playlist=%s "
            "result=no_password",
            caller.user_id, caller.device_id, playlist_id,
        )
        return {"playlist_id": playlist_id, "password": None}

    try:
        password = decrypt_secret(row.encrypted_password)
    except ValueError:
        _log.warning(
            "CREDENTIAL_RESTORE_FAILED kind=playlist user=%s device=%s "
            "playlist=%s reason=decrypt_failed",
            caller.user_id, caller.device_id, playlist_id,
        )
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail={
                "code": "credential_restore_failed",
                "error_code": "credential_restore_failed",
                "message": "Playlist credentials could not be restored. Please re-save.",
            },
        )

    _log.info(
        "CREDENTIAL_RESTORE kind=playlist user=%s device=%s playlist=%s "
        "result=password_returned",
        caller.user_id, caller.device_id, playlist_id,
    )
    return {"playlist_id": playlist_id, "password": password}


def _get_own_playlist(db: Session, user_id: str, playlist_id: str) -> UserPlaylist:
    uid = uuid.UUID(user_id)
    row = (
        db.query(UserPlaylist)
        .filter(
            UserPlaylist.user_id == uid,
            UserPlaylist.playlist_id == playlist_id,
        )
        .first()
    )
    if not row:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Playlist '{playlist_id}' not found.",
        )
    return row


def _active_device_id_for_installation(
    db: Session,
    uid: uuid.UUID,
    installation_id: str | None,
) -> uuid.UUID | None:
    if not installation_id:
        return None
    device = (
        db.query(Device)
        .filter(
            Device.user_id == uid,
            Device.installation_id == installation_id,
            Device.is_active == True,  # noqa: E712
        )
        .first()
    )
    return device.id if device else None


def _log_playlist_save(
    db: Session,
    *,
    uid: uuid.UUID,
    playlist_id: str,
    playlist_type: str,
    installation_id: str | None,
    action: str,
) -> None:
    row_count = db.query(UserPlaylist).filter(UserPlaylist.user_id == uid).count()
    device_id = _active_device_id_for_installation(db, uid, installation_id)
    _log.info(
        "PLAYLIST_SAVE_COMMITTED action=%s playlist=%s type=%s user=%s "
        "installation=%s device=%s row_count=%d",
        action,
        playlist_id,
        playlist_type,
        uid,
        installation_id,
        device_id,
        row_count,
    )


def _probe_epg_url(epg_url: str) -> EpgValidateResult:
    url = epg_url.strip()
    try:
        current_url = _assert_safe_fetch_url(url)
    except ValueError as exc:
        return EpgValidateResult(
            success=False,
            status="invalid_url",
            message=str(exc),
        )

    try:
        with httpx.Client(
            timeout=_EPG_FETCH_TIMEOUT,
            follow_redirects=False,
            trust_env=False,
        ) as client:
            redirects = 0
            while True:
                with client.stream(
                    "GET",
                    current_url,
                    headers={"accept": "application/xml,text/xml,application/gzip,*/*"},
                ) as resp:
                    if resp.status_code in {301, 302, 303, 307, 308}:
                        location = resp.headers.get("location")
                        if not location:
                            return EpgValidateResult(
                                success=False,
                                status="broken",
                                message="EPG URL redirected without a Location header.",
                                http_status=resp.status_code,
                                content_type=resp.headers.get("content-type"),
                            )
                        redirects += 1
                        if redirects > _EPG_MAX_REDIRECTS:
                            return EpgValidateResult(
                                success=False,
                                status="broken",
                                message="EPG URL redirected too many times.",
                                http_status=resp.status_code,
                                content_type=resp.headers.get("content-type"),
                            )
                        try:
                            current_url = _assert_safe_fetch_url(urljoin(current_url, location))
                        except ValueError as exc:
                            return EpgValidateResult(
                                success=False,
                                status="invalid_url",
                                message=str(exc),
                                http_status=resp.status_code,
                                content_type=resp.headers.get("content-type"),
                            )
                        continue

                    content_type = resp.headers.get("content-type")
                    if resp.status_code < 200 or resp.status_code >= 300:
                        return EpgValidateResult(
                            success=False,
                            status="broken",
                            message=f"EPG URL returned HTTP {resp.status_code}.",
                            http_status=resp.status_code,
                            content_type=content_type,
                        )

                    data = bytearray()
                    truncated = False
                    for chunk in resp.iter_bytes():
                        if not chunk:
                            continue
                        data.extend(chunk)
                        if len(data) > _EPG_FETCH_MAX_BYTES:
                            del data[_EPG_FETCH_MAX_BYTES:]
                            truncated = True
                            break
                    return _inspect_epg_payload(
                        bytes(data),
                        http_status=resp.status_code,
                        content_type=content_type,
                        truncated=truncated,
                    )
    except httpx.TimeoutException:
        return EpgValidateResult(
            success=False,
            status="broken",
            message="EPG URL timed out.",
        )
    except httpx.HTTPError as exc:
        return EpgValidateResult(
            success=False,
            status="broken",
            message=f"EPG URL could not be reached ({type(exc).__name__}).",
        )


def _assert_safe_fetch_url(url: str) -> str:
    parsed = urlparse(url)
    if parsed.scheme not in _HTTP_SCHEMES or not parsed.hostname:
        raise ValueError("Enter an EPG URL that starts with http:// or https://.")
    if len(url) > 2048:
        raise ValueError("EPG URL exceeds the 2048 character limit.")

    host = parsed.hostname.strip().lower()
    if host in {"localhost", "localhost.localdomain"} or host.endswith(".local"):
        raise ValueError("EPG URL host is not allowed.")

    _assert_public_host(host, parsed.port)
    return url


def _assert_public_host(host: str, port: int | None) -> None:
    try:
        ip = ipaddress.ip_address(host.strip("[]"))
    except ValueError:
        ip = None

    if ip is not None:
        _assert_public_ip(ip)
        return

    try:
        infos = socket.getaddrinfo(
            host,
            port or 443,
            type=socket.SOCK_STREAM,
        )
    except socket.gaierror as exc:
        raise ValueError("EPG URL host could not be resolved.") from exc

    if not infos:
        raise ValueError("EPG URL host could not be resolved.")
    for info in infos:
        address = info[4][0]
        try:
            _assert_public_ip(ipaddress.ip_address(address))
        except ValueError as exc:
            raise ValueError("EPG URL host is not allowed.") from exc


def _assert_public_ip(ip: ipaddress.IPv4Address | ipaddress.IPv6Address) -> None:
    if (
        ip.is_private
        or ip.is_loopback
        or ip.is_link_local
        or ip.is_multicast
        or ip.is_reserved
        or ip.is_unspecified
    ):
        raise ValueError("EPG URL host is not allowed.")


def _inspect_epg_payload(
    payload: bytes,
    *,
    http_status: int,
    content_type: str | None,
    truncated: bool,
) -> EpgValidateResult:
    if not payload:
        return EpgValidateResult(
            success=False,
            status="empty",
            message="EPG URL returned an empty response.",
            http_status=http_status,
            content_type=content_type,
        )

    data = payload
    if payload.startswith(b"\x1f\x8b"):
        try:
            data = _decompress_gzip_prefix(payload)
        except ValueError:
            return EpgValidateResult(
                success=False,
                status="not_epg",
                message="EPG URL returned gzip data that could not be read.",
                http_status=http_status,
                content_type=content_type,
                bytes_checked=len(payload),
            )

    sample = data.lower()
    channel_count = len(re.findall(rb"<\s*channel\b", sample))
    programme_count = len(re.findall(rb"<\s*programme\b", sample))
    has_tv_root = re.search(rb"<\s*tv(?:\s|>)", sample) is not None

    if not has_tv_root:
        return EpgValidateResult(
            success=False,
            status="not_epg",
            message="The link works, but it does not look like XMLTV EPG data.",
            http_status=http_status,
            content_type=content_type,
            channel_count=channel_count,
            programme_count=programme_count,
            bytes_checked=len(data),
        )

    if programme_count < 1:
        return EpgValidateResult(
            success=False,
            status="no_programmes",
            message="The link works, but no programme entries were found.",
            http_status=http_status,
            content_type=content_type,
            channel_count=channel_count,
            programme_count=programme_count,
            bytes_checked=len(data),
        )

    suffix = f" Sample was truncated after the first {_EPG_FETCH_MAX_BYTES} bytes." if truncated else ""
    return EpgValidateResult(
        success=True,
        status="ok",
        message=(
            f"EPG data found: {programme_count} programme entries"
            f" and {channel_count} channels in the checked sample.{suffix}"
        ),
        http_status=http_status,
        content_type=content_type,
        channel_count=channel_count,
        programme_count=programme_count,
        bytes_checked=len(data),
    )


def _decompress_gzip_prefix(payload: bytes) -> bytes:
    decompressor = zlib.decompressobj(16 + zlib.MAX_WBITS)
    output = bytearray()
    try:
        for offset in range(0, len(payload), 65536):
            if len(output) >= _EPG_DECOMPRESS_MAX_BYTES:
                break
            chunk = payload[offset:offset + 65536]
            output.extend(
                decompressor.decompress(
                    chunk,
                    _EPG_DECOMPRESS_MAX_BYTES - len(output),
                )
            )
    except zlib.error as exc:
        if not output:
            raise ValueError("invalid gzip") from exc
    if not output:
        raise ValueError("empty gzip")
    return bytes(output)
