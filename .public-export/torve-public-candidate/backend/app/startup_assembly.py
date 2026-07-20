"""
Startup candidate assembler.

Merges acceleration primitives (resolve memory, inventory snapshots,
hash availability) into an ordered fast-path candidate list with
explainable confidence scores and reason codes.

This is a pre-resolver accelerator, not a resolver replacement. When
it returns candidates, the client can attempt them first. When it
returns nothing, the client falls back to the full resolver unchanged.

Scoring model:
  Each candidate accumulates points from independent signals. The total
  score is a simple weighted sum. Weights are chosen to be explainable:
  a known-cached exact-content recent success scores highest; a title-
  only inventory match with no cache observation scores lowest.

  All weights are constants in this module for easy auditing.
"""
import logging
import math
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone

from sqlalchemy.orm import Session

from app import hash_availability_service as has_svc
from app import inventory_service as inv_svc
from app import resolve_memory_service as rsm_svc
from app.acceleration_coverage import client_safe_source_key

_log = logging.getLogger(__name__)


# ── Scoring weights ─────────────────────────────────────────────────────
# Each weight is the max contribution of that signal to the total score.
# Total theoretical max ≈ 100. Practical scores cluster 10-80.

W_EXACT_CONTENT_MATCH = 20   # Content ID + season + episode match
W_EPISODE_CARRYFORWARD = 8   # Same series, adjacent episode
W_SUCCESS_FREQUENCY = 15     # Log-scaled success count (capped)
W_SUCCESS_RECENCY = 15       # How recently it last worked
W_INVENTORY_DIRECT = 12      # Found in provider cloud inventory
W_KNOWN_CACHED = 20          # Hash observed as cached on provider
W_PROVIDER_RELIABILITY = 10  # Provider success rate for this user

# Recency half-life: a success 3 days ago gets half the recency score
RECENCY_HALF_LIFE_DAYS = 3.0

# Maximum success_count for scoring (diminishing returns)
MAX_SUCCESS_COUNT_FOR_SCORE = 20


# ── Reason codes ────────────────────────────────────────────────────────

REASON_EXACT_MATCH = "exact_content_match"
REASON_CARRYFORWARD = "episode_carryforward"
REASON_HIGH_FREQUENCY = "high_success_frequency"
REASON_RECENT_SUCCESS = "recent_success"
REASON_INVENTORY_HIT = "inventory_match"
REASON_KNOWN_CACHED = "known_cached"
REASON_PROVIDER_RELIABLE = "provider_reliable"


# ── Scored candidate ────────────────────────────────────────────────────


@dataclass
class ScoredCandidate:
    """A startup candidate with an explainable confidence score."""
    # Identity
    provider_type: str
    memory_id: str | None = None
    source_key: str | None = None
    infohash: str | None = None
    # Metadata
    file_name: str | None = None
    quality: str | None = None
    audio_flags: str | None = None
    file_size: int | None = None
    inventory_class: str | None = None
    # Provenance
    provenance: str = "recent_success"
    is_cached: bool | None = None
    # Scoring
    score: float = 0.0
    reasons: list[str] = field(default_factory=list)
    score_breakdown: dict[str, float] = field(default_factory=dict)
    # Freshness
    success_count: int | None = None
    last_success_at: str | None = None
    observed_at: str | None = None
    expires_at: str | None = None
    # Provenance discriminator + NzbDAV round-trip fields. Populated only
    # for rows emitted with provenance_kind=USENET_NZBDAV; the scoring
    # logic treats them identically to recent_success rows otherwise.
    provenance_kind: str | None = None
    candidate_id: str | None = None
    hash_key: str | None = None
    nzb_url: str | None = None


@dataclass
class StartupAssemblyResult:
    """Output of the startup assembler."""
    content_id: str
    season: int | None = None
    episode: int | None = None
    candidates: list[ScoredCandidate] = field(default_factory=list)
    sources_used: list[str] = field(default_factory=list)
    provider_reliability: dict[str, float] = field(default_factory=dict)
    assembled_at: str = ""


# ── Row conversion helper ───────────────────────────────────────────────


def _rsm_row_to_scored(row, *, provenance: str) -> ScoredCandidate | None:
    """Convert a ResolveSuccessMemory row into a ScoredCandidate.

    Read-side guardrail: if the row is tagged USENET_NZBDAV but is missing
    candidate_id or hash_key, suppress emission entirely (return None).
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
    return ScoredCandidate(
        memory_id=memory_id,
        provider_type=row.provider_type,
        source_key=client_safe_source_key(row.source_key, memory_id=memory_id),
        infohash=row.infohash,
        file_name=row.file_name,
        quality=row.quality,
        audio_flags=row.audio_flags,
        file_size=row.file_size,
        provenance=provenance,
        success_count=row.success_count,
        last_success_at=row.last_success_at.isoformat() if row.last_success_at else None,
        provenance_kind=kind if kind == "USENET_NZBDAV" else None,
        candidate_id=nzb_cand if kind == "USENET_NZBDAV" else None,
        hash_key=nzb_hash if kind == "USENET_NZBDAV" else None,
        nzb_url=nzb_url if kind == "USENET_NZBDAV" else None,
    )


# ── Assembly ────────────────────────────────────────────────────────────


def assemble_startup_candidates(
    db: Session,
    *,
    user_id: uuid.UUID,
    content_id: str,
    season: int | None = None,
    episode: int | None = None,
    title: str | None = None,
    year: int | None = None,
    infohash: str | None = None,
    provider_type: str | None = None,
    max_candidates: int = 10,
) -> StartupAssemblyResult:
    """Assemble and score startup candidates from all acceleration sources.

    Flow:
    1. Load provider reliability (user's recent provider success rates)
    2. Load recent successful sources (exact + carryforward)
    3. Load provider inventory matches
    4. Enrich with hash availability observations
    5. Score each candidate
    6. Deduplicate and sort by score descending
    7. Return top N
    """
    now = datetime.now(timezone.utc)
    sources_used: set[str] = set()

    # Step 1: Provider reliability for this user
    provider_reliability = _compute_provider_reliability(db, user_id)

    # Step 2: Recent successful sources
    candidates: list[ScoredCandidate] = []

    recent = rsm_svc.get_recent_for_content(
        db, user_id=user_id, content_id=content_id,
        season=season, episode=episode,
        provider_type=provider_type, limit=max_candidates,
    )
    for r in recent:
        built = _rsm_row_to_scored(r, provenance="recent_success")
        if built is None:
            continue
        candidates.append(built)
        sources_used.add("recent_success")

    # Carryforward for episodic content when no exact matches
    if not recent and season is not None and episode is not None:
        cf = rsm_svc.get_series_carryforward(
            db, user_id=user_id, content_id=content_id,
            season=season, episode=episode,
            provider_type=provider_type, lookback_episodes=3,
        )
        for r in cf[:5]:
            built = _rsm_row_to_scored(r, provenance="carryforward")
            if built is None:
                continue
            candidates.append(built)
            sources_used.add("carryforward")

    # Step 3: Inventory matches
    inv_rows = []
    if infohash:
        inv_rows = inv_svc.match_by_infohash(
            db, user_id=user_id, infohash=infohash, provider_type=provider_type,
        )
    if not inv_rows and title:
        inv_rows = inv_svc.match_by_title(
            db, user_id=user_id, title=title, year=year,
            season=season, episode=episode, provider_type=provider_type,
        )
    for inv in inv_rows:
        memory_ref = rsm_svc.get_best_reference_for_candidate(
            db,
            user_id=user_id,
            content_id=content_id,
            season=season,
            episode=episode,
            provider_type=inv.provider_type,
            infohash=inv.infohash,
        )
        memory_id = str(memory_ref.id) if memory_ref is not None else None
        candidates.append(ScoredCandidate(
            provider_type=inv.provider_type,
            memory_id=memory_id,
            source_key=(
                client_safe_source_key(memory_ref.source_key, memory_id=memory_id)
                if memory_ref is not None
                else None
            ),
            infohash=inv.infohash,
            file_name=inv.file_name,
            quality=inv.quality,
            file_size=inv.file_size,
            inventory_class=inv.inventory_class,
            provenance="inventory_match",
            success_count=memory_ref.success_count if memory_ref is not None else None,
            last_success_at=(
                memory_ref.last_success_at.isoformat()
                if memory_ref is not None and memory_ref.last_success_at
                else None
            ),
            observed_at=inv.last_seen_at.isoformat() if inv.last_seen_at else None,
            expires_at=inv.expires_at.isoformat() if inv.expires_at else None,
        ))
        sources_used.add("inventory_match")

    # Step 4: Enrich with hash availability
    candidate_hashes = {c.infohash for c in candidates if c.infohash}
    cached_by_provider: dict[str, set[str]] = {}
    if candidate_hashes:
        providers_seen = {c.provider_type for c in candidates}
        for pt in providers_seen:
            cached_by_provider[pt] = set(has_svc.get_cached_hashes(
                db, user_id=user_id, provider_type=pt,
            ))

    for c in candidates:
        if c.infohash and c.infohash in cached_by_provider.get(c.provider_type, set()):
            c.is_cached = True
            sources_used.add("known_cached")

    # Step 5: Score each candidate
    is_exact = season is not None or episode is not None  # episodic request
    for c in candidates:
        _score_candidate(c, now, provider_reliability, is_exact_episode=is_exact)

    # Step 6: Deduplicate and sort
    candidates = _deduplicate(candidates)
    candidates.sort(key=lambda c: c.score, reverse=True)
    candidates = candidates[:max_candidates]

    _log.info(
        "STARTUP_ASSEMBLY user=%s content=%s s=%s e=%s candidates=%d sources=%s top_score=%.1f",
        user_id, content_id, season, episode, len(candidates),
        sorted(sources_used),
        candidates[0].score if candidates else 0.0,
    )

    return StartupAssemblyResult(
        content_id=content_id,
        season=season,
        episode=episode,
        candidates=candidates,
        sources_used=sorted(sources_used),
        provider_reliability=provider_reliability,
        assembled_at=now.isoformat(),
    )


# ── Scoring ─────────────────────────────────────────────────────────────


def _score_candidate(
    c: ScoredCandidate,
    now: datetime,
    provider_reliability: dict[str, float],
    is_exact_episode: bool,
) -> None:
    """Score a single candidate. Mutates c.score, c.reasons, c.score_breakdown."""
    score = 0.0
    breakdown: dict[str, float] = {}
    reasons: list[str] = []

    # Signal 1: Exact content match vs carryforward
    if c.provenance == "recent_success":
        s = W_EXACT_CONTENT_MATCH
        score += s
        breakdown["exact_content_match"] = s
        reasons.append(REASON_EXACT_MATCH)
    elif c.provenance == "carryforward":
        s = W_EPISODE_CARRYFORWARD
        score += s
        breakdown["episode_carryforward"] = s
        reasons.append(REASON_CARRYFORWARD)

    # Signal 2: Success frequency (log-scaled, diminishing returns)
    if c.success_count and c.success_count > 0:
        capped = min(c.success_count, MAX_SUCCESS_COUNT_FOR_SCORE)
        # log2(1)=0, log2(2)=1, log2(20)≈4.3. Normalize to 0..1 range.
        freq_ratio = math.log2(1 + capped) / math.log2(1 + MAX_SUCCESS_COUNT_FOR_SCORE)
        s = round(W_SUCCESS_FREQUENCY * freq_ratio, 2)
        score += s
        breakdown["success_frequency"] = s
        if c.success_count >= 3:
            reasons.append(REASON_HIGH_FREQUENCY)

    # Signal 3: Success recency (exponential decay)
    if c.last_success_at:
        try:
            last = datetime.fromisoformat(c.last_success_at)
            age_days = max(0.0, (now - last).total_seconds() / 86400)
            decay = 0.5 ** (age_days / RECENCY_HALF_LIFE_DAYS)
            s = round(W_SUCCESS_RECENCY * decay, 2)
            score += s
            breakdown["success_recency"] = s
            if age_days < 1.0:
                reasons.append(REASON_RECENT_SUCCESS)
        except (ValueError, TypeError):
            pass

    # Signal 4: Inventory match
    if c.provenance == "inventory_match" or c.inventory_class:
        s = W_INVENTORY_DIRECT
        score += s
        breakdown["inventory_direct"] = s
        reasons.append(REASON_INVENTORY_HIT)

    # Signal 5: Known cached
    if c.is_cached:
        s = W_KNOWN_CACHED
        score += s
        breakdown["known_cached"] = s
        reasons.append(REASON_KNOWN_CACHED)

    # Signal 6: Provider reliability
    reliability = provider_reliability.get(c.provider_type, 0.0)
    if reliability > 0:
        s = round(W_PROVIDER_RELIABILITY * reliability, 2)
        score += s
        breakdown["provider_reliability"] = s
        if reliability >= 0.5:
            reasons.append(REASON_PROVIDER_RELIABLE)

    c.score = round(score, 2)
    c.score_breakdown = breakdown
    c.reasons = reasons


# ── Provider reliability ────────────────────────────────────────────────


def _compute_provider_reliability(
    db: Session,
    user_id: uuid.UUID,
    since_days: int = 14,
) -> dict[str, float]:
    """Compute per-provider reliability score (0..1) from recent success history.

    Score = normalized ratio of this provider's successes vs the user's
    most-used provider. The top provider gets 1.0, others proportionally less.
    """
    provider_stats = rsm_svc.get_provider_success_set(
        db, user_id=user_id, since_days=since_days,
    )
    if not provider_stats:
        return {}

    max_successes = max(p["total_successes"] for p in provider_stats)
    if max_successes <= 0:
        return {}

    return {
        p["provider_type"]: round(p["total_successes"] / max_successes, 3)
        for p in provider_stats
    }


# ── Deduplication ───────────────────────────────────────────────────────


def _deduplicate(candidates: list[ScoredCandidate]) -> list[ScoredCandidate]:
    """Keep highest-scored candidate per (provider_type, dedup_key).

    Dedup key = infohash if present, else source_key. Merges is_cached
    and reasons from lower-priority duplicates.
    """
    seen: dict[tuple, ScoredCandidate] = {}

    for c in candidates:
        dedup_id = c.infohash or c.source_key
        if not dedup_id:
            # No dedup key — keep as unique candidate
            seen[("_noid_", id(c))] = c
            continue
        key = (c.provider_type, dedup_id)
        if key in seen:
            existing = seen[key]
            if c.score > existing.score:
                # Merge attributes from lower-scored
                if existing.memory_id and not c.memory_id:
                    c.memory_id = existing.memory_id
                if existing.source_key and not c.source_key:
                    c.source_key = existing.source_key
                if existing.success_count and not c.success_count:
                    c.success_count = existing.success_count
                if existing.last_success_at and not c.last_success_at:
                    c.last_success_at = existing.last_success_at
                if existing.is_cached:
                    c.is_cached = True
                if existing.inventory_class and not c.inventory_class:
                    c.inventory_class = existing.inventory_class
                seen[key] = c
            else:
                if c.memory_id and not existing.memory_id:
                    existing.memory_id = c.memory_id
                if c.source_key and not existing.source_key:
                    existing.source_key = c.source_key
                if c.success_count and not existing.success_count:
                    existing.success_count = c.success_count
                if c.last_success_at and not existing.last_success_at:
                    existing.last_success_at = c.last_success_at
                if c.is_cached:
                    existing.is_cached = True
                if c.inventory_class and not existing.inventory_class:
                    existing.inventory_class = c.inventory_class
        else:
            seen[key] = c

    return list(seen.values())
