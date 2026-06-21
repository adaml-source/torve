"""
Hash availability memory service.

Records and queries per-user, per-provider observations of infohash
cached/available state. Short TTL (hours). Used to skip redundant
availability checks during source resolution.
"""
import logging
import uuid
from datetime import datetime, timedelta, timezone

from sqlalchemy import and_, desc
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.orm import Session

from app.models import HashAvailabilityMemory

_log = logging.getLogger(__name__)

DEFAULT_TTL_HOURS = 4


def record_observation(
    db: Session,
    *,
    user_id: uuid.UUID,
    provider_type: str,
    infohash: str,
    is_cached: bool,
    source: str = "resolve",
    confidence: str = "observed",
    ttl_hours: int = DEFAULT_TTL_HOURS,
) -> HashAvailabilityMemory:
    """Record or update a hash availability observation. Idempotent upsert."""
    now = datetime.now(timezone.utc)
    expires_at = now + timedelta(hours=ttl_hours)
    infohash = infohash.lower().strip()

    stmt = pg_insert(HashAvailabilityMemory).values(
        user_id=user_id,
        provider_type=provider_type,
        infohash=infohash,
        is_cached=is_cached,
        observed_at=now,
        observation_source=source,
        confidence=confidence,
        expires_at=expires_at,
    ).on_conflict_do_update(
        index_elements=["user_id", "provider_type", "infohash"],
        set_={
            "is_cached": is_cached,
            "observed_at": now,
            "observation_source": source,
            "confidence": confidence,
            "expires_at": expires_at,
        },
    )
    db.execute(stmt)
    db.flush()
    return db.query(HashAvailabilityMemory).filter(
        HashAvailabilityMemory.user_id == user_id,
        HashAvailabilityMemory.provider_type == provider_type,
        HashAvailabilityMemory.infohash == infohash,
    ).one()


MAX_BATCH_SIZE = 200


def record_batch(
    db: Session,
    *,
    user_id: uuid.UUID,
    provider_type: str,
    observations: list[dict],
    source: str = "resolve",
    ttl_hours: int = DEFAULT_TTL_HOURS,
) -> int:
    """Record a batch of hash observations. Returns count recorded.

    Capped at MAX_BATCH_SIZE to prevent abuse.
    """
    count = 0
    for obs in observations[:MAX_BATCH_SIZE]:
        infohash = obs.get("infohash")
        if not infohash:
            continue
        record_observation(
            db,
            user_id=user_id,
            provider_type=provider_type,
            infohash=infohash,
            is_cached=obs.get("is_cached", False),
            source=source,
            ttl_hours=ttl_hours,
        )
        count += 1
    db.flush()
    return count


def check_hash(
    db: Session,
    *,
    user_id: uuid.UUID,
    provider_type: str,
    infohash: str,
) -> dict | None:
    """Check if a hash has a fresh availability observation.

    Returns {infohash, is_cached, observed_at, confidence, expires_at}
    or None if no fresh observation exists.
    """
    now = datetime.now(timezone.utc)
    row = db.query(HashAvailabilityMemory).filter(
        HashAvailabilityMemory.user_id == user_id,
        HashAvailabilityMemory.provider_type == provider_type,
        HashAvailabilityMemory.infohash == infohash.lower().strip(),
        HashAvailabilityMemory.expires_at > now,
    ).first()

    if not row:
        return None

    return {
        "infohash": row.infohash,
        "is_cached": row.is_cached,
        "observed_at": row.observed_at.isoformat(),
        "confidence": row.confidence,
        "expires_at": row.expires_at.isoformat(),
    }


def check_hashes(
    db: Session,
    *,
    user_id: uuid.UUID,
    provider_type: str,
    infohashes: list[str],
) -> list[dict]:
    """Batch check: return fresh observations for multiple hashes."""
    if not infohashes:
        return []
    now = datetime.now(timezone.utc)
    clean = [h.lower().strip() for h in infohashes if h]
    rows = db.query(HashAvailabilityMemory).filter(
        HashAvailabilityMemory.user_id == user_id,
        HashAvailabilityMemory.provider_type == provider_type,
        HashAvailabilityMemory.infohash.in_(clean),
        HashAvailabilityMemory.expires_at > now,
    ).all()

    return [
        {
            "infohash": r.infohash,
            "is_cached": r.is_cached,
            "observed_at": r.observed_at.isoformat(),
            "confidence": r.confidence,
            "expires_at": r.expires_at.isoformat(),
        }
        for r in rows
    ]


def get_cached_hashes(
    db: Session,
    *,
    user_id: uuid.UUID,
    provider_type: str,
    limit: int = 500,
) -> list[str]:
    """Return infohashes known to be cached for a user+provider.

    Limited to avoid unbounded result sets for heavy users.
    """
    now = datetime.now(timezone.utc)
    rows = db.query(HashAvailabilityMemory.infohash).filter(
        HashAvailabilityMemory.user_id == user_id,
        HashAvailabilityMemory.provider_type == provider_type,
        HashAvailabilityMemory.is_cached == True,  # noqa: E712
        HashAvailabilityMemory.expires_at > now,
    ).order_by(desc(HashAvailabilityMemory.observed_at)).limit(limit).all()
    return [r[0] for r in rows]


def cleanup_expired(db: Session, batch_size: int = 2000) -> int:
    """Delete expired hash-availability rows. Returns count deleted.

    Short TTL table (default 4h); cleanup keeps row count proportional
    to active users rather than unbounded historical observations.
    """
    now = datetime.now(timezone.utc)
    expired_ids = (
        db.query(HashAvailabilityMemory.id)
        .filter(HashAvailabilityMemory.expires_at < now)
        .limit(batch_size)
        .subquery()
    )
    deleted = (
        db.query(HashAvailabilityMemory)
        .filter(HashAvailabilityMemory.id.in_(db.query(expired_ids)))
        .delete(synchronize_session=False)
    )
    return deleted
