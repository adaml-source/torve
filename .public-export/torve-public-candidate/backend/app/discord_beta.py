"""Discord beta application flow.

This module keeps beta access separate from paid premium entitlements. A
Discord role is only a community marker; app access is decided by
beta_access_grants rows returned through server-authoritative endpoints.
"""
from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import logging
import re
import secrets
import uuid
from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any

import httpx
from sqlalchemy import or_
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.beta_campaign import (
    beta_application_blocked_reason,
    beta_free_access_end_at_display,
    beta_free_access_ended,
    beta_signup_close_at,
    beta_signup_close_at_display,
    capped_beta_grant_expires_at,
)
from app.config import settings
from app.database import SessionLocal
from app.models import (
    BetaAccessGrant,
    DiscordAccountLink,
    DiscordBetaApplication,
    DiscordBetaApplicationDraft,
    DiscordBetaLinkCode,
    User,
)

_log = logging.getLogger(__name__)

BETA_SOURCE_DISCORD = "discord_beta"
EPHEMERAL_MESSAGE_FLAG = 64
APPLICATION_SUBMITTED_MESSAGE = "Your beta application was submitted. Torve Team will review it."
APPLICATION_BUTTON_CUSTOM_ID = "torve_beta_apply"
APPLICATION_MODAL_CUSTOM_ID = "torve_beta_application_modal"
DEVICE_SELECT_PREFIX = "torve_beta_devices:"
FEATURE_SELECT_PREFIX = "torve_beta_features:"
STABILITY_SELECT_PREFIX = "torve_beta_stability:"
APPROVE_BUTTON_PREFIX = "torve_beta_approve:"
REJECT_BUTTON_PREFIX = "torve_beta_reject:"
REJECT_MODAL_PREFIX = "torve_beta_reject_modal:"

FIELD_LINK_CODE = "torve_beta_link_code"
FIELD_DEVICES = "torve_beta_devices"
FIELD_INTEGRATIONS = "torve_beta_integrations"
FIELD_MOTIVATION = "torve_beta_motivation"
FIELD_CONFIRMATION = "torve_beta_confirmation"
FIELD_REJECTION_REASON = "torve_beta_rejection_reason"

DEVICE_OPTIONS: tuple[tuple[str, str], ...] = (
    ("android_tv", "Android TV"),
    ("fire_tv", "Fire TV"),
    ("windows", "Windows"),
    ("android_mobile", "Android Mobile"),
    ("ios", "iPhone / iPad"),
    ("google_tv", "Google TV"),
    ("nvidia_shield", "NVIDIA Shield"),
    ("other", "Other"),
)
FEATURE_OPTIONS: tuple[tuple[str, str], ...] = (
    ("playback", "Playback"),
    ("search", "Search"),
    ("library", "Library"),
    ("watchlist_favorites", "Watchlist / Favorites"),
    ("iptv_epg", "IPTV / EPG"),
    ("recordings", "Recordings"),
    ("stremio_addons", "Stremio Addons"),
    ("usenet", "Usenet"),
    ("debrid", "Debrid"),
    ("trakt_calendar", "Trakt / Calendar"),
    ("desktop_app", "Desktop App"),
    ("billing_premium", "Billing / Premium"),
    ("onboarding_login", "Onboarding / Login"),
    ("ui_navigation", "UI / Navigation"),
)
STABILITY_OPTIONS: tuple[tuple[str, str], ...] = (
    ("unstable_ok", "I can test unstable builds"),
    ("mostly_stable", "I prefer mostly stable beta builds"),
    ("release_candidate", "I only want release-candidate builds"),
)
DEVICE_LABELS = dict(DEVICE_OPTIONS)
FEATURE_LABELS = dict(FEATURE_OPTIONS)
STABILITY_LABELS = dict(STABILITY_OPTIONS)

_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
_CODE_LENGTH = 6
_CODE_TTL_MINUTES = 15
_DISCORD_API_BASE = "https://discord.com/api/v10"
_DISCORD_TIMEOUT = httpx.Timeout(connect=2.0, read=3.0, write=2.0, pool=2.0)
_MAX_ATTEMPTS = 2
_SECRETISH_RE = re.compile(
    r"(?i)\b(?:authorization|bearer|password|passwd|pwd|secret|token|api[_-]?key|"
    r"client[_-]?secret|private[_ -]?key|signature|webhook|signed[_ -]?url)\b"
)
_SECRET_PAIR_RE = re.compile(
    r"(?i)\b((?:access[_-]?token|refresh[_-]?token|id[_-]?token|api[_-]?key|"
    r"client[_-]?secret|password|secret|authorization|auth|token|key)\s*[=:]\s*)\S+"
)
_URL_RE = re.compile(r"https?://\S+", re.IGNORECASE)
_PRIVATE_PATH_RE = re.compile(r"(?:/opt/|/var/|/home/|/root/|\.env\b)\S*", re.IGNORECASE)
_SPACE_RE = re.compile(r"\s+")
_SNOWFLAKE_RE = re.compile(r"^\d{5,30}$")
_FLOW_TTL_SECONDS = 15 * 60
_FLOW_EXPIRED_MESSAGE = "Your beta application session expired. Please click Apply for Beta again."


class BetaFlowError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(code)
        self.code = code
        self.message = message


@dataclass(frozen=True)
class BetaLinkCodeResult:
    code: str
    expires_at: datetime


@dataclass(frozen=True)
class DiscordUserIdentity:
    user_id: str
    username: str
    discriminator_or_global_name: str | None = None


@dataclass(frozen=True)
class BetaSubmitResult:
    application: DiscordBetaApplication
    action: str


@dataclass(frozen=True)
class BetaApprovalResult:
    application: DiscordBetaApplication
    grant: BetaAccessGrant | None
    action: str


@dataclass(frozen=True)
class DiscordApiResult:
    ok: bool
    action: str
    status_code: int | None = None
    data: dict[str, Any] | None = None
    message_id: str | None = None


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


def as_utc(dt: datetime) -> datetime:
    if dt.tzinfo is None:
        return dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc)


def normalize_link_code(code: str) -> str:
    cleaned = _SPACE_RE.sub("", str(code or "")).upper()
    if cleaned.startswith("BETA") and not cleaned.startswith("BETA-"):
        cleaned = f"BETA-{cleaned[4:]}"
    return cleaned


def code_hash(code: str) -> str:
    normalized = normalize_link_code(code)
    key = (settings.REBATE_CODE_HMAC_SECRET or settings.JWT_SECRET).encode("utf-8")
    return hmac.new(key, normalized.encode("utf-8"), hashlib.sha256).hexdigest()


def beta_flow_error_status(code: str) -> int:
    if code in {
        "email_not_verified",
        "account_unavailable",
        "already_active",
        "beta_signup_closed",
        "beta_access_ended",
        "beta_unavailable",
    }:
        return 403
    if code == "user_not_found":
        return 404
    return 400


def beta_campaign_error(reason: str) -> BetaFlowError:
    if reason == "beta_access_ended":
        return BetaFlowError("beta_access_ended", "Beta access has ended.")
    return BetaFlowError("beta_signup_closed", "Beta signup is closed.")


def ensure_beta_link_code_eligible(db: Session, torve_user_id: uuid.UUID) -> User:
    user = db.query(User).filter(User.id == torve_user_id).first()
    if not user:
        raise BetaFlowError("user_not_found", "Account not found.")
    if not user.is_active:
        raise BetaFlowError("account_unavailable", "This account cannot apply for beta access.")
    if not user.is_verified:
        raise BetaFlowError(
            "email_not_verified",
            "Verify your email address before applying for beta access.",
        )
    if get_active_beta_grant(db, torve_user_id):
        raise BetaFlowError("already_active", "This Torve account already has active beta access.")
    return user


def beta_application_eligibility(db: Session, torve_user_id: uuid.UUID) -> tuple[bool, str | None]:
    user = db.query(User).filter(User.id == torve_user_id).first()
    if not user or not user.is_active:
        return False, "beta_unavailable"
    if not user.is_verified:
        return False, "email_not_verified"
    if get_active_beta_grant(db, torve_user_id):
        return False, "already_active"
    latest_application = db.query(DiscordBetaApplication).filter(
        DiscordBetaApplication.torve_user_id == torve_user_id,
    ).order_by(DiscordBetaApplication.created_at.desc()).first()
    if latest_application:
        if latest_application.status in {"submitted", "rejected", "approved"}:
            return False, "beta_unavailable"
    return True, None


def create_beta_link_code(db: Session, torve_user_id: uuid.UUID) -> BetaLinkCodeResult:
    now = utcnow()
    ensure_beta_link_code_eligible(db, torve_user_id)
    db.query(DiscordBetaLinkCode).filter(
        DiscordBetaLinkCode.torve_user_id == torve_user_id,
        DiscordBetaLinkCode.consumed_at.is_(None),
    ).update(
        {
            "consumed_at": now,
            "updated_at": now,
        },
        synchronize_session=False,
    )

    expires_at = now + timedelta(minutes=_CODE_TTL_MINUTES)
    for _attempt in range(8):
        code = "BETA-" + "".join(secrets.choice(_CODE_ALPHABET) for _ in range(_CODE_LENGTH))
        row = DiscordBetaLinkCode(
            torve_user_id=torve_user_id,
            code_hash=code_hash(code),
            expires_at=expires_at,
            created_at=now,
            updated_at=now,
        )
        db.add(row)
        try:
            db.flush()
            return BetaLinkCodeResult(code=code, expires_at=expires_at)
        except IntegrityError:
            db.rollback()
    raise BetaFlowError("link_code_unavailable", "Please try again in a moment.")


def consume_beta_link_code(
    db: Session,
    *,
    code: str,
    discord_user_id: str,
) -> DiscordBetaLinkCode:
    normalized = normalize_link_code(code)
    if not re.match(r"^BETA-[A-Z2-9]{6}$", normalized):
        raise BetaFlowError("invalid_link_code", "That beta link code is invalid or expired.")

    now = utcnow()
    row = (
        db.query(DiscordBetaLinkCode)
        .filter(
            DiscordBetaLinkCode.code_hash == code_hash(normalized),
            DiscordBetaLinkCode.consumed_at.is_(None),
        )
        .with_for_update()
        .first()
    )
    if not row or row.expires_at <= now:
        raise BetaFlowError("invalid_link_code", "That beta link code is invalid or expired.")

    ensure_beta_link_code_eligible(db, row.torve_user_id)
    row.consumed_at = now
    row.consumed_by_discord_user_id = clean_snowflake(discord_user_id)
    row.updated_at = now
    db.flush()
    return row


def submit_beta_application(
    db: Session,
    *,
    link_code: str,
    discord_user: DiscordUserIdentity,
    devices: list[str] | None = None,
    integrations: list[str] | None = None,
    stability_preference: str | None = None,
    motivation: str = "",
    confirmation_text: str,
) -> BetaSubmitResult:
    discord_user_id = clean_snowflake(discord_user.user_id)
    if not discord_user_id:
        raise BetaFlowError("invalid_discord_user", "Discord user information was not available.")
    if not accepted_confirmation(confirmation_text):
        raise BetaFlowError(
            "terms_not_accepted",
            "Please confirm that you understand the beta safety rules.",
        )

    sanitized_devices = validate_device_values(devices or [])
    sanitized_integrations = validate_feature_values(integrations or [])
    sanitized_stability = validate_stability_value(stability_preference)
    sanitized_motivation = sanitize_application_text(motivation, max_length=1000)
    if not sanitized_devices or not sanitized_integrations:
        raise BetaFlowError("missing_selections", "Please choose at least one device and one beta area.")
    validate_no_private_material(motivation)

    link_code_row = consume_beta_link_code(
        db,
        code=link_code,
        discord_user_id=discord_user_id,
    )
    torve_user_id = link_code_row.torve_user_id
    active_grant = get_active_beta_grant(db, torve_user_id)
    if active_grant:
        raise BetaFlowError("already_active", "This Torve account already has active beta access.")

    upsert_discord_account_link(
        db,
        torve_user_id=torve_user_id,
        discord_user=discord_user,
    )

    now = utcnow()
    application = (
        db.query(DiscordBetaApplication)
        .filter(
            DiscordBetaApplication.torve_user_id == torve_user_id,
            DiscordBetaApplication.discord_user_id == discord_user_id,
            DiscordBetaApplication.status == "submitted",
        )
        .order_by(DiscordBetaApplication.created_at.desc())
        .first()
    )
    action = "updated"
    if application is None:
        action = "created"
        application = DiscordBetaApplication(
            torve_user_id=torve_user_id,
            discord_user_id=discord_user_id,
            discord_username=sanitize_inline(discord_user.username, max_length=255) or "unknown",
            status="submitted",
            created_at=now,
        )
        db.add(application)

    application.discord_username = sanitize_inline(discord_user.username, max_length=255) or "unknown"
    application.devices_json = sanitized_devices
    application.integrations_json = sanitized_integrations
    application.stability_preference = sanitized_stability
    application.motivation = sanitized_motivation
    application.accepted_beta_terms = True
    application.accepted_no_credentials = True
    application.rejection_reason = None
    application.updated_at = now
    db.flush()
    return BetaSubmitResult(application=application, action=action)


def upsert_discord_account_link(
    db: Session,
    *,
    torve_user_id: uuid.UUID,
    discord_user: DiscordUserIdentity,
) -> DiscordAccountLink:
    now = utcnow()
    discord_user_id = clean_snowflake(discord_user.user_id)
    username = sanitize_inline(discord_user.username, max_length=255) or "unknown"
    global_name = sanitize_inline(discord_user.discriminator_or_global_name or "", max_length=255) or None

    active_links = db.query(DiscordAccountLink).filter(
        DiscordAccountLink.unlinked_at.is_(None),
        or_(
            DiscordAccountLink.torve_user_id == torve_user_id,
            DiscordAccountLink.discord_user_id == discord_user_id,
        ),
    ).all()
    matching = None
    for link in active_links:
        if link.torve_user_id == torve_user_id and link.discord_user_id == discord_user_id:
            matching = link
        else:
            link.unlinked_at = now
            link.updated_at = now

    if matching is None:
        matching = DiscordAccountLink(
            torve_user_id=torve_user_id,
            discord_user_id=discord_user_id,
            linked_at=now,
            created_at=now,
        )
        db.add(matching)

    matching.discord_username = username
    matching.discord_discriminator_or_global_name = global_name
    matching.unlinked_at = None
    matching.updated_at = now
    db.flush()
    return matching


def approve_beta_application(
    db: Session,
    *,
    application_id: uuid.UUID,
    reviewer_discord_user_id: str,
    grant_days: int | None = None,
) -> BetaApprovalResult:
    now = utcnow()
    application = get_application_or_error(db, application_id)
    if application.status == "approved":
        grant = get_latest_beta_grant(db, application.torve_user_id) if application.torve_user_id else None
        return BetaApprovalResult(application=application, grant=grant, action="already_approved")
    if application.status in {"rejected", "revoked", "expired"}:
        raise BetaFlowError("application_finalized", "This beta application has already been finalized.")
    if not application.torve_user_id:
        _log.warning(
            "DISCORD_BETA_GRANT_FAILED discord_user_hash=%s application=%s reason=missing_torve_link",
            hash_discord_user_id(application.discord_user_id),
            application.id,
        )
        raise BetaFlowError("missing_torve_link", "This beta application is not linked to a Torve account.")
    if not application.accepted_beta_terms or not application.accepted_no_credentials:
        raise BetaFlowError("terms_not_accepted", "The applicant did not accept the beta safety rules.")

    expire_due_grants_for_user(db, application.torve_user_id, now=now)
    active_grant = get_active_beta_grant(db, application.torve_user_id, now=now)
    application.staff_reviewer_discord_user_id = clean_snowflake(reviewer_discord_user_id) or None
    application.staff_reviewed_at = now
    application.updated_at = now
    if active_grant:
        application.status = "approved"
        db.flush()
        return BetaApprovalResult(application=application, grant=active_grant, action="already_active")

    free_premium_eligible = (
        as_utc(application.created_at) <= beta_signup_close_at()
        and not beta_free_access_ended(now=now)
    )
    if not free_premium_eligible:
        application.status = "approved"
        db.flush()
        return BetaApprovalResult(application=application, grant=None, action="approved_discord_only")

    days = grant_days if grant_days is not None else max(1, int(settings.TORVE_BETA_GRANT_DAYS or 30))
    expires_at = capped_beta_grant_expires_at(now=now, grant_days=days)
    if expires_at <= now:
        raise BetaFlowError("beta_access_ended", "Beta access has ended.")
    grant = BetaAccessGrant(
        torve_user_id=application.torve_user_id,
        discord_user_id=application.discord_user_id,
        source=BETA_SOURCE_DISCORD,
        status="active",
        starts_at=now,
        expires_at=expires_at,
        created_at=now,
        updated_at=now,
    )
    application.status = "approved"
    db.add(grant)
    db.flush()
    _log.info(
        "DISCORD_BETA_GRANT_PERSISTED discord_user_hash=%s app_user=%s entitlement=%s "
        "source=%s status=%s expires=%s",
        hash_discord_user_id(application.discord_user_id),
        application.torve_user_id,
        grant.id,
        grant.source,
        grant.status,
        grant.expires_at.isoformat(),
    )
    return BetaApprovalResult(application=application, grant=grant, action="granted")


def beta_auto_approve_allowed(db: Session, application: DiscordBetaApplication) -> bool:
    if not application.torve_user_id:
        return False
    if not application.accepted_beta_terms or not application.accepted_no_credentials:
        return False
    return not has_recent_beta_grant_abuse(
        db,
        torve_user_id=application.torve_user_id,
        discord_user_id=application.discord_user_id,
    )


def has_recent_beta_grant_abuse(
    db: Session,
    *,
    torve_user_id: uuid.UUID,
    discord_user_id: str,
    now: datetime | None = None,
) -> bool:
    now = now or utcnow()
    cutoff = now - timedelta(days=90)
    return db.query(BetaAccessGrant).filter(
        BetaAccessGrant.source == BETA_SOURCE_DISCORD,
        BetaAccessGrant.status == "revoked",
        BetaAccessGrant.updated_at >= cutoff,
        or_(
            BetaAccessGrant.torve_user_id == torve_user_id,
            BetaAccessGrant.discord_user_id == discord_user_id,
        ),
    ).count() > 0


def reject_beta_application(
    db: Session,
    *,
    application_id: uuid.UUID,
    reviewer_discord_user_id: str,
    reason: str | None = None,
) -> DiscordBetaApplication:
    now = utcnow()
    application = get_application_or_error(db, application_id)
    if application.status == "approved":
        raise BetaFlowError("already_approved", "Approved applications cannot be rejected here.")
    if application.status in {"rejected", "revoked", "expired"}:
        return application
    application.status = "rejected"
    application.staff_reviewer_discord_user_id = clean_snowflake(reviewer_discord_user_id) or None
    application.staff_reviewed_at = now
    application.rejection_reason = sanitize_application_text(reason or "", max_length=500) or None
    application.updated_at = now
    db.flush()
    return application


def revoke_beta_access(
    db: Session,
    *,
    torve_user_id: uuid.UUID,
    reason: str,
) -> list[BetaAccessGrant]:
    now = utcnow()
    sanitized_reason = sanitize_application_text(reason, max_length=300) or "manual revoke"
    grants = db.query(BetaAccessGrant).filter(
        BetaAccessGrant.torve_user_id == torve_user_id,
        BetaAccessGrant.source == BETA_SOURCE_DISCORD,
        BetaAccessGrant.status == "active",
    ).all()
    for grant in grants:
        grant.status = "revoked"
        grant.revoked_at = now
        grant.updated_at = now
    db.query(DiscordBetaApplication).filter(
        DiscordBetaApplication.torve_user_id == torve_user_id,
        DiscordBetaApplication.status.in_(("submitted", "approved")),
    ).update(
        {
            "status": "revoked",
            "rejection_reason": sanitized_reason,
            "updated_at": now,
        },
        synchronize_session=False,
    )
    db.flush()
    return grants


def expire_due_beta_grants(
    db: Session,
    *,
    discord: "DiscordBotClient | None" = None,
    now: datetime | None = None,
    torve_user_ids: list[uuid.UUID] | None = None,
) -> dict[str, int]:
    now = now or utcnow()
    discord = discord or DiscordBotClient.from_settings()
    expired_drafts = cleanup_expired_application_drafts(db, now=now)
    grant_due_filters = [
        BetaAccessGrant.expires_at <= now,
    ]
    if beta_free_access_ended(now=now):
        grant_due_filters.append(BetaAccessGrant.expires_at > now)
    grants = db.query(BetaAccessGrant).filter(
        BetaAccessGrant.source == BETA_SOURCE_DISCORD,
        BetaAccessGrant.status == "active",
        or_(*grant_due_filters),
    )
    if torve_user_ids is not None:
        grants = grants.filter(BetaAccessGrant.torve_user_id.in_(torve_user_ids))
    grants = grants.order_by(BetaAccessGrant.expires_at.asc()).all()

    expired = 0
    role_remove_attempted = 0
    role_remove_failed = 0
    for grant in grants:
        grant.status = "expired"
        grant.updated_at = now
        expired += 1
        db.query(DiscordBetaApplication).filter(
            DiscordBetaApplication.torve_user_id == grant.torve_user_id,
            DiscordBetaApplication.status == "approved",
        ).update(
            {
                "status": "expired",
                "updated_at": now,
            },
            synchronize_session=False,
        )
        db.flush()

        discord_user_id = grant.discord_user_id or linked_discord_user_id(db, grant.torve_user_id)
        if discord_user_id:
            role_remove_attempted += 1
            result = discord.remove_beta_role(discord_user_id)
            if not result.ok:
                role_remove_failed += 1
                _log.warning(
                    "Discord beta role removal failed user=%s action=%s status=%s",
                    discord_user_id,
                    result.action,
                    result.status_code,
                )
    db.flush()
    return {
        "expired": expired,
        "expired_drafts": expired_drafts,
        "role_remove_attempted": role_remove_attempted,
        "role_remove_failed": role_remove_failed,
    }


def expire_due_grants_for_user(db: Session, torve_user_id: uuid.UUID, *, now: datetime | None = None) -> int:
    now = now or utcnow()
    count = 0
    grant_due_filters = [BetaAccessGrant.expires_at <= now]
    if beta_free_access_ended(now=now):
        grant_due_filters.append(BetaAccessGrant.expires_at > now)
    grants = db.query(BetaAccessGrant).filter(
        BetaAccessGrant.torve_user_id == torve_user_id,
        BetaAccessGrant.source == BETA_SOURCE_DISCORD,
        BetaAccessGrant.status == "active",
        or_(*grant_due_filters),
    ).all()
    for grant in grants:
        grant.status = "expired"
        grant.updated_at = now
        db.query(DiscordBetaApplication).filter(
            DiscordBetaApplication.torve_user_id == grant.torve_user_id,
            DiscordBetaApplication.status == "approved",
        ).update(
            {
                "status": "expired",
                "updated_at": now,
            },
            synchronize_session=False,
        )
        count += 1
    if count:
        db.flush()
    return count


def get_active_beta_grant(
    db: Session,
    torve_user_id: uuid.UUID,
    *,
    now: datetime | None = None,
) -> BetaAccessGrant | None:
    now = now or utcnow()
    if beta_free_access_ended(now=now):
        return None
    return db.query(BetaAccessGrant).filter(
        BetaAccessGrant.torve_user_id == torve_user_id,
        BetaAccessGrant.source == BETA_SOURCE_DISCORD,
        BetaAccessGrant.status == "active",
        BetaAccessGrant.expires_at > now,
    ).order_by(BetaAccessGrant.expires_at.desc()).first()


def get_latest_beta_grant(db: Session, torve_user_id: uuid.UUID | None) -> BetaAccessGrant | None:
    if not torve_user_id:
        return None
    return db.query(BetaAccessGrant).filter(
        BetaAccessGrant.torve_user_id == torve_user_id,
        BetaAccessGrant.source == BETA_SOURCE_DISCORD,
    ).order_by(BetaAccessGrant.created_at.desc()).first()


def get_beta_status(db: Session, torve_user_id: uuid.UUID) -> dict[str, Any]:
    now = utcnow()
    link = db.query(DiscordAccountLink).filter(
        DiscordAccountLink.torve_user_id == torve_user_id,
        DiscordAccountLink.unlinked_at.is_(None),
    ).first()
    latest_application = db.query(DiscordBetaApplication).filter(
        DiscordBetaApplication.torve_user_id == torve_user_id,
    ).order_by(DiscordBetaApplication.created_at.desc()).first()
    active_grant = get_active_beta_grant(db, torve_user_id, now=now)
    days_remaining = None
    if active_grant:
        seconds = max(0.0, (active_grant.expires_at - now).total_seconds())
        days_remaining = int((seconds + 86399) // 86400)
    can_apply, blocked_reason = beta_application_eligibility(db, torve_user_id)
    return {
        "discord_linked": link is not None,
        "beta_application_status": latest_application.status if latest_application else "none",
        "beta_access_active": active_grant is not None,
        "beta_access_expires_at": active_grant.expires_at.isoformat() if active_grant else None,
        "days_remaining": days_remaining,
        "can_apply": can_apply,
        "blocked_reason": blocked_reason,
        "beta_signup_close_at": beta_signup_close_at_display(),
        "beta_free_access_end_at": beta_free_access_end_at_display(),
    }


def beta_access_state(db: Session, torve_user_id: uuid.UUID) -> dict[str, Any]:
    now = utcnow()
    latest_grant = get_latest_beta_grant(db, torve_user_id)
    if not latest_grant:
        return {
            "active": False,
            "source": None,
            "expires_at": None,
            "status": "none",
        }
    active = (
        latest_grant.status == "active"
        and latest_grant.expires_at > now
        and not beta_free_access_ended(now=now)
    )
    status = (
        "expired"
        if latest_grant.status == "active" and (latest_grant.expires_at <= now or beta_free_access_ended(now=now))
        else latest_grant.status
    )
    return {
        "active": active,
        "source": latest_grant.source,
        "expires_at": latest_grant.expires_at.isoformat() if latest_grant.expires_at else None,
        "status": status,
    }


def linked_discord_user_id(db: Session, torve_user_id: uuid.UUID) -> str | None:
    link = db.query(DiscordAccountLink).filter(
        DiscordAccountLink.torve_user_id == torve_user_id,
        DiscordAccountLink.unlinked_at.is_(None),
    ).first()
    return link.discord_user_id if link else None


def get_application_or_error(db: Session, application_id: uuid.UUID) -> DiscordBetaApplication:
    application = db.query(DiscordBetaApplication).filter(DiscordBetaApplication.id == application_id).first()
    if not application:
        raise BetaFlowError("application_not_found", "This beta application was not found.")
    return application


def build_application_message_payload() -> dict[str, Any]:
    return {
        "content": "",
        "allowed_mentions": {"parse": []},
        "embeds": [
            {
                "title": "Torve Beta Program",
                "description": (
                    "Apply for a limited beta slot. Start in Torve to create a one-time beta "
                    "link code, then press the button below and choose what you can test."
                ),
                "color": 0xF5B301,
            },
            {
                "title": "Safety",
                "description": (
                    "Do not post emails, credentials, provider names, playlist links, tokens, "
                    "private file paths, or illegal sources in Discord."
                ),
                "color": 0xEF4444,
            },
        ],
        "components": [
            {
                "type": 1,
                "components": [
                    {
                        "type": 2,
                        "style": 1,
                        "label": "Apply for Beta",
                        "custom_id": APPLICATION_BUTTON_CUSTOM_ID,
                    }
                ],
            }
        ],
    }


def build_device_select_payload(flow_id: str) -> dict[str, Any]:
    return {
        "content": "Choose the devices and platforms you can test.",
        "flags": EPHEMERAL_MESSAGE_FLAG,
        "allowed_mentions": {"parse": []},
        "components": [
            {
                "type": 1,
                "components": [
                    _select_menu(
                        custom_id=f"{DEVICE_SELECT_PREFIX}{flow_id}",
                        placeholder="Select devices and platforms",
                        options=DEVICE_OPTIONS,
                        min_values=1,
                        max_values=len(DEVICE_OPTIONS),
                    )
                ],
            }
        ],
    }


def build_feature_select_payload(flow_id: str, selected_devices: list[str]) -> dict[str, Any]:
    return {
        "content": (
            "Devices: "
            f"{labels_for_values(selected_devices, DEVICE_LABELS)}\n"
            "Choose the beta areas you can test."
        ),
        "flags": EPHEMERAL_MESSAGE_FLAG,
        "allowed_mentions": {"parse": []},
        "components": [
            {
                "type": 1,
                "components": [
                    _select_menu(
                        custom_id=f"{FEATURE_SELECT_PREFIX}{flow_id}",
                        placeholder="Select features and integrations",
                        options=FEATURE_OPTIONS,
                        min_values=1,
                        max_values=len(FEATURE_OPTIONS),
                    )
                ],
            }
        ],
    }


def build_stability_select_payload(
    flow_id: str,
    selected_devices: list[str],
    selected_features: list[str],
) -> dict[str, Any]:
    return {
        "content": (
            "Devices: "
            f"{labels_for_values(selected_devices, DEVICE_LABELS)}\n"
            "Beta areas: "
            f"{labels_for_values(selected_features, FEATURE_LABELS)}\n"
            "Choose your beta build comfort level."
        ),
        "flags": EPHEMERAL_MESSAGE_FLAG,
        "allowed_mentions": {"parse": []},
        "components": [
            {
                "type": 1,
                "components": [
                    _select_menu(
                        custom_id=f"{STABILITY_SELECT_PREFIX}{flow_id}",
                        placeholder="Select stability preference",
                        options=STABILITY_OPTIONS,
                        min_values=1,
                        max_values=1,
                    )
                ],
            }
        ],
    }


def build_application_modal(flow_id: str) -> dict[str, Any]:
    return {
        "custom_id": f"{APPLICATION_MODAL_CUSTOM_ID}:{flow_id}",
        "title": "Torve Beta Application",
        "components": [
            _modal_text(FIELD_LINK_CODE, "Torve beta link code", "BETA-XXXXXX", 1, 20, required=True),
            _modal_text(FIELD_MOTIVATION, "Testing notes (optional)", "Anything specific you can verify.", 2, 1000, required=False),
            _modal_text(FIELD_CONFIRMATION, "Type I UNDERSTAND to accept beta safety rules", "I UNDERSTAND", 1, 80, required=True),
        ],
    }


def build_rejection_modal(application_id: uuid.UUID) -> dict[str, Any]:
    return {
        "custom_id": f"{REJECT_MODAL_PREFIX}{application_id}",
        "title": "Reject Beta Application",
        "components": [
            _modal_text(
                FIELD_REJECTION_REASON,
                "Reason (optional)",
                "Optional brief reason",
                2,
                500,
                required=False,
            )
        ],
    }


def build_staff_review_payload(application: DiscordBetaApplication) -> dict[str, Any]:
    torve_user = str(application.torve_user_id) if application.torve_user_id else "not linked"
    submitted_at = application.created_at.isoformat() if application.created_at else "unknown"
    return {
        "content": "",
        "allowed_mentions": {"parse": []},
        "embeds": [
            {
                "title": "Beta Application Review",
                "color": 0x4D8DFF,
                "fields": [
                    {"name": "Status", "value": application.status, "inline": True},
                    {"name": "Discord User ID", "value": application.discord_user_id, "inline": True},
                    {"name": "Discord Username", "value": sanitize_inline(application.discord_username, max_length=255) or "unknown", "inline": True},
                    {"name": "Torve User ID", "value": torve_user, "inline": False},
                    {"name": "Devices", "value": list_for_discord(application.devices_json, DEVICE_LABELS), "inline": False},
                    {"name": "Features / Integrations", "value": list_for_discord(application.integrations_json, FEATURE_LABELS), "inline": False},
                    {
                        "name": "Stability Preference",
                        "value": label_for_value(application.stability_preference, STABILITY_LABELS) or "not selected",
                        "inline": False,
                    },
                    {"name": "Motivation", "value": sanitize_application_text(application.motivation, max_length=1000) or "none", "inline": False},
                    {"name": "Submitted At", "value": submitted_at, "inline": True},
                    {
                        "name": "Safety Confirmation",
                        "value": "accepted" if application.accepted_beta_terms and application.accepted_no_credentials else "missing",
                        "inline": True,
                    },
                ],
            }
        ],
        "components": [
            {
                "type": 1,
                "components": [
                    {
                        "type": 2,
                        "style": 3,
                        "label": "Approve",
                        "custom_id": f"{APPROVE_BUTTON_PREFIX}{application.id}",
                    },
                    {
                        "type": 2,
                        "style": 4,
                        "label": "Reject",
                        "custom_id": f"{REJECT_BUTTON_PREFIX}{application.id}",
                    },
                ],
            }
        ],
    }


def build_review_result_payload(
    application: DiscordBetaApplication,
    *,
    action: str,
    reviewer_id: str | None,
    expires_at: datetime | None = None,
    reason: str | None = None,
) -> dict[str, Any]:
    color = 0x36C275 if application.status == "approved" else 0xEF4444
    fields = [
        {"name": "Status", "value": action, "inline": True},
        {"name": "Reviewer", "value": reviewer_id or "unknown", "inline": True},
        {"name": "Discord User ID", "value": application.discord_user_id, "inline": True},
    ]
    if expires_at:
        fields.append({"name": "Expires", "value": expires_at.isoformat(), "inline": False})
    if reason:
        fields.append({"name": "Reason", "value": sanitize_application_text(reason, max_length=500), "inline": False})
    return {
        "content": "",
        "allowed_mentions": {"parse": []},
        "embeds": [
            {
                "title": "Beta Application Review",
                "color": color,
                "fields": fields,
            }
        ],
        "components": [],
    }


class DiscordBotClient:
    def __init__(
        self,
        *,
        bot_token: str,
        guild_id: str,
        beta_role_id: str,
        application_channel_id: str,
        review_channel_id: str,
    ):
        self.bot_token = bot_token.strip()
        self.guild_id = guild_id.strip()
        self.beta_role_id = beta_role_id.strip()
        self.application_channel_id = application_channel_id.strip()
        self.review_channel_id = review_channel_id.strip()

    @classmethod
    def from_settings(cls) -> "DiscordBotClient":
        return cls(
            bot_token=settings.DISCORD_BOT_TOKEN,
            guild_id=settings.DISCORD_GUILD_ID,
            beta_role_id=settings.DISCORD_BETA_TESTER_ROLE_ID,
            application_channel_id=settings.DISCORD_BETA_APPLICATION_CHANNEL_ID,
            review_channel_id=settings.DISCORD_BETA_REVIEW_CHANNEL_ID,
        )

    def add_beta_role(self, discord_user_id: str) -> DiscordApiResult:
        if not self._has_role_config():
            return DiscordApiResult(ok=False, action="missing_config")
        return self._request(
            "PUT",
            f"/guilds/{self.guild_id}/members/{discord_user_id}/roles/{self.beta_role_id}",
            action="add_role",
        )

    def remove_beta_role(self, discord_user_id: str) -> DiscordApiResult:
        if not self._has_role_config():
            return DiscordApiResult(ok=False, action="missing_config")
        return self._request(
            "DELETE",
            f"/guilds/{self.guild_id}/members/{discord_user_id}/roles/{self.beta_role_id}",
            action="remove_role",
        )

    def post_staff_review(self, application: DiscordBetaApplication) -> DiscordApiResult:
        if not self.bot_token or not self.review_channel_id:
            return DiscordApiResult(ok=False, action="missing_config")
        return self.post_channel_message(self.review_channel_id, build_staff_review_payload(application))

    def publish_application_message(self, *, message_id: str | None = None) -> DiscordApiResult:
        if not self.bot_token or not self.application_channel_id:
            return DiscordApiResult(ok=False, action="missing_config")
        payload = build_application_message_payload()
        if message_id:
            return self.edit_channel_message(self.application_channel_id, message_id, payload)
        return self.post_channel_message(self.application_channel_id, payload)

    def post_channel_message(self, channel_id: str, payload: dict[str, Any]) -> DiscordApiResult:
        if not self.bot_token or not channel_id:
            return DiscordApiResult(ok=False, action="missing_config")
        result = self._request("POST", f"/channels/{channel_id}/messages", json_payload=payload, action="post_message")
        message_id = _message_id(result.data)
        return DiscordApiResult(result.ok, result.action, result.status_code, result.data, message_id)

    def edit_channel_message(self, channel_id: str, message_id: str, payload: dict[str, Any]) -> DiscordApiResult:
        if not self.bot_token or not channel_id or not message_id:
            return DiscordApiResult(ok=False, action="missing_config")
        if not _SNOWFLAKE_RE.match(message_id):
            return DiscordApiResult(ok=False, action="invalid_message_id")
        result = self._request(
            "PATCH",
            f"/channels/{channel_id}/messages/{message_id}",
            json_payload=payload,
            action="edit_message",
        )
        returned_id = _message_id(result.data) or message_id
        return DiscordApiResult(result.ok, result.action, result.status_code, result.data, returned_id)

    def dm_user(self, discord_user_id: str, content: str) -> DiscordApiResult:
        if not self.bot_token:
            return DiscordApiResult(ok=False, action="missing_config")
        dm = self._request("POST", "/users/@me/channels", json_payload={"recipient_id": discord_user_id}, action="create_dm")
        channel_id = _message_id(dm.data)
        if not dm.ok or not channel_id:
            return DiscordApiResult(ok=False, action="dm_failed", status_code=dm.status_code)
        return self.post_channel_message(
            channel_id,
            {"content": sanitize_application_text(content, max_length=1500), "allowed_mentions": {"parse": []}},
        )

    def _has_role_config(self) -> bool:
        return bool(self.bot_token and self.guild_id and self.beta_role_id)

    def _request(
        self,
        method: str,
        path: str,
        *,
        json_payload: dict[str, Any] | None = None,
        action: str,
    ) -> DiscordApiResult:
        if not self.bot_token:
            return DiscordApiResult(ok=False, action="missing_config")
        url = f"{_DISCORD_API_BASE}{path}"
        headers = {
            "Authorization": f"Bot {self.bot_token}",
            "Content-Type": "application/json",
        }
        attempts = _MAX_ATTEMPTS
        for attempt in range(1, attempts + 1):
            try:
                response = httpx.request(
                    method,
                    url,
                    headers=headers,
                    json=json_payload,
                    timeout=_DISCORD_TIMEOUT,
                )
            except Exception as exc:  # noqa: BLE001 - sanitized operational log
                _log.warning(
                    "Discord API request failed action=%s error_type=%s attempt=%d/%d",
                    action,
                    exc.__class__.__name__,
                    attempt,
                    attempts,
                )
                continue

            if 200 <= response.status_code < 300:
                return DiscordApiResult(
                    ok=True,
                    action=action,
                    status_code=response.status_code,
                    data=_json_response(response),
                )
            retry_after = retry_after_seconds(response)
            if response.status_code == 429:
                _log.warning(
                    "Discord API rate limited action=%s retry_after_ms=%s attempt=%d/%d",
                    action,
                    retry_after_ms(retry_after),
                    attempt,
                    attempts,
                )
                if attempt < attempts and retry_after is not None and retry_after <= 2.0:
                    time.sleep(max(0.0, retry_after))
                continue
            if response.status_code >= 500 and attempt < attempts:
                continue
            _log.warning(
                "Discord API request failed action=%s status=%s attempt=%d/%d",
                action,
                response.status_code,
                attempt,
                attempts,
            )
            return DiscordApiResult(ok=False, action=action, status_code=response.status_code)
        return DiscordApiResult(ok=False, action=action)


def verify_discord_signature(public_key: str, timestamp: str, signature: str, body: bytes) -> bool:
    if not public_key or not timestamp or not signature:
        return False
    try:
        from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey

        key = Ed25519PublicKey.from_public_bytes(bytes.fromhex(public_key.strip()))
        key.verify(bytes.fromhex(signature.strip()), timestamp.encode("utf-8") + body)
        return True
    except Exception:  # noqa: BLE001 - Discord wants a plain unauthorized response
        return False


def handle_interaction_payload(db: Session, payload: dict[str, Any]) -> dict[str, Any]:
    interaction_type = payload.get("type")
    if interaction_type == 1:
        return {"type": 1}
    if interaction_type == 3:
        custom_id = _custom_id(payload)
        if custom_id == APPLICATION_BUTTON_CUSTOM_ID:
            return handle_application_button(db, payload)
        if custom_id.startswith(DEVICE_SELECT_PREFIX):
            return handle_device_select_interaction(db, payload, custom_id)
        if custom_id.startswith(FEATURE_SELECT_PREFIX):
            return handle_feature_select_interaction(db, payload, custom_id)
        if custom_id.startswith(STABILITY_SELECT_PREFIX):
            return handle_stability_select_interaction(db, payload, custom_id)
        if custom_id.startswith(APPROVE_BUTTON_PREFIX):
            return handle_approve_interaction(db, payload, custom_id)
        if custom_id.startswith(REJECT_BUTTON_PREFIX):
            return handle_reject_button_interaction(payload, custom_id)
    if interaction_type == 5:
        custom_id = _custom_id(payload)
        if custom_id == APPLICATION_MODAL_CUSTOM_ID or custom_id.startswith(f"{APPLICATION_MODAL_CUSTOM_ID}:"):
            return handle_application_modal_submit(db, payload, custom_id)
        if custom_id.startswith(REJECT_MODAL_PREFIX):
            return handle_reject_modal_submit(db, payload, custom_id)
    return ephemeral_response("This Discord action is not supported.")


def handle_application_button(db: Session, payload: dict[str, Any]) -> dict[str, Any]:
    discord_user = discord_user_from_payload(payload)
    if not clean_snowflake(discord_user.user_id):
        return ephemeral_response("Discord user information was not available.")
    try:
        flow_id = create_application_flow(db, discord_user)
        db.commit()
        return {"type": 4, "data": build_device_select_payload(flow_id)}
    except Exception as exc:  # noqa: BLE001 - sanitized Discord response
        db.rollback()
        _log.warning("Discord beta draft start failed error_type=%s", exc.__class__.__name__)
        return ephemeral_response("Could not start the beta application. Please try again later.")


def handle_device_select_interaction(db: Session, payload: dict[str, Any], custom_id: str) -> dict[str, Any]:
    flow_id = custom_id[len(DEVICE_SELECT_PREFIX):]
    try:
        devices = validate_device_values(component_values(payload))
        update_application_flow(db, flow_id, interaction_user_id(payload), devices=devices)
        db.commit()
        return {"type": 7, "data": build_feature_select_payload(flow_id, devices)}
    except BetaFlowError as exc:
        finish_application_flow_error(db, exc)
        return ephemeral_response(exc.message)
    except Exception as exc:  # noqa: BLE001 - sanitized Discord response
        db.rollback()
        _log.warning("Discord beta device select failed error_type=%s", exc.__class__.__name__)
        return ephemeral_response("Could not save your beta selections. Please try again later.")


def handle_feature_select_interaction(db: Session, payload: dict[str, Any], custom_id: str) -> dict[str, Any]:
    flow_id = custom_id[len(FEATURE_SELECT_PREFIX):]
    try:
        features = validate_feature_values(component_values(payload))
        flow = update_application_flow(db, flow_id, interaction_user_id(payload), features=features)
        db.commit()
        return {
            "type": 7,
            "data": build_stability_select_payload(flow_id, flow["devices"], features),
        }
    except BetaFlowError as exc:
        finish_application_flow_error(db, exc)
        return ephemeral_response(exc.message)
    except Exception as exc:  # noqa: BLE001 - sanitized Discord response
        db.rollback()
        _log.warning("Discord beta feature select failed error_type=%s", exc.__class__.__name__)
        return ephemeral_response("Could not save your beta selections. Please try again later.")


def handle_stability_select_interaction(db: Session, payload: dict[str, Any], custom_id: str) -> dict[str, Any]:
    flow_id = custom_id[len(STABILITY_SELECT_PREFIX):]
    try:
        stability = validate_stability_value(one_component_value(payload))
        update_application_flow(db, flow_id, interaction_user_id(payload), stability_preference=stability)
        db.commit()
        return {"type": 9, "data": build_application_modal(flow_id)}
    except BetaFlowError as exc:
        finish_application_flow_error(db, exc)
        return ephemeral_response(exc.message)
    except Exception as exc:  # noqa: BLE001 - sanitized Discord response
        db.rollback()
        _log.warning("Discord beta stability select failed error_type=%s", exc.__class__.__name__)
        return ephemeral_response("Could not save your beta selections. Please try again later.")


def handle_application_modal_submit(db: Session, payload: dict[str, Any], custom_id: str) -> dict[str, Any]:
    values = modal_values(payload)
    user = discord_user_from_payload(payload)
    try:
        flow = application_flow_for_modal(db, custom_id, user.user_id)
        submit = submit_beta_application(
            db,
            link_code=values.get(FIELD_LINK_CODE, ""),
            discord_user=user,
            devices=flow["devices"],
            integrations=flow["features"],
            stability_preference=flow["stability_preference"],
            motivation=values.get(FIELD_MOTIVATION, ""),
            confirmation_text=values.get(FIELD_CONFIRMATION, ""),
        )
        application = submit.application
        discord = DiscordBotClient.from_settings()
        if settings.DISCORD_BETA_AUTO_APPROVE and beta_auto_approve_allowed(db, application):
            approval = approve_beta_application(
                db,
                application_id=application.id,
                reviewer_discord_user_id="auto",
            )
            mark_application_flow_consumed(db, flow["flow_id"])
            db.commit()
            role_result = discord.add_beta_role(application.discord_user_id)
            if not role_result.ok:
                _log.warning("Discord beta auto-approve role add failed action=%s", role_result.action)
            if not approval.grant:
                return ephemeral_response(
                    "Beta access approved. You now have access to the Discord beta area."
                )
            return ephemeral_response(
                "Beta access approved. Your Torve beta access expires "
                f"{approval.grant.expires_at.date().isoformat()}."
            )

        mark_application_flow_consumed(db, flow["flow_id"])
        db.commit()
        review_result = discord.post_staff_review(application)
        if not review_result.ok:
            _log.warning("Discord beta staff review post failed action=%s", review_result.action)
        return ephemeral_response(APPLICATION_SUBMITTED_MESSAGE, components=[])
    except BetaFlowError as exc:
        finish_application_flow_error(db, exc)
        return ephemeral_response(exc.message)
    except Exception as exc:  # noqa: BLE001 - sanitized Discord response
        db.rollback()
        _log.warning("Discord beta modal submit failed error_type=%s", exc.__class__.__name__)
        return ephemeral_response("Something went wrong. Please try again later.")


def finish_application_flow_error(db: Session, exc: BetaFlowError) -> None:
    if exc.code == "selection_expired":
        db.commit()
    else:
        db.rollback()


def handle_approve_interaction(db: Session, payload: dict[str, Any], custom_id: str) -> dict[str, Any]:
    if not interaction_member_is_staff(payload):
        return ephemeral_response("Only Torve staff can review beta applications.")
    reviewer_id = interaction_user_id(payload)
    application_id = parse_uuid_after_prefix(custom_id, APPROVE_BUTTON_PREFIX)
    if not application_id:
        return ephemeral_response("This beta review action is invalid.")
    discord = DiscordBotClient.from_settings()
    try:
        approval = approve_beta_application(
            db,
            application_id=application_id,
            reviewer_discord_user_id=reviewer_id or "",
        )
        db.commit()
        role_result = discord.add_beta_role(approval.application.discord_user_id)
        if not role_result.ok:
            _log.warning("Discord beta role add failed action=%s status=%s", role_result.action, role_result.status_code)
        if approval.grant:
            dm_result = discord.dm_user(
                approval.application.discord_user_id,
                f"Your Torve beta access was approved through {approval.grant.expires_at.date().isoformat()}.",
            )
            if not dm_result.ok:
                _log.warning("Discord beta approval DM failed action=%s status=%s", dm_result.action, dm_result.status_code)
        else:
            dm_result = discord.dm_user(
                approval.application.discord_user_id,
                "Your Torve beta application was approved. You now have access to the Discord beta area.",
            )
            if not dm_result.ok:
                _log.warning("Discord beta approval DM failed action=%s status=%s", dm_result.action, dm_result.status_code)
        return {
            "type": 7,
            "data": build_review_result_payload(
                approval.application,
                action=approval.action,
                reviewer_id=reviewer_id,
                expires_at=approval.grant.expires_at if approval.grant else None,
            ),
        }
    except BetaFlowError as exc:
        db.rollback()
        return ephemeral_response(exc.message)
    except Exception as exc:  # noqa: BLE001
        db.rollback()
        _log.warning("Discord beta approve failed error_type=%s", exc.__class__.__name__)
        return ephemeral_response("Approval failed. Please try again later.")


def handle_reject_button_interaction(payload: dict[str, Any], custom_id: str) -> dict[str, Any]:
    if not interaction_member_is_staff(payload):
        return ephemeral_response("Only Torve staff can review beta applications.")
    application_id = parse_uuid_after_prefix(custom_id, REJECT_BUTTON_PREFIX)
    if not application_id:
        return ephemeral_response("This beta review action is invalid.")
    return {"type": 9, "data": build_rejection_modal(application_id)}


def handle_reject_modal_submit(db: Session, payload: dict[str, Any], custom_id: str) -> dict[str, Any]:
    if not interaction_member_is_staff(payload):
        return ephemeral_response("Only Torve staff can review beta applications.")
    reviewer_id = interaction_user_id(payload)
    application_id = parse_uuid_after_prefix(custom_id, REJECT_MODAL_PREFIX)
    if not application_id:
        return ephemeral_response("This beta review action is invalid.")
    values = modal_values(payload)
    discord = DiscordBotClient.from_settings()
    try:
        application = reject_beta_application(
            db,
            application_id=application_id,
            reviewer_discord_user_id=reviewer_id or "",
            reason=values.get(FIELD_REJECTION_REASON, ""),
        )
        db.commit()
        dm_result = discord.dm_user(application.discord_user_id, "Your Torve beta application was not approved at this time.")
        if not dm_result.ok:
            _log.warning("Discord beta rejection DM failed action=%s status=%s", dm_result.action, dm_result.status_code)
        return {
            "type": 7,
            "data": build_review_result_payload(
                application,
                action="rejected",
                reviewer_id=reviewer_id,
                reason=application.rejection_reason,
            ),
        }
    except BetaFlowError as exc:
        db.rollback()
        return ephemeral_response(exc.message)
    except Exception as exc:  # noqa: BLE001
        db.rollback()
        _log.warning("Discord beta reject failed error_type=%s", exc.__class__.__name__)
        return ephemeral_response("Rejection failed. Please try again later.")


def interaction_member_is_staff(payload: dict[str, Any]) -> bool:
    user_id = interaction_user_id(payload)
    configured_users = comma_set(settings.DISCORD_BETA_REVIEWER_USER_IDS)
    if user_id and user_id in configured_users:
        return True
    member = payload.get("member") if isinstance(payload.get("member"), dict) else {}
    roles = set(str(role) for role in member.get("roles", []) if role is not None)
    if roles & comma_set(settings.DISCORD_BETA_REVIEWER_ROLE_IDS):
        return True
    try:
        permissions = int(str(member.get("permissions") or "0"))
    except ValueError:
        permissions = 0
    administrator = 1 << 3
    manage_guild = 1 << 5
    manage_roles = 1 << 28
    moderate_members = 1 << 40
    return bool(permissions & (administrator | manage_guild | manage_roles | moderate_members))


def interaction_user_id(payload: dict[str, Any]) -> str | None:
    member = payload.get("member") if isinstance(payload.get("member"), dict) else {}
    member_user = member.get("user") if isinstance(member.get("user"), dict) else {}
    direct_user = payload.get("user") if isinstance(payload.get("user"), dict) else {}
    return clean_snowflake(str(member_user.get("id") or direct_user.get("id") or "")) or None


def discord_user_from_payload(payload: dict[str, Any]) -> DiscordUserIdentity:
    member = payload.get("member") if isinstance(payload.get("member"), dict) else {}
    user = member.get("user") if isinstance(member.get("user"), dict) else payload.get("user")
    if not isinstance(user, dict):
        user = {}
    username = str(user.get("username") or member.get("nick") or "unknown")
    global_name = user.get("global_name") or user.get("discriminator")
    return DiscordUserIdentity(
        user_id=str(user.get("id") or ""),
        username=username,
        discriminator_or_global_name=str(global_name) if global_name is not None else None,
    )


def create_application_flow(
    db: Session,
    discord_user: DiscordUserIdentity | str,
    *,
    interaction_token: str | None = None,
) -> str:
    now = utcnow()
    if isinstance(discord_user, DiscordUserIdentity):
        discord_user_id = clean_snowflake(discord_user.user_id)
        discord_username = sanitize_inline(discord_user.username, max_length=255) or "unknown"
    else:
        discord_user_id = clean_snowflake(str(discord_user))
        discord_username = "unknown"
    if not discord_user_id:
        raise BetaFlowError("missing_discord_user", "Discord user information was not available.")

    db.query(DiscordBetaApplicationDraft).filter(
        DiscordBetaApplicationDraft.discord_user_id == discord_user_id,
        DiscordBetaApplicationDraft.consumed_at.is_(None),
    ).update(
        {
            "consumed_at": now,
            "updated_at": now,
        },
        synchronize_session=False,
    )
    draft = DiscordBetaApplicationDraft(
        discord_user_id=discord_user_id,
        discord_username=discord_username,
        interaction_token_hash=hash_interaction_token(interaction_token),
        selected_devices_json=[],
        selected_integrations_json=[],
        stability_preference=None,
        current_step="devices",
        expires_at=now + timedelta(seconds=_FLOW_TTL_SECONDS),
        created_at=now,
        updated_at=now,
    )
    db.add(draft)
    db.flush()
    return str(draft.id)


def update_application_flow(
    db: Session,
    flow_id: str,
    discord_user_id: str | None,
    *,
    devices: list[str] | None = None,
    features: list[str] | None = None,
    stability_preference: str | None = None,
) -> dict[str, Any]:
    now = utcnow()
    draft = get_application_flow(db, flow_id, discord_user_id, now=now)
    if devices is not None:
        draft.selected_devices_json = list(devices)
        draft.current_step = "features"
    if features is not None:
        draft.selected_integrations_json = list(features)
        draft.current_step = "stability"
    if stability_preference is not None:
        draft.stability_preference = stability_preference
        draft.current_step = "modal"
    draft.updated_at = now
    db.flush()
    return application_flow_dict(draft)


def application_flow_for_modal(db: Session, custom_id: str, discord_user_id: str) -> dict[str, Any]:
    flow_id = ""
    if custom_id.startswith(f"{APPLICATION_MODAL_CUSTOM_ID}:"):
        flow_id = custom_id.split(":", 1)[1]
    if not flow_id:
        raise BetaFlowError("missing_selections", "Please restart the beta application and choose your beta selections.")
    flow = application_flow_dict(get_application_flow(db, flow_id, discord_user_id))
    if not flow.get("devices") or not flow.get("features") or not flow.get("stability_preference"):
        raise BetaFlowError("missing_selections", "Please complete the beta selection steps before submitting.")
    return flow


def get_application_flow(
    db: Session,
    flow_id: str,
    discord_user_id: str | None,
    *,
    now: datetime | None = None,
) -> DiscordBetaApplicationDraft:
    draft_id = parse_uuid(flow_id)
    if not draft_id:
        raise BetaFlowError("selection_expired", _FLOW_EXPIRED_MESSAGE)
    draft = db.query(DiscordBetaApplicationDraft).filter(
        DiscordBetaApplicationDraft.id == draft_id,
        DiscordBetaApplicationDraft.consumed_at.is_(None),
    ).first()
    if not draft:
        raise BetaFlowError("selection_expired", _FLOW_EXPIRED_MESSAGE)
    now = now or utcnow()
    if draft.expires_at <= now:
        draft.consumed_at = now
        draft.updated_at = now
        db.flush()
        raise BetaFlowError("selection_expired", _FLOW_EXPIRED_MESSAGE)
    clean_user_id = clean_snowflake(discord_user_id)
    if not clean_user_id or clean_user_id != draft.discord_user_id:
        raise BetaFlowError("selection_mismatch", "This beta application session is not yours. Please start again.")
    return draft


def mark_application_flow_consumed(db: Session, flow_id: str) -> None:
    draft_id = parse_uuid(flow_id)
    if not draft_id:
        return
    now = utcnow()
    db.query(DiscordBetaApplicationDraft).filter(
        DiscordBetaApplicationDraft.id == draft_id,
        DiscordBetaApplicationDraft.consumed_at.is_(None),
    ).update(
        {
            "consumed_at": now,
            "current_step": "submitted",
            "updated_at": now,
        },
        synchronize_session=False,
    )
    db.flush()


def cleanup_expired_application_drafts(db: Session, *, now: datetime | None = None) -> int:
    now = now or utcnow()
    result = db.query(DiscordBetaApplicationDraft).filter(
        DiscordBetaApplicationDraft.consumed_at.is_(None),
        DiscordBetaApplicationDraft.expires_at <= now,
    ).update(
        {
            "consumed_at": now,
            "updated_at": now,
        },
        synchronize_session=False,
    )
    if result:
        db.flush()
    return int(result or 0)


def application_flow_dict(draft: DiscordBetaApplicationDraft) -> dict[str, Any]:
    return {
        "flow_id": str(draft.id),
        "discord_user_id": draft.discord_user_id,
        "devices": list(draft.selected_devices_json or []),
        "features": list(draft.selected_integrations_json or []),
        "stability_preference": draft.stability_preference,
        "expires_at": draft.expires_at,
    }


def hash_interaction_token(token: str | None) -> str | None:
    if not token:
        return None
    return hashlib.sha256(str(token).encode("utf-8")).hexdigest()


def modal_values(payload: dict[str, Any]) -> dict[str, str]:
    values: dict[str, str] = {}
    data = payload.get("data") if isinstance(payload.get("data"), dict) else {}
    rows = data.get("components") if isinstance(data.get("components"), list) else []
    for row in rows:
        if not isinstance(row, dict):
            continue
        components = row.get("components") if isinstance(row.get("components"), list) else []
        for component in components:
            if not isinstance(component, dict):
                continue
            custom_id = str(component.get("custom_id") or "")
            if custom_id:
                values[custom_id] = str(component.get("value") or "")
    return values


def component_values(payload: dict[str, Any]) -> list[str]:
    data = payload.get("data") if isinstance(payload.get("data"), dict) else {}
    values = data.get("values")
    if not isinstance(values, list):
        return []
    return [str(value) for value in values]


def one_component_value(payload: dict[str, Any]) -> str | None:
    values = component_values(payload)
    return values[0] if values else None


def ephemeral_response(message: str, *, components: list[dict[str, Any]] | None = None) -> dict[str, Any]:
    data: dict[str, Any] = {
        "content": sanitize_application_text(message, max_length=1500),
        "flags": EPHEMERAL_MESSAGE_FLAG,
        "allowed_mentions": {"parse": []},
    }
    if components is not None:
        data["components"] = components
    return {
        "type": 4,
        "data": data,
    }


def _custom_id(payload: dict[str, Any]) -> str:
    data = payload.get("data") if isinstance(payload.get("data"), dict) else {}
    return str(data.get("custom_id") or "")


def _modal_text(
    custom_id: str,
    label: str,
    placeholder: str,
    style: int,
    max_length: int,
    *,
    required: bool,
) -> dict[str, Any]:
    return {
        "type": 1,
        "components": [
            {
                "type": 4,
                "custom_id": custom_id,
                "label": label,
                "style": style,
                "placeholder": placeholder,
                "min_length": 1 if required else 0,
                "max_length": max_length,
                "required": required,
            }
        ],
    }


def _select_menu(
    *,
    custom_id: str,
    placeholder: str,
    options: tuple[tuple[str, str], ...],
    min_values: int,
    max_values: int,
) -> dict[str, Any]:
    return {
        "type": 3,
        "custom_id": custom_id,
        "placeholder": placeholder,
        "min_values": min_values,
        "max_values": max_values,
        "options": [
            {
                "label": label,
                "value": value,
            }
            for value, label in options
        ],
    }


def accepted_confirmation(value: str) -> bool:
    return _SPACE_RE.sub(" ", str(value or "").strip()).upper() == "I UNDERSTAND"


def validate_device_values(values: list[str]) -> list[str]:
    return validate_controlled_values(
        values,
        allowed=DEVICE_LABELS,
        missing_code="missing_devices",
        missing_message="Please choose at least one device or platform.",
        unknown_code="unknown_device",
        unknown_message="One selected device is no longer supported. Please start again.",
    )


def validate_feature_values(values: list[str]) -> list[str]:
    return validate_controlled_values(
        values,
        allowed=FEATURE_LABELS,
        missing_code="missing_features",
        missing_message="Please choose at least one beta area.",
        unknown_code="unknown_feature",
        unknown_message="One selected beta area is no longer supported. Please start again.",
    )


def validate_stability_value(value: str | None) -> str:
    cleaned = str(value or "").strip()
    if not cleaned:
        raise BetaFlowError("missing_stability_preference", "Please choose a beta stability preference.")
    if cleaned not in STABILITY_LABELS:
        raise BetaFlowError(
            "unknown_stability_preference",
            "The selected stability preference is no longer supported. Please start again.",
        )
    return cleaned


def validate_controlled_values(
    values: list[str],
    *,
    allowed: dict[str, str],
    missing_code: str,
    missing_message: str,
    unknown_code: str,
    unknown_message: str,
) -> list[str]:
    cleaned: list[str] = []
    for value in values:
        item = str(value or "").strip()
        if not item:
            continue
        if item not in allowed:
            raise BetaFlowError(unknown_code, unknown_message)
        if item not in cleaned:
            cleaned.append(item)
    if not cleaned:
        raise BetaFlowError(missing_code, missing_message)
    return cleaned


def split_answer_list(value: str) -> list[str]:
    cleaned = sanitize_application_text(value, max_length=600)
    parts = re.split(r"[\n,;]+", cleaned)
    result = []
    for part in parts:
        item = sanitize_inline(part, max_length=80)
        if item and item not in result:
            result.append(item)
    return result[:20]


def validate_no_private_material(*values: str) -> None:
    text = "\n".join(str(value or "") for value in values)
    lowered = text.lower()
    if (
        _SECRETISH_RE.search(text)
        or _URL_RE.search(text)
        or _PRIVATE_PATH_RE.search(text)
        or ".m3u" in lowered
        or "playlist link" in lowered
    ):
        raise BetaFlowError(
            "private_material_rejected",
            "Please remove links, tokens, credentials, provider details, and private paths.",
        )


def sanitize_application_text(value: str | None, *, max_length: int) -> str:
    text = str(value or "")
    text = _SECRET_PAIR_RE.sub("[redacted]", text)
    text = _URL_RE.sub("[link redacted]", text)
    text = _PRIVATE_PATH_RE.sub("[path redacted]", text)
    text = _SECRETISH_RE.sub("[redacted]", text)
    text = text.replace("\x00", "")
    text = re.sub(r"[\r\t]+", " ", text)
    text = re.sub(r"\n{3,}", "\n\n", text).strip()
    if len(text) > max_length:
        return text[: max_length - 3].rstrip() + "..."
    return text


def sanitize_inline(value: str | None, *, max_length: int) -> str:
    text = sanitize_application_text(value, max_length=max_length)
    text = _SPACE_RE.sub(" ", text).strip()
    if len(text) > max_length:
        return text[:max_length].rstrip()
    return text


def clean_snowflake(value: str | None) -> str:
    cleaned = str(value or "").strip()
    return cleaned if _SNOWFLAKE_RE.match(cleaned) else ""


def hash_discord_user_id(value: str | None) -> str | None:
    cleaned = clean_snowflake(value)
    if not cleaned:
        return None
    return hashlib.sha256(f"discord:{cleaned}".encode("utf-8")).hexdigest()[:16]


def comma_set(value: str) -> set[str]:
    return {item.strip() for item in str(value or "").split(",") if item.strip()}


def label_for_value(value: str | None, labels: dict[str, str]) -> str | None:
    if value is None:
        return None
    return labels.get(str(value), sanitize_inline(str(value), max_length=80))


def labels_for_values(values: list[str], labels: dict[str, str]) -> str:
    display = [label_for_value(value, labels) for value in values]
    display = [value for value in display if value]
    return ", ".join(display) or "none"


def list_for_discord(items: Any, labels: dict[str, str] | None = None) -> str:
    if not isinstance(items, list):
        return "none"
    if labels is None:
        cleaned = [sanitize_inline(str(item), max_length=80) for item in items]
    else:
        cleaned = [label_for_value(str(item), labels) or "" for item in items]
    cleaned = [item for item in cleaned if item]
    return "\n".join(f"- {item}" for item in cleaned[:20]) or "none"


def retry_after_seconds(response: httpx.Response) -> float | None:
    header_value = response.headers.get("retry-after") if hasattr(response, "headers") else None
    if header_value:
        try:
            return max(0.0, float(header_value))
        except ValueError:
            pass
    data = _json_response(response)
    value = data.get("retry_after") if data else None
    if isinstance(value, (int, float)):
        return max(0.0, float(value))
    return None


def retry_after_ms(retry_after: float | None) -> str:
    if retry_after is None:
        return "unknown"
    return str(int(retry_after * 1000))


def parse_uuid(value: str | uuid.UUID | None) -> uuid.UUID | None:
    try:
        return value if isinstance(value, uuid.UUID) else uuid.UUID(str(value or ""))
    except (TypeError, ValueError):
        return None


def parse_uuid_after_prefix(value: str, prefix: str) -> uuid.UUID | None:
    return parse_uuid(str(value or "")[len(prefix):])


def _json_response(response: httpx.Response) -> dict[str, Any] | None:
    try:
        data = response.json()
    except Exception:  # noqa: BLE001
        return None
    return data if isinstance(data, dict) else None


def _message_id(data: dict[str, Any] | None) -> str | None:
    value = data.get("id") if isinstance(data, dict) else None
    cleaned = clean_snowflake(str(value or ""))
    return cleaned or None


def _print_json(data: dict[str, Any]) -> None:
    print(json.dumps(data, indent=2, sort_keys=True))


def beta_stats(db: Session) -> dict[str, Any]:
    status_counts: Counter[str] = Counter()
    device_counts: Counter[str] = Counter()
    feature_counts: Counter[str] = Counter()
    stability_counts: Counter[str] = Counter()

    for application in db.query(DiscordBetaApplication).all():
        status_counts[application.status] += 1
        if isinstance(application.devices_json, list):
            for value in application.devices_json:
                if value in DEVICE_LABELS:
                    device_counts[value] += 1
        if isinstance(application.integrations_json, list):
            for value in application.integrations_json:
                if value in FEATURE_LABELS:
                    feature_counts[value] += 1
        if application.stability_preference in STABILITY_LABELS:
            stability_counts[application.stability_preference] += 1

    now = utcnow()
    active_grants = 0
    if not beta_free_access_ended(now=now):
        active_grants = db.query(BetaAccessGrant).filter(
            BetaAccessGrant.source == BETA_SOURCE_DISCORD,
            BetaAccessGrant.status == "active",
            BetaAccessGrant.expires_at > now,
        ).count()
    pending_applications = status_counts.get("submitted", 0)

    return {
        "beta_signup_close_at": beta_signup_close_at_display(),
        "beta_free_access_end_at": beta_free_access_end_at_display(),
        "signup_closed": beta_application_blocked_reason(now=now) in {"beta_signup_closed", "beta_access_ended"},
        "free_access_ended": beta_free_access_ended(now=now),
        "applications_by_status": dict(sorted(status_counts.items())),
        "active_grants": active_grants,
        "pending_applications": pending_applications,
        "selected_devices": dict(sorted(device_counts.items())),
        "selected_features_integrations": dict(sorted(feature_counts.items())),
        "stability_preferences": dict(sorted(stability_counts.items())),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Manage Torve Discord beta applications.")
    subparsers = parser.add_subparsers(dest="command", required=True)

    publish_parser = subparsers.add_parser("publish-application-message")
    publish_parser.add_argument("--message-id", default=settings.DISCORD_BETA_APPLICATION_MESSAGE_ID)

    subparsers.add_parser("expire-grants")
    subparsers.add_parser("list-active")
    subparsers.add_parser("stats")

    revoke_parser = subparsers.add_parser("revoke")
    revoke_parser.add_argument("--torve-user-id", required=True)
    revoke_parser.add_argument("--reason", required=True)

    args = parser.parse_args(argv)
    db = SessionLocal()
    discord = DiscordBotClient.from_settings()
    try:
        if args.command == "publish-application-message":
            result = discord.publish_application_message(message_id=args.message_id.strip() or None)
            if not result.ok:
                if args.message_id.strip() and result.action == "edit_message" and result.status_code in {403, 404}:
                    print(
                        "Could not edit the configured beta application message. It may not be "
                        "bot-authored or may no longer exist. Clear "
                        "DISCORD_BETA_APPLICATION_MESSAGE_ID and republish."
                    )
                    return 2
                print(f"Discord beta application message publish failed action={result.action}")
                return 2 if result.action in {"missing_config", "invalid_message_id"} else 1
            print(f"Discord beta application message {result.action} message_id={result.message_id or 'unknown'}")
            return 0
        if args.command == "expire-grants":
            result = expire_due_beta_grants(db, discord=discord)
            db.commit()
            _print_json(result)
            return 0
        if args.command == "list-active":
            now = utcnow()
            rows = db.query(BetaAccessGrant).filter(
                BetaAccessGrant.source == BETA_SOURCE_DISCORD,
                BetaAccessGrant.status == "active",
                BetaAccessGrant.expires_at > now,
            ).order_by(BetaAccessGrant.expires_at.asc()).all()
            _print_json(
                {
                    "active": [
                        {
                            "torve_user_id": str(row.torve_user_id),
                            "discord_user_id": row.discord_user_id,
                            "expires_at": row.expires_at.isoformat(),
                        }
                        for row in rows
                    ]
                }
            )
            return 0
        if args.command == "stats":
            _print_json(beta_stats(db))
            return 0
        if args.command == "revoke":
            torve_user_id = uuid.UUID(args.torve_user_id)
            grants = revoke_beta_access(db, torve_user_id=torve_user_id, reason=args.reason)
            db.commit()
            removed = 0
            failed = 0
            discord_user_id = grants[0].discord_user_id if grants else linked_discord_user_id(db, torve_user_id)
            if discord_user_id:
                role_result = discord.remove_beta_role(discord_user_id)
                removed = 1 if role_result.ok else 0
                failed = 0 if role_result.ok else 1
            _print_json({"revoked": len(grants), "role_removed": removed, "role_remove_failed": failed})
            return 0
    except ValueError:
        db.rollback()
        print("Invalid Torve user id")
        return 2
    except Exception as exc:  # noqa: BLE001
        db.rollback()
        _log.warning("Discord beta admin command failed error_type=%s", exc.__class__.__name__)
        print("Discord beta admin command failed")
        return 1
    finally:
        db.close()
    return 1


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
