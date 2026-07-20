"""
Resolve memory service: records and queries source resolution outcomes.

Write paths:
- record_success(): upsert a successful playback/resolution
- record_failure(): mark a known source as having failed recently

Read paths:
- get_best_for_content(): highest-confidence source for exact content
- get_recent_for_content(): most recently successful source for exact content
- get_provider_success_set(): recent successful providers for a user
- get_series_carryforward(): successful sources from adjacent episodes

All operations are strictly account-scoped. No cross-user leakage.
"""
import logging
import uuid
from datetime import datetime, timedelta, timezone
from urllib.parse import urlparse

from sqlalchemy import and_, desc, func
from sqlalchemy.orm import Session

from app.models import ResolveSuccessMemory, _hash_source_key

_log = logging.getLogger(__name__)

# Default retention: successful sources remembered for 30 days
DEFAULT_RETENTION_DAYS = 30


def _source_log_ref(source_key: str) -> str:
    """Return a non-secret source reference for logs."""
    source_hash = _hash_source_key(source_key)[:12]
    try:
        parsed = urlparse(source_key)
    except Exception:  # noqa: BLE001
        parsed = None
    if parsed and parsed.scheme in {"http", "https"} and parsed.hostname:
        return f"url:{parsed.hostname}:sha256:{source_hash}"
    return f"sha256:{source_hash}"


# ── Write paths ─────────────────────────────────────────────────────────


def record_success(
    db: Session,
    *,
    user_id: uuid.UUID,
    content_id: str,
    provider_type: str,
    source_key: str,
    season: int | None = None,
    episode: int | None = None,
    infohash: str | None = None,
    file_name: str | None = None,
    quality: str | None = None,
    audio_flags: str | None = None,
    file_size: int | None = None,
    device_type: str | None = None,
    retention_days: int = DEFAULT_RETENTION_DAYS,
    provenance_kind: str | None = None,
    nzbdav_candidate_id: str | None = None,
    nzbdav_hash_key: str | None = None,
    nzbdav_nzb_url: str | None = None,
) -> ResolveSuccessMemory:
    """Record a successful source resolution. Idempotent upsert.

    If the same user+content+season+episode+provider+source_key already
    exists, increments success_count and refreshes last_success_at and
    expiry. Otherwise creates a new row.
    """
    now = datetime.now(timezone.utc)
    expires_at = now + timedelta(days=retention_days)
    source_key_hash = _hash_source_key(source_key)

    # Try to find existing row for this exact combination.
    # The unique index uq_rsm_user_content_source ensures at most one.
    # NOTE: PostgreSQL treats NULL != NULL in unique indexes, so
    # (season=NULL, episode=NULL) rows won't collide even without
    # explicit NULL handling in the filter.
    filters = [
        ResolveSuccessMemory.user_id == user_id,
        ResolveSuccessMemory.content_id == content_id,
        ResolveSuccessMemory.provider_type == provider_type,
        ResolveSuccessMemory.source_key_hash == source_key_hash,
        ResolveSuccessMemory.source_key == source_key,
    ]
    if season is not None:
        filters.append(ResolveSuccessMemory.season == season)
    else:
        filters.append(ResolveSuccessMemory.season.is_(None))
    if episode is not None:
        filters.append(ResolveSuccessMemory.episode == episode)
    else:
        filters.append(ResolveSuccessMemory.episode.is_(None))

    existing = db.query(ResolveSuccessMemory).filter(and_(*filters)).first()

    if existing:
        existing.success_count += 1
        existing.last_success_at = now
        existing.expires_at = expires_at
        # Update metadata if provided (may improve over time)
        if infohash and not existing.infohash:
            existing.infohash = infohash
        if file_name:
            existing.file_name = file_name
        if quality:
            existing.quality = quality
        if audio_flags:
            existing.audio_flags = audio_flags
        if file_size:
            existing.file_size = file_size
        if device_type:
            existing.last_device_type = device_type
        # Provenance + NzbDAV payload updates — only applied when all
        # required NzbDAV fields are present. The caller (outcome endpoint)
        # is responsible for enforcing the write-side guardrail; here we
        # just persist what was asked for.
        if provenance_kind:
            existing.provenance_kind = provenance_kind
        if nzbdav_candidate_id:
            existing.nzbdav_candidate_id = nzbdav_candidate_id
        if nzbdav_hash_key:
            existing.nzbdav_hash_key = nzbdav_hash_key
        if nzbdav_nzb_url:
            existing.nzbdav_nzb_url = nzbdav_nzb_url
        db.flush()
        _log.info(
            "RESOLVE_MEMORY_SUCCESS_UPSERT user=%s content=%s s=%s e=%s provider=%s count=%d",
            user_id, content_id, season, episode, provider_type, existing.success_count,
        )
        return existing

    row = ResolveSuccessMemory(
        user_id=user_id,
        content_id=content_id,
        season=season,
        episode=episode,
        provider_type=provider_type,
        source_key=source_key,
        source_key_hash=source_key_hash,
        infohash=infohash,
        file_name=file_name,
        quality=quality,
        audio_flags=audio_flags,
        file_size=file_size,
        success_count=1,
        last_success_at=now,
        last_device_type=device_type,
        expires_at=expires_at,
        provenance_kind=provenance_kind,
        nzbdav_candidate_id=nzbdav_candidate_id,
        nzbdav_hash_key=nzbdav_hash_key,
        nzbdav_nzb_url=nzbdav_nzb_url,
    )
    db.add(row)
    db.flush()
    _log.info(
        "RESOLVE_MEMORY_SUCCESS_NEW user=%s content=%s s=%s e=%s provider=%s source_ref=%s",
        user_id, content_id, season, episode, provider_type, _source_log_ref(source_key),
    )
    return row


def record_failure(
    db: Session,
    *,
    user_id: uuid.UUID,
    content_id: str,
    provider_type: str,
    source_key: str,
    season: int | None = None,
    episode: int | None = None,
) -> ResolveSuccessMemory | None:
    """Record a failed attempt for a previously-known source.

    Only updates existing rows (doesn't create rows for never-seen sources).
    Sets last_failure_at so confidence can be reduced when ranking.
    Returns the updated row, or None if no memory existed.
    """
    now = datetime.now(timezone.utc)
    source_key_hash = _hash_source_key(source_key)

    filters = [
        ResolveSuccessMemory.user_id == user_id,
        ResolveSuccessMemory.content_id == content_id,
        ResolveSuccessMemory.provider_type == provider_type,
        ResolveSuccessMemory.source_key_hash == source_key_hash,
        ResolveSuccessMemory.source_key == source_key,
    ]
    if season is not None:
        filters.append(ResolveSuccessMemory.season == season)
    else:
        filters.append(ResolveSuccessMemory.season.is_(None))
    if episode is not None:
        filters.append(ResolveSuccessMemory.episode == episode)
    else:
        filters.append(ResolveSuccessMemory.episode.is_(None))

    existing = db.query(ResolveSuccessMemory).filter(and_(*filters)).first()
    if not existing:
        return None

    existing.last_failure_at = now
    db.flush()
    _log.info(
        "RESOLVE_MEMORY_FAILURE user=%s content=%s s=%s e=%s provider=%s source_ref=%s",
        user_id, content_id, season, episode, provider_type, _source_log_ref(source_key),
    )
    return existing


# ── Read paths ──────────────────────────────────────────────────────────


def get_best_for_content(
    db: Session,
    *,
    user_id: uuid.UUID,
    content_id: str,
    season: int | None = None,
    episode: int | None = None,
    provider_type: str | None = None,
) -> ResolveSuccessMemory | None:
    """Highest-confidence source for exact content.

    Confidence = highest success_count, tie-broken by most recent success,
    penalized if last_failure_at > last_success_at (source broke since
    last success).
    """
    now = datetime.now(timezone.utc)

    q = db.query(ResolveSuccessMemory).filter(
        ResolveSuccessMemory.user_id == user_id,
        ResolveSuccessMemory.content_id == content_id,
        ResolveSuccessMemory.expires_at > now,
    )
    if season is not None:
        q = q.filter(ResolveSuccessMemory.season == season)
    else:
        q = q.filter(ResolveSuccessMemory.season.is_(None))
    if episode is not None:
        q = q.filter(ResolveSuccessMemory.episode == episode)
    else:
        q = q.filter(ResolveSuccessMemory.episode.is_(None))
    if provider_type:
        q = q.filter(ResolveSuccessMemory.provider_type == provider_type)

    # Exclude rows where last failure is more recent than last success
    # (source broke since it last worked)
    q = q.filter(
        (ResolveSuccessMemory.last_failure_at.is_(None))
        | (ResolveSuccessMemory.last_failure_at < ResolveSuccessMemory.last_success_at)
    )

    return q.order_by(
        desc(ResolveSuccessMemory.success_count),
        desc(ResolveSuccessMemory.last_success_at),
    ).first()


def get_best_reference_for_candidate(
    db: Session,
    *,
    user_id: uuid.UUID,
    content_id: str,
    provider_type: str,
    season: int | None = None,
    episode: int | None = None,
    infohash: str | None = None,
) -> ResolveSuccessMemory | None:
    """Best backend memory row that can back an emitted acceleration candidate.

    Inventory/hash candidates do not store playback references themselves. When
    the backend already has a recent successful source for the same content,
    provider, and infohash, attach that row's opaque id so clients can use the
    stream handoff endpoint instead of needing any raw source URL.
    """
    if not infohash:
        return None

    now = datetime.now(timezone.utc)
    q = db.query(ResolveSuccessMemory).filter(
        ResolveSuccessMemory.user_id == user_id,
        ResolveSuccessMemory.content_id == content_id,
        ResolveSuccessMemory.provider_type == provider_type,
        ResolveSuccessMemory.infohash == infohash,
        ResolveSuccessMemory.expires_at > now,
    )
    if season is not None:
        q = q.filter(ResolveSuccessMemory.season == season)
    else:
        q = q.filter(ResolveSuccessMemory.season.is_(None))
    if episode is not None:
        q = q.filter(ResolveSuccessMemory.episode == episode)
    else:
        q = q.filter(ResolveSuccessMemory.episode.is_(None))

    q = q.filter(
        (ResolveSuccessMemory.last_failure_at.is_(None))
        | (ResolveSuccessMemory.last_failure_at < ResolveSuccessMemory.last_success_at)
    )
    return q.order_by(
        desc(ResolveSuccessMemory.success_count),
        desc(ResolveSuccessMemory.last_success_at),
    ).first()


def get_recent_for_content(
    db: Session,
    *,
    user_id: uuid.UUID,
    content_id: str,
    season: int | None = None,
    episode: int | None = None,
    provider_type: str | None = None,
    limit: int = 5,
) -> list[ResolveSuccessMemory]:
    """Most recently successful sources for exact content.

    Returns up to `limit` rows ordered by last_success_at descending.
    Excludes sources that have failed more recently than they succeeded.
    """
    now = datetime.now(timezone.utc)

    q = db.query(ResolveSuccessMemory).filter(
        ResolveSuccessMemory.user_id == user_id,
        ResolveSuccessMemory.content_id == content_id,
        ResolveSuccessMemory.expires_at > now,
    )
    if season is not None:
        q = q.filter(ResolveSuccessMemory.season == season)
    else:
        q = q.filter(ResolveSuccessMemory.season.is_(None))
    if episode is not None:
        q = q.filter(ResolveSuccessMemory.episode == episode)
    else:
        q = q.filter(ResolveSuccessMemory.episode.is_(None))
    if provider_type:
        q = q.filter(ResolveSuccessMemory.provider_type == provider_type)

    q = q.filter(
        (ResolveSuccessMemory.last_failure_at.is_(None))
        | (ResolveSuccessMemory.last_failure_at < ResolveSuccessMemory.last_success_at)
    )

    return q.order_by(desc(ResolveSuccessMemory.last_success_at)).limit(limit).all()


def get_provider_success_set(
    db: Session,
    *,
    user_id: uuid.UUID,
    since_days: int = 7,
) -> list[dict]:
    """Recent provider success summary for a user.

    Returns a list of {provider_type, success_count, last_success_at}
    aggregated across all content for the given user within the lookback
    window. Used for provider ranking and preference inference.
    """
    now = datetime.now(timezone.utc)
    since = now - timedelta(days=since_days)

    rows = (
        db.query(
            ResolveSuccessMemory.provider_type,
            func.sum(ResolveSuccessMemory.success_count).label("total_successes"),
            func.max(ResolveSuccessMemory.last_success_at).label("last_success_at"),
            func.count(ResolveSuccessMemory.id).label("distinct_sources"),
        )
        .filter(
            ResolveSuccessMemory.user_id == user_id,
            ResolveSuccessMemory.last_success_at >= since,
            ResolveSuccessMemory.expires_at > now,
        )
        .group_by(ResolveSuccessMemory.provider_type)
        .order_by(desc("total_successes"))
        .all()
    )

    return [
        {
            "provider_type": r.provider_type,
            "total_successes": r.total_successes,
            "last_success_at": r.last_success_at.isoformat() if r.last_success_at else None,
            "distinct_sources": r.distinct_sources,
        }
        for r in rows
    ]


def get_series_carryforward(
    db: Session,
    *,
    user_id: uuid.UUID,
    content_id: str,
    season: int,
    episode: int,
    provider_type: str | None = None,
    lookback_episodes: int = 3,
) -> list[ResolveSuccessMemory]:
    """Successful sources from adjacent earlier episodes in the same series.

    For episode N, looks back at episodes (N-1), (N-2), ... up to
    lookback_episodes. Returns sources that worked for those episodes,
    ordered by closest episode first, then by success_count.

    Used to pre-rank sources for the next episode based on what worked
    for recent episodes in the same series/season.
    """
    now = datetime.now(timezone.utc)
    min_episode = max(1, episode - lookback_episodes)

    q = db.query(ResolveSuccessMemory).filter(
        ResolveSuccessMemory.user_id == user_id,
        ResolveSuccessMemory.content_id == content_id,
        ResolveSuccessMemory.season == season,
        ResolveSuccessMemory.episode >= min_episode,
        ResolveSuccessMemory.episode < episode,
        ResolveSuccessMemory.expires_at > now,
    )
    if provider_type:
        q = q.filter(ResolveSuccessMemory.provider_type == provider_type)

    # Exclude recently-failed sources
    q = q.filter(
        (ResolveSuccessMemory.last_failure_at.is_(None))
        | (ResolveSuccessMemory.last_failure_at < ResolveSuccessMemory.last_success_at)
    )

    # Closest episode first, then highest success count
    return q.order_by(
        desc(ResolveSuccessMemory.episode),
        desc(ResolveSuccessMemory.success_count),
    ).limit(20).all()


# ── Maintenance ─────────────────────────────────────────────────────────


def cleanup_expired(db: Session, batch_size: int = 1000) -> int:
    """Delete expired resolve memory rows. Returns count deleted."""
    now = datetime.now(timezone.utc)
    # Subquery for batch-limited deletion (SQLAlchemy can't .delete() with .limit())
    expired_ids = (
        db.query(ResolveSuccessMemory.id)
        .filter(ResolveSuccessMemory.expires_at < now)
        .limit(batch_size)
        .subquery()
    )
    count = (
        db.query(ResolveSuccessMemory)
        .filter(ResolveSuccessMemory.id.in_(expired_ids.select()))
        .delete(synchronize_session=False)
    )
    if count:
        db.commit()
        _log.info("RESOLVE_MEMORY_CLEANUP deleted=%d", count)
    return count
