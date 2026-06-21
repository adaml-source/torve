"""Deprecated checkout endpoints retained for client compatibility."""
import logging

from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.deps import get_current_user_id, get_db

_log = logging.getLogger(__name__)

router = APIRouter(prefix="/me/checkout", tags=["checkout"])


@router.post("/intent")
def create_intent(
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> dict:
    """Return a free-software-safe response; checkout is no longer needed."""
    _log.info("CHECKOUT_INTENT_DEPRECATED_FREE_SOFTWARE user=%s", user_id)
    return {
        "deprecated": True,
        "checkout_required": False,
        "intent_id": None,
        "product_id": None,
        "price_id": None,
        "expires_in_minutes": 0,
        "message": "Checkout is no longer required; Torve access is free.",
    }
