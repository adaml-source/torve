import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, Field, field_validator
from sqlalchemy.orm import Session

from app.deps import get_current_user_id, get_db
from app.events import UserEvent, event_bus
from app.models import Device, UserMediaFavorite

router = APIRouter(prefix="/me/media-favorites", tags=["media-favorites"])

MEDIA_FAVORITES_UPDATED = "MEDIA_FAVORITES_UPDATED"
_VALID_MEDIA_TYPES = {"movie", "series"}


class MediaFavoriteUpsert(BaseModel):
    media_type: str = Field(..., min_length=1, max_length=20)
    tmdb_id: int | None = None
    imdb_id: str | None = Field(default=None, max_length=64)
    title: str = Field(..., min_length=1, max_length=500)
    poster_url: str | None = None
    backdrop_url: str | None = None
    rating: float | None = None
    year: int | None = None
    source_device_id: uuid.UUID | None = None

    @field_validator("media_type")
    @classmethod
    def validate_media_type(cls, value: str) -> str:
        normalized = value.strip().lower()
        if normalized == "tv":
            normalized = "series"
        if normalized not in _VALID_MEDIA_TYPES:
            raise ValueError("media_type must be 'movie' or 'series'")
        return normalized

    @field_validator("imdb_id", "poster_url", "backdrop_url")
    @classmethod
    def blank_to_none(cls, value: str | None) -> str | None:
        if value is None:
            return None
        stripped = value.strip()
        return stripped or None

    @field_validator("title")
    @classmethod
    def validate_title(cls, value: str) -> str:
        stripped = value.strip()
        if not stripped:
            raise ValueError("title is required")
        return stripped


class MediaFavoriteOut(BaseModel):
    id: uuid.UUID
    media_key: str
    media_type: str
    tmdb_id: int | None = None
    imdb_id: str | None = None
    title: str
    poster_url: str | None = None
    backdrop_url: str | None = None
    rating: float | None = None
    year: int | None = None
    added_at: datetime
    updated_at: datetime
    source_device_id: uuid.UUID | None = None

    model_config = {"from_attributes": True}


class MediaFavoritesList(BaseModel):
    items: list[MediaFavoriteOut]
    updated_at: datetime | None = None


class MediaFavoriteDeleteResponse(BaseModel):
    removed: bool


@router.get("", response_model=MediaFavoritesList)
def list_media_favorites(
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> MediaFavoritesList:
    uid = uuid.UUID(user_id)
    rows = (
        db.query(UserMediaFavorite)
        .filter(UserMediaFavorite.user_id == uid)
        .order_by(UserMediaFavorite.added_at.desc(), UserMediaFavorite.title.asc())
        .all()
    )
    updated_at = max((row.updated_at for row in rows), default=None)
    return MediaFavoritesList(items=rows, updated_at=updated_at)


@router.put("/{media_key:path}", response_model=MediaFavoriteOut)
def upsert_media_favorite(
    media_key: str,
    body: MediaFavoriteUpsert,
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> UserMediaFavorite:
    normalized_key = _normalize_media_key(media_key)
    uid = uuid.UUID(user_id)
    source_device_id = _validate_source_device(db, uid, body.source_device_id)
    now = datetime.now(timezone.utc)

    row = (
        db.query(UserMediaFavorite)
        .filter(
            UserMediaFavorite.user_id == uid,
            UserMediaFavorite.media_key == normalized_key,
        )
        .first()
    )
    if row is None:
        row = UserMediaFavorite(
            user_id=uid,
            media_key=normalized_key,
            media_type=body.media_type,
            tmdb_id=body.tmdb_id,
            imdb_id=body.imdb_id,
            title=body.title,
            poster_url=body.poster_url,
            backdrop_url=body.backdrop_url,
            rating=body.rating,
            year=body.year,
            source_device_id=source_device_id,
            added_at=now,
            updated_at=now,
        )
        db.add(row)
    else:
        row.media_type = body.media_type
        row.tmdb_id = body.tmdb_id
        row.imdb_id = body.imdb_id
        row.title = body.title
        row.poster_url = body.poster_url
        row.backdrop_url = body.backdrop_url
        row.rating = body.rating
        row.year = body.year
        row.source_device_id = source_device_id
        row.updated_at = now

    db.commit()
    db.refresh(row)
    _emit_favorites_updated(uid)
    return row


@router.delete("/{media_key:path}", response_model=MediaFavoriteDeleteResponse)
def delete_media_favorite(
    media_key: str,
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> MediaFavoriteDeleteResponse:
    normalized_key = _normalize_media_key(media_key)
    uid = uuid.UUID(user_id)
    row = (
        db.query(UserMediaFavorite)
        .filter(
            UserMediaFavorite.user_id == uid,
            UserMediaFavorite.media_key == normalized_key,
        )
        .first()
    )
    if row is None:
        return MediaFavoriteDeleteResponse(removed=False)

    db.delete(row)
    db.commit()
    _emit_favorites_updated(uid)
    return MediaFavoriteDeleteResponse(removed=True)


def _normalize_media_key(media_key: str) -> str:
    normalized = media_key.strip()
    if not normalized or len(normalized) > 255:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="media_key must be 1-255 characters",
        )
    return normalized


def _validate_source_device(
    db: Session,
    user_id: uuid.UUID,
    source_device_id: uuid.UUID | None,
) -> uuid.UUID | None:
    if source_device_id is None:
        return None
    exists = (
        db.query(Device.id)
        .filter(Device.user_id == user_id, Device.id == source_device_id)
        .first()
    )
    if exists is None:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="source_device_id does not belong to the signed-in user",
        )
    return source_device_id


def _emit_favorites_updated(user_id: uuid.UUID) -> None:
    event_bus.emit(UserEvent(event_type=MEDIA_FAVORITES_UPDATED, user_id=user_id))
