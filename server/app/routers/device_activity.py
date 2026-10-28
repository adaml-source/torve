"""Authenticated, account-scoped device measurements; no changes to subscription access."""
import uuid
from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session
from app.deps import get_current_user_id, get_db
from app.device_activity import ActivityReport, record_activity

router = APIRouter(tags=["device-activity"])


@router.post("/me/device-activity")
def report(body: ActivityReport, user_id: str = Depends(get_current_user_id), db: Session = Depends(get_db)):
    result = record_activity(db, uuid.UUID(user_id), body)
    db.commit()
    return result
