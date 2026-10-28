"""Apple-only purchase, restoration, device slots and signed V2 notifications."""
import uuid
from typing import Literal
from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel, Field
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session
from app.deps import get_current_user_id, get_db
from app import apple_subscription as service
from app.apple_subscription_models import AppleSubscriptionDevice

router = APIRouter(tags=["apple-subscription"])


class SignedTransaction(BaseModel):
    signed_transaction: str = Field(min_length=10, max_length=25000)
    device_id: uuid.UUID


class AppleDevice(BaseModel):
    device_id: uuid.UUID
    platform: Literal["ios", "ipados", "tvos"]
    display_name: str = Field(min_length=1, max_length=100)


class Notification(BaseModel):
    signedPayload: str = Field(min_length=10, max_length=50000)


@router.get("/me/apple-subscription")
def status(device_id: uuid.UUID = Query(), user_id: str = Depends(get_current_user_id), db: Session = Depends(get_db)):
    return service.access_state(db, uuid.UUID(user_id), device_id)


@router.post("/me/apple-subscription/verify")
def verify(body: SignedTransaction, user_id: str = Depends(get_current_user_id), db: Session = Depends(get_db)):
    payload = service.verify_signed_transaction(body.signed_transaction, uuid.UUID(user_id))
    try:
        service.record_transaction(db, uuid.UUID(user_id), payload)
        db.commit()
    except IntegrityError:
        db.rollback()
        raise HTTPException(409, detail="The purchase is already being verified. Restore again to refresh its status.") from None
    return service.access_state(db, uuid.UUID(user_id), body.device_id)


@router.post("/me/apple-subscription/devices/activate")
def activate(body: AppleDevice, user_id: str = Depends(get_current_user_id), db: Session = Depends(get_db)):
    state = service.activate_device(db, uuid.UUID(user_id), body.device_id, body.platform, body.display_name)
    db.commit()
    return state


@router.delete("/me/apple-subscription/devices/{device_id}")
def revoke(device_id: uuid.UUID, current_device_id: uuid.UUID = Query(), user_id: str = Depends(get_current_user_id), db: Session = Depends(get_db)):
    uid = uuid.UUID(user_id)
    service.require_configuration()
    service.lock_account(db, uid)
    row = db.get(AppleSubscriptionDevice, (uid, device_id))
    if row is None:
        raise HTTPException(404, detail="Apple device not found.")
    row.active = False
    db.commit()
    return service.access_state(db, uid, current_device_id)


@router.post("/apple/subscriptions/notifications")
def notifications(body: Notification, db: Session = Depends(get_db)):
    notification = service.verify_signed_notification(body.signedPayload)
    data = notification.data
    if data is None or not data.signedTransactionInfo:
        return {"accepted": True}  # Includes Apple's signed TEST notification.
    # Use the envelope's verified environment. The inner transaction must independently match it.
    envelope_environment = getattr(data.environment, "value", data.environment)
    from appstoreserverlibrary.signed_data_verifier import VerificationException, VerificationStatus
    try:
        payload = service.verifier(envelope_environment).verify_and_decode_signed_transaction(data.signedTransactionInfo)
    except VerificationException as exc:
        retryable = exc.status == VerificationStatus.RETRYABLE_VERIFICATION_FAILURE
        raise HTTPException(503 if retryable else 400, detail="Apple transaction verification temporarily unavailable." if retryable else "Invalid Apple notification transaction.") from None
    if getattr(payload.environment, "value", payload.environment) != envelope_environment:
        raise HTTPException(400, detail="Apple notification transaction environment mismatch.")
    try:
        uid = uuid.UUID(str(payload.appAccountToken))
    except (ValueError, TypeError):
        raise HTTPException(400, detail="The purchase has no Torve account binding.") from None
    # A deleted account cannot be resurrected by renewal delivery.
    from app.models import User
    if db.get(User, uid) is None:
        return {"accepted": True}
    try:
        service.record_transaction(db, uid, payload)
        db.commit()
    except IntegrityError:
        db.rollback()
        raise HTTPException(503, detail="Apple notification is being processed; please retry.") from None
    return {"accepted": True}
