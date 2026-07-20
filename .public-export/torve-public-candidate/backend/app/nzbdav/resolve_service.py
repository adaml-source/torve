"""Normalized resolve service for a single candidate.

Given one UsenetCandidate, returns the current simplified state (ready,
warming, failed). On `ready` the response carries a ResolvedStream whose
URL is a Torve-side handoff URL — NEVER the raw upstream URL.

Flow:
1. Check dead-release cache -> failed fast.
2. Check warm-success cache -> ready + mint handoff token.
3. Check existing WarmJob -> map to simplified state.
4. Otherwise kick a new warm via NzbdavWarmService.
"""
from __future__ import annotations

import logging
import time
import uuid
from dataclasses import dataclass

from sqlalchemy.orm import Session

from app.config import settings
from app.models import WarmJob
from app.nzbdav import state as st
from app.nzbdav import telemetry
from app.nzbdav.account_store import account_store
from app.nzbdav.failures import (
    DEAD_RELEASE_CODES,
    FailureCode,
    UpstreamError,
    default_fallback_suggestions,
)
from app.nzbdav.handoff import mint as mint_handoff
from app.nzbdav.release_cache import release_cache
from app.nzbdav.warm_service import (
    WarmCandidateInput,
    warm_service,
)

_log = logging.getLogger(__name__)


@dataclass
class ResolveResult:
    simplified_state: str  # ready | warming | failed
    job_id: str | None = None
    failure_code: str | None = None
    fallback_suggestions: list[str] | None = None
    # Only populated on ready
    stream_url: str | None = None
    is_direct: bool | None = None
    supports_range: bool | None = None
    stream_id: str | None = None


class NzbdavResolveService:
    async def resolve(
        self,
        db: Session,
        *,
        user_id: uuid.UUID,
        content_id: str,
        candidate: WarmCandidateInput,
        device_id: uuid.UUID | None = None,
    ) -> ResolveResult:
        start = time.monotonic()
        result = await self._resolve_inner(
            db,
            user_id=user_id,
            content_id=content_id,
            candidate=candidate,
            device_id=device_id,
        )
        latency_ms = int((time.monotonic() - start) * 1000)
        telemetry.resolve(
            latency_ms=latency_ms,
            user_id=user_id,
            outcome=(result.failure_code or "ok"),
            simplified_state=result.simplified_state,
        )
        return result

    async def _resolve_inner(
        self,
        db: Session,
        *,
        user_id: uuid.UUID,
        content_id: str,
        candidate: WarmCandidateInput,
        device_id: uuid.UUID | None = None,
    ) -> ResolveResult:
        # 1. Dead-release cache
        dead = release_cache.get_dead_release(candidate.hash_key)
        if dead is not None:
            telemetry.cache_hit(
                cache="dead_release", hash_key=candidate.hash_key
            )
            code = FailureCode(dead)
            return ResolveResult(
                simplified_state=st.SIMPLIFIED_FAILED,
                failure_code=code.value,
                fallback_suggestions=default_fallback_suggestions(code),
            )

        # 2. Warm-success cache — fast-path to ready
        cached = release_cache.get_warm_success(candidate.hash_key)
        if cached is not None:
            telemetry.cache_hit(
                cache="warm_success", hash_key=candidate.hash_key
            )
            return _build_ready_result(
                user_id=user_id,
                device_id=device_id,
                content_id=content_id,
                cached=cached,
            )

        # 3. Check existing WarmJob
        existing = (
            db.query(WarmJob)
            .filter(
                WarmJob.user_id == user_id,
                WarmJob.content_id == content_id,
                WarmJob.hash_key == candidate.hash_key,
            )
            .order_by(WarmJob.created_at.desc())
            .first()
        )
        if existing is not None:
            simplified = st.to_simplified(existing.state)
            if simplified == st.SIMPLIFIED_READY:
                # Success state persisted but cache evicted — fall through
                # to starting a new warm if we don't have a cached URL.
                cached = release_cache.get_warm_success(candidate.hash_key)
                if cached is not None:
                    return _build_ready_result(
                        user_id=user_id,
                        device_id=device_id,
                        content_id=content_id,
                        cached=cached,
                    )
            elif simplified == st.SIMPLIFIED_WARMING:
                return ResolveResult(
                    simplified_state=st.SIMPLIFIED_WARMING,
                    job_id=str(existing.job_id),
                )
            elif simplified == st.SIMPLIFIED_FAILED:
                code_str = existing.failure_code or FailureCode.UNKNOWN_UPSTREAM_ERROR.value
                try:
                    code = FailureCode(code_str)
                except ValueError:
                    code = FailureCode.UNKNOWN_UPSTREAM_ERROR
                # Only return the persisted failed state if it's recent.
                # Otherwise kick off a new warm below.
                if code.value in DEAD_RELEASE_CODES:
                    return ResolveResult(
                        simplified_state=st.SIMPLIFIED_FAILED,
                        failure_code=code.value,
                        fallback_suggestions=default_fallback_suggestions(code),
                        job_id=str(existing.job_id),
                    )

        # 4. No known state — kick a fresh warm (which may itself fail fast).
        warm_results = await warm_service.start_warm(
            db,
            user_id=user_id,
            content_id=content_id,
            candidates=[candidate],
            top_n=1,
        )
        if not warm_results:
            return ResolveResult(
                simplified_state=st.SIMPLIFIED_FAILED,
                failure_code=FailureCode.UNKNOWN_UPSTREAM_ERROR.value,
                fallback_suggestions=default_fallback_suggestions(
                    FailureCode.UNKNOWN_UPSTREAM_ERROR
                ),
            )
        r = warm_results[0]
        if r.simplified_state == st.SIMPLIFIED_READY:
            cached = release_cache.get_warm_success(candidate.hash_key)
            if cached is not None:
                return _build_ready_result(
                    user_id=user_id,
                    device_id=device_id,
                    content_id=content_id,
                    cached=cached,
                )
            # ready with no cache entry — very rare, treat as warming.
            return ResolveResult(
                simplified_state=st.SIMPLIFIED_WARMING, job_id=r.job_id,
            )
        if r.simplified_state == st.SIMPLIFIED_FAILED:
            code_str = r.failure_code or FailureCode.UNKNOWN_UPSTREAM_ERROR.value
            try:
                code = FailureCode(code_str)
            except ValueError:
                code = FailureCode.UNKNOWN_UPSTREAM_ERROR
            return ResolveResult(
                simplified_state=st.SIMPLIFIED_FAILED,
                failure_code=code.value,
                fallback_suggestions=default_fallback_suggestions(code),
                job_id=r.job_id,
            )
        return ResolveResult(
            simplified_state=st.SIMPLIFIED_WARMING, job_id=r.job_id,
        )


def _build_ready_result(
    *,
    user_id: uuid.UUID,
    device_id: uuid.UUID | None,
    content_id: str,
    cached: dict,
) -> ResolveResult:
    stream_id = cached.get("stream_id") or str(uuid.uuid4())
    token = mint_handoff(
        user_id=str(user_id),
        stream_id=stream_id,
        device_id=str(device_id) if device_id else None,
        content_id=content_id,
    )
    # The public handoff URL is always /resolver/usenet/handoff/{token}.
    # The settings.APP_PUBLIC_API_URL builds an absolute form when
    # returned to clients.
    base = (settings.APP_PUBLIC_API_URL or "").rstrip("/")
    url = f"{base}/resolver/usenet/handoff/{token}"
    return ResolveResult(
        simplified_state=st.SIMPLIFIED_READY,
        stream_url=url,
        stream_id=stream_id,
        is_direct=False,
        supports_range=True,
    )


resolve_service = NzbdavResolveService()
