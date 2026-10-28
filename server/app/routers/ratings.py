import json
import uuid
from typing import Literal

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from app.deps import get_current_user_id, get_db
from app.crypto import decrypt_secret
from app.models import UserIntegration
from app.ratings_service import RatingIdentity, get_batch_ratings


router = APIRouter(prefix="/me/ratings", tags=["ratings"])


class RatingLookup(BaseModel):
    media_type: Literal["movie", "tv"]
    tmdb_id: int = Field(gt=0)
    imdb_id: str | None = Field(default=None, max_length=32)


class RatingBatchRequest(BaseModel):
    items: list[RatingLookup] = Field(min_length=1, max_length=12)


class RatingOut(BaseModel):
    media_type: str
    tmdb_id: int
    imdb_id: str | None = None
    imdb_score: float | None = None
    imdb_votes: int | None = None
    tmdb_score: float | None = None
    rotten_tomatoes_score: int | None = None
    rt_audience_score: int | None = None
    metacritic_score: int | None = None
    letterboxd_score: float | None = None
    trakt_score: float | None = None
    mdblist_score: float | None = None
    mal_score: float | None = None
    cached: bool


class RatingBatchResponse(BaseModel):
    items: list[RatingOut]


@router.post("/batch", response_model=RatingBatchResponse)
async def batch_ratings(
    body: RatingBatchRequest,
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> RatingBatchResponse:
    identities = [
        RatingIdentity(
            media_type=item.media_type,
            tmdb_id=item.tmdb_id,
            imdb_id=item.imdb_id,
        )
        for item in body.items
    ]
    personal_keys = _personal_rating_provider_keys(db, user_id)
    return RatingBatchResponse(
        items=await get_batch_ratings(
            db,
            identities,
            fallback_mdblist_api_key=personal_keys.get("mdblist", ""),
            fallback_omdb_api_key=personal_keys.get("omdb", ""),
        )
    )


def _personal_rating_provider_keys(db: Session, user_id: str) -> dict[str, str]:
    """Compatibility fallback until server-owned provider keys are configured."""
    rows = (
        db.query(UserIntegration)
        .filter(
            UserIntegration.user_id == uuid.UUID(user_id),
            UserIntegration.integration_type.in_(
                [
                    "mdblist",
                    "mdb_list",
                    "MDBLIST_API_KEY",
                    "omdb",
                    "OMDB_API_KEY",
                ]
            ),
            UserIntegration.storage_mode == "account",
        )
        .all()
    )
    found: dict[str, str] = {}
    for row in rows:
        if not row.encrypted_credentials:
            continue
        try:
            credentials = json.loads(decrypt_secret(row.encrypted_credentials))
        except (ValueError, json.JSONDecodeError):
            continue
        if not isinstance(credentials, dict):
            continue
        value = credentials.get("api_key") or next(
            (candidate for candidate in credentials.values() if isinstance(candidate, str)),
            None,
        )
        if not isinstance(value, str) or not value.strip():
            continue
        key = "omdb" if "omdb" in row.integration_type.lower() else "mdblist"
        found[key] = value.strip()
    return found
