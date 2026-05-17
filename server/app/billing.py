"""
Billing service: entitlement grants, revocations, and premium resolution.

Canonical layer for managing Torve access entitlements.
All entitlement changes go through this module.

Supported entitlement types:
  - lifetime_access: permanent premium, no expiry
  - subscription_monthly: premium while active, has expires_at

Premium access = any active lifetime OR any active non-expired subscription.
"""
import logging
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

from sqlalchemy import and_ as sa_and, or_ as sa_or
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.config import settings
from app.models import LifetimeGrantRecord, PurchaseIntent, User, UserEntitlement, WebPayment

_log = logging.getLogger(__name__)

# Entitlement type constants
ENTITLEMENT_LIFETIME = "lifetime_access"
ENTITLEMENT_SUBSCRIPTION = "subscription_monthly"

# Source constants
SOURCE_PADDLE = "paddle_web"
SOURCE_GOOGLE_PLAY = "google_play"
SOURCE_AMAZON = "amazon"
SOURCE_ADMIN = "admin_grant"
SOURCE_REBATE = "rebate_code"
SOURCE_STRIPE = "stripe"

STRIPE_PURCHASE_MONTHLY = "monthly"
STRIPE_PURCHASE_LIFETIME = "lifetime"


@dataclass(frozen=True)
class StripePurchaseEligibility:
    allowed: bool
    error_code: str | None = None
    message: str | None = None


def _active_entitlements_for_user(db: Session, user_id: uuid.UUID) -> list[UserEntitlement]:
    now = datetime.now(timezone.utc)
    return db.query(UserEntitlement).filter(
        UserEntitlement.user_id == user_id,
        UserEntitlement.status == "active",
        sa_or(
            UserEntitlement.expires_at.is_(None),
            UserEntitlement.expires_at > now,
        ),
    ).all()


def evaluate_stripe_purchase_eligibility(
    db: Session,
    user_id: uuid.UUID,
    purchase_type: str,
) -> StripePurchaseEligibility:
    """Check whether Stripe checkout is allowed for the current user.

    This is the server-side source of truth for purchase blocking. Clients
    may hide buttons, but checkout session creation must enforce the same
    policy before touching Stripe.
    """
    normalized_purchase = (purchase_type or "").strip().lower()
    if normalized_purchase not in {STRIPE_PURCHASE_MONTHLY, STRIPE_PURCHASE_LIFETIME}:
        return StripePurchaseEligibility(
            allowed=False,
            error_code="stripe_invalid_purchase_type",
            message="This purchase type is not supported.",
        )

    active = _active_entitlements_for_user(db, user_id)
    if not active:
        return StripePurchaseEligibility(allowed=True)

    lifetime = [
        ent for ent in active
        if ent.entitlement_type == ENTITLEMENT_LIFETIME
    ]
    if lifetime:
        if any(ent.source == SOURCE_STRIPE for ent in lifetime):
            return StripePurchaseEligibility(
                allowed=False,
                error_code="stripe_lifetime_already_owned",
                message="Lifetime premium is already active for this account.",
            )
        return StripePurchaseEligibility(
            allowed=False,
            error_code="stripe_cross_store_purchase_blocked",
            message="Premium is already active from another purchase source.",
        )

    subscriptions = [
        ent for ent in active
        if ent.entitlement_type == ENTITLEMENT_SUBSCRIPTION
    ]
    if not subscriptions:
        # Be conservative for any unknown active entitlement shape.
        return StripePurchaseEligibility(
            allowed=False,
            error_code="stripe_purchase_not_allowed",
            message="This account is not eligible for Stripe checkout.",
        )

    non_stripe_subscriptions = [
        ent for ent in subscriptions
        if ent.source != SOURCE_STRIPE
    ]
    if non_stripe_subscriptions:
        return StripePurchaseEligibility(
            allowed=False,
            error_code="stripe_cross_store_purchase_blocked",
            message="Premium is already active from another purchase source.",
        )

    # At this point the active paid source is Stripe monthly.
    if normalized_purchase == STRIPE_PURCHASE_MONTHLY:
        return StripePurchaseEligibility(
            allowed=False,
            error_code="stripe_duplicate_subscription",
            message="A Stripe monthly subscription is already active.",
        )

    return StripePurchaseEligibility(allowed=True)


def grant_entitlement(
    db: Session,
    user_id: uuid.UUID,
    source: str,
    source_ref: str,
    entitlement_type: str = ENTITLEMENT_LIFETIME,
    product_id: str | None = None,
    expires_at: datetime | None = None,
    auto_renew: bool | None = None,
    originating_device_id: uuid.UUID | None = None,
) -> UserEntitlement | None:
    """Grant an entitlement. Idempotent: returns existing if already granted.

    For lifetime: expires_at and auto_renew should be None.
    For subscription: expires_at required, auto_renew indicates renewal state.

    `originating_device_id` (Pattern B): the device that initiated this
    grant. When set AND the user is unverified, only that device can use
    the entitlement until verification — see check_premium_active. Server-
    initiated grants (Paddle webhook, admin) typically pass None, which
    the gate treats as "grandfathered to all devices".
    """
    existing = db.query(UserEntitlement).filter(
        UserEntitlement.source == source,
        UserEntitlement.source_ref == source_ref,
        UserEntitlement.entitlement_type == entitlement_type,
    ).first()

    now = datetime.now(timezone.utc)

    if existing:
        if existing.status == "active":
            # Update subscription fields if changed (e.g. renewal extended expiry)
            changed = False
            if expires_at and existing.expires_at != expires_at:
                existing.expires_at = expires_at
                changed = True
            if auto_renew is not None and existing.auto_renew != auto_renew:
                existing.auto_renew = auto_renew
                changed = True
            if changed:
                existing.last_verified_at = now
                existing.updated_at = now
                recompute_user_premium(db, user_id)
            _log.info("ENTITLEMENT_ALREADY_ACTIVE source=%s ref=%s user=%s type=%s",
                       source, source_ref, user_id, entitlement_type)
            return existing
        # Reactivate revoked/expired entitlement from same source
        existing.status = "active"
        existing.revoked_at = None
        existing.expires_at = expires_at
        existing.auto_renew = auto_renew
        existing.last_verified_at = now
        existing.updated_at = now
        recompute_user_premium(db, user_id)
        _log.info("ENTITLEMENT_REACTIVATED source=%s ref=%s user=%s type=%s",
                   source, source_ref, user_id, entitlement_type)
        return existing

    ent = UserEntitlement(
        user_id=user_id,
        entitlement_type=entitlement_type,
        source=source,
        source_ref=source_ref,
        product_id=product_id,
        status="active",
        expires_at=expires_at,
        auto_renew=auto_renew,
        last_verified_at=now,
        originating_device_id=originating_device_id,
    )
    try:
        db.add(ent)
        db.flush()
    except IntegrityError:
        db.rollback()
        _log.info("ENTITLEMENT_DUPLICATE_BLOCKED source=%s ref=%s", source, source_ref)
        return db.query(UserEntitlement).filter(
            UserEntitlement.source == source,
            UserEntitlement.source_ref == source_ref,
            UserEntitlement.entitlement_type == entitlement_type,
        ).first()

    recompute_user_premium(db, user_id)
    _log.info("ENTITLEMENT_GRANTED source=%s ref=%s user=%s type=%s",
               source, source_ref, user_id, entitlement_type)

    # Persistent email-keyed ledger of lifetime grants. Only written for
    # lifetime entitlements — subscriptions don't need post-delete restore
    # because they expire naturally. The row is NOT cascade-linked to the
    # user row, so it survives account deletion and enables auto-restore
    # on re-signup.
    if entitlement_type == ENTITLEMENT_LIFETIME:
        user = db.query(User).filter(User.id == user_id).first()
        if user and user.email:
            _record_lifetime_grant(db, email=user.email, source=source,
                                   source_ref=source_ref, product_id=product_id)
    return ent


def _record_lifetime_grant(
    db: Session,
    email: str,
    source: str,
    source_ref: str,
    product_id: str | None = None,
    notes: str | None = None,
) -> LifetimeGrantRecord | None:
    """Idempotently write a persistent lifetime-grant record keyed by email.

    Skips refs that start with 'restored:' — those are re-provisions of an
    already-recorded grant, not new grant events. Without this guard, every
    restore_lifetime_if_granted() call writes a fresh ledger row whose ref
    chains the previous restore's ref, and the next restore picks that row
    up and chains again — exponential growth in both ledger and entitlement
    rows. Hit live for adam.losonczy@gmail.com on 2026-04-26 (3 chained rows
    after two Refresh-Access taps).
    """
    if source_ref.startswith("restored:"):
        return None
    email_norm = email.lower().strip()
    existing = db.query(LifetimeGrantRecord).filter(
        LifetimeGrantRecord.source == source,
        LifetimeGrantRecord.source_ref == source_ref,
    ).first()
    if existing:
        # If previously revoked and now re-granted (e.g. Paddle refund
        # reversed), clear the revoke so restore will pick it up again.
        if existing.revoked_at is not None:
            existing.revoked_at = None
            existing.revoke_reason = None
            db.flush()
        return existing
    row = LifetimeGrantRecord(
        email=email_norm,
        source=source,
        source_ref=source_ref,
        product_id=product_id,
        notes=notes,
    )
    try:
        db.add(row)
        db.flush()
        _log.info("LIFETIME_GRANT_RECORDED email=%s source=%s ref=%s",
                   email_norm, source, source_ref)
    except IntegrityError:
        db.rollback()
        return db.query(LifetimeGrantRecord).filter(
            LifetimeGrantRecord.source == source,
            LifetimeGrantRecord.source_ref == source_ref,
        ).first()
    return row


def revoke_entitlement(
    db: Session,
    source: str,
    source_ref: str,
    entitlement_type: str = ENTITLEMENT_LIFETIME,
) -> UserEntitlement | None:
    """Revoke an entitlement by source reference. Idempotent."""
    ent = db.query(UserEntitlement).filter(
        UserEntitlement.source == source,
        UserEntitlement.source_ref == source_ref,
        UserEntitlement.entitlement_type == entitlement_type,
    ).first()

    if not ent:
        _log.warning("ENTITLEMENT_REVOKE_NOT_FOUND source=%s ref=%s", source, source_ref)
        return None

    if ent.status == "revoked":
        _log.info("ENTITLEMENT_ALREADY_REVOKED source=%s ref=%s", source, source_ref)
        return ent

    now = datetime.now(timezone.utc)
    ent.status = "revoked"
    ent.revoked_at = now
    ent.updated_at = now
    db.flush()
    recompute_user_premium(db, ent.user_id)
    _log.info("ENTITLEMENT_REVOKED source=%s ref=%s user=%s type=%s",
               source, source_ref, ent.user_id, ent.entitlement_type)

    # Mirror the revoke into the persistent ledger so re-signup won't
    # auto-restore a refunded grant.
    if ent.entitlement_type == ENTITLEMENT_LIFETIME:
        row = db.query(LifetimeGrantRecord).filter(
            LifetimeGrantRecord.source == source,
            LifetimeGrantRecord.source_ref == source_ref,
        ).first()
        if row and row.revoked_at is None:
            row.revoked_at = now
            row.revoke_reason = "entitlement_revoked"
            db.flush()
    return ent


def restore_lifetime_if_granted(db: Session, user: User) -> UserEntitlement | None:
    """If the user's email has an un-revoked lifetime grant in the ledger
    but no active lifetime entitlement, re-provision one.

    Call this AFTER the user has verified their email — otherwise someone
    signing up with a known-lifetime email they don't own would silently
    inherit premium.
    """
    email_norm = (user.email or "").lower().strip()
    if not email_norm:
        return None

    # Don't double-grant — if the user already has an active lifetime
    # entitlement, skip.
    active_ent = db.query(UserEntitlement).filter(
        UserEntitlement.user_id == user.id,
        UserEntitlement.entitlement_type == ENTITLEMENT_LIFETIME,
        UserEntitlement.status == "active",
    ).first()
    if active_ent:
        return active_ent

    # Pick the original grant — never a row whose source_ref is itself
    # a "restored:..." chain, otherwise restorations compound across runs
    # of legacy data that was written before _record_lifetime_grant
    # learned to skip restored refs. Most-recent original wins.
    row = db.query(LifetimeGrantRecord).filter(
        LifetimeGrantRecord.email == email_norm,
        LifetimeGrantRecord.revoked_at.is_(None),
        ~LifetimeGrantRecord.source_ref.like("restored:%"),
    ).order_by(LifetimeGrantRecord.granted_at.desc()).first()
    if not row:
        return None

    # Use a unique source_ref so this restore doesn't collide with the
    # original Paddle/promo row if the original user was deleted and that
    # old entitlement was cascade-wiped.
    restore_ref = f"restored:{row.source}:{row.source_ref}:{user.id}"
    _log.info("LIFETIME_RESTORE email=%s original_source=%s original_ref=%s user=%s",
               email_norm, row.source, row.source_ref, user.id)
    return grant_entitlement(
        db,
        user_id=user.id,
        source=SOURCE_ADMIN,
        source_ref=restore_ref,
        entitlement_type=ENTITLEMENT_LIFETIME,
        product_id=row.product_id,
    )


def expire_subscription(
    db: Session,
    source: str,
    source_ref: str,
) -> UserEntitlement | None:
    """Mark a subscription as expired. Used when renewal fails or subscription ends."""
    ent = db.query(UserEntitlement).filter(
        UserEntitlement.source == source,
        UserEntitlement.source_ref == source_ref,
        UserEntitlement.entitlement_type == ENTITLEMENT_SUBSCRIPTION,
    ).first()

    if not ent:
        return None
    if ent.status in ("expired", "revoked"):
        return ent

    now = datetime.now(timezone.utc)
    ent.status = "expired"
    ent.auto_renew = False
    ent.updated_at = now
    db.flush()
    recompute_user_premium(db, ent.user_id)
    _log.info("SUBSCRIPTION_EXPIRED source=%s ref=%s user=%s", source, source_ref, ent.user_id)
    return ent


def recompute_user_premium(db: Session, user_id: uuid.UUID) -> None:
    """Recompute user premium access from all active entitlements.

    Premium = any active lifetime OR any active non-expired subscription.
    Updates both has_lifetime_access and has_premium_access.
    """
    now = datetime.now(timezone.utc)

    has_lifetime = db.query(UserEntitlement).filter(
        UserEntitlement.user_id == user_id,
        UserEntitlement.entitlement_type == ENTITLEMENT_LIFETIME,
        UserEntitlement.status == "active",
    ).count() > 0

    has_active_sub = db.query(UserEntitlement).filter(
        UserEntitlement.user_id == user_id,
        UserEntitlement.entitlement_type == ENTITLEMENT_SUBSCRIPTION,
        UserEntitlement.status == "active",
        UserEntitlement.expires_at > now,
    ).count() > 0

    user = db.query(User).filter(User.id == user_id).first()
    if user:
        user.has_lifetime_access = has_lifetime
        user.has_premium_access = has_lifetime or has_active_sub
        user.updated_at = now


def check_premium_active(
    db: Session,
    user_id: uuid.UUID,
    *,
    requesting_device_id: uuid.UUID | None = None,
) -> bool:
    """Live check whether a user currently has premium access.

    Unlike the cached User.has_premium_access boolean, this queries
    entitlements directly. If a stale boolean is detected, it triggers
    recompute so the cache stays honest.

    Used by device-cap enforcement and any code that needs a real-time
    premium answer without raising on failure.

    Pattern B device-aware gate (`requesting_device_id`): when supplied
    AND the user is unverified, premium is granted ONLY for entitlements
    whose `originating_device_id` matches OR is NULL (grandfathered
    pre-feature rows + server-initiated grants like Paddle webhooks).
    Verified users always get premium on any device. Callers that don't
    care about device-scope (internal jobs, tests) leave the kwarg unset.
    """
    user = db.query(User).filter(User.id == user_id).first()
    if not user:
        return False

    if not user.has_premium_access:
        return False

    # Verified users get the existing global behavior — any active
    # entitlement enables premium on any device.
    if user.is_verified or requesting_device_id is None:
        if user.has_lifetime_access:
            return True
        now = datetime.now(timezone.utc)
        has_active_sub = db.query(UserEntitlement).filter(
            UserEntitlement.user_id == user_id,
            UserEntitlement.entitlement_type == ENTITLEMENT_SUBSCRIPTION,
            UserEntitlement.status == "active",
            UserEntitlement.expires_at > now,
        ).count() > 0
        if not has_active_sub:
            recompute_user_premium(db, user_id)
            db.commit()
            return False
        return True

    # Unverified user with a device in scope. An entitlement counts only
    # when its originating_device_id matches the calling device OR is
    # NULL (grandfathered / server-initiated). After verification, the
    # branch above takes over and all devices get access.
    now = datetime.now(timezone.utc)
    matches_q = db.query(UserEntitlement).filter(
        UserEntitlement.user_id == user_id,
        UserEntitlement.status == "active",
        sa_or(
            UserEntitlement.originating_device_id.is_(None),
            UserEntitlement.originating_device_id == requesting_device_id,
        ),
        sa_or(
            # Lifetime never expires; subscription must be unexpired.
            UserEntitlement.entitlement_type == ENTITLEMENT_LIFETIME,
            sa_and(
                UserEntitlement.entitlement_type == ENTITLEMENT_SUBSCRIPTION,
                UserEntitlement.expires_at > now,
            ),
        ),
    )
    return matches_q.count() > 0


def resolve_access_state(
    db: Session,
    user_id: uuid.UUID,
    installation_id: str | None = None,
) -> dict:
    """Resolve the current access state for a user. Used by /me/access-state.

    If installation_id is provided, includes device activation status.
    """
    from app.models import Device

    now = datetime.now(timezone.utc)
    user = db.query(User).filter(User.id == user_id).first()
    if not user:
        return {"has_premium_access": False, "access_tier": "free"}

    # Device activation context
    is_device_activated = None
    device_block_reason = None
    calling_device_id: uuid.UUID | None = None
    if installation_id:
        device = db.query(Device).filter(
            Device.user_id == user_id,
            Device.installation_id == installation_id,
            Device.is_active == True,  # noqa: E712
        ).first()
        is_device_activated = device is not None
        if device is not None:
            calling_device_id = device.id
        if not is_device_activated:
            active_count = db.query(Device).filter(
                Device.user_id == user_id,
                Device.is_active == True,  # noqa: E712
            ).count()
            from app.config import settings as _s
            effective_cap = user.device_cap_override or _s.MAX_DEVICES_PER_ACCOUNT
            if active_count >= effective_cap:
                device_block_reason = "device_limit_reached"
            else:
                device_block_reason = "device_not_registered"

    # Pattern B device-aware gate. For verified users this is a no-op
    # (any active entitlement counts on any device). For unverified users
    # with a known calling device, an entitlement only counts when its
    # originating_device_id matches OR is NULL (grandfathered / server-
    # initiated grant). Computed once and reused below.
    def _entitlement_visible_on_device(ent: UserEntitlement) -> bool:
        if user.is_verified or calling_device_id is None:
            return True
        if ent.originating_device_id is None:
            return True
        return ent.originating_device_id == calling_device_id

    # `needs_verification` flag — true ONLY when the user has at least
    # one active entitlement that is hidden from the calling device
    # because of unverified state. Lets the client surface "verify your
    # email to use Lifetime on this device" without the client having
    # to compute it.
    def _has_hidden_entitlement() -> bool:
        if user.is_verified or calling_device_id is None:
            return False
        any_active = db.query(UserEntitlement).filter(
            UserEntitlement.user_id == user_id,
            UserEntitlement.status == "active",
            sa_or(
                UserEntitlement.expires_at.is_(None),
                UserEntitlement.expires_at > now,
            ),
        ).all()
        if not any_active:
            return False
        return not any(_entitlement_visible_on_device(e) for e in any_active)

    needs_verification = _has_hidden_entitlement()

    # Lifetime takes priority
    lifetime_ent = db.query(UserEntitlement).filter(
        UserEntitlement.user_id == user_id,
        UserEntitlement.entitlement_type == ENTITLEMENT_LIFETIME,
        UserEntitlement.status == "active",
    ).first()

    if lifetime_ent and _entitlement_visible_on_device(lifetime_ent):
        return {
            "has_premium_access": True,
            "access_tier": "premium_lifetime",
            "entitlement_type": "lifetime_access",
            "source": lifetime_ent.source,
            "granted_at": lifetime_ent.granted_at.isoformat(),
            "expires_at": None,
            "auto_renew": None,
            "is_device_activated": is_device_activated,
            "device_block_reason": device_block_reason,
            "needs_verification": needs_verification,
        }

    # Active subscription
    sub_candidates = db.query(UserEntitlement).filter(
        UserEntitlement.user_id == user_id,
        UserEntitlement.entitlement_type == ENTITLEMENT_SUBSCRIPTION,
        UserEntitlement.status == "active",
        UserEntitlement.expires_at > now,
    ).order_by(UserEntitlement.expires_at.desc()).all()
    sub_ent = next((s for s in sub_candidates if _entitlement_visible_on_device(s)), None)

    if sub_ent:
        return {
            "has_premium_access": True,
            "access_tier": "premium_subscription",
            "entitlement_type": "subscription_monthly",
            "source": sub_ent.source,
            "granted_at": sub_ent.granted_at.isoformat(),
            "expires_at": sub_ent.expires_at.isoformat() if sub_ent.expires_at else None,
            "auto_renew": sub_ent.auto_renew,
            "is_device_activated": is_device_activated,
            "device_block_reason": device_block_reason,
            "needs_verification": needs_verification,
        }

    return {
        "has_premium_access": False,
        "access_tier": "free",
        "entitlement_type": None,
        "source": None,
        "granted_at": None,
        "expires_at": None,
        "auto_renew": None,
        "is_device_activated": is_device_activated,
        "device_block_reason": device_block_reason,
        "needs_verification": needs_verification,
    }


# ── Stale device pruning ─────────────────────────────────────────────────

def prune_stale_devices(
    db: Session,
    stale_days: int = 90,
    dry_run: bool = True,
) -> list[dict]:
    """Revoke devices not seen in stale_days. Returns list of affected devices.

    Only revokes devices that have not sent a heartbeat or been seen
    in the specified period. Does not touch devices for users who have
    fewer than MAX_DEVICES active (no urgency to free slots).
    """
    cutoff = datetime.now(timezone.utc) - timedelta(days=stale_days)
    stale = db.query(Device).filter(
        Device.is_active == True,  # noqa: E712
        Device.last_seen_at < cutoff,
    ).all()

    results = []
    now = datetime.now(timezone.utc)
    for d in stale:
        entry = {
            "device_id": str(d.id),
            "user_id": str(d.user_id),
            "platform": d.platform,
            "last_seen_at": d.last_seen_at.isoformat(),
            "days_stale": (now - d.last_seen_at).days,
        }
        if not dry_run:
            d.is_active = False
            d.revoked_at = now
            entry["action"] = "revoked"
            _log.info("STALE_DEVICE_PRUNED device=%s user=%s last_seen=%s",
                      d.id, d.user_id, d.last_seen_at.isoformat())
        else:
            entry["action"] = "would_revoke"
        results.append(entry)

    if not dry_run:
        db.commit()

    return results


# ── Product classification ────────────────────────────────────────────────

def classify_paddle_product(price_id: str) -> str | None:
    """Returns 'lifetime', 'subscription', or None."""
    if settings.PADDLE_PRICE_ID and price_id == settings.PADDLE_PRICE_ID:
        return "lifetime"
    if settings.PADDLE_SUBSCRIPTION_PRICE_ID and price_id == settings.PADDLE_SUBSCRIPTION_PRICE_ID:
        return "subscription"
    return None


def classify_google_play_product(product_id: str) -> str | None:
    """Returns 'lifetime', 'subscription', or None."""
    if settings.google_play_lifetime_id and product_id == settings.google_play_lifetime_id:
        return "lifetime"
    if settings.GOOGLE_PLAY_SUBSCRIPTION_ID and product_id == settings.GOOGLE_PLAY_SUBSCRIPTION_ID:
        return "subscription"
    return None


def classify_amazon_product(product_id: str) -> str | None:
    """Returns 'lifetime', 'subscription', or None."""
    if settings.amazon_lifetime_id and product_id == settings.amazon_lifetime_id:
        return "lifetime"
    if product_id in (settings.AMAZON_SUBSCRIPTION_PRODUCT_ID, settings.AMAZON_MONTHLY_PRODUCT_ID):
        return "subscription"
    return None


# ── Purchase intent (Paddle web checkout) ─────────────────────────────────

def create_purchase_intent(
    db: Session,
    user_id: uuid.UUID,
    product_type: str = "lifetime",
    ttl_minutes: int = 60,
) -> PurchaseIntent:
    """Create a server-side purchase intent for safe checkout binding."""
    if product_type == "subscription":
        price_id = settings.PADDLE_SUBSCRIPTION_PRICE_ID
    else:
        price_id = settings.PADDLE_PRICE_ID

    intent = PurchaseIntent(
        user_id=user_id,
        product_id=settings.PADDLE_PRODUCT_ID or "",
        price_id=price_id or "",
        expires_at=datetime.now(timezone.utc) + timedelta(minutes=ttl_minutes),
    )
    db.add(intent)
    db.flush()
    return intent


def resolve_purchase_intent(
    db: Session,
    intent_id_str: str,
) -> PurchaseIntent | None:
    """Look up and validate a purchase intent. Returns None if invalid/expired."""
    try:
        intent_id = uuid.UUID(intent_id_str)
    except (ValueError, TypeError):
        return None

    intent = db.query(PurchaseIntent).filter(PurchaseIntent.id == intent_id).first()
    if not intent:
        return None
    if intent.status != "pending":
        return None
    if intent.expires_at < datetime.now(timezone.utc):
        intent.status = "expired"
        return None

    return intent
