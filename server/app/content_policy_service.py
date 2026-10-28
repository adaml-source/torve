"""
Content policy service: manages user policy state and applies filtering.

Key hardening decisions:
- Policy mode is resolved per-request from X-Torve-Channel header, NOT stored on account.
  This prevents a desktop client from switching the account to open mode for Google Play.
- Null/unknown classifications fail closed (treated as REVIEW_REQUIRED).
- REVIEW_REQUIRED fails closed in ALL modes until manually allowlisted.
- policy_state_version increments on every policy-relevant change for cache invalidation.
"""
import logging
import uuid
from datetime import date, datetime, timezone

from fastapi import Request
from sqlalchemy.orm import Session

from app.content_policy import (
    CHANNEL_HEADER,
    CURRENT_POLICY_VERSION,
    DEFAULT_CHANNEL,
    VALID_CHANNELS,
    AccessContext,
    AddonClassification,
    AgeBand,
    ContentClassification,
    ContentPolicyMode,
    PolicyDecision,
    classify_addon,
    classify_text,
    decide,
)
from app.models import ContentOverride, UserContentPolicy

_log = logging.getLogger(__name__)


# ── Channel resolution ──────────────────────────────────────────────────


def resolve_channel(request: Request | None = None, header_value: str | None = None) -> ContentPolicyMode:
    """Resolve content policy mode from the request's X-Torve-Channel header.

    Falls back to GOOGLE_PLAY_SAFE_DEFAULT (fail safe) if header is missing
    or unrecognized. This is the ONLY way policy mode is determined — it is
    never stored on the user account.
    """
    raw = header_value
    if raw is None and request is not None:
        raw = request.headers.get(CHANNEL_HEADER)
    if raw and raw.strip().lower() in VALID_CHANNELS:
        channel = raw.strip().lower()
    else:
        channel = DEFAULT_CHANNEL
    return ContentPolicyMode(channel) if channel == "open" else ContentPolicyMode.GOOGLE_PLAY_SAFE_DEFAULT


# ── User policy state ───────────────────────────────────────────────────


def get_or_create_policy(db: Session, user_id: uuid.UUID) -> UserContentPolicy:
    """Get or create the user's content policy record."""
    row = db.query(UserContentPolicy).filter(
        UserContentPolicy.user_id == user_id,
    ).first()
    if not row:
        row = UserContentPolicy(user_id=user_id)
        db.add(row)
        db.flush()
    return row


def get_policy_state(
    db: Session,
    user_id: uuid.UUID,
    policy_mode: ContentPolicyMode = ContentPolicyMode.GOOGLE_PLAY_SAFE_DEFAULT,
) -> dict:
    """Return the minimal policy state contract for the client.

    policy_mode is resolved per-request and passed in — not read from the DB.
    """
    policy = get_or_create_policy(db, user_id)
    return {
        "content_policy_mode": policy_mode.value,
        "age_band": policy.age_band,
        "adult_eligible": policy.adult_eligible,
        "sensitive_material_enabled": policy.sensitive_material_enabled,
        "sensitive_material_policy_version": policy.sensitive_material_policy_version,
        "can_enable_sensitive_material": policy.adult_eligible and policy.age_band == AgeBand.ADULT.value,
        "current_policy_version": CURRENT_POLICY_VERSION,
        "policy_state_version": policy.policy_state_version,
    }


def _bump_version(policy: UserContentPolicy) -> None:
    """Increment the monotonic policy state version for cache invalidation."""
    policy.policy_state_version = (policy.policy_state_version or 0) + 1


def set_date_of_birth(
    db: Session, user_id: uuid.UUID, dob: date,
) -> UserContentPolicy:
    """Set date of birth and compute age band."""
    policy = get_or_create_policy(db, user_id)
    policy.date_of_birth = dob
    policy.age_band = _compute_age_band(dob)
    policy.adult_eligible = policy.age_band == AgeBand.ADULT.value
    # If they become non-adult, disable sensitive material
    if not policy.adult_eligible:
        policy.sensitive_material_enabled = False
        policy.sensitive_material_enabled_at = None
        policy.sensitive_material_policy_version = None
    _bump_version(policy)
    db.flush()
    _log.info("POLICY_DOB_SET user=%s age_band=%s adult=%s v=%d",
              user_id, policy.age_band, policy.adult_eligible, policy.policy_state_version)
    return policy


def enable_sensitive_material(
    db: Session,
    user_id: uuid.UUID,
    policy_version: str,
) -> UserContentPolicy:
    """Enable sensitive material access. Requires adult eligibility."""
    policy = get_or_create_policy(db, user_id)

    if not policy.adult_eligible or policy.age_band != AgeBand.ADULT.value:
        raise ValueError("User is not adult-eligible")

    now = datetime.now(timezone.utc)
    policy.sensitive_material_enabled = True
    policy.sensitive_material_enabled_at = now
    policy.sensitive_material_policy_version = policy_version
    _bump_version(policy)
    db.flush()
    _log.info("POLICY_SENSITIVE_ENABLED user=%s version=%s v=%d",
              user_id, policy_version, policy.policy_state_version)
    return policy


def disable_sensitive_material(
    db: Session, user_id: uuid.UUID,
) -> UserContentPolicy:
    """Disable sensitive material access."""
    policy = get_or_create_policy(db, user_id)
    policy.sensitive_material_enabled = False
    _bump_version(policy)
    db.flush()
    _log.info("POLICY_SENSITIVE_DISABLED user=%s v=%d", user_id, policy.policy_state_version)
    return policy


# ── Age computation ─────────────────────────────────────────────────────


def _compute_age_band(dob: date) -> str:
    """Compute age band from date of birth."""
    today = date.today()
    age = today.year - dob.year - ((today.month, today.day) < (dob.month, dob.day))
    if age >= 18:
        return AgeBand.ADULT.value
    return AgeBand.UNDER_18.value


# ── Override management ─────────────────────────────────────────────────


def get_override_sets(db: Session) -> tuple[frozenset[str], frozenset[str], frozenset[str], frozenset[str]]:
    """Load all overrides into four sets: content_allow, content_deny, addon_allow, addon_deny."""
    rows = db.query(ContentOverride).all()
    content_allow: set[str] = set()
    content_deny: set[str] = set()
    addon_allow: set[str] = set()
    addon_deny: set[str] = set()
    for r in rows:
        if r.override_type == "content":
            if r.action == "allow":
                content_allow.add(r.external_key)
            elif r.action == "deny":
                content_deny.add(r.external_key)
        elif r.override_type == "addon":
            if r.action == "allow":
                addon_allow.add(r.external_key)
            elif r.action == "deny":
                addon_deny.add(r.external_key)
    return frozenset(content_allow), frozenset(content_deny), frozenset(addon_allow), frozenset(addon_deny)


def upsert_override(
    db: Session,
    *,
    override_type: str,
    external_key: str,
    action: str,
    note: str | None = None,
    updated_by: str | None = None,
) -> ContentOverride:
    """Create or update a manual override."""
    existing = db.query(ContentOverride).filter(
        ContentOverride.override_type == override_type,
        ContentOverride.external_key == external_key,
    ).first()
    now = datetime.now(timezone.utc)
    if existing:
        existing.action = action
        existing.note = note
        existing.updated_by = updated_by
        existing.updated_at = now
        db.flush()
        return existing
    row = ContentOverride(
        override_type=override_type,
        external_key=external_key,
        action=action,
        note=note,
        updated_by=updated_by,
    )
    db.add(row)
    db.flush()
    return row


# ── Filtering helpers ───────────────────────────────────────────────────


def should_include_item(
    db: Session,
    *,
    user_id: uuid.UUID,
    text: str | None,
    policy_mode: ContentPolicyMode,
    external_id: str | None = None,
    context: AccessContext = AccessContext.ACCELERATION_INVENTORY,
    cached_classification: str | None = None,
) -> tuple[PolicyDecision, ContentClassification]:
    """Decide whether an item should be included for this user+context.

    Returns (decision, classification) so callers can either filter or redact.
    Null cached_classification fails closed (treated as REVIEW_REQUIRED).
    """
    policy = get_or_create_policy(db, user_id)
    age_band = AgeBand(policy.age_band)

    # Use cached classification or classify now
    if cached_classification:
        try:
            classification = ContentClassification(cached_classification)
        except ValueError:
            classification = ContentClassification.REVIEW_REQUIRED  # Unknown = fail closed
    else:
        ca, cd, _, _ = get_override_sets(db)
        classification = classify_text(text or "", allowlist_ids=ca, denylist_ids=cd, external_id=external_id)

    decision = decide(
        policy_mode=policy_mode,
        classification=classification,
        age_band=age_band,
        sensitive_enabled=policy.sensitive_material_enabled,
        context=context,
    )
    return decision, classification


def classify_and_decide_addon(
    db: Session,
    *,
    user_id: uuid.UUID,
    policy_mode: ContentPolicyMode,
    manifest_url: str | None,
    name: str | None,
    description: str | None,
    addon_id: str | None,
    context: AccessContext = AccessContext.DEFAULT_DISCOVERY,
) -> tuple[PolicyDecision, AddonClassification]:
    """Classify an addon and decide whether the user can access it."""
    policy = get_or_create_policy(db, user_id)
    _, _, addon_allow, addon_deny = get_override_sets(db)

    classification = classify_addon(
        manifest_url=manifest_url, name=name, description=description,
        addon_id=addon_id, allowlist_urls=addon_allow, denylist_urls=addon_deny,
    )

    decision = decide(
        policy_mode=policy_mode,
        classification=classification,
        age_band=AgeBand(policy.age_band),
        sensitive_enabled=policy.sensitive_material_enabled,
        context=context,
    )
    return decision, classification


def get_addon_policy_flags(
    db: Session,
    *,
    user_id: uuid.UUID,
    policy_mode: ContentPolicyMode,
    addon_classification: str | None,
) -> dict:
    """Return addon policy flags for the client without exposing moderation internals.

    Returns {installable, shelf_eligible, catalog_queryable}.
    """
    policy = get_or_create_policy(db, user_id)

    # Null classification fails closed
    if not addon_classification:
        cls: AddonClassification | ContentClassification = AddonClassification.REVIEW_REQUIRED
    else:
        try:
            cls = AddonClassification(addon_classification)
        except ValueError:
            cls = AddonClassification.REVIEW_REQUIRED

    decision = decide(
        policy_mode=policy_mode,
        classification=cls,
        age_band=AgeBand(policy.age_band),
        sensitive_enabled=policy.sensitive_material_enabled,
        context=AccessContext.DEFAULT_DISCOVERY,
    )

    blocked = decision == PolicyDecision.BLOCK
    hidden = decision == PolicyDecision.HIDE

    return {
        "installable": not blocked,
        "shelf_eligible": not blocked and not hidden,
        "catalog_queryable": not blocked and not hidden,
    }
