"""
Account-scoped playlist backup and restore.

Playlists (M3U and Xtream) are stored under the authenticated user's
account so they can be restored on sign-in. Xtream passwords are
encrypted at rest. Passwords are never returned in list/metadata responses.
"""

import logging
import re
import uuid
from datetime import datetime, timezone
from urllib.parse import urlsplit

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session

from app.crypto import decrypt_secret, encrypt_secret
from app.deps import get_current_user_id, get_db, require_premium
from app.models import UserPlaylist
from app.schemas import PlaylistOut, PlaylistSaveRequest

_log = logging.getLogger(__name__)

router = APIRouter(prefix="/me/playlists", tags=["playlists"])


def _normalize_http_url(value: str | None, field_name: str) -> str | None:
    if value is None:
        return None
    normalized = re.sub(r"^(https?://)\s+", r"\1", value.strip(), flags=re.IGNORECASE)
    if not normalized:
        return None
    parsed = urlsplit(normalized)
    if parsed.scheme.lower() not in {"http", "https"} or not parsed.hostname:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=f"{field_name} must be a valid http:// or https:// URL.")
    if any(ch.isspace() for ch in parsed.netloc):
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=f"{field_name} host must not contain whitespace.")
    if len(normalized) > 2048:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=f"{field_name} exceeds maximum length of 2048 characters.")
    return normalized


def _playlist_identity(
    playlist_type: str,
    *,
    url: str | None,
    server: str | None,
    username: str | None,
) -> tuple[str, ...] | None:
    try:
        if playlist_type == "xtream":
            if not server or not username:
                return None
            normalized = re.sub(r"^(https?://)\s+", r"\1", server.strip(), flags=re.IGNORECASE)
            parsed = urlsplit(normalized.rstrip("/"))
            port = f":{parsed.port}" if parsed.port else ""
            path = parsed.path.rstrip("/")
            return ("xtream", f"{parsed.scheme.lower()}://{(parsed.hostname or '').lower()}{port}{path}", username.strip())
        if not url:
            return None
        normalized = re.sub(r"^(https?://)\s+", r"\1", url.strip(), flags=re.IGNORECASE)
        parsed = urlsplit(normalized)
        port = f":{parsed.port}" if parsed.port else ""
        identity_url = f"{parsed.scheme.lower()}://{(parsed.hostname or '').lower()}{port}{parsed.path}"
        if parsed.query:
            identity_url += f"?{parsed.query}"
        return ("m3u", identity_url)
    except ValueError:
        return None


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


@router.get("", response_model=list[PlaylistOut])
def list_playlists(
    user_id: str = Depends(get_current_user_id),
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
    _log.info("Listed %d playlists for user %s", len(rows), uid)
    return [_to_out(r) for r in rows]


@router.put("/{playlist_id}", response_model=PlaylistOut)
def save_playlist(
    playlist_id: str,
    body: PlaylistSaveRequest,
    user_id: str = Depends(require_premium),
    db: Session = Depends(get_db),
) -> PlaylistOut:
    """Save or update a playlist backup. Upserts by user + playlist_id.

    For Xtream playlists, the password is encrypted before storage.
    The response never includes the raw password.
    """
    uid = uuid.UUID(user_id)
    normalized_url = _normalize_http_url(body.url, "url")
    normalized_epg_url = _normalize_http_url(body.epg_url, "epg_url")
    normalized_server = _normalize_http_url(body.server, "server")
    if normalized_server:
        normalized_server = normalized_server.rstrip("/")

    if body.playlist_id != playlist_id:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="playlist_id in body must match URL path.",
        )

    # Validate required fields per type
    if body.playlist_type == "m3u" and not normalized_url:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="url is required for m3u playlists.",
        )
    if body.playlist_type == "xtream":
        if not normalized_server or not body.username:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="server and username are required for xtream playlists.",
            )

    # Validate URL schemes (defense-in-depth — backend stores but does not fetch)
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

    if existing is None:
        incoming_identity = _playlist_identity(
            body.playlist_type,
            url=normalized_url,
            server=normalized_server,
            username=body.username,
        )
        if incoming_identity is not None:
            candidates = db.query(UserPlaylist).filter(UserPlaylist.user_id == uid).all()
            existing = next(
                (
                    row for row in candidates
                    if _playlist_identity(
                        row.playlist_type,
                        url=row.url,
                        server=row.server,
                        username=row.username,
                    ) == incoming_identity
                ),
                None,
            )

    if existing:
        existing.name = body.name
        existing.playlist_type = body.playlist_type
        existing.url = normalized_url
        existing.epg_url = normalized_epg_url
        existing.server = normalized_server
        existing.username = body.username
        if enc_password is not None:
            existing.encrypted_password = enc_password
        existing.updated_at = datetime.now(timezone.utc)
        db.commit()
        db.refresh(existing)
        _log.info("Updated playlist %s (type=%s) for user %s",
                  playlist_id, body.playlist_type, uid)
        return _to_out(existing)

    row = UserPlaylist(
        user_id=uid,
        playlist_id=playlist_id,
        name=body.name,
        playlist_type=body.playlist_type,
        url=normalized_url,
        epg_url=normalized_epg_url,
        server=normalized_server,
        username=body.username,
        encrypted_password=enc_password,
    )
    db.add(row)
    db.commit()
    db.refresh(row)
    _log.info("Created playlist %s (type=%s) for user %s",
              playlist_id, body.playlist_type, uid)
    return _to_out(row)


@router.delete("/{playlist_id}", status_code=status.HTTP_204_NO_CONTENT)
def remove_playlist(
    playlist_id: str,
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> None:
    """Remove a backed-up playlist."""
    row = _get_own_playlist(db, user_id, playlist_id)
    db.delete(row)
    db.commit()
    _log.info("Removed playlist %s for user %s", playlist_id, user_id)


@router.get("/{playlist_id}/credentials", include_in_schema=False)
def get_playlist_credentials(
    playlist_id: str,
    user_id: str = Depends(require_premium),
    db: Session = Depends(get_db),
) -> dict:
    """Return decrypted Xtream password for restore.

    Only meaningful for xtream playlists. M3U playlists return
    empty password. Hidden from public API docs.
    """
    row = _get_own_playlist(db, user_id, playlist_id)

    if row.playlist_type != "xtream" or not row.encrypted_password:
        _log.info("Credentials requested for non-xtream/no-password playlist %s", playlist_id)
        return {"playlist_id": playlist_id, "password": None}

    try:
        password = decrypt_secret(row.encrypted_password)
    except ValueError:
        _log.error("Failed to decrypt password for playlist %s user %s",
                   playlist_id, user_id)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Failed to decrypt playlist credentials. Please re-save.",
        )

    _log.info("Credentials retrieved for playlist %s user %s", playlist_id, user_id)
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
