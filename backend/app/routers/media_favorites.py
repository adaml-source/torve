"""Account-backed movie/series favorites sync.

The backend is the source of truth. Clients update optimistically, listen to
/me/events, and refetch this collection whenever MEDIA_FAVORITES_UPDATED lands.
"""
from __future__ import annotations

import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, Path, status
from sqlalchemy import desc, func
from sqlalchemy.orm import Session

from app.deps import get_current_user_id, get_db
from app.events import UserEvent, event_bus
from app.models import Device, UserMediaFavorite
from app.schemas import (
    MediaFavoriteDeleteResponse,
    MediaFavoriteMutationResponse,
    MediaFavoriteOut,
    MediaFavoritesListResponse,
    MediaFavoriteSaveRequest,
)

router = APIRouter(prefix="/me/media-favorites", tags=["media-favorites"])


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _version(dt: datetime | None) -> str | None:
    return dt.isoformat() if dt else None


def _to_out(row: UserMediaFavorite) -> MediaFavoriteOut:
    return MediaFavoriteOut(
        id=row.id,
        media_key=row.media_key,
        media_type=row.media_type,
        tmdb_id=row.tmdb_id,
        imdb_id=row.imdb_id,
        title=row.title,
        poster_url=row.poster_url,
        backdrop_url=row.backdrop_url,
        rating=row.rating,
        year=row.year,
        added_at=row.added_at,
        updated_at=row.updated_at,
        source_device_id=row.source_device_id,
    )


def _collection_updated_at(db: Session, user_id: uuid.UUID) -> datetime | None:
    return (
        db.query(func.max(UserMediaFavorite.updated_at))
        .filter(UserMediaFavorite.user_id == user_id)
        .scalar()
    )


def _source_device_id(db: Session, user_id: uuid.UUID, device_id: uuid.UUID | None) -> uuid.UUID | None:
    if device_id is None:
        return None
    device = (
        db.query(Device)
        .filter(Device.id == device_id, Device.user_id == user_id)
        .one_or_none()
    )
    if device is None:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="source_device_id must belong to the authenticated user.",
        )
    return device.id


def _favorite_values(
    media_key: str,
    body: MediaFavoriteSaveRequest,
    source_device_id: uuid.UUID | None,
) -> dict:
    return {
        "media_key": media_key,
        "media_type": body.media_type,
        "tmdb_id": body.tmdb_id,
        "imdb_id": body.imdb_id,
        "title": body.title,
        "poster_url": body.poster_url,
        "backdrop_url": body.backdrop_url,
        "rating": body.rating,
        "year": body.year,
        "source_device_id": source_device_id,
    }


def _has_changes(row: UserMediaFavorite, values: dict) -> bool:
    return any(getattr(row, key) != value for key, value in values.items())


@router.get("", response_model=MediaFavoritesListResponse)
def list_media_favorites(
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> MediaFavoritesListResponse:
    uid = uuid.UUID(user_id)
    rows = (
        db.query(UserMediaFavorite)
        .filter(UserMediaFavorite.user_id == uid)
        .order_by(desc(UserMediaFavorite.added_at), UserMediaFavorite.title)
        .all()
    )
    updated_at = _collection_updated_at(db, uid)
    return MediaFavoritesListResponse(
        favorites=[_to_out(row) for row in rows],
        version=_version(updated_at),
        updated_at=updated_at,
    )


@router.put("/{media_key}", response_model=MediaFavoriteMutationResponse)
def save_media_favorite(
    body: MediaFavoriteSaveRequest,
    media_key: str = Path(min_length=1, max_length=255),
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> MediaFavoriteMutationResponse:
    uid = uuid.UUID(user_id)
    source_device_id = _source_device_id(db, uid, body.source_device_id)
    values = _favorite_values(media_key, body, source_device_id)

    row = (
        db.query(UserMediaFavorite)
        .filter(
            UserMediaFavorite.user_id == uid,
            UserMediaFavorite.media_key == media_key,
        )
        .one_or_none()
    )

    mutated = False
    if row is None:
        now = _now()
        row = UserMediaFavorite(
            user_id=uid,
            added_at=now,
            updated_at=now,
            **values,
        )
        db.add(row)
        mutated = True
    elif _has_changes(row, values):
        for key, value in values.items():
            setattr(row, key, value)
        row.updated_at = _now()
        mutated = True

    if mutated:
        db.commit()
        db.refresh(row)
        event_bus.emit(UserEvent("MEDIA_FAVORITES_UPDATED", uid))

    return MediaFavoriteMutationResponse(
        favorite=_to_out(row),
        version=_version(row.updated_at) or "",
        updated_at=row.updated_at,
    )


@router.delete("/{media_key}", response_model=MediaFavoriteDeleteResponse)
def remove_media_favorite(
    media_key: str = Path(min_length=1, max_length=255),
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> MediaFavoriteDeleteResponse:
    uid = uuid.UUID(user_id)
    row = (
        db.query(UserMediaFavorite)
        .filter(
            UserMediaFavorite.user_id == uid,
            UserMediaFavorite.media_key == media_key,
        )
        .one_or_none()
    )

    updated_at = _now()
    deleted = row is not None
    if deleted:
        db.delete(row)
        db.commit()
        event_bus.emit(UserEvent("MEDIA_FAVORITES_UPDATED", uid))

    return MediaFavoriteDeleteResponse(
        media_key=media_key,
        deleted=deleted,
        version=_version(updated_at) or "",
        updated_at=updated_at,
    )
