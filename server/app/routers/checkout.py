"""
Checkout endpoints for authenticated web purchases.

Creates server-side purchase intents so the webhook can safely
bind payments to Torve accounts without trusting client-supplied IDs.
"""
import logging
import uuid

from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.billing import create_purchase_intent
from app.config import settings
from app.deps import get_current_user_id, get_db

_log = logging.getLogger(__name__)

router = APIRouter(prefix="/me/checkout", tags=["checkout"])


@router.post("/intent")
def create_intent(
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> dict:
    """Create a purchase intent for the authenticated user.

    The returned intent_id must be passed into Paddle checkout as
    custom_data.torve_purchase_intent so the webhook can resolve
    the account safely.
    """
    uid = uuid.UUID(user_id)
    intent = create_purchase_intent(db, uid)
    db.commit()

    _log.info("Purchase intent created: %s for user %s", intent.id, uid)

    return {
        "intent_id": str(intent.id),
        "product_id": settings.PADDLE_PRODUCT_ID,
        "price_id": settings.PADDLE_PRICE_ID,
        "expires_in_minutes": 60,
    }
