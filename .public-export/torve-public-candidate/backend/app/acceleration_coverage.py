"""Memory-id coverage helpers for acceleration candidate emission."""
from __future__ import annotations

import hashlib
import hmac
import logging
from collections import Counter
from dataclasses import dataclass
from urllib.parse import urlparse

from app.config import settings


@dataclass(frozen=True)
class MemoryCoverageSnapshot:
    endpoint: str
    user_hash: str
    device_hash: str
    content_type: str
    eligible_candidate_count: int
    memory_id_emitted_count: int
    memory_id_missing_count: int
    memory_id_coverage_ratio: float
    missing_reason_counts: dict[str, int]
    provider_category_counts: dict[str, int]
    source_category_counts: dict[str, int]


def anonymize_identifier(value: object | None) -> str:
    if value is None:
        return "none"
    raw = str(value).strip()
    if not raw:
        return "none"
    key = (settings.JWT_SECRET or "torve").encode("utf-8")
    return hmac.new(key, raw.encode("utf-8"), hashlib.sha256).hexdigest()[:12]


def content_type_for_request(season: int | None, episode: int | None) -> str:
    if season is not None and episode is not None:
        return "episode"
    if season is not None or episode is not None:
        return "series"
    return "movie_or_unknown"


def is_url_source_key(value: str | None) -> bool:
    if not value:
        return False
    try:
        parsed = urlparse(value)
    except Exception:  # noqa: BLE001
        return False
    return parsed.scheme in {"http", "https"} and bool(parsed.netloc)


def client_safe_source_key(source_key: str | None, *, memory_id: str | None) -> str | None:
    """Do not emit raw playback URLs once a backend memory reference exists."""
    if memory_id and is_url_source_key(source_key):
        return None
    return source_key


def source_category(
    *,
    provider_type: str | None,
    source_key: str | None = None,
    provenance: str | None = None,
    provenance_kind: str | None = None,
) -> str:
    pt = (provider_type or "").strip().lower()
    sk = (source_key or "").strip().lower()
    prov = (provenance or "").strip().lower()

    if provenance_kind == "USENET_NZBDAV" or pt == "nzbdav":
        return "usenet"
    if "panda" in pt or sk.startswith("panda:"):
        return "panda"
    if any(token in pt for token in ("debrid", "premiumize", "torbox")) or pt in {
        "rd",
        "ad",
        "pm",
        "realdebrid",
        "real_debrid",
        "all_debrid",
    }:
        return "debrid"
    if any(token in pt for token in ("addon", "torrentio", "stremio", "comet", "mediafusion")):
        return "addon"
    if sk.startswith(("stremio:", "torrentio:", "addon:")):
        return "addon"
    if is_url_source_key(source_key):
        return "legacy_direct"
    if prov in {"recent_success", "carryforward", "known_cached", "inventory_match"}:
        return "acceleration"
    return "unknown"


def memory_missing_reason(candidate: object) -> str:
    provenance = str(getattr(candidate, "provenance", "") or "")
    if provenance in {"inventory_match", "known_cached"}:
        return "not_backed_by_resolve_memory"
    return "memory_reference_unavailable"


def build_memory_id_coverage(
    *,
    endpoint: str,
    candidates: list[object],
    user_id: object | None,
    installation_id: str | None,
    content_type: str,
) -> MemoryCoverageSnapshot:
    emitted = 0
    missing = 0
    provider_counts: Counter[str] = Counter()
    source_counts: Counter[str] = Counter()
    missing_reasons: Counter[str] = Counter()
    buckets: Counter[tuple[str, str, str, str]] = Counter()

    for candidate in candidates:
        provider = str(getattr(candidate, "provider_type", "") or "unknown")
        memory_id = getattr(candidate, "memory_id", None)
        category = source_category(
            provider_type=provider,
            source_key=getattr(candidate, "source_key", None),
            provenance=getattr(candidate, "provenance", None),
            provenance_kind=getattr(candidate, "provenance_kind", None),
        )
        source_counts[category] += 1
        if category == "usenet":
            continue
        result = "emitted" if memory_id else "missing"
        reason = "has_memory_id" if memory_id else memory_missing_reason(candidate)
        emitted += 1 if memory_id else 0
        missing += 0 if memory_id else 1
        provider_counts[category] += 1
        if not memory_id:
            missing_reasons[reason] += 1
        buckets[(result, provider, category, reason)] += 1

    eligible = emitted + missing
    ratio = emitted / eligible if eligible else 0.0
    user_hash = anonymize_identifier(user_id)
    device_hash = anonymize_identifier(installation_id)
    return MemoryCoverageSnapshot(
        endpoint=endpoint,
        user_hash=user_hash,
        device_hash=device_hash,
        content_type=content_type,
        eligible_candidate_count=eligible,
        memory_id_emitted_count=emitted,
        memory_id_missing_count=missing,
        memory_id_coverage_ratio=round(ratio, 6),
        missing_reason_counts=dict(sorted(missing_reasons.items())),
        provider_category_counts=dict(sorted(provider_counts.items())),
        source_category_counts=dict(sorted(source_counts.items())),
    )


def log_memory_id_coverage(
    logger: logging.Logger,
    *,
    endpoint: str,
    candidates: list[object],
    user_id: object | None,
    installation_id: str | None,
    content_type: str,
) -> MemoryCoverageSnapshot:
    snapshot = build_memory_id_coverage(
        endpoint=endpoint,
        candidates=candidates,
        user_id=user_id,
        installation_id=installation_id,
        content_type=content_type,
    )
    coverage_pct = round(snapshot.memory_id_coverage_ratio * 100, 2)
    logger.info(
        "ACCEL_MEMORY_ID_COVERAGE endpoint=%s user_hash=%s device_hash=%s content_type=%s eligible=%d emitted=%d missing=%d coverage_pct=%.2f categories=%s missing_reasons=%s",
        endpoint,
        snapshot.user_hash,
        snapshot.device_hash,
        content_type,
        snapshot.eligible_candidate_count,
        snapshot.memory_id_emitted_count,
        snapshot.memory_id_missing_count,
        coverage_pct,
        snapshot.provider_category_counts,
        snapshot.missing_reason_counts,
    )
    buckets: Counter[tuple[str, str, str, str]] = Counter()
    for candidate in candidates:
        provider = str(getattr(candidate, "provider_type", "") or "unknown")
        memory_id = getattr(candidate, "memory_id", None)
        category = source_category(
            provider_type=provider,
            source_key=getattr(candidate, "source_key", None),
            provenance=getattr(candidate, "provenance", None),
            provenance_kind=getattr(candidate, "provenance_kind", None),
        )
        if category == "usenet":
            continue
        result = "emitted" if memory_id else "missing"
        reason = "has_memory_id" if memory_id else memory_missing_reason(candidate)
        buckets[(result, provider, category, reason)] += 1
    for (result, provider, category, reason), count in sorted(buckets.items()):
        logger.info(
            "ACCEL_MEMORY_ID_BUCKET endpoint=%s user_hash=%s device_hash=%s content_type=%s result=%s provider=%s category=%s reason=%s count=%d",
            endpoint,
            snapshot.user_hash,
            snapshot.device_hash,
            content_type,
            result,
            provider,
            category,
            reason,
            count,
        )
    return snapshot
