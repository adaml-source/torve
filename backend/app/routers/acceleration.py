"""
Source acceleration API.

Exposes fast-path signals for source resolution:
- Recent successful sources for content
- Known hash availability per provider
- Provider inventory matches
- Merged startup candidates

All endpoints require authentication and active account access.
Responses degrade gracefully to empty when no data exists.
"""
import logging
import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, Query, Request
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from app.deps import get_db, require_account_access
from app import hash_availability_service as has_svc
from app import inventory_service as inv_svc
from app import resolve_memory_service as rsm_svc
from app import startup_assembly
from app.acceleration_coverage import (
    client_safe_source_key,
    content_type_for_request,
    log_memory_id_coverage,
)
from app.stream_path_telemetry import record_memory_coverage_snapshot
from app.content_policy import (
    AccessContext,
    ContentClassification,
    ContentPolicyMode,
    AgeBand,
    PolicyDecision,
    classify_text,
    decide,
)
from app.content_policy_service import get_or_create_policy, get_override_sets, resolve_channel

_log = logging.getLogger(__name__)

router = APIRouter(prefix="/me/acceleration", tags=["acceleration"])


def _record_memory_coverage(db: Session, snapshot) -> None:
    """Persist structured coverage without making playback/read paths brittle."""
    try:
        record_memory_coverage_snapshot(
            db,
            endpoint=snapshot.endpoint,
            user_hash=snapshot.user_hash,
            device_hash=snapshot.device_hash,
            content_type=snapshot.content_type,
            eligible_candidate_count=snapshot.eligible_candidate_count,
            memory_id_emitted_count=snapshot.memory_id_emitted_count,
            memory_id_missing_count=snapshot.memory_id_missing_count,
            memory_id_coverage_ratio=snapshot.memory_id_coverage_ratio,
            missing_reason_counts=snapshot.missing_reason_counts,
            provider_category_counts=snapshot.provider_category_counts,
            source_category_counts=snapshot.source_category_counts,
        )
        db.commit()
    except Exception as exc:  # noqa: BLE001
        db.rollback()
        _log.warning("ACCEL_MEMORY_ID_COVERAGE_STORE_FAILED error=%s", type(exc).__name__)


# ── Response models ─────────────────────────────────────────────────────


class SourceCandidate(BaseModel):
    """A single acceleration candidate with provenance."""
    provenance: str  # "recent_success", "inventory_match", "known_cached"
    memory_id: str | None = None
    provider_type: str
    source_key: str | None = None
    infohash: str | None = None
    file_name: str | None = None
    quality: str | None = None
    audio_flags: str | None = None
    file_size: int | None = None
    # Confidence / ranking signals
    success_count: int | None = None
    last_success_at: str | None = None
    inventory_class: str | None = None
    is_cached: bool | None = None
    # Freshness
    observed_at: str | None = None
    expires_at: str | None = None
    # Provenance discriminator + NzbDAV round-trip fields (Sprint 2 emission).
    # Populated only for USENET_NZBDAV rows; null/absent for baseline rows.
    provenance_kind: str | None = None
    candidate_id: str | None = None
    hash_key: str | None = None
    nzb_url: str | None = None


class RecentSourcesResponse(BaseModel):
    content_id: str
    season: int | None = None
    episode: int | None = None
    candidates: list[SourceCandidate]
    carryforward: list[SourceCandidate] = Field(default_factory=list)


class HashAvailabilityResponse(BaseModel):
    provider_type: str
    observations: list[dict]


class HashAvailabilityWriteRequest(BaseModel):
    provider_type: str
    observations: list[dict]  # [{infohash, is_cached}, ...]


class InventoryMatchResponse(BaseModel):
    content_id: str
    season: int | None = None
    episode: int | None = None
    matches: list[SourceCandidate]


class ScoredCandidateOut(BaseModel):
    """Startup candidate with explainable confidence score."""
    provenance: str
    memory_id: str | None = None
    provider_type: str
    source_key: str | None = None
    infohash: str | None = None
    file_name: str | None = None
    quality: str | None = None
    audio_flags: str | None = None
    file_size: int | None = None
    inventory_class: str | None = None
    is_cached: bool | None = None
    success_count: int | None = None
    last_success_at: str | None = None
    observed_at: str | None = None
    expires_at: str | None = None
    # Scoring
    score: float = 0.0
    reasons: list[str] = Field(default_factory=list)
    score_breakdown: dict[str, float] = Field(default_factory=dict)
    # Provenance discriminator + NzbDAV round-trip fields (Sprint 2 emission).
    provenance_kind: str | None = None
    candidate_id: str | None = None
    hash_key: str | None = None
    nzb_url: str | None = None


class StartupCandidatesResponse(BaseModel):
    content_id: str
    season: int | None = None
    episode: int | None = None
    candidates: list[ScoredCandidateOut]
    sources_used: list[str]
    provider_reliability: dict[str, float] = Field(default_factory=dict)
    assembled_at: str | None = None


class ResolveOutcomeRequest(BaseModel):
    content_id: str
    provider_type: str
    source_key: str
    season: int | None = None
    episode: int | None = None
    success: bool
    infohash: str | None = None
    file_name: str | None = None
    quality: str | None = None
    audio_flags: str | None = None
    file_size: int | None = None
    device_type: str | None = None
    # NzbDAV-specific round-trip payload. Only persisted when
    # provider_type == "nzbdav", success == True, and both candidate_id and
    # hash_key are non-blank.
    nzbdav_candidate_id: str | None = None
    nzbdav_hash_key: str | None = None
    nzbdav_nzb_url: str | None = None


class InventoryIngestRequest(BaseModel):
    provider_type: str
    items: list[dict]  # Raw provider response items
    replace: bool = True


# ── Helpers ─────────────────────────────────────────────────────────────

_REDACTED_FILENAME = "[Sensitive content hidden]"


def _filter_candidates(
    candidates: list[SourceCandidate],
    db: Session,
    user_id: uuid.UUID,
    policy_mode: ContentPolicyMode,
    context: AccessContext = AccessContext.ACCELERATION_INVENTORY,
) -> list[SourceCandidate]:
    """Filter/redact candidates based on content policy.

    BLOCKED_ILLEGAL: removed entirely.
    SENSITIVE_CATALOG/REVIEW_REQUIRED with HIDE decision: removed.
    Null classification: fails closed (treated as REVIEW_REQUIRED).
    SAFE: returned unchanged.
    """
    policy = get_or_create_policy(db, user_id)
    age_band = AgeBand(policy.age_band)
    ca, cd, _, _ = get_override_sets(db)

    filtered = []
    for c in candidates:
        classification = classify_text(c.file_name or "", allowlist_ids=ca, denylist_ids=cd)
        decision = decide(
            policy_mode=policy_mode,
            classification=classification,
            age_band=age_band,
            sensitive_enabled=policy.sensitive_material_enabled,
            context=context,
        )
        if decision == PolicyDecision.BLOCK:
            continue
        if decision == PolicyDecision.HIDE:
            continue
        if decision == PolicyDecision.ALLOW_REDACTED:
            c.file_name = _REDACTED_FILENAME
        filtered.append(c)
    return filtered


def _rsm_to_candidate(row, provenance: str = "recent_success") -> SourceCandidate | None:
    """Build a SourceCandidate from a ResolveSuccessMemory row.

    Read-side guardrail: if the row is tagged as USENET_NZBDAV but is
    missing candidate_id or hash_key, suppress emission entirely.
    Returns None in that case; callers must filter None.
    """
    kind = getattr(row, "provenance_kind", None)
    nzb_cand = getattr(row, "nzbdav_candidate_id", None)
    nzb_hash = getattr(row, "nzbdav_hash_key", None)
    nzb_url = getattr(row, "nzbdav_nzb_url", None)

    if kind == "USENET_NZBDAV":
        if not (nzb_cand and str(nzb_cand).strip()) or not (nzb_hash and str(nzb_hash).strip()):
            _log.warning(
                "NZBDAV_EMISSION_SUPPRESSED reason=missing_fields row_id=%s user=%s content=%s",
                getattr(row, "id", None),
                getattr(row, "user_id", None),
                getattr(row, "content_id", None),
            )
            return None

    memory_id = str(row.id) if getattr(row, "id", None) else None
    return SourceCandidate(
        provenance=provenance,
        memory_id=memory_id,
        provider_type=row.provider_type,
        source_key=client_safe_source_key(row.source_key, memory_id=memory_id),
        infohash=row.infohash,
        file_name=row.file_name,
        quality=row.quality,
        audio_flags=row.audio_flags,
        file_size=row.file_size,
        success_count=row.success_count,
        last_success_at=row.last_success_at.isoformat() if row.last_success_at else None,
        provenance_kind=kind if kind == "USENET_NZBDAV" else None,
        candidate_id=nzb_cand if kind == "USENET_NZBDAV" else None,
        hash_key=nzb_hash if kind == "USENET_NZBDAV" else None,
        nzb_url=nzb_url if kind == "USENET_NZBDAV" else None,
    )


def _inv_to_candidate(row) -> SourceCandidate:
    return SourceCandidate(
        provenance="inventory_match",
        provider_type=row.provider_type,
        infohash=row.infohash,
        file_name=row.file_name,
        quality=row.quality,
        file_size=row.file_size,
        inventory_class=row.inventory_class,
        observed_at=row.last_seen_at.isoformat() if row.last_seen_at else None,
        expires_at=row.expires_at.isoformat() if row.expires_at else None,
    )


def _attach_memory_refs_to_candidates(
    db: Session,
    *,
    user_id: uuid.UUID,
    content_id: str,
    season: int | None,
    episode: int | None,
    candidates: list[SourceCandidate],
) -> list[SourceCandidate]:
    for candidate in candidates:
        if candidate.memory_id or not candidate.infohash:
            continue
        ref = rsm_svc.get_best_reference_for_candidate(
            db,
            user_id=user_id,
            content_id=content_id,
            season=season,
            episode=episode,
            provider_type=candidate.provider_type,
            infohash=candidate.infohash,
        )
        if ref is None:
            continue
        candidate.memory_id = str(ref.id)
        if not candidate.source_key:
            candidate.source_key = client_safe_source_key(ref.source_key, memory_id=candidate.memory_id)
        candidate.success_count = candidate.success_count or ref.success_count
        candidate.last_success_at = (
            candidate.last_success_at
            or (ref.last_success_at.isoformat() if ref.last_success_at else None)
        )
    return candidates


# ── 1. Recent successful sources ───────────────────────────────────────


@router.get("/sources", response_model=RecentSourcesResponse)
def get_recent_sources(
    request: Request,
    content_id: str,
    season: int | None = None,
    episode: int | None = None,
    provider_type: str | None = None,
    limit: int = Query(default=10, ge=1, le=50),
    user_id: str = Depends(require_account_access),
    db: Session = Depends(get_db),
) -> RecentSourcesResponse:
    """Recent and best successful sources for exact content.

    If the content is episodic and no exact matches exist, includes
    carryforward candidates from adjacent episodes in the same season.
    """
    uid = uuid.UUID(user_id)

    candidates_raw = rsm_svc.get_recent_for_content(
        db, user_id=uid, content_id=content_id,
        season=season, episode=episode,
        provider_type=provider_type, limit=limit,
    )
    candidates = [c for c in (_rsm_to_candidate(r) for r in candidates_raw) if c is not None]

    # Carryforward for episodic content when no exact matches
    carryforward = []
    if not candidates and season is not None and episode is not None:
        cf_raw = rsm_svc.get_series_carryforward(
            db, user_id=uid, content_id=content_id,
            season=season, episode=episode,
            provider_type=provider_type,
        )
        carryforward = [
            c for c in (_rsm_to_candidate(r, "carryforward") for r in cf_raw) if c is not None
        ]

    # Apply content policy filtering
    pm = resolve_channel(request)
    candidates = _filter_candidates(candidates, db, uid, pm, AccessContext.HISTORY_DERIVED)
    carryforward = _filter_candidates(carryforward, db, uid, pm, AccessContext.HISTORY_DERIVED)
    all_emitted = [*candidates, *carryforward]
    coverage = log_memory_id_coverage(
        _log,
        endpoint="sources",
        candidates=all_emitted,
        user_id=uid,
        installation_id=request.headers.get("X-Torve-Installation-Id"),
        content_type=content_type_for_request(season, episode),
    )
    _record_memory_coverage(db, coverage)

    _log.info(
        "ACCEL_SOURCES user=%s content=%s s=%s e=%s exact=%d carryforward=%d",
        uid, content_id, season, episode, len(candidates), len(carryforward),
    )

    return RecentSourcesResponse(
        content_id=content_id,
        season=season,
        episode=episode,
        candidates=candidates,
        carryforward=carryforward,
    )


# ── 2. Hash availability ───────────────────────────────────────────────


@router.get("/hashes", response_model=HashAvailabilityResponse)
def get_hash_availability(
    provider_type: str,
    infohashes: str = Query(description="Comma-separated infohashes"),
    user_id: str = Depends(require_account_access),
    db: Session = Depends(get_db),
) -> HashAvailabilityResponse:
    """Check known cached state for a list of infohashes."""
    uid = uuid.UUID(user_id)
    hashes = [h.strip() for h in infohashes.split(",") if h.strip()]

    observations = has_svc.check_hashes(
        db, user_id=uid, provider_type=provider_type, infohashes=hashes,
    )

    return HashAvailabilityResponse(
        provider_type=provider_type,
        observations=observations,
    )


@router.post("/hashes", status_code=201)
def record_hash_availability(
    body: HashAvailabilityWriteRequest,
    user_id: str = Depends(require_account_access),
    db: Session = Depends(get_db),
) -> dict:
    """Record hash availability observations from the client."""
    uid = uuid.UUID(user_id)
    count = has_svc.record_batch(
        db, user_id=uid, provider_type=body.provider_type,
        observations=body.observations,
    )
    db.commit()
    _log.info("ACCEL_HASH_WRITE user=%s provider=%s count=%d", uid, body.provider_type, count)
    return {"recorded": count}


# ── 3. Provider inventory matches ──────────────────────────────────────


@router.get("/inventory", response_model=InventoryMatchResponse)
def get_inventory_matches(
    request: Request,
    content_id: str,
    title: str | None = None,
    year: int | None = None,
    season: int | None = None,
    episode: int | None = None,
    infohash: str | None = None,
    provider_type: str | None = None,
    user_id: str = Depends(require_account_access),
    db: Session = Depends(get_db),
) -> InventoryMatchResponse:
    """Match content against provider inventory snapshots.

    Matches by infohash first (most precise), then falls back to
    normalized title matching. Returns empty if no snapshots exist.
    """
    uid = uuid.UUID(user_id)
    matches = []

    # Priority 1: infohash match
    if infohash:
        rows = inv_svc.match_by_infohash(
            db, user_id=uid, infohash=infohash, provider_type=provider_type,
        )
        matches.extend(_inv_to_candidate(r) for r in rows)

    # Priority 2: title match (if no hash match or additional results wanted)
    if title and not matches:
        rows = inv_svc.match_by_title(
            db, user_id=uid, title=title, year=year,
            season=season, episode=episode, provider_type=provider_type,
        )
        matches.extend(_inv_to_candidate(r) for r in rows)
    matches = _attach_memory_refs_to_candidates(
        db,
        user_id=uid,
        content_id=content_id,
        season=season,
        episode=episode,
        candidates=matches,
    )

    # Apply content policy filtering
    pm = resolve_channel(request)
    matches = _filter_candidates(matches, db, uid, pm, AccessContext.ACCELERATION_INVENTORY)
    coverage = log_memory_id_coverage(
        _log,
        endpoint="inventory",
        candidates=matches,
        user_id=uid,
        installation_id=request.headers.get("X-Torve-Installation-Id"),
        content_type=content_type_for_request(season, episode),
    )
    _record_memory_coverage(db, coverage)

    _log.info(
        "ACCEL_INVENTORY user=%s content=%s matches=%d", uid, content_id, len(matches),
    )

    return InventoryMatchResponse(
        content_id=content_id,
        season=season,
        episode=episode,
        matches=matches,
    )


@router.post("/inventory/ingest", status_code=201)
def ingest_inventory(
    body: InventoryIngestRequest,
    user_id: str = Depends(require_account_access),
    db: Session = Depends(get_db),
) -> dict:
    """Ingest provider inventory snapshot from client-side sync."""
    uid = uuid.UUID(user_id)

    adapter = inv_svc.get_adapter(body.provider_type)
    if not adapter:
        _log.info("ACCEL_INVENTORY_UNSUPPORTED user=%s provider=%s", uid, body.provider_type)
        return {"upserted": 0, "expired": 0, "supported": False}

    items = adapter(body.items)
    result = inv_svc.ingest_snapshot(
        db, user_id=uid, provider_type=body.provider_type,
        items=items, replace=body.replace,
        classify_fn=lambda text: classify_text(text).value,
    )
    db.commit()
    _log.info(
        "ACCEL_INVENTORY_INGEST user=%s provider=%s upserted=%d expired=%d",
        uid, body.provider_type, result["upserted"], result["expired"],
    )
    return {**result, "supported": True}


# ── 4. Startup candidates ──────────────────────────────────────────────


@router.get("/startup", response_model=StartupCandidatesResponse)
def get_startup_candidates(
    request: Request,
    content_id: str,
    title: str | None = None,
    year: int | None = None,
    season: int | None = None,
    episode: int | None = None,
    infohash: str | None = None,
    provider_type: str | None = None,
    user_id: str = Depends(require_account_access),
    db: Session = Depends(get_db),
) -> StartupCandidatesResponse:
    """Scored fast-path candidate set for immediate playback.

    Assembles, scores, and ranks candidates from:
    1. Recent successful sources (exact match + carryforward)
    2. Provider inventory matches (infohash + title)
    3. Known-cached hash observations

    Each candidate includes an explainable confidence score and reason
    codes. Returns an empty candidate list if no acceleration data exists.
    The client should fall back to the full resolver in that case.
    """
    uid = uuid.UUID(user_id)

    result = startup_assembly.assemble_startup_candidates(
        db, user_id=uid, content_id=content_id,
        season=season, episode=episode,
        title=title, year=year, infohash=infohash,
        provider_type=provider_type,
    )

    # Convert dataclass candidates to response model, then apply policy filtering
    policy = get_or_create_policy(db, uid)
    policy_mode = resolve_channel(request)
    age_band_val = AgeBand(policy.age_band)
    ca, cd, _, _ = get_override_sets(db)

    candidates_out = []
    for c in result.candidates:
        classification = classify_text(c.file_name or "", allowlist_ids=ca, denylist_ids=cd)
        decision = decide(
            policy_mode=policy_mode, classification=classification,
            age_band=age_band_val, sensitive_enabled=policy.sensitive_material_enabled,
            context=AccessContext.ACCELERATION_INVENTORY,
        )
        if decision in (PolicyDecision.BLOCK, PolicyDecision.HIDE):
            continue
        fn = c.file_name
        if decision == PolicyDecision.ALLOW_REDACTED:
            fn = _REDACTED_FILENAME
        candidates_out.append(ScoredCandidateOut(
            provenance=c.provenance,
            memory_id=getattr(c, "memory_id", None),
            provider_type=c.provider_type,
            source_key=c.source_key,
            infohash=c.infohash,
            file_name=fn,
            quality=c.quality,
            audio_flags=c.audio_flags,
            file_size=c.file_size,
            inventory_class=c.inventory_class,
            is_cached=c.is_cached,
            success_count=c.success_count,
            last_success_at=c.last_success_at,
            observed_at=c.observed_at,
            expires_at=c.expires_at,
            score=c.score,
            reasons=c.reasons,
            score_breakdown=c.score_breakdown,
            provenance_kind=getattr(c, "provenance_kind", None),
            candidate_id=getattr(c, "candidate_id", None),
            hash_key=getattr(c, "hash_key", None),
            nzb_url=getattr(c, "nzb_url", None),
        ))
    coverage = log_memory_id_coverage(
        _log,
        endpoint="startup",
        candidates=candidates_out,
        user_id=uid,
        installation_id=request.headers.get("X-Torve-Installation-Id"),
        content_type=content_type_for_request(season, episode),
    )
    _record_memory_coverage(db, coverage)

    return StartupCandidatesResponse(
        content_id=result.content_id,
        season=result.season,
        episode=result.episode,
        candidates=candidates_out,
        sources_used=result.sources_used,
        provider_reliability=result.provider_reliability,
        assembled_at=result.assembled_at,
    )


# ── 5. Resolve outcome write-back ──────────────────────────────────────


@router.post("/outcome", status_code=201)
def record_resolve_outcome(
    body: ResolveOutcomeRequest,
    user_id: str = Depends(require_account_access),
    db: Session = Depends(get_db),
) -> dict:
    """Record success or failure of a source resolution attempt."""
    uid = uuid.UUID(user_id)

    if body.success:
        # Write-side guardrail for NzbDAV: only persist NzbDAV-specific
        # payload when both candidate_id and hash_key are non-blank. If
        # either is missing, persist the baseline row (provider_type +
        # source_key) normally and log a warning — don't 422.
        prov_kind: str | None = None
        nzb_cand = (body.nzbdav_candidate_id or "").strip() or None
        nzb_hash = (body.nzbdav_hash_key or "").strip() or None
        nzb_url = (body.nzbdav_nzb_url or "").strip() or None

        if body.provider_type == "nzbdav":
            if nzb_cand and nzb_hash:
                prov_kind = "USENET_NZBDAV"
            else:
                _log.warning(
                    "NZBDAV_OUTCOME_MALFORMED user=%s content=%s has_candidate_id=%s has_hash_key=%s — persisting baseline row only",
                    uid, body.content_id, bool(nzb_cand), bool(nzb_hash),
                )
                nzb_cand = None
                nzb_hash = None
                nzb_url = None

        row = rsm_svc.record_success(
            db, user_id=uid,
            content_id=body.content_id,
            provider_type=body.provider_type,
            source_key=body.source_key,
            season=body.season,
            episode=body.episode,
            infohash=body.infohash,
            file_name=body.file_name,
            quality=body.quality,
            audio_flags=body.audio_flags,
            file_size=body.file_size,
            device_type=body.device_type,
            provenance_kind=prov_kind,
            nzbdav_candidate_id=nzb_cand if prov_kind else None,
            nzbdav_hash_key=nzb_hash if prov_kind else None,
            nzbdav_nzb_url=nzb_url if prov_kind else None,
        )
        db.commit()
        return {"recorded": True, "success_count": row.success_count}
    else:
        row = rsm_svc.record_failure(
            db, user_id=uid,
            content_id=body.content_id,
            provider_type=body.provider_type,
            source_key=body.source_key,
            season=body.season,
            episode=body.episode,
        )
        db.commit()
        return {"recorded": row is not None, "was_known": row is not None}
