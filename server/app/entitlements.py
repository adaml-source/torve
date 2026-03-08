"""
Core entitlement business logic.
Handles granting, revoking, and resolving premium access.
"""
import logging
from datetime import datetime, timezone

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from .models import Entitlement, EntitlementEvent, Purchase, utcnow
from .store_verification import PRODUCT_TO_ENTITLEMENT, VerificationResult

logger = logging.getLogger(__name__)


async def process_verified_purchase(
    session: AsyncSession,
    user_id: str,
    store: str,
    platform: str,
    result: VerificationResult,
) -> tuple[Purchase, list[Entitlement]]:
    """
    Idempotent purchase processing.
    If this transaction was already processed, returns the existing purchase and entitlements.
    Otherwise, creates a new purchase record and grants entitlements.
    """
    orig_txn = result.original_transaction_id or result.transaction_id

    # Check for existing purchase with same store + original_transaction_id (idempotency)
    existing_result = await session.execute(
        select(Purchase).where(
            Purchase.store == store,
            Purchase.original_transaction_id == orig_txn,
        )
    )
    existing_purchase = existing_result.scalar_one_or_none()

    if existing_purchase:
        # Idempotent: if already verified for this user, return existing
        if existing_purchase.user_id == user_id and existing_purchase.verification_status == "verified":
            entitlements = await get_user_entitlements(session, user_id)
            return existing_purchase, entitlements

        # Different user trying to use same receipt
        if existing_purchase.user_id != user_id:
            raise ValueError("This purchase is already associated with a different account")

        # Re-verify a previously pending/rejected purchase for same user
        existing_purchase.verification_status = "verified"
        existing_purchase.verified_at = utcnow()
        existing_purchase.updated_at = utcnow()
        await session.flush()
        entitlements = await grant_entitlement_for_purchase(session, existing_purchase)
        return existing_purchase, entitlements

    # Resolve entitlement key from product_id
    mapping = PRODUCT_TO_ENTITLEMENT.get(result.product_id)
    if not mapping:
        raise ValueError(f"Unknown product ID: {result.product_id}")
    entitlement_key, purchase_type = mapping

    # Create purchase record
    purchased_at = None
    if result.purchased_at_ms:
        purchased_at = datetime.fromtimestamp(result.purchased_at_ms / 1000, tz=timezone.utc)

    purchase = Purchase(
        user_id=user_id,
        store=store,
        platform=platform,
        product_id=result.product_id,
        purchase_type=purchase_type,
        transaction_id=result.transaction_id,
        original_transaction_id=orig_txn,
        purchase_token=None,
        raw_payload_json=result.raw,
        purchased_at=purchased_at,
        verified_at=utcnow(),
        verification_status="verified",
    )
    session.add(purchase)
    await session.flush()

    entitlements = await grant_entitlement_for_purchase(session, purchase)
    return purchase, entitlements


async def grant_entitlement_for_purchase(
    session: AsyncSession,
    purchase: Purchase,
) -> list[Entitlement]:
    """Grant entitlement based on verified purchase. Idempotent."""
    mapping = PRODUCT_TO_ENTITLEMENT.get(purchase.product_id)
    if not mapping:
        return []
    entitlement_key, _ = mapping

    # Check if entitlement already exists for this user + key + purchase
    existing_result = await session.execute(
        select(Entitlement).where(
            Entitlement.user_id == purchase.user_id,
            Entitlement.entitlement_key == entitlement_key,
            Entitlement.source_purchase_id == purchase.id,
        )
    )
    existing = existing_result.scalar_one_or_none()
    if existing:
        if existing.status != "active":
            existing.status = "active"
            existing.updated_at = utcnow()
        all_entitlements = await get_user_entitlements(session, purchase.user_id)
        return all_entitlements

    ends_at = None
    if purchase.purchase_type == "subscription":
        # For subscriptions, set expires. For v1, default 35 days if not known.
        from datetime import timedelta
        ends_at = utcnow() + timedelta(days=35)

    entitlement = Entitlement(
        user_id=purchase.user_id,
        entitlement_key=entitlement_key,
        status="active",
        source_store=purchase.store,
        source_purchase_id=purchase.id,
        starts_at=purchase.purchased_at or utcnow(),
        ends_at=ends_at,
        granted_at=utcnow(),
    )
    session.add(entitlement)

    # Audit log
    session.add(EntitlementEvent(
        user_id=purchase.user_id,
        entitlement_key=entitlement_key,
        event_type="granted",
        source_purchase_id=purchase.id,
        metadata_json={"store": purchase.store, "product_id": purchase.product_id},
    ))

    await session.flush()
    return await get_user_entitlements(session, purchase.user_id)


async def get_user_entitlements(session: AsyncSession, user_id: str) -> list[Entitlement]:
    """Get all active entitlements for a user."""
    result = await session.execute(
        select(Entitlement).where(
            Entitlement.user_id == user_id,
            Entitlement.status == "active",
        )
    )
    return list(result.scalars().all())


def resolve_premium_access(entitlements: list[Entitlement]) -> bool:
    """
    Access resolution logic:
    - lifetime active → premium
    - monthly active and not expired → premium
    - else → false
    Lifetime always overrides monthly.
    """
    now = utcnow()
    for e in entitlements:
        if e.status != "active":
            continue
        if e.entitlement_key == "torve_pro_lifetime":
            return True
        if e.entitlement_key == "torve_pro_monthly":
            if e.ends_at is None or e.ends_at > now:
                return True
    return False


async def revoke_purchase(session: AsyncSession, purchase: Purchase, reason: str) -> None:
    """Revoke a purchase and its associated entitlements."""
    purchase.verification_status = "revoked"
    purchase.revocation_reason = reason
    purchase.updated_at = utcnow()

    entitlements_result = await session.execute(
        select(Entitlement).where(Entitlement.source_purchase_id == purchase.id)
    )
    for ent in entitlements_result.scalars().all():
        ent.status = "revoked"
        ent.updated_at = utcnow()
        session.add(EntitlementEvent(
            user_id=ent.user_id,
            entitlement_key=ent.entitlement_key,
            event_type="revoked",
            source_purchase_id=purchase.id,
            metadata_json={"reason": reason},
        ))

    await session.flush()
