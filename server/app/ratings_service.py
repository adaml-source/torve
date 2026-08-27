"""Server-owned, cacheable external media-rating lookups."""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any

import httpx
from sqlalchemy import or_, select
from sqlalchemy.dialects.postgresql import insert as postgresql_insert
from sqlalchemy.sql.dml import Insert
from sqlalchemy.orm import Session

from app.config import settings
from app.models import GlobalMediaRating


@dataclass(frozen=True)
class RatingIdentity:
    media_type: str
    tmdb_id: int
    imdb_id: str | None = None


def rating_row_payload(row: GlobalMediaRating, *, cached: bool) -> dict[str, Any]:
    return {
        "media_type": row.media_type,
        "tmdb_id": row.tmdb_id,
        "imdb_id": row.imdb_id,
        "imdb_score": row.imdb_score,
        "imdb_votes": row.imdb_votes,
        "tmdb_score": row.tmdb_score,
        "rotten_tomatoes_score": row.rotten_tomatoes_score,
        "rt_audience_score": row.rt_audience_score,
        "metacritic_score": row.metacritic_score,
        "letterboxd_score": row.letterboxd_score,
        "trakt_score": row.trakt_score,
        "mdblist_score": row.mdblist_score,
        "mal_score": row.mal_score,
        "cached": cached,
    }


def _rating_value(ratings: list[dict[str, Any]], source: str, field: str = "value") -> Any:
    entry = next((item for item in ratings if item.get("source") == source), None)
    return entry.get(field) if entry else None


def _float_or_none(value: Any) -> float | None:
    try:
        parsed = float(value)
        return parsed if parsed > 0 else None
    except (TypeError, ValueError):
        return None


def _int_or_none(value: Any) -> int | None:
    try:
        parsed = int(float(value))
        return parsed if parsed > 0 else None
    except (TypeError, ValueError):
        return None


def parse_mdblist_payload(identity: RatingIdentity, payload: dict[str, Any]) -> dict[str, Any] | None:
    ratings = payload.get("ratings")
    if not isinstance(ratings, list):
        return None
    imdb_score = _float_or_none(_rating_value(ratings, "imdb"))
    if imdb_score is None:
        return None
    return {
        "media_type": identity.media_type,
        "tmdb_id": identity.tmdb_id,
        "imdb_id": payload.get("imdbid") or payload.get("imdb_id") or identity.imdb_id,
        "imdb_score": imdb_score,
        "imdb_votes": _int_or_none(_rating_value(ratings, "imdb", "votes")),
        "tmdb_score": _float_or_none(_rating_value(ratings, "tmdb")),
        "rotten_tomatoes_score": _int_or_none(_rating_value(ratings, "tomatoes")),
        "rt_audience_score": _int_or_none(_rating_value(ratings, "tomatoesaudience")),
        "metacritic_score": _int_or_none(_rating_value(ratings, "metacritic")),
        "letterboxd_score": _float_or_none(_rating_value(ratings, "letterboxd")),
        "trakt_score": _float_or_none(_rating_value(ratings, "trakt")),
        "mdblist_score": _float_or_none(_rating_value(ratings, "mdblist", "score")),
        "mal_score": _float_or_none(_rating_value(ratings, "mal")),
    }


def parse_omdb_payload(identity: RatingIdentity, payload: dict[str, Any]) -> dict[str, Any] | None:
    if payload.get("Response") == "False":
        return None
    score_text = str(payload.get("imdbRating") or "").strip()
    score = _float_or_none(score_text) if score_text not in {"", "N/A"} else None
    if score is None:
        return None
    votes_text = str(payload.get("imdbVotes") or "").replace(",", "")
    rt_score = None
    for entry in payload.get("Ratings") or []:
        if entry.get("Source") == "Rotten Tomatoes":
            rt_score = _int_or_none(str(entry.get("Value") or "").rstrip("%"))
            break
    return {
        "media_type": identity.media_type,
        "tmdb_id": identity.tmdb_id,
        "imdb_id": payload.get("imdbID") or identity.imdb_id,
        "imdb_score": score,
        "imdb_votes": _int_or_none(votes_text),
        "rotten_tomatoes_score": rt_score,
        "metacritic_score": _int_or_none(payload.get("Metascore")),
    }


async def _fetch_one(
    client: httpx.AsyncClient,
    identity: RatingIdentity,
    semaphore: asyncio.Semaphore,
    mdblist_api_key: str,
    omdb_api_key: str,
) -> dict[str, Any] | None:
    async with semaphore:
        if mdblist_api_key:
            kind = "movie" if identity.media_type == "movie" else "show"
            try:
                response = await client.get(
                    f"https://api.mdblist.com/tmdb/{kind}/{identity.tmdb_id}",
                    params={"apikey": mdblist_api_key},
                )
                if response.status_code == 200:
                    parsed = parse_mdblist_payload(identity, response.json())
                    if parsed is not None:
                        return parsed
            except (httpx.HTTPError, ValueError):
                pass

        if omdb_api_key and identity.imdb_id:
            try:
                response = await client.get(
                    "https://www.omdbapi.com/",
                    params={"apikey": omdb_api_key, "i": identity.imdb_id},
                )
                if response.status_code == 200:
                    return parse_omdb_payload(identity, response.json())
            except (httpx.HTTPError, ValueError):
                pass
    return None


RATING_VALUE_FIELDS = (
    "imdb_id",
    "imdb_score",
    "imdb_votes",
    "tmdb_score",
    "rotten_tomatoes_score",
    "rt_audience_score",
    "metacritic_score",
    "letterboxd_score",
    "trakt_score",
    "mdblist_score",
    "mal_score",
)


def _rating_upsert_statement(data: dict[str, Any], now: datetime) -> Insert:
    """Build one atomic PostgreSQL upsert for the canonical rating identity.

    The previous select-then-add flow allowed concurrent batch requests to both
    observe a missing row and race on the unique (media_type, tmdb_id) index.
    Keeping the conflict decision in PostgreSQL makes that path deterministic
    without discarding non-null scores already cached by another request.
    """
    values: dict[str, Any] = {
        "media_type": data["media_type"],
        "tmdb_id": data["tmdb_id"],
        "fetched_at": now,
        "updated_at": now,
    }
    for field in RATING_VALUE_FIELDS:
        value = data.get(field)
        if value is not None:
            values[field] = value

    insert = postgresql_insert(GlobalMediaRating).values(**values)
    updates = {
        field: getattr(insert.excluded, field)
        for field in RATING_VALUE_FIELDS
        if field in values
    }
    updates["fetched_at"] = insert.excluded.fetched_at
    updates["updated_at"] = insert.excluded.updated_at
    return insert.on_conflict_do_update(
        index_elements=[GlobalMediaRating.media_type, GlobalMediaRating.tmdb_id],
        set_=updates,
    ).returning(GlobalMediaRating)


def _upsert_rating(db: Session, data: dict[str, Any], now: datetime) -> GlobalMediaRating:
    row = db.scalar(_rating_upsert_statement(data, now))
    if row is None:
        raise RuntimeError("Rating upsert did not return the persisted row")
    return row


async def get_batch_ratings(
    db: Session,
    identities: list[RatingIdentity],
    *,
    fallback_mdblist_api_key: str = "",
    fallback_omdb_api_key: str = "",
) -> list[dict[str, Any]]:
    if not identities:
        return []
    unique = list({(item.media_type, item.tmdb_id): item for item in identities}.values())
    conditions = [
        (GlobalMediaRating.media_type == item.media_type) &
        (GlobalMediaRating.tmdb_id == item.tmdb_id)
        for item in unique
    ]
    rows = list(db.scalars(select(GlobalMediaRating).where(or_(*conditions))).all())
    by_key = {(row.media_type, row.tmdb_id): row for row in rows}
    now = datetime.now(timezone.utc)
    cutoff = now - timedelta(days=max(settings.RATINGS_CACHE_TTL_DAYS, 1))
    fresh = {
        key: row
        for key, row in by_key.items()
        if row.fetched_at >= cutoff and row.imdb_score is not None
    }
    misses = [item for item in unique if (item.media_type, item.tmdb_id) not in fresh]

    fetched_rows: list[GlobalMediaRating] = []
    mdblist_api_key = settings.MDBLIST_API_KEY.strip() or fallback_mdblist_api_key.strip()
    omdb_api_key = settings.OMDB_API_KEY.strip() or fallback_omdb_api_key.strip()
    if misses and (mdblist_api_key or omdb_api_key):
        timeout = httpx.Timeout(settings.RATINGS_PROVIDER_TIMEOUT_SECONDS)
        async with httpx.AsyncClient(timeout=timeout) as client:
            semaphore = asyncio.Semaphore(6)
            payloads = await asyncio.gather(
                *(
                    _fetch_one(
                        client,
                        item,
                        semaphore,
                        mdblist_api_key,
                        omdb_api_key,
                    )
                    for item in misses
                ),
            )
        for payload in payloads:
            if payload is not None:
                fetched_rows.append(_upsert_rating(db, payload, now))
        if fetched_rows:
            db.commit()

    fetched_keys = {(row.media_type, row.tmdb_id) for row in fetched_rows}
    result = [rating_row_payload(row, cached=True) for row in fresh.values()]
    result.extend(rating_row_payload(row, cached=False) for row in fetched_rows)
    # A stale value is better than an empty badge if every provider is currently
    # unavailable. It never blocks the response and is marked cached.
    result.extend(
        rating_row_payload(row, cached=True)
        for key, row in by_key.items()
        if key not in fresh and key not in fetched_keys and row.imdb_score is not None
    )
    return result
