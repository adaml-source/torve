"""
Rebate code service: generation, hashing, validation, redemption, and throttling.

Security model:
- Codes stored as HMAC-SHA256 hashes only (REBATE_CODE_HMAC_SECRET).
- Raw codes visible only at admin creation time.
- Input normalized before hashing to handle pasted formatting noise.
- Redemption uses row-level locking for race safety.
- One successful rebate per account (DB-enforced partial unique index).
- PostgreSQL-backed rate limiting across all workers.
- Raw codes never appear in logs.
"""
import hashlib
import hmac
import ipaddress
import logging
import re
import secrets
import unicodedata
import uuid
from datetime import datetime, timedelta, timezone

from sqlalchemy import func, select, text
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.config import settings
from app.models import RebateCode, RebateRedemption, User

_log = logging.getLogger(__name__)

SOURCE_REBATE = "rebate_code"

_CODE_PREFIX = "TRV-LIFE"
_SEGMENT_LENGTH = 4
_SEGMENT_COUNT = 4
_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"

# Rate limit config
_WINDOW_SECONDS = 300  # 5 min
_MAX_ATTEMPTS_PER_USER = 5
_MAX_ATTEMPTS_PER_IP = 10


# ── Input normalization ───────────────────────────────────────────────────

def normalize_code(raw: str) -> str:
    """Normalize user-entered rebate code to canonical form.

    Strips zero-width/non-printing chars, converts unicode dashes,
    removes whitespace, NFC-normalizes, uppercases.
    """
    cleaned = "".join(
        c for c in raw
        if unicodedata.category(c) not in ("Cf", "Cc", "Zl", "Zp")
        or c in ("\n", "\r", "\t")
    )
    cleaned = unicodedata.normalize("NFC", cleaned).upper()
    cleaned = re.sub(
        r"[\u2010\u2011\u2012\u2013\u2014\u2015\uFE58\uFE63\uFF0D\u00AD\u2212]+",
        "-", cleaned
    )
    cleaned = re.sub(r"\s+", "", cleaned)
    return cleaned.strip()


# ── HMAC hashing ──────────────────────────────────────────────────────────

def _get_hmac_key() -> bytes:
    """Dedicated HMAC key for rebate codes. Not shared with JWT."""
    secret = settings.REBATE_CODE_HMAC_SECRET
    if not secret:
        if settings.APP_ENV == "production":
            raise RuntimeError("REBATE_CODE_HMAC_SECRET must be set in production")
        _log.warning("REBATE_CODE_HMAC_SECRET not set, using dev fallback")
        secret = "dev-rebate-fallback-not-for-production"
    return secret.encode()


def _hash_code(raw_code: str) -> str:
    """HMAC-SHA256 hash of a normalized code."""
    normalized = normalize_code(raw_code)
    return hmac.new(_get_hmac_key(), normalized.encode(), hashlib.sha256).hexdigest()


def _extract_prefix(raw_code: str) -> str:
    """Short display prefix for admin/support (never the full code)."""
    parts = raw_code.split("-")
    return "-".join(parts[:3]) if len(parts) >= 3 else raw_code[:16]


def _hash_for_audit(value: str | None) -> str | None:
    """One-way hash for IP/user-agent. Raw values never stored."""
    if not value:
        return None
    return hashlib.sha256(value.encode()).hexdigest()[:16]


# ── PostgreSQL-backed rate limiting ───────────────────────────────────────

def _check_rate_limit_db(db: Session, user_id: uuid.UUID, ip: str | None) -> str | None:
    """Check redemption attempt rate limits using rebate_redemptions table.

    Counts recent attempts (all result_status values) within the window.
    This works across all workers because it queries the shared database.
    Returns error message if blocked, None if allowed.
    """
    cutoff = datetime.now(timezone.utc) - timedelta(seconds=_WINDOW_SECONDS)

    # Per-user check
    user_count = db.query(func.count(RebateRedemption.id)).filter(
        RebateRedemption.user_id == user_id,
        RebateRedemption.redeemed_at >= cutoff,
    ).scalar() or 0

    if user_count >= _MAX_ATTEMPTS_PER_USER:
        _log.info("REBATE_THROTTLED user=%s count=%d", user_id, user_count)
        return "Too many attempts. Please wait a few minutes."

    # Per-IP check (uses hashed IP for lookup against existing records)
    if ip:
        ip_hash = _hash_for_audit(ip)
        ip_count = db.query(func.count(RebateRedemption.id)).filter(
            RebateRedemption.source_ip_hash == ip_hash,
            RebateRedemption.redeemed_at >= cutoff,
        ).scalar() or 0

        if ip_count >= _MAX_ATTEMPTS_PER_IP:
            _log.info("REBATE_THROTTLED ip_hash=%s count=%d", ip_hash, ip_count)
            return "Too many attempts from this location. Please wait."

    return None


def _record_attempt(
    db: Session,
    code_id: uuid.UUID | None,
    user_id: uuid.UUID,
    ip: str | None,
    ua: str | None,
    status: str,
) -> None:
    """Record a redemption attempt for throttling and audit.

    Failed attempts with no code_id use a sentinel UUID.
    """
    r = RebateRedemption(
        rebate_code_id=code_id,
        user_id=user_id,
        source_ip_hash=_hash_for_audit(ip),
        user_agent_hash=_hash_for_audit(ua),
        result_status=status,
    )
    db.add(r)


# ── Admin IP allowlist ────────────────────────────────────────────────────

def check_admin_ip(request_ip: str | None) -> bool:
    """Check if request IP is in the admin allowlist.

    Returns True if allowlist is not configured (disabled) or IP is allowed.
    Returns False if allowlist is configured and IP is not in it.

    Reads ADMIN_IP_ALLOWLIST from settings. Format: comma-separated IPs or CIDRs.
    Trusts X-Real-IP if set (assumes reverse proxy like nginx is configured).
    """
    allowlist_str = getattr(settings, "ADMIN_IP_ALLOWLIST", "")
    if not allowlist_str:
        return True  # Disabled

    if not request_ip:
        return False

    allowed = [s.strip() for s in allowlist_str.split(",") if s.strip()]
    if not allowed:
        return True

    try:
        client_ip = ipaddress.ip_address(request_ip)
    except ValueError:
        return False

    for entry in allowed:
        try:
            if "/" in entry:
                if client_ip in ipaddress.ip_network(entry, strict=False):
                    return True
            else:
                if client_ip == ipaddress.ip_address(entry):
                    return True
        except ValueError:
            continue

    return False


# ── Code generation ───────────────────────────────────────────────────────

def _generate_raw_code() -> str:
    segments = [
        "".join(secrets.choice(_ALPHABET) for _ in range(_SEGMENT_LENGTH))
        for _ in range(_SEGMENT_COUNT)
    ]
    return f"{_CODE_PREFIX}-" + "-".join(segments)


def create_rebate_codes(
    db: Session,
    count: int = 1,
    campaign_name: str | None = None,
    expires_at: datetime | None = None,
    allowed_email: str | None = None,
    allowed_email_domain: str | None = None,
    note: str | None = None,
    grant_duration_days: int | None = None,
    created_by: str | None = None,
) -> list[tuple[str, RebateCode]]:
    """Create rebate codes. Returns (raw_code, db_row) pairs.

    Raw codes only available in this return value. Never stored or logged.
    """
    results = []
    for _ in range(min(count, 500)):
        for attempt in range(10):
            raw = _generate_raw_code()
            code_hash = _hash_code(raw)
            existing = db.query(RebateCode).filter(RebateCode.code_hash == code_hash).first()
            if not existing:
                break
        else:
            _log.error("REBATE_CODE_COLLISION could not generate unique code after 10 attempts")
            continue

        row = RebateCode(
            code_hash=code_hash,
            code_prefix=_extract_prefix(raw),
            campaign_name=campaign_name,
            note=note,
            created_by=created_by,
            expires_at=expires_at,
            allowed_email=allowed_email.lower().strip() if allowed_email else None,
            allowed_email_domain=allowed_email_domain.lower().strip() if allowed_email_domain else None,
            grant_duration_days=grant_duration_days if (grant_duration_days and grant_duration_days > 0) else None,
        )
        db.add(row)
        results.append((raw, row))

    db.flush()
    _log.info("REBATE_CODE_CREATED count=%d campaign=%s created_by=%s",
              len(results), campaign_name, created_by)
    return results


# ── Code redemption ───────────────────────────────────────────────────────

class RedemptionResult:
    def __init__(self, success: bool, error_code: str | None = None, message: str = ""):
        self.success = success
        self.error_code = error_code
        self.message = message


def redeem_code(
    db: Session,
    user_id: uuid.UUID,
    raw_code: str,
    source_ip: str | None = None,
    user_agent: str | None = None,
) -> RedemptionResult:
    """Redeem a rebate code. Atomic, race-safe, rate-limited, idempotent.

    Idempotency: if the user already successfully redeemed this exact code
    (post-commit retry), returns stable access_already_granted response.
    """
    # DB-backed rate limit
    throttle_msg = _check_rate_limit_db(db, user_id, source_ip)
    if throttle_msg:
        return RedemptionResult(False, "rate_limited", throttle_msg)

    now = datetime.now(timezone.utc)
    code_hash = _hash_code(raw_code)

    # Lock the code row
    code_row = (
        db.execute(
            select(RebateCode)
            .where(RebateCode.code_hash == code_hash)
            .with_for_update()
        )
        .scalar_one_or_none()
    )

    if not code_row:
        _record_attempt(db, None, user_id, source_ip, user_agent, "invalid_code")
        db.commit()
        _log.info("REBATE_CODE_REDEEM_FAIL user=%s reason=not_found", user_id)
        return RedemptionResult(False, "invalid_or_ineligible_code", "Code not recognized.")

    # Idempotency: check if this exact user already redeemed this exact code
    prior_same = db.query(RebateRedemption).filter(
        RebateRedemption.rebate_code_id == code_row.id,
        RebateRedemption.user_id == user_id,
        RebateRedemption.result_status == "success",
    ).first()
    if prior_same:
        _log.info("REBATE_CODE_REDEEM_RETRY user=%s code_prefix=%s", user_id, code_row.code_prefix)
        return RedemptionResult(True, "rebate_already_recorded",
                                "This rebate code was already recorded.")

    if code_row.revoked_at is not None:
        _record_attempt(db, code_row.id, user_id, source_ip, user_agent, "code_revoked")
        db.commit()
        _log.info("REBATE_CODE_REDEEM_FAIL user=%s code_prefix=%s reason=revoked",
                   user_id, code_row.code_prefix)
        return RedemptionResult(False, "code_revoked", "This code is no longer valid.")

    if code_row.expires_at and code_row.expires_at < now:
        _record_attempt(db, code_row.id, user_id, source_ip, user_agent, "code_expired")
        db.commit()
        _log.info("REBATE_CODE_REDEEM_FAIL user=%s code_prefix=%s reason=expired",
                   user_id, code_row.code_prefix)
        return RedemptionResult(False, "code_expired", "This code has expired.")

    if code_row.redeemed_count >= code_row.max_redemptions:
        _record_attempt(db, code_row.id, user_id, source_ip, user_agent, "already_redeemed")
        db.commit()
        _log.info("REBATE_CODE_REDEEM_FAIL user=%s code_prefix=%s reason=already_redeemed",
                   user_id, code_row.code_prefix)
        return RedemptionResult(False, "already_redeemed", "This code has already been used.")

    user = db.query(User).filter(User.id == user_id).first()
    if not user:
        return RedemptionResult(False, "invalid_or_ineligible_code", "Account not found.")

    user_email = user.email.lower().strip()

    if code_row.allowed_email and user_email != code_row.allowed_email:
        _record_attempt(db, code_row.id, user_id, source_ip, user_agent, "email_mismatch")
        db.commit()
        _log.info("REBATE_CODE_REDEEM_FAIL user=%s code_prefix=%s reason=email_mismatch",
                   user_id, code_row.code_prefix)
        return RedemptionResult(False, "invalid_or_ineligible_code",
                                "This code is not valid for your account.")

    if code_row.allowed_email_domain:
        domain = user_email.split("@")[-1] if "@" in user_email else ""
        if domain != code_row.allowed_email_domain:
            _record_attempt(db, code_row.id, user_id, source_ip, user_agent, "domain_mismatch")
            db.commit()
            _log.info("REBATE_CODE_REDEEM_FAIL user=%s code_prefix=%s reason=domain_mismatch",
                       user_id, code_row.code_prefix)
            return RedemptionResult(False, "invalid_or_ineligible_code",
                                    "This code is not valid for your account.")

    # Check one-rebate-per-account rule
    prior_any = db.query(RebateRedemption).filter(
        RebateRedemption.user_id == user_id,
        RebateRedemption.result_status == "success",
    ).first()
    if prior_any:
        _record_attempt(db, code_row.id, user_id, source_ip, user_agent, "account_already_used")
        db.commit()
        _log.info("REBATE_CODE_REDEEM_FAIL user=%s reason=rebate_already_used_by_account", user_id)
        return RedemptionResult(False, "rebate_already_used_by_account",
                                "Your account has already used a rebate code.")

    # Atomic redemption. Rebate codes are now historical/informational only;
    # they never create an access-controlling entitlement.
    try:
        redemption = RebateRedemption(
            rebate_code_id=code_row.id,
            user_id=user_id,
            source_ip_hash=_hash_for_audit(source_ip),
            user_agent_hash=_hash_for_audit(user_agent),
            result_status="success",
        )
        db.add(redemption)
        code_row.redeemed_count += 1
        db.commit()

        _log.info("REBATE_CODE_REDEEM_RECORDED user=%s code_prefix=%s campaign=%s duration=%s",
                   user_id, code_row.code_prefix, code_row.campaign_name,
                   code_row.grant_duration_days or "lifetime")
        return RedemptionResult(
            True,
            None,
            "Rebate code recorded. Torve access is free and does not require a code.",
        )

    except IntegrityError:
        db.rollback()
        _log.warning("REBATE_CODE_REDEEM_CONFLICT user=%s code_prefix=%s",
                      user_id, code_row.code_prefix)
        return RedemptionResult(False, "already_redeemed",
                                "This code has already been used.")


# ── Code revocation ───────────────────────────────────────────────────────

def revoke_code(db: Session, code_id: uuid.UUID, reason: str | None = None) -> RebateCode | None:
    code_row = db.query(RebateCode).filter(RebateCode.id == code_id).first()
    if not code_row:
        return None
    if code_row.revoked_at is not None:
        return code_row
    code_row.revoked_at = datetime.now(timezone.utc)
    code_row.revoked_reason = reason
    db.commit()
    _log.info("REBATE_CODE_REVOKED code_prefix=%s reason=%s", code_row.code_prefix, reason)
    return code_row
