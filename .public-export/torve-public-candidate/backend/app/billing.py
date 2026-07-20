"""
Billing/history service and free-software access resolution.

Torve product access is free by default for authenticated active accounts.
Payment, rebate, beta, and lifetime records are kept only for historical and
support compatibility; they are not access-controlling sources of truth.
"""
import logging
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.beta_campaign import beta_free_access_ended
from app.config import settings
from app.models import BetaAccessGrant, LifetimeGrantRecord, PurchaseIntent, User, UserEntitlement, WebPayment

_log = logging.getLogger(__name__)

# Entitlement type constants
ENTITLEMENT_LIFETIME = "lifetime_access"
ENTITLEMENT_SUBSCRIPTION = "subscription_monthly"

# Source constants
SOURCE_PADDLE = "paddle_web"
SOURCE_STRIPE = "stripe"
SOURCE_GOOGLE_PLAY = "google_play"
SOURCE_AMAZON = "amazon"
SOURCE_ADMIN = "admin_grant"
SOURCE_REBATE = "rebate_code"

# Beta access is stored separately from paid/store entitlements and is now
# informational/community state only.
BETA_ENTITLEMENT_TYPE = "beta_access"
BETA_ACCESS_TIER = "beta"
BETA_SOURCE_DISCORD = "discord_beta"
BETA_ACCESS_SOURCES = {BETA_SOURCE_DISCORD}
FREE_SOFTWARE_ACCESS_TIER = "free"
FREE_SOFTWARE_MESSAGE_SOURCE = "free_software"


@dataclass(frozen=True)
class BetaAccessResolution:
    active: bool
    source: str | None
    expires_at: datetime | None
    status: str
    active_grant: BetaAccessGrant | None
    ignored_reasons: list[str]

    def response(self) -> dict:
        return {
            "active": self.active,
            "source": self.source,
            "expires_at": self.expires_at.isoformat() if self.expires_at else None,
            "status": self.status,
        }


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
    """Record a legacy entitlement row without controlling product access.

    This helper remains for historical data paths and old tests. The stored
    row is not an access source of truth in the free-software model.
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

    # Persistent email-keyed historical ledger for lifetime rows. It survives
    # account deletion for reconciliation only; restore no longer grants access.
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
    """Legacy restore hook retained for callers; no longer grants access."""
    _log.info("LIFETIME_RESTORE_SKIPPED_FREE_SOFTWARE user=%s", user.id)
    return None


def expire_subscription(
    db: Session,
    source: str,
    source_ref: str,
) -> UserEntitlement | None:
    """Mark a historical subscription row as expired."""
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
    """Refresh legacy booleans for the free-software access model."""
    now = datetime.now(timezone.utc)
    user = db.query(User).filter(User.id == user_id).first()
    if user:
        user.has_lifetime_access = False
        user.has_premium_access = bool(user.is_active)
        user.updated_at = now


def check_premium_active(
    db: Session,
    user_id: uuid.UUID,
    *,
    requesting_device_id: uuid.UUID | None = None,
) -> bool:
    """Legacy compatibility check.

    Product access is no longer paid-entitlement based. Authenticated active
    accounts have the default product access regardless of subscriptions,
    purchases, trials, rebates, donations, or historical entitlements.
    """
    user = db.query(User).filter(User.id == user_id).first()
    return bool(user and user.is_active)


def check_account_access_active(
    db: Session,
    user_id: uuid.UUID,
    *,
    requesting_device_id: uuid.UUID | None = None,
) -> bool:
    """Return whether an authenticated account may use product features."""
    return check_premium_active(db, user_id, requesting_device_id=requesting_device_id)


def resolve_access_state(
    db: Session,
    user_id: uuid.UUID,
    installation_id: str | None = None,
) -> dict:
    """Resolve free/default product access for /me/access-state."""
    from app.models import Device

    now = datetime.now(timezone.utc)
    beta_state = _resolve_beta_access(db, user_id, now=now)
    beta_access = beta_state.response()
    user = db.query(User).filter(User.id == user_id).first()
    if not user:
        state = {
            "has_premium_access": False,
            "access_tier": FREE_SOFTWARE_ACCESS_TIER,
            "entitlement_type": None,
            "source": None,
            "granted_at": None,
            "expires_at": None,
            "auto_renew": None,
            "is_device_activated": None,
            "device_block_reason": None,
            "needs_verification": False,
            "beta_access": beta_access,
        }
        _log_access_state_resolution(
            user_id=user_id,
            state=state,
            beta_state=beta_state,
            is_device_activated=None,
            calling_device_id=None,
            ignored_reasons=["user_not_found"],
        )
        return state

    active_device_count = db.query(Device).filter(
        Device.user_id == user_id,
        Device.is_active == True,  # noqa: E712
    ).count()
    device_cap_state = {
        "device_limit": None,
        "device_cap_override": None,
        "active_device_count": active_device_count,
    }

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
            device_block_reason = "device_not_registered"

    state = {
        "has_premium_access": bool(user.is_active),
        "access_tier": FREE_SOFTWARE_ACCESS_TIER,
        "entitlement_type": None,
        "source": None,
        "granted_at": None,
        "expires_at": None,
        "auto_renew": None,
        "is_device_activated": is_device_activated,
        "device_block_reason": device_block_reason,
        "needs_verification": False,
        "beta_access": beta_access,
        **device_cap_state,
    }
    _log_access_state_resolution(
        user_id=user_id,
        state=state,
        beta_state=beta_state,
        is_device_activated=is_device_activated,
        calling_device_id=calling_device_id,
        ignored_reasons=["free_software_default_access"],
    )
    return state


def _resolve_beta_access(
    db: Session,
    user_id: uuid.UUID,
    *,
    now: datetime,
) -> BetaAccessResolution:
    grants = db.query(BetaAccessGrant).filter(
        BetaAccessGrant.torve_user_id == user_id,
        BetaAccessGrant.source.in_(BETA_ACCESS_SOURCES),
    ).order_by(BetaAccessGrant.created_at.desc()).all()
    if not grants:
        return BetaAccessResolution(
            active=False,
            source=None,
            expires_at=None,
            status="none",
            active_grant=None,
            ignored_reasons=[],
        )

    ignored_reasons: list[str] = []
    active_grant: BetaAccessGrant | None = None
    campaign_ended = beta_free_access_ended(now=now)
    for grant in grants:
        reason = _beta_grant_inactive_reason(grant, now=now, campaign_ended=campaign_ended)
        if reason is None:
            active_grant = grant
            break
        ignored_reasons.append(f"{grant.source}:{reason}")

    grant = active_grant or grants[0]
    active = active_grant is not None
    status = (
        "expired"
        if grant.status == "active" and (grant.expires_at <= now or campaign_ended)
        else grant.status
    )
    return BetaAccessResolution(
        active=active,
        source=grant.source,
        expires_at=grant.expires_at,
        status=status,
        active_grant=active_grant,
        ignored_reasons=ignored_reasons,
    )


def _beta_grant_inactive_reason(
    grant: BetaAccessGrant,
    *,
    now: datetime,
    campaign_ended: bool,
) -> str | None:
    if grant.source not in BETA_ACCESS_SOURCES:
        return "unsupported_source"
    if grant.status == "revoked" or grant.revoked_at is not None:
        return "revoked"
    if grant.status != "active":
        return f"status_{grant.status}"
    if grant.starts_at and grant.starts_at > now:
        return "not_started"
    if grant.expires_at <= now:
        return "expired"
    if campaign_ended:
        return "campaign_ended"
    return None


def _log_access_state_resolution(
    *,
    user_id: uuid.UUID,
    state: dict,
    beta_state: BetaAccessResolution,
    is_device_activated: bool | None,
    calling_device_id: uuid.UUID | None,
    ignored_reasons: list[str],
) -> None:
    sources_considered: list[str] = []
    source = state.get("source")
    if source:
        sources_considered.append(str(source))
    if beta_state.source and beta_state.source not in sources_considered:
        sources_considered.append(beta_state.source)

    all_ignored = [*ignored_reasons, *beta_state.ignored_reasons]
    _log.info(
        "ACCESS_STATE_RESOLVED user=%s access_tier=%s entitled=%s "
        "sources=%s ignored=%s device_active=%s device=%s device_block_reason=%s",
        user_id,
        state.get("access_tier"),
        state.get("has_premium_access"),
        sources_considered,
        all_ignored,
        is_device_activated,
        calling_device_id,
        state.get("device_block_reason"),
    )


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


def classify_stripe_product(price_id: str) -> str | None:
    """Returns 'lifetime', 'subscription', or None for configured Stripe prices."""
    if settings.stripe_lifetime_price_id and price_id == settings.stripe_lifetime_price_id:
        return "lifetime"
    if settings.stripe_monthly_price_id and price_id == settings.stripe_monthly_price_id:
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
