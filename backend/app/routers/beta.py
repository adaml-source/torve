"""Authenticated beta access endpoints."""
from __future__ import annotations

import logging
import uuid

from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy.orm import Session

from app.deps import get_current_user_id, get_db
from app.discord_beta import (
    BetaFlowError,
    beta_flow_error_status,
    create_beta_link_code,
    get_beta_status,
)
from app.rate_limits import enforce_rate_limit

_log = logging.getLogger(__name__)

router = APIRouter(prefix="/me/beta", tags=["beta"])


@router.post("/discord-link-code")
def create_discord_beta_link_code(
    request: Request,
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> dict:
    """Create a short-lived one-time code for Discord beta application linking."""
    enforce_rate_limit(
        category="discord_beta_link_code",
        request=request,
        user_id=user_id,
        limit=3,
        window_seconds=3600,
    )
    try:
        result = create_beta_link_code(db, uuid.UUID(user_id))
        db.commit()
        return {
            "code": result.code,
            "expires_at": result.expires_at.isoformat(),
        }
    except BetaFlowError as exc:
        db.rollback()
        raise HTTPException(
            status_code=beta_flow_error_status(exc.code),
            detail={"error_code": exc.code, "message": exc.message},
        )
    except Exception as exc:  # noqa: BLE001 - sanitized client response
        db.rollback()
        _log.warning("Discord beta link code create failed error_type=%s", exc.__class__.__name__)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail={
                "error_code": "beta_link_code_failed",
                "message": "Could not create a beta link code. Please try again later.",
            },
        )


@router.get("/status")
def get_discord_beta_status(
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> dict:
    return get_beta_status(db, uuid.UUID(user_id))
