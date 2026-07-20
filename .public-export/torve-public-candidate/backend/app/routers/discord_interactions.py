"""Discord outgoing interaction webhook."""
from __future__ import annotations

import json
import logging

from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy.orm import Session

from app.config import settings
from app.deps import get_db
from app.discord_beta import handle_interaction_payload, verify_discord_signature

_log = logging.getLogger(__name__)

router = APIRouter(prefix="/discord", tags=["discord"])


@router.post("/interactions")
async def discord_interactions(request: Request, db: Session = Depends(get_db)) -> dict:
    body = await request.body()
    if not settings.DISCORD_PUBLIC_KEY.strip():
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={"error_code": "discord_interactions_not_configured"},
        )
    if not verify_discord_signature(
        settings.DISCORD_PUBLIC_KEY,
        request.headers.get("x-signature-timestamp", ""),
        request.headers.get("x-signature-ed25519", ""),
        body,
    ):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid signature")
    try:
        payload = json.loads(body.decode("utf-8"))
    except json.JSONDecodeError:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid JSON")
    try:
        return handle_interaction_payload(db, payload)
    except Exception as exc:  # noqa: BLE001 - Discord gets sanitized response
        db.rollback()
        _log.warning("Discord interaction failed error_type=%s", exc.__class__.__name__)
        return {
            "type": 4,
            "data": {
                "content": "Something went wrong. Please try again later.",
                "flags": 64,
                "allowed_mentions": {"parse": []},
            },
        }
