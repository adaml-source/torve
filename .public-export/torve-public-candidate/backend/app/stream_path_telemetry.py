"""Privacy-preserving stream protection telemetry aggregation."""
from __future__ import annotations

from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any
import uuid

from sqlalchemy import func
from sqlalchemy.orm import Session

from app.models import StreamMemoryCoverageSnapshot, StreamPathTelemetry

ALLOWED_PATH_TYPES = {
    "usenet_handoff",
    "generic_handoff_memory_id",
    "legacy_direct_no_memory_id",
    "iptv_direct",
    "direct_free",
}

HANDOFF_ERROR_TYPES = {
    "stream_expired",
    "stream_reference_not_found",
    "stream_handoff_unavailable",
}

SENSITIVE_FIELD_NAMES = {
    "url",
    "stream_url",
    "playback_url",
    "memory_id",
    "source_key",
    "provider_token",
    "debrid_token",
    "iptv_username",
    "iptv_password",
    "authorization",
    "bearer",
    "provider_response_body",
    "query",
    "search_query",
}


@dataclass(frozen=True)
class StreamPathEventInput:
    user_hash: str
    device_hash: str
    path_type: str
    platform: str | None
    app_version: str | None
    distribution_channel: str | None
    content_type: str | None
    provider_category: str | None
    source_category: str | None
    device_category: str | None
    generated_at: datetime | None


def generated_at_from_epoch_millis(value: int | None) -> datetime | None:
    if value is None or value <= 0:
        return None
    # Ignore absurdly distant client clocks instead of preserving noisy data.
    if value > 4_102_444_800_000:  # 2100-01-01T00:00:00Z
        return None
    try:
        return datetime.fromtimestamp(value / 1000, tz=timezone.utc)
    except (OverflowError, OSError, ValueError):
        return None


def record_stream_path_event(db: Session, event: StreamPathEventInput) -> tuple[bool, str | None]:
    """Persist a coarse stream-path event, suppressing rapid duplicates."""
    cutoff = datetime.now(timezone.utc).timestamp() - 5
    cutoff_dt = datetime.fromtimestamp(cutoff, tz=timezone.utc)
    duplicate = (
        db.query(StreamPathTelemetry.id)
        .filter(
            StreamPathTelemetry.user_hash == event.user_hash,
            StreamPathTelemetry.device_hash == event.device_hash,
            StreamPathTelemetry.path_type == event.path_type,
            StreamPathTelemetry.platform == event.platform,
            StreamPathTelemetry.app_version == event.app_version,
            StreamPathTelemetry.distribution_channel == event.distribution_channel,
            StreamPathTelemetry.content_type == event.content_type,
            StreamPathTelemetry.provider_category == event.provider_category,
            StreamPathTelemetry.source_category == event.source_category,
            StreamPathTelemetry.device_category == event.device_category,
            StreamPathTelemetry.created_at >= cutoff_dt,
        )
        .first()
    )
    if duplicate is not None:
        return False, "duplicate"

    db.add(
        StreamPathTelemetry(
            id=uuid.uuid4(),
            user_hash=event.user_hash,
            device_hash=event.device_hash,
            path_type=event.path_type,
            platform=event.platform,
            app_version=event.app_version,
            distribution_channel=event.distribution_channel,
            content_type=event.content_type,
            provider_category=event.provider_category,
            source_category=event.source_category,
            device_category=event.device_category,
            generated_at=event.generated_at,
            created_at=datetime.now(timezone.utc),
        )
    )
    return True, None


def record_stream_handoff_error(
    db: Session,
    *,
    error_code: str,
    user_hash: str = "none",
    device_hash: str = "none",
    provider_category: str | None = None,
    source_category: str = "generic_handoff",
) -> None:
    """Record sanitized backend handoff failures for aggregate dashboards."""
    if error_code not in HANDOFF_ERROR_TYPES:
        return
    db.add(
        StreamPathTelemetry(
            id=uuid.uuid4(),
            user_hash=user_hash,
            device_hash=device_hash,
            path_type=error_code,
            platform="backend",
            app_version=None,
            distribution_channel=None,
            content_type=None,
            provider_category=provider_category,
            source_category=source_category,
            device_category=None,
            generated_at=None,
            created_at=datetime.now(timezone.utc),
        )
    )


def record_memory_coverage_snapshot(
    db: Session,
    *,
    endpoint: str,
    user_hash: str,
    device_hash: str,
    content_type: str,
    eligible_candidate_count: int,
    memory_id_emitted_count: int,
    memory_id_missing_count: int,
    memory_id_coverage_ratio: float,
    missing_reason_counts: dict[str, int],
    provider_category_counts: dict[str, int],
    source_category_counts: dict[str, int],
) -> None:
    db.add(
        StreamMemoryCoverageSnapshot(
            id=uuid.uuid4(),
            endpoint=endpoint,
            user_hash=user_hash,
            device_hash=device_hash,
            content_type=content_type,
            eligible_candidate_count=eligible_candidate_count,
            memory_id_emitted_count=memory_id_emitted_count,
            memory_id_missing_count=memory_id_missing_count,
            memory_id_coverage_ratio=memory_id_coverage_ratio,
            missing_reason_counts=missing_reason_counts,
            provider_category_counts=provider_category_counts,
            source_category_counts=source_category_counts,
            created_at=datetime.now(timezone.utc),
        )
    )


def aggregate_stream_protection_metrics(db: Session, *, since: datetime) -> dict[str, Any]:
    path_counts = _path_type_counts(db, since=since)
    generic = path_counts.get("generic_handoff_memory_id", 0)
    legacy = path_counts.get("legacy_direct_no_memory_id", 0)
    handoff_denominator = generic + legacy
    protected_ratio = generic / handoff_denominator if handoff_denominator else 0.0
    legacy_ratio = legacy / handoff_denominator if handoff_denominator else 0.0
    coverage = _memory_coverage_metrics(db, since=since)

    return {
        "total_events": sum(path_counts.get(path_type, 0) for path_type in ALLOWED_PATH_TYPES),
        "generic_handoff_memory_id_count": generic,
        "legacy_direct_no_memory_id_count": legacy,
        "usenet_handoff_count": path_counts.get("usenet_handoff", 0),
        "iptv_direct_count": path_counts.get("iptv_direct", 0),
        "direct_free_count": path_counts.get("direct_free", 0),
        "stream_expired_count": path_counts.get("stream_expired", 0),
        "stream_reference_not_found_count": path_counts.get("stream_reference_not_found", 0),
        "stream_handoff_unavailable_count": path_counts.get("stream_handoff_unavailable", 0),
        "handoff_error_counts": {
            "stream_expired": path_counts.get("stream_expired", 0),
            "stream_reference_not_found": path_counts.get("stream_reference_not_found", 0),
            "stream_handoff_unavailable": path_counts.get("stream_handoff_unavailable", 0),
        },
        "protected_handoff_ratio": round(protected_ratio, 6),
        "legacy_fallback_ratio": round(legacy_ratio, 6),
        "memory_id_coverage_ratio": coverage["memory_id_coverage_ratio"],
        "breakdown_by_platform": _breakdown(db, since=since, column=StreamPathTelemetry.platform),
        "breakdown_by_provider_category": _breakdown(
            db, since=since, column=StreamPathTelemetry.provider_category
        ),
        "breakdown_by_app_version": _breakdown(
            db, since=since, column=StreamPathTelemetry.app_version
        ),
        "memory_id_coverage": coverage,
    }


def _path_type_counts(db: Session, *, since: datetime) -> dict[str, int]:
    rows = (
        db.query(StreamPathTelemetry.path_type, func.count(StreamPathTelemetry.id))
        .filter(StreamPathTelemetry.created_at >= since)
        .group_by(StreamPathTelemetry.path_type)
        .all()
    )
    return {str(path_type): int(count) for path_type, count in rows}


def _breakdown(db: Session, *, since: datetime, column) -> dict[str, dict[str, int]]:
    rows = (
        db.query(column, StreamPathTelemetry.path_type, func.count(StreamPathTelemetry.id))
        .filter(
            StreamPathTelemetry.created_at >= since,
            StreamPathTelemetry.path_type.in_(ALLOWED_PATH_TYPES),
        )
        .group_by(column, StreamPathTelemetry.path_type)
        .all()
    )
    result: dict[str, dict[str, int]] = {}
    for key, path_type, count in rows:
        bucket = str(key or "unknown")
        result.setdefault(bucket, {"total": 0})
        result[bucket][str(path_type)] = int(count)
        result[bucket]["total"] += int(count)
    return dict(sorted(result.items()))


def _memory_coverage_metrics(db: Session, *, since: datetime) -> dict[str, Any]:
    rows = (
        db.query(StreamMemoryCoverageSnapshot)
        .filter(StreamMemoryCoverageSnapshot.created_at >= since)
        .all()
    )
    eligible = sum(int(r.eligible_candidate_count or 0) for r in rows)
    emitted = sum(int(r.memory_id_emitted_count or 0) for r in rows)
    missing = sum(int(r.memory_id_missing_count or 0) for r in rows)
    ratio = emitted / eligible if eligible else 0.0
    missing_reasons: Counter[str] = Counter()
    provider_counts: Counter[str] = Counter()
    source_counts: Counter[str] = Counter()
    content_counts: Counter[str] = Counter()
    for row in rows:
        missing_reasons.update(_json_counter(row.missing_reason_counts))
        provider_counts.update(_json_counter(row.provider_category_counts))
        source_counts.update(_json_counter(row.source_category_counts))
        content_counts[str(row.content_type or "unknown")] += int(row.eligible_candidate_count or 0)
    return {
        "eligible_candidate_count": eligible,
        "memory_id_emitted_count": emitted,
        "memory_id_missing_count": missing,
        "memory_id_coverage_ratio": round(ratio, 6),
        "missing_reason_counts": dict(sorted(missing_reasons.items())),
        "provider_category_breakdown": dict(sorted(provider_counts.items())),
        "source_category_breakdown": dict(sorted(source_counts.items())),
        "content_type_breakdown": dict(sorted(content_counts.items())),
    }


def _json_counter(value: object) -> Counter[str]:
    if not isinstance(value, dict):
        return Counter()
    out: Counter[str] = Counter()
    for key, count in value.items():
        try:
            out[str(key)] += int(count)
        except (TypeError, ValueError):
            continue
    return out
