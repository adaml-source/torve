"""Speculative warm jobs for NzbDAV streams.

Given a ranked list of UsenetCandidate, kick off background warm work for
up to top_n candidates. Dedupes on (user_id, content_id, hash_key) against
in-flight WarmJob rows and the warm-success cache.

Concurrency is capped per user (default 4) via an in-process counter.
Background work uses asyncio.create_task so it runs inside the FastAPI
event loop.
"""
from __future__ import annotations

import asyncio
import logging
import threading
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Callable

from sqlalchemy.orm import Session

from app.config import settings
from app.database import SessionLocal
from app.models import NzbdavConfig, WarmJob
from app.nzbdav import state as st
from app.nzbdav import telemetry
from app.nzbdav.account_store import account_store
from app.nzbdav.client import NzbdavClient
from app.nzbdav.failures import (
    DEAD_RELEASE_CODES,
    FailureCode,
    UpstreamError,
    default_fallback_suggestions,
)
from app.nzbdav.release_cache import release_cache

_log = logging.getLogger(__name__)

# Hard ceiling on how many candidates a single warm call can enqueue.
HARD_CAP_TOP_N = 10
DEFAULT_TOP_N = 5
# Synchronously-kicked (non-blocking) top-of-list budget
DEFAULT_SYNC_PREWARM = 2
# WarmJob row TTL (for stale cleanup)
WARM_JOB_TTL_SECONDS = 60 * 60


# ── Per-user concurrency cap ────────────────────────────────────────────


class _ConcurrencyTracker:
    """In-process per-user concurrency counter."""

    def __init__(self) -> None:
        self._counts: dict[str, int] = {}
        self._lock = threading.Lock()

    def try_acquire(self, user_id: uuid.UUID, *, cap: int) -> bool:
        key = str(user_id)
        with self._lock:
            cur = self._counts.get(key, 0)
            if cur >= cap:
                return False
            self._counts[key] = cur + 1
            return True

    def release(self, user_id: uuid.UUID) -> None:
        key = str(user_id)
        with self._lock:
            cur = self._counts.get(key, 0)
            if cur <= 1:
                self._counts.pop(key, None)
            else:
                self._counts[key] = cur - 1

    def current(self, user_id: uuid.UUID) -> int:
        key = str(user_id)
        with self._lock:
            return self._counts.get(key, 0)

    def reset(self) -> None:
        with self._lock:
            self._counts.clear()


concurrency = _ConcurrencyTracker()


# ── Candidate / result types (Pydantic-friendly dataclasses) ─────────────


@dataclass
class WarmCandidateInput:
    candidate_id: str
    hash_key: str
    nzb_url: str | None = None


@dataclass
class WarmCandidateState:
    candidate_id: str
    hash_key: str
    job_id: str | None
    simplified_state: str
    failure_code: str | None = None
    fallback_suggestions: list[str] = field(default_factory=list)


# ── Service ─────────────────────────────────────────────────────────────


ClientFactory = Callable[[str, str], NzbdavClient]


def _default_client_factory(base_url: str, api_key: str) -> NzbdavClient:
    return NzbdavClient(base_url=base_url, api_key=api_key)


class NzbdavWarmService:
    """Coordinates speculative warm jobs for a user.

    `client_factory` is injectable for tests — production code uses the
    default factory that builds a real NzbdavClient.
    """

    def __init__(
        self,
        *,
        client_factory: ClientFactory = _default_client_factory,
    ) -> None:
        self._client_factory = client_factory

    async def start_warm(
        self,
        db: Session,
        *,
        user_id: uuid.UUID,
        content_id: str,
        candidates: list[WarmCandidateInput],
        top_n: int = DEFAULT_TOP_N,
        sync_prewarm: int = DEFAULT_SYNC_PREWARM,
    ) -> list[WarmCandidateState]:
        start = time.monotonic()
        top_n = max(1, min(HARD_CAP_TOP_N, int(top_n or DEFAULT_TOP_N)))
        ranked = candidates[:top_n]

        cfg = account_store.get_for_user(db, user_id)
        if cfg is None or not cfg.is_enabled:
            # No config — every candidate fails fast without any upstream call.
            return [
                WarmCandidateState(
                    candidate_id=c.candidate_id,
                    hash_key=c.hash_key,
                    job_id=None,
                    simplified_state=st.SIMPLIFIED_FAILED,
                    failure_code=FailureCode.AUTH_INVALID.value,
                    fallback_suggestions=default_fallback_suggestions(
                        FailureCode.AUTH_INVALID
                    ),
                )
                for c in ranked
            ]

        provider_key = _provider_key(cfg)
        circuit_tripped = release_cache.is_circuit_tripped(provider_key)

        results: list[WarmCandidateState] = []
        for idx, cand in enumerate(ranked):
            state = self._evaluate_candidate(
                db,
                user_id=user_id,
                content_id=content_id,
                cand=cand,
                circuit_tripped=circuit_tripped,
            )
            if state is not None:
                results.append(state)
                continue

            # Need to start a new warm job.
            if not concurrency.try_acquire(
                user_id, cap=settings.NZBDAV_WARM_CONCURRENCY_PER_USER
            ):
                # Over the concurrency cap — still record a warming row so
                # the client sees a consistent response. We do NOT start
                # upstream work for this candidate this cycle.
                results.append(WarmCandidateState(
                    candidate_id=cand.candidate_id,
                    hash_key=cand.hash_key,
                    job_id=None,
                    simplified_state=st.SIMPLIFIED_WARMING,
                ))
                continue

            job = _create_warm_job_row(
                db, user_id=user_id, content_id=content_id, cand=cand
            )
            task = asyncio.create_task(
                self._run_warm_job(
                    user_id=user_id,
                    cfg_id=cfg.id,
                    job_id=job.job_id,
                    cand=cand,
                    provider_key=provider_key,
                )
            )
            # Tasks in the first `sync_prewarm` slots are kicked off
            # synchronously (non-blocking) — they still run in background,
            # but we don't artificially defer them. The rest are "queued"
            # in the same sense; in practice asyncio schedules them immediately.
            # We record them with simplified "warming" state regardless.
            _detach_task(task)
            _ = sync_prewarm  # (reserved for future throttling hook)
            _ = idx
            results.append(WarmCandidateState(
                candidate_id=cand.candidate_id,
                hash_key=cand.hash_key,
                job_id=str(job.job_id),
                simplified_state=st.SIMPLIFIED_WARMING,
            ))

        latency_ms = int((time.monotonic() - start) * 1000)
        telemetry.warm_start(
            latency_ms=latency_ms,
            content_id=content_id,
            user_id=user_id,
            candidates=len(ranked),
        )
        return results

    # ── Internal ────────────────────────────────────────────────────────

    def _evaluate_candidate(
        self,
        db: Session,
        *,
        user_id: uuid.UUID,
        content_id: str,
        cand: WarmCandidateInput,
        circuit_tripped: bool,
    ) -> WarmCandidateState | None:
        """Return a terminal state if no new work is needed, else None."""
        # Warm-success cache hit?
        cached = release_cache.get_warm_success(cand.hash_key)
        if cached is not None:
            telemetry.cache_hit(cache="warm_success", hash_key=cand.hash_key)
            return WarmCandidateState(
                candidate_id=cand.candidate_id,
                hash_key=cand.hash_key,
                job_id=None,
                simplified_state=st.SIMPLIFIED_READY,
            )

        # Dead-release cache hit?
        dead_code = release_cache.get_dead_release(cand.hash_key)
        if dead_code is not None:
            telemetry.cache_hit(cache="dead_release", hash_key=cand.hash_key)
            return WarmCandidateState(
                candidate_id=cand.candidate_id,
                hash_key=cand.hash_key,
                job_id=None,
                simplified_state=st.SIMPLIFIED_FAILED,
                failure_code=dead_code,
                fallback_suggestions=default_fallback_suggestions(
                    FailureCode(dead_code)
                ),
            )

        # Circuit tripped — fail fast without touching the upstream.
        if circuit_tripped:
            return WarmCandidateState(
                candidate_id=cand.candidate_id,
                hash_key=cand.hash_key,
                job_id=None,
                simplified_state=st.SIMPLIFIED_FAILED,
                failure_code=FailureCode.UPSTREAM_UNREACHABLE.value,
                fallback_suggestions=default_fallback_suggestions(
                    FailureCode.UPSTREAM_UNREACHABLE
                ),
            )

        # Dedupe against in-flight jobs.
        existing = (
            db.query(WarmJob)
            .filter(
                WarmJob.user_id == user_id,
                WarmJob.content_id == content_id,
                WarmJob.hash_key == cand.hash_key,
            )
            .order_by(WarmJob.created_at.desc())
            .first()
        )
        if existing is not None:
            simplified = st.to_simplified(existing.state)
            if simplified == st.SIMPLIFIED_READY:
                return WarmCandidateState(
                    candidate_id=cand.candidate_id,
                    hash_key=cand.hash_key,
                    job_id=str(existing.job_id),
                    simplified_state=st.SIMPLIFIED_READY,
                )
            if simplified == st.SIMPLIFIED_WARMING:
                # Return the in-flight job — do NOT start a duplicate.
                return WarmCandidateState(
                    candidate_id=cand.candidate_id,
                    hash_key=cand.hash_key,
                    job_id=str(existing.job_id),
                    simplified_state=st.SIMPLIFIED_WARMING,
                )
            # existing is failed/expired; fall through and start a new job
        return None

    async def _run_warm_job(
        self,
        *,
        user_id: uuid.UUID,
        cfg_id: uuid.UUID,
        job_id: uuid.UUID,
        cand: WarmCandidateInput,
        provider_key: str,
    ) -> None:
        """Background task that drives a single WarmJob through submit+poll."""
        started = time.monotonic()
        db: Session = SessionLocal()
        try:
            cfg = (
                db.query(NzbdavConfig)
                .filter(NzbdavConfig.id == cfg_id)
                .one_or_none()
            )
            if cfg is None:
                _update_job(db, job_id, state=st.FAILED,
                            failure_code=FailureCode.AUTH_INVALID.value,
                            failure_detail="config_missing")
                return
            try:
                api_key = account_store.get_decrypted_api_key(cfg)
                client = self._client_factory(cfg.base_url, api_key)
            except Exception as exc:  # noqa: BLE001
                _update_job(db, job_id, state=st.FAILED,
                            failure_code=FailureCode.AUTH_INVALID.value,
                            failure_detail=f"client_init_{type(exc).__name__}")
                telemetry.failure(
                    code=FailureCode.AUTH_INVALID.value,
                    user_id=user_id, phase="client_init",
                )
                return

            try:
                _update_job(db, job_id, state=st.SUBMITTING)
                submit = await client.submit_nzb(
                    nzb_url=cand.nzb_url, hash_key=cand.hash_key
                )
                _update_job(db, job_id, state=st.ACCEPTED,
                            phase="accepted")

                # Poll loop with a bounded total budget.
                poll_budget_sec = 90
                poll_deadline = time.monotonic() + poll_budget_sec
                stream_url: str | None = None
                while time.monotonic() < poll_deadline:
                    poll = await client.poll_job(
                        upstream_job_id=submit.upstream_job_id
                    )
                    upstream_state = poll.upstream_state.lower()
                    if upstream_state == "ready" and poll.stream_url:
                        stream_url = poll.stream_url
                        break
                    if upstream_state in ("repairing",):
                        _update_job(db, job_id, state=st.REPAIRING,
                                    phase=poll.phase)
                    elif upstream_state in ("extracting", "unpacking"):
                        _update_job(db, job_id, state=st.EXTRACTING,
                                    phase=poll.phase)
                    elif upstream_state in ("checking",):
                        _update_job(db, job_id, state=st.CHECKING,
                                    phase=poll.phase)
                    # Honor exact queue state from upstream if present
                    await asyncio.sleep(2.0)

                if stream_url is None:
                    raise UpstreamError(
                        FailureCode.STREAM_NOT_READY,
                        detail="poll_budget_exceeded",
                    )

                # Cache the upstream URL keyed by a Torve-side stream id.
                stream_id = str(uuid.uuid4())
                release_cache.set_warm_success(cand.hash_key, {
                    "stream_id": stream_id,
                    "upstream_url": stream_url,
                    "user_id": str(user_id),
                    "content_id": str(content_id),
                })
                _update_job(
                    db, job_id,
                    state=st.READY, phase=None,
                    stream_ready_at=datetime.now(timezone.utc),
                )
                duration_ms = int((time.monotonic() - started) * 1000)
                telemetry.warm_to_ready(
                    duration_ms=duration_ms,
                    hash_key=cand.hash_key,
                    user_id=user_id,
                )
                release_cache.record_success(
                    provider_key, latency_ms=float(duration_ms)
                )
            except UpstreamError as exc:
                _handle_upstream_error(
                    db, job_id=job_id, user_id=user_id,
                    hash_key=cand.hash_key, exc=exc,
                    provider_key=provider_key, phase="warm",
                )
            except asyncio.CancelledError:
                _update_job(db, job_id, state=st.CANCELLED)
                telemetry.cancel(reason="task_cancelled", user_id=user_id)
                raise
            except Exception as exc:  # noqa: BLE001
                _log.exception("NZBDAV_WARM_UNEXPECTED user=%s", user_id)
                _update_job(db, job_id, state=st.FAILED,
                            failure_code=FailureCode.UNKNOWN_UPSTREAM_ERROR.value,
                            failure_detail=type(exc).__name__)
                telemetry.failure(
                    code=FailureCode.UNKNOWN_UPSTREAM_ERROR.value,
                    user_id=user_id, phase="warm",
                )
            finally:
                try:
                    await client.aclose()
                except Exception:  # noqa: BLE001
                    pass
        finally:
            db.close()
            concurrency.release(user_id)


# ── Module helpers ──────────────────────────────────────────────────────


def _provider_key(cfg: NzbdavConfig) -> str:
    # Bucket health by config id so failures for one user don't trip
    # another user's circuit.
    return f"nzbdav:{cfg.id}"


def _detach_task(task: asyncio.Task) -> None:
    # Prevent "Task was destroyed but it is pending" warnings: swallow
    # exceptions here because _run_warm_job owns its own error handling.
    def _cb(_t: asyncio.Task) -> None:
        try:
            _t.result()
        except Exception:  # noqa: BLE001
            pass

    task.add_done_callback(_cb)


def _create_warm_job_row(
    db: Session,
    *,
    user_id: uuid.UUID,
    content_id: str,
    cand: WarmCandidateInput,
) -> WarmJob:
    now = datetime.now(timezone.utc)
    job = WarmJob(
        job_id=uuid.uuid4(),
        user_id=user_id,
        content_id=content_id,
        candidate_id=cand.candidate_id,
        hash_key=cand.hash_key,
        state=st.QUEUED,
        phase=None,
        expires_at=now + timedelta(seconds=WARM_JOB_TTL_SECONDS),
    )
    db.add(job)
    db.commit()
    db.refresh(job)
    return job


def _update_job(
    db: Session,
    job_id: uuid.UUID,
    *,
    state: str | None = None,
    phase: str | None = None,
    failure_code: str | None = None,
    failure_detail: str | None = None,
    stream_ready_at: datetime | None = None,
) -> None:
    job = db.query(WarmJob).filter(WarmJob.job_id == job_id).one_or_none()
    if job is None:
        return
    if state is not None:
        job.state = state
    if phase is not None:
        job.phase = phase
    if failure_code is not None:
        job.failure_code = failure_code
    if failure_detail is not None:
        job.failure_detail = failure_detail
    if stream_ready_at is not None:
        job.stream_ready_at = stream_ready_at
    db.commit()


def _handle_upstream_error(
    db: Session,
    *,
    job_id: uuid.UUID,
    user_id: uuid.UUID,
    hash_key: str,
    exc: UpstreamError,
    provider_key: str,
    phase: str,
) -> None:
    code = exc.code.value
    _update_job(
        db, job_id,
        state=st.FAILED,
        failure_code=code,
        failure_detail=exc.detail or "",
    )
    telemetry.failure(code=code, user_id=user_id, phase=phase)
    if code in DEAD_RELEASE_CODES:
        release_cache.set_dead_release(hash_key, code)
    if exc.code in (
        FailureCode.UPSTREAM_UNREACHABLE,
        FailureCode.TIMEOUT,
    ):
        tripped = release_cache.record_error(provider_key)
        if tripped:
            telemetry.circuit_tripped(
                until_ts=release_cache.circuit_until(provider_key)
            )


warm_service = NzbdavWarmService()


def cleanup_expired(db: Session, batch_size: int = 1000) -> int:
    """Delete stale NzbDAV warm-job rows. Returns count deleted.

    Removes rows whose expires_at has passed OR whose state is terminal
    (ready/failed/expired/cancelled) AND updated_at is older than 24h.
    Active rows (submitting/checking/accepted/…) are preserved regardless
    of age so the operator always sees currently-running jobs.
    """
    from datetime import timedelta

    now = datetime.now(timezone.utc)
    terminal_cutoff = now - timedelta(hours=24)
    terminal_states = ("ready", "failed", "expired", "cancelled")

    expired_ids = (
        db.query(WarmJob.job_id)
        .filter(
            (WarmJob.expires_at < now)
            | (
                WarmJob.state.in_(terminal_states)
                & (WarmJob.updated_at < terminal_cutoff)
            )
        )
        .limit(batch_size)
        .subquery()
    )
    deleted = (
        db.query(WarmJob)
        .filter(WarmJob.job_id.in_(db.query(expired_ids)))
        .delete(synchronize_session=False)
    )
    return deleted
