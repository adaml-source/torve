"""Rate-limited, authenticated monitoring; responses never grant or deny app access."""
import json
import logging
from typing import Literal

from fastapi import APIRouter, Depends, Request
from pydantic import BaseModel, Field

from app.deps import get_calling_installation_id, get_current_user_id
from app.rate_limits import enforce_rate_limit
from app import play_integrity_monitor as monitor

router = APIRouter(prefix="/me/integrity-monitor", tags=["integrity-monitor"])
# Uvicorn's configured INFO handler persists these sanitized observations to journald.
log = logging.getLogger("uvicorn.error.integrity_monitor")


class Start(BaseModel):
    event_type: Literal["login", "foreground", "credential_transfer"]


class Submission(BaseModel):
    challenge: str = Field(min_length=1, max_length=2048)
    integrity_token: str | None = Field(default=None, max_length=20000)


@router.post("/challenge")
def challenge(body: Start, request: Request, user_id: str = Depends(get_current_user_id),
              installation_id: str | None = Depends(get_calling_installation_id)):
    enforce_rate_limit(category="integrity_monitor_challenge", request=request, user_id=user_id,
                       limit=8, window_seconds=3600)
    if not monitor.enabled(): return {"enabled": False}
    return monitor.create_challenge(user_id, installation_id, body.event_type)


@router.post("/verdict")
def verdict(body: Submission, request: Request, user_id: str = Depends(get_current_user_id),
            installation_id: str | None = Depends(get_calling_installation_id)):
    enforce_rate_limit(category="integrity_monitor_verdict", request=request, user_id=user_id,
                       limit=8, window_seconds=3600)
    result = monitor.observe(body.challenge, body.integrity_token, user_id, installation_id)
    # Sanitized verdict labels only: no raw tokens, challenges, account IDs, or Google payloads.
    log.info("PLAY_INTEGRITY_MONITOR %s", json.dumps(result, sort_keys=True))
    return {"accepted": True, "mode": "monitor_only", **result}
