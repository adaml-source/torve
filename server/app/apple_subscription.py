"""Validate App Store signatures and account bindings before granting Apple access."""
from functools import lru_cache
from pathlib import Path
import time
import uuid

from fastapi import HTTPException
from sqlalchemy.orm import Session
from app.config import settings
from app.models import User
from app.apple_subscription_models import AppleSubscriptionOwner, AppleSubscriptionTransaction, AppleSubscriptionDevice

DEVICE_LIMIT = None  # Apple devices have no registration/activation cap.
OFFLINE_LEASE_MS = 24 * 60 * 60 * 1000


def now_ms() -> int:
    return int(time.time() * 1000)


def require_configuration() -> None:
    if not settings.APPLE_SUBSCRIPTION_ENABLED:
        raise HTTPException(503, detail={"code": "apple_subscription_not_configured", "message": "Apple subscriptions are not available yet. Please try again later."})
    if settings.APPLE_SUBSCRIPTION_ENVIRONMENT not in {"Production", "Sandbox"}:
        raise HTTPException(503, detail="Apple subscription environment is not configured.")


@lru_cache(maxsize=4)
def _verifier(environment: str, bundle_id: str, app_id: int):
    from appstoreserverlibrary.signed_data_verifier import SignedDataVerifier
    from appstoreserverlibrary.models.Environment import Environment
    roots = [p.read_bytes() for p in sorted((Path(__file__).parent / "apple_certificates").glob("*.cer"))]
    if len(roots) != 3:
        raise HTTPException(503, detail="Apple verification certificates are not configured.")
    return SignedDataVerifier(roots, True, Environment(environment), bundle_id, app_id)


def verifier(environment: str | None = None):
    require_configuration()
    return _verifier(environment or settings.APPLE_SUBSCRIPTION_ENVIRONMENT, settings.APPLE_BUNDLE_ID, settings.APPLE_APP_ID)


def allowed_environments(user_id: uuid.UUID) -> list[str]:
    environments = [settings.APPLE_SUBSCRIPTION_ENVIRONMENT]
    testers = {value.strip().lower() for value in settings.APPLE_SUBSCRIPTION_SANDBOX_USER_IDS.split(",") if value.strip()}
    if environments == ["Production"] and str(user_id).lower() in testers:
        environments.append("Sandbox")
    return environments


def verify_signed_transaction(signed_transaction: str, user_id: uuid.UUID | None = None):
    from appstoreserverlibrary.signed_data_verifier import VerificationException, VerificationStatus
    retryable = False
    for environment in allowed_environments(user_id) if user_id else [settings.APPLE_SUBSCRIPTION_ENVIRONMENT]:
        try:
            return verifier(environment).verify_and_decode_signed_transaction(signed_transaction)
        except VerificationException as exc:
            retryable |= exc.status == VerificationStatus.RETRYABLE_VERIFICATION_FAILURE
    raise HTTPException(503 if retryable else 400, detail={"code": "apple_verification_unavailable" if retryable else "apple_transaction_invalid",
        "message": "Apple verification is temporarily unavailable. Restore your purchase again later." if retryable else "The Apple purchase could not be verified."}) from None


def verify_signed_notification(signed_payload: str):
    from appstoreserverlibrary.signed_data_verifier import VerificationException, VerificationStatus
    require_configuration()
    environments = [settings.APPLE_SUBSCRIPTION_ENVIRONMENT]
    if environments == ["Production"] and settings.APPLE_SUBSCRIPTION_SANDBOX_USER_IDS.strip():
        environments.append("Sandbox")
    retryable = False
    for environment in environments:
        try:
            return verifier(environment).verify_and_decode_notification(signed_payload)
        except VerificationException as exc:
            retryable |= exc.status == VerificationStatus.RETRYABLE_VERIFICATION_FAILURE
    raise HTTPException(503 if retryable else 400, detail="Apple notification verification temporarily unavailable." if retryable else "Invalid Apple subscription notification.") from None


def lock_account(db: Session, user_id: uuid.UUID) -> User:
    # All slot mutations take the same PostgreSQL row lock, including the first slot.
    user = db.query(User).filter(User.id == user_id).with_for_update().first()
    if user is None or not user.is_active:
        raise HTTPException(401, detail="Sign in to an active Torve account.")
    return user


def _value(value):
    return getattr(value, "value", value)


def record_transaction(db: Session, user_id: uuid.UUID, payload) -> None:
    """Only called with a cryptographically verified Apple payload."""
    require_configuration()
    if (payload.bundleId != settings.APPLE_BUNDLE_ID or payload.productId != settings.APPLE_SUBSCRIPTION_PRODUCT_ID
        or _value(payload.environment) not in allowed_environments(user_id)
        or _value(payload.type) != "Auto-Renewable Subscription"):
        raise HTTPException(400, detail="This Apple purchase is not the configured Torve subscription.")
    try:
        account = uuid.UUID(str(payload.appAccountToken))
    except (ValueError, TypeError):
        raise HTTPException(400, detail="The Apple purchase has no valid Torve account binding.") from None
    if account != user_id:
        raise HTTPException(409, detail="This purchase belongs to a different Torve account. Sign in to the account used to subscribe.")
    transaction_id, original_id = payload.transactionId, payload.originalTransactionId
    if not all(isinstance(v, str) and v.isdecimal() and 1 <= len(v) <= 64 for v in (transaction_id, original_id)):
        raise HTTPException(400, detail="The Apple transaction identifiers are invalid.")
    if not all(type(v) is int and v > 0 for v in (payload.expiresDate, payload.signedDate)) or payload.signedDate > now_ms() + 300_000:
        raise HTTPException(400, detail="The Apple transaction dates are invalid.")
    if payload.revocationDate is not None and (type(payload.revocationDate) is not int or payload.revocationDate <= 0):
        raise HTTPException(400, detail="The Apple revocation date is invalid.")
    lock_account(db, user_id)
    key = (_value(payload.environment), original_id)
    owner = db.get(AppleSubscriptionOwner, key)
    if owner is not None and owner.user_id != user_id:
        raise HTTPException(409, detail="This subscription is already linked to another Torve account.")
    if owner is None:
        db.add(AppleSubscriptionOwner(environment=key[0], original_transaction_id=original_id, user_id=user_id))
        db.flush()
    row = db.get(AppleSubscriptionTransaction, (key[0], transaction_id))
    if row is not None and row.original_transaction_id != original_id:
        raise HTTPException(400, detail="The Apple transaction binding is inconsistent.")
    # A refund of an old billing period must not replace a later paid renewal.
    # Each period has its own row. Older replays cannot erase a newer revocation.
    if row is not None:
        if payload.signedDate < row.signed_at_ms:
            return
        if payload.signedDate == row.signed_at_ms and (row.revoked_at_ms is not None or (payload.revocationDate is None and not payload.isUpgraded)):
            return
    if row is None:
        row = AppleSubscriptionTransaction(environment=key[0], transaction_id=transaction_id, original_transaction_id=original_id)
        db.add(row)
    row.product_id = payload.productId
    row.expires_at_ms = payload.expiresDate
    row.signed_at_ms = payload.signedDate
    row.revoked_at_ms = payload.revocationDate
    row.is_upgraded = payload.isUpgraded is True
    db.flush()


def active_expiry(db: Session, user_id: uuid.UUID) -> int | None:
    row = (db.query(AppleSubscriptionTransaction)
        .join(AppleSubscriptionOwner,
            (AppleSubscriptionTransaction.environment == AppleSubscriptionOwner.environment)
            & (AppleSubscriptionTransaction.original_transaction_id == AppleSubscriptionOwner.original_transaction_id))
        .filter(AppleSubscriptionOwner.user_id == user_id,
            AppleSubscriptionTransaction.environment.in_(allowed_environments(user_id)),
            AppleSubscriptionTransaction.product_id == settings.APPLE_SUBSCRIPTION_PRODUCT_ID,
            AppleSubscriptionTransaction.expires_at_ms > now_ms(),
            AppleSubscriptionTransaction.revoked_at_ms.is_(None),
            AppleSubscriptionTransaction.is_upgraded.is_(False))
        .order_by(AppleSubscriptionTransaction.expires_at_ms.desc()).first())
    return row.expires_at_ms if row else None


def access_state(db: Session, user_id: uuid.UUID, device_id: uuid.UUID) -> dict:
    require_configuration()
    user = db.get(User, user_id)
    if user is None or not user.is_active:
        raise HTTPException(401, detail="Sign in to an active Torve account.")
    expiry = active_expiry(db, user_id)
    devices = db.query(AppleSubscriptionDevice).filter_by(user_id=user_id, active=True).order_by(AppleSubscriptionDevice.last_seen_ms.desc()).all()
    activated = any(d.device_id == device_id for d in devices)
    access = expiry is not None and activated
    now = now_ms()
    return dict(account_id=str(user_id), product_id=settings.APPLE_SUBSCRIPTION_PRODUCT_ID,
        subscription_active=expiry is not None, device_activated=activated, access_allowed=access,
        # Older shipped clients require this integer to be exactly five before
        # accepting a verified lease. Retain their wire field without enforcing
        # it; new clients/admins use the explicit unlimited policy.
        device_limit=5, device_limit_unlimited=True, expires_at_ms=expiry, verified_at_ms=now,
        lease_expires_at_ms=min(expiry, now + OFFLINE_LEASE_MS) if access else None,
        devices=[dict(device_id=str(d.device_id), platform=d.platform, display_name=d.display_name, current=d.device_id == device_id) for d in devices])


def activate_device(db: Session, user_id: uuid.UUID, device_id: uuid.UUID, platform: str, display_name: str) -> dict:
    require_configuration()
    if platform not in {"ios", "ipados", "tvos"}:
        raise HTTPException(422, detail="Only Apple devices use this subscription allowance.")
    lock_account(db, user_id)
    if active_expiry(db, user_id) is None:
        raise HTTPException(403, detail="An active Apple subscription is required.")
    row = db.get(AppleSubscriptionDevice, (user_id, device_id))
    if row is None:
        row = AppleSubscriptionDevice(user_id=user_id, device_id=device_id)
        db.add(row)
    row.platform, row.display_name = platform, display_name.strip() or "Apple device"
    row.active, row.last_seen_ms = True, now_ms()
    db.flush()
    return access_state(db, user_id, device_id)
