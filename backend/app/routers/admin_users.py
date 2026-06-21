"""
Admin user-management endpoints. Powers the /app/admin-accounts.html UI.

One aggregate GET endpoint joins user + devices + entitlements + payments
+ lifetime-grant ledger so the UI gets everything it needs in a single
round-trip. Action endpoints are narrow and idempotent where possible.

All endpoints are gated by PADDLE_ADMIN_SECRET via _verify_admin (same
header as the other admin routers). Every authenticated call is logged
at warning level by _verify_admin → ADMIN_CALL audit line.
"""
import hmac as _hmac
import logging
import uuid
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, Header, HTTPException, Query, Request
from pydantic import BaseModel, EmailStr, Field
from sqlalchemy import func, or_
from sqlalchemy.orm import Session

from app.config import settings
from app.deps import get_db
from app.billing import resolve_access_state
from app.beta_campaign import (
    beta_free_access_end_at,
    beta_free_access_ended,
    capped_beta_grant_expires_at,
)
from app.discord_beta import (
    BETA_SOURCE_DISCORD,
    DiscordBotClient,
    expire_due_grants_for_user,
    get_active_beta_grant,
    get_latest_beta_grant,
    linked_discord_user_id,
    revoke_beta_access,
    sanitize_application_text,
)
from app.models import (
    AccountSettings,
    BetaAccessGrant,
    Device,
    DevicePairing,
    LifetimeGrantRecord,
    PairingCode,
    PairingSigninCode,
    StreamHandoffReference,
    TransferSession,
    User,
    UserEntitlement,
    UserMediaFavorite,
    WatchStateReport,
    WebPayment,
)

_log = logging.getLogger(__name__)

router = APIRouter(prefix="/admin/users", tags=["admin"])


def _verify_admin(request: Request, x_admin_secret: str = Header(None)):
    if not settings.PADDLE_ADMIN_SECRET:
        raise HTTPException(status_code=503, detail="Not configured")
    if not x_admin_secret:
        raise HTTPException(status_code=403, detail="Forbidden")
    if not _hmac.compare_digest(x_admin_secret, settings.PADDLE_ADMIN_SECRET):
        raise HTTPException(status_code=403, detail="Forbidden")
    client_ip = request.headers.get("x-real-ip") or (request.client.host if request.client else None)
    _log.warning("ADMIN_CALL ip=%s method=%s path=%s", client_ip, request.method, request.url.path)


# ── DTO helpers ──────────────────────────────────────────────────────


def _iso(dt: datetime | None) -> str | None:
    return _as_utc(dt).isoformat() if dt else None


def _as_utc(dt: datetime) -> datetime:
    if dt.tzinfo is None:
        return dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc)


def _beta_access_summary(grant: BetaAccessGrant | None) -> dict:
    if not grant:
        return {
            "id": None,
            "active": False,
            "source": None,
            "status": "none",
            "starts_at": None,
            "expires_at": None,
            "revoked_at": None,
            "discord_user_id": None,
            "days_remaining": None,
        }

    now = datetime.now(timezone.utc)
    grant_expires_at = _as_utc(grant.expires_at)
    active = (
        grant.status == "active"
        and grant_expires_at > now
        and not beta_free_access_ended(now=now)
    )
    status = (
        "expired"
        if grant.status == "active" and not active
        else grant.status
    )
    days_remaining = None
    if active:
        seconds = max(0.0, (grant_expires_at - now).total_seconds())
        days_remaining = int((seconds + 86399) // 86400)

    return {
        "id": str(grant.id),
        "active": active,
        "source": grant.source,
        "status": status,
        "starts_at": _iso(grant.starts_at),
        "expires_at": _iso(grant.expires_at),
        "revoked_at": _iso(grant.revoked_at),
        "discord_user_id": grant.discord_user_id,
        "days_remaining": days_remaining,
    }


def _latest_beta_grants_by_user(db: Session, user_ids: list[uuid.UUID]) -> dict[uuid.UUID, BetaAccessGrant]:
    if not user_ids:
        return {}
    grants = (
        db.query(BetaAccessGrant)
        .filter(
            BetaAccessGrant.torve_user_id.in_(user_ids),
            BetaAccessGrant.source == BETA_SOURCE_DISCORD,
        )
        .order_by(BetaAccessGrant.created_at.desc())
        .all()
    )
    latest: dict[uuid.UUID, BetaAccessGrant] = {}
    for grant in grants:
        latest.setdefault(grant.torve_user_id, grant)
    return latest


def _user_summary(
    u: User,
    device_count: int,
    active_entitlements: int,
    beta_access: dict | None = None,
    access_state: dict | None = None,
) -> dict:
    access_state = access_state or {}
    return {
        "id": str(u.id),
        "email": u.email,
        "display_name": u.display_name,
        "is_active": u.is_active,
        "is_verified": u.is_verified,
        "has_premium_access": bool(access_state.get("has_premium_access", u.has_premium_access)),
        "has_lifetime_access": False,
        "access_tier": access_state.get("access_tier"),
        "entitlement_type": access_state.get("entitlement_type"),
        "entitlement_source": access_state.get("source"),
        "entitlement_expires_at": access_state.get("expires_at"),
        "device_cap_override": u.device_cap_override,
        "device_limit": u.device_cap_override or settings.MAX_DEVICES_PER_ACCOUNT,
        "created_at": _iso(u.created_at),
        "updated_at": _iso(u.updated_at),
        "device_count": device_count,
        "active_entitlements": active_entitlements,
        "beta_access": beta_access or _beta_access_summary(None),
    }


def _device_row(d: Device) -> dict:
    return {
        "id": str(d.id),
        "device_type": d.device_type,
        "platform": d.platform,
        "display_name": d.display_name,
        "installation_id": d.installation_id,
        "stable_device_id": d.stable_device_id,
        "app_version": d.app_version,
        "is_active": d.is_active,
        "last_seen_at": _iso(d.last_seen_at),
        "revoked_at": _iso(d.revoked_at),
        "created_at": _iso(d.created_at),
    }


def _entitlement_row(e: UserEntitlement) -> dict:
    return {
        "id": str(e.id),
        "entitlement_type": e.entitlement_type,
        "source": e.source,
        "source_ref": e.source_ref,
        "product_id": e.product_id,
        "status": e.status,
        "granted_at": _iso(e.granted_at),
        "expires_at": _iso(e.expires_at),
        "auto_renew": e.auto_renew,
        "originating_device_id": str(e.originating_device_id) if e.originating_device_id else None,
        "revoked_at": _iso(e.revoked_at),
        "last_verified_at": _iso(e.last_verified_at),
    }


def _payment_row(p: WebPayment) -> dict:
    return {
        "id": str(p.id),
        "paddle_transaction_id": p.paddle_transaction_id,
        "amount": p.amount,
        "currency": p.currency,
        "status": p.status,
        "discount_code": p.discount_code,
        "entitlement_granted": p.entitlement_granted,
        "refunded_at": _iso(p.refunded_at),
        "revoked_at": _iso(p.revoked_at),
        "last_event_type": p.last_event_type,
        "created_at": _iso(p.created_at),
    }


DEVICE_ACTIVITY_CATEGORIES = (
    {
        "id": "device",
        "label": "Device registration and status",
        "policy_scope": "device info, app version, account/device sync",
    },
    {
        "id": "pairing",
        "label": "Pairing and sign-in",
        "policy_scope": "paired-device info and service interaction logs",
    },
    {
        "id": "entitlements",
        "label": "Entitlement origin",
        "policy_scope": "purchases, account/device sync, fraud prevention",
    },
    {
        "id": "watch_state",
        "label": "Playback resume reports",
        "policy_scope": "playback sync and service interaction logs",
    },
    {
        "id": "favorites",
        "label": "Favorites and watchlist",
        "policy_scope": "watchlist and personalization sync",
    },
    {
        "id": "settings",
        "label": "Settings updates",
        "policy_scope": "UI, personalization, and app configuration settings",
    },
    {
        "id": "stream_handoff",
        "label": "Stream handoff references",
        "policy_scope": "service interaction logs and troubleshooting",
    },
    {
        "id": "transfers",
        "label": "Credential transfer sessions",
        "policy_scope": "paired-device info and service interaction logs",
    },
)
DEVICE_ACTIVITY_CATEGORY_IDS = {c["id"] for c in DEVICE_ACTIVITY_CATEGORIES}


def _short_ref(value: object | None, keep: int = 12) -> str | None:
    if value is None:
        return None
    text = str(value)
    if len(text) <= keep:
        return text
    return f"{text[:keep]}..."


def _activity_event(
    *,
    when: datetime | None,
    category: str,
    action: str,
    summary: str,
    metadata: dict | None = None,
) -> dict:
    return {
        "at": _iso(when),
        "category": category,
        "action": action,
        "summary": summary,
        "metadata": metadata or {},
    }


def _selected_activity_categories(categories: str | None) -> list[str]:
    if not categories:
        return [c["id"] for c in DEVICE_ACTIVITY_CATEGORIES]
    selected = [part.strip() for part in categories.split(",") if part.strip()]
    unknown = sorted(set(selected) - DEVICE_ACTIVITY_CATEGORY_IDS)
    if unknown:
        raise HTTPException(
            status_code=400,
            detail=f"Unknown activity categories: {', '.join(unknown)}",
        )
    return selected


def _transfer_session_state(row: TransferSession, now: datetime) -> str:
    expires_at = _as_utc(row.expires_at)
    if row.consumed_at is not None:
        return "consumed"
    if expires_at < now:
        return "expired"
    if row.envelope_json is not None:
        return "delivered"
    return "pending"


def _pairing_code_state(row: PairingCode, now: datetime) -> str:
    if row.claimed_at is not None:
        return "claimed"
    if _as_utc(row.expires_at) <= now:
        return "expired_unclaimed"
    return "active_unclaimed"


def _device_activity_device_events(device: Device) -> list[dict]:
    events = [
        _activity_event(
            when=device.created_at,
            category="device",
            action="registered",
            summary="Device registered",
            metadata={
                "device_type": device.device_type,
                "platform": device.platform,
                "display_name": device.display_name,
                "app_version": device.app_version,
            },
        ),
        _activity_event(
            when=device.last_seen_at,
            category="device",
            action="last_seen",
            summary="Device last seen",
            metadata={
                "is_active": device.is_active,
                "app_version": device.app_version,
            },
        ),
    ]
    if device.revoked_at:
        events.append(
            _activity_event(
                when=device.revoked_at,
                category="device",
                action="revoked",
                summary="Device revoked",
                metadata={"is_active": device.is_active},
            )
        )
    return events


def _device_activity_pairing_events(
    db: Session,
    *,
    user_id: uuid.UUID,
    device: Device,
    limit: int,
) -> list[dict]:
    events: list[dict] = []
    now = datetime.now(timezone.utc)
    pairings = (
        db.query(DevicePairing)
        .filter(
            DevicePairing.user_id == user_id,
            or_(
                DevicePairing.controller_device_id == device.id,
                DevicePairing.target_device_id == device.id,
            ),
        )
        .order_by(DevicePairing.updated_at.desc())
        .limit(limit)
        .all()
    )
    for pairing in pairings:
        role = "controller" if pairing.controller_device_id == device.id else "target"
        other_device_id = (
            pairing.target_device_id if role == "controller" else pairing.controller_device_id
        )
        events.append(
            _activity_event(
                when=pairing.created_at,
                category="pairing",
                action="pairing_created",
                summary=f"Device paired as {role}",
                metadata={
                    "pairing_id": str(pairing.id),
                    "role": role,
                    "status": pairing.status,
                    "other_device_id": str(other_device_id),
                },
            )
        )
        if pairing.status != "active":
            events.append(
                _activity_event(
                    when=pairing.updated_at,
                    category="pairing",
                    action="pairing_updated",
                    summary=f"Pairing marked {pairing.status}",
                    metadata={
                        "pairing_id": str(pairing.id),
                        "role": role,
                        "status": pairing.status,
                    },
                )
            )

    pairing_codes = (
        db.query(PairingCode)
        .filter(
            PairingCode.user_id == user_id,
            or_(
                PairingCode.target_device_id == device.id,
                PairingCode.claimed_by_device_id == device.id,
            ),
        )
        .order_by(PairingCode.created_at.desc())
        .limit(limit)
        .all()
    )
    for code in pairing_codes:
        role = "target" if code.target_device_id == device.id else "claiming_device"
        events.append(
            _activity_event(
                when=code.created_at,
                category="pairing",
                action="pairing_code_created",
                summary="Pairing code created",
                metadata={
                    "pairing_code_id": str(code.id),
                    "role": role,
                    "status": _pairing_code_state(code, now),
                    "expires_at": _iso(code.expires_at),
                },
            )
        )
        if code.claimed_at:
            events.append(
                _activity_event(
                    when=code.claimed_at,
                    category="pairing",
                    action="pairing_code_claimed",
                    summary="Pairing code claimed",
                    metadata={
                        "pairing_code_id": str(code.id),
                        "target_device_id": str(code.target_device_id),
                        "claimed_by_device_id": (
                            str(code.claimed_by_device_id)
                            if code.claimed_by_device_id
                            else None
                        ),
                    },
                )
            )

    install_ids = [
        value
        for value in (device.installation_id, device.stable_device_id)
        if value
    ]
    signin_filter = PairingSigninCode.claimed_device_id == device.id
    if install_ids:
        signin_filter = or_(
            signin_filter,
            PairingSigninCode.device_installation_id.in_(install_ids),
        )
    signin_codes = (
        db.query(PairingSigninCode)
        .filter(
            PairingSigninCode.claimed_by_user_id == user_id,
            signin_filter,
        )
        .order_by(PairingSigninCode.created_at.desc())
        .limit(limit)
        .all()
    )
    for code in signin_codes:
        events.append(
            _activity_event(
                when=code.created_at,
                category="pairing",
                action="signin_code_created",
                summary="Device sign-in code created",
                metadata={
                    "device_installation_id": _short_ref(code.device_installation_id),
                    "device_name": code.device_name,
                    "device_type": code.device_type,
                    "platform": code.platform,
                    "expires_at": _iso(code.expires_at),
                },
            )
        )
        if code.claimed_at:
            events.append(
                _activity_event(
                    when=code.claimed_at,
                    category="pairing",
                    action="signin_code_claimed",
                    summary="Device sign-in code claimed",
                    metadata={
                        "claimed_device_id": (
                            str(code.claimed_device_id)
                            if code.claimed_device_id
                            else None
                        ),
                    },
                )
            )
        if code.consumed_at:
            events.append(
                _activity_event(
                    when=code.consumed_at,
                    category="pairing",
                    action="signin_code_consumed",
                    summary="Device sign-in token consumed",
                    metadata={
                        "claimed_device_id": (
                            str(code.claimed_device_id)
                            if code.claimed_device_id
                            else None
                        ),
                    },
                )
            )
    return events


def _device_activity_entitlement_events(
    db: Session,
    *,
    user_id: uuid.UUID,
    device_id: uuid.UUID,
    limit: int,
) -> list[dict]:
    events: list[dict] = []
    rows = (
        db.query(UserEntitlement)
        .filter(
            UserEntitlement.user_id == user_id,
            UserEntitlement.originating_device_id == device_id,
        )
        .order_by(UserEntitlement.granted_at.desc())
        .limit(limit)
        .all()
    )
    for ent in rows:
        metadata = {
            "entitlement_id": str(ent.id),
            "entitlement_type": ent.entitlement_type,
            "source": ent.source,
            "source_ref": _short_ref(ent.source_ref),
            "product_id": ent.product_id,
            "status": ent.status,
            "expires_at": _iso(ent.expires_at),
            "auto_renew": ent.auto_renew,
        }
        events.append(
            _activity_event(
                when=ent.granted_at,
                category="entitlements",
                action="entitlement_granted",
                summary=f"{ent.entitlement_type} granted from this device",
                metadata=metadata,
            )
        )
        if ent.revoked_at:
            events.append(
                _activity_event(
                    when=ent.revoked_at,
                    category="entitlements",
                    action="entitlement_revoked",
                    summary=f"{ent.entitlement_type} revoked",
                    metadata=metadata,
                )
            )
    return events


def _device_activity_watch_state_events(
    db: Session,
    *,
    user_id: uuid.UUID,
    device_id: uuid.UUID,
    limit: int,
) -> list[dict]:
    rows = (
        db.query(WatchStateReport)
        .filter(
            WatchStateReport.user_id == user_id,
            WatchStateReport.device_id == device_id,
        )
        .order_by(WatchStateReport.reported_at.desc())
        .limit(limit)
        .all()
    )
    return [
        _activity_event(
            when=row.reported_at,
            category="watch_state",
            action="watch_state_reported",
            summary="Playback position reported",
            metadata={
                "content_id": row.content_id,
                "provider": row.provider,
                "position_ms": row.position_ms,
            },
        )
        for row in rows
    ]


def _device_activity_favorite_events(
    db: Session,
    *,
    user_id: uuid.UUID,
    device_id: uuid.UUID,
    limit: int,
) -> list[dict]:
    rows = (
        db.query(UserMediaFavorite)
        .filter(
            UserMediaFavorite.user_id == user_id,
            UserMediaFavorite.source_device_id == device_id,
        )
        .order_by(UserMediaFavorite.updated_at.desc())
        .limit(limit)
        .all()
    )
    return [
        _activity_event(
            when=row.updated_at or row.added_at,
            category="favorites",
            action="favorite_saved",
            summary="Favorite or watchlist item saved",
            metadata={
                "media_type": row.media_type,
                "title": row.title,
                "year": row.year,
                "tmdb_id": row.tmdb_id,
                "imdb_id": row.imdb_id,
            },
        )
        for row in rows
    ]


def _device_activity_settings_events(
    db: Session,
    *,
    user_id: uuid.UUID,
    device_id: uuid.UUID,
) -> list[dict]:
    row = (
        db.query(AccountSettings)
        .filter(
            AccountSettings.user_id == user_id,
            AccountSettings.updated_by_device_id == device_id,
        )
        .first()
    )
    if not row:
        return []
    keys = sorted(row.settings.keys()) if isinstance(row.settings, dict) else []
    return [
        _activity_event(
            when=row.updated_at,
            category="settings",
            action="account_settings_updated",
            summary="Account settings updated from this device",
            metadata={
                "settings_id": str(row.id),
                "version": row.version,
                "settings_keys": keys,
            },
        )
    ]


def _device_activity_stream_handoff_events(
    db: Session,
    *,
    user_id: uuid.UUID,
    device_id: uuid.UUID,
    limit: int,
) -> list[dict]:
    rows = (
        db.query(StreamHandoffReference)
        .filter(
            StreamHandoffReference.user_id == user_id,
            StreamHandoffReference.device_id == device_id,
        )
        .order_by(StreamHandoffReference.created_at.desc())
        .limit(limit)
        .all()
    )
    return [
        _activity_event(
            when=row.created_at,
            category="stream_handoff",
            action="stream_handoff_created",
            summary="Stream handoff reference created",
            metadata={
                "stream_id": _short_ref(row.stream_id),
                "content_id": row.content_id,
                "provider_type": row.provider_type,
                "source_ref": _short_ref(row.source_ref),
                "expires_at": _iso(row.expires_at),
            },
        )
        for row in rows
    ]


def _device_activity_transfer_events(
    db: Session,
    *,
    user_id: uuid.UUID,
    device: Device,
    limit: int,
) -> list[dict]:
    device_refs = [
        value
        for value in (str(device.id), device.installation_id, device.stable_device_id)
        if value
    ]
    if not device_refs:
        return []
    rows = (
        db.query(TransferSession)
        .filter(
            TransferSession.user_id == user_id,
            TransferSession.receiver_device_id.in_(device_refs),
        )
        .order_by(TransferSession.created_at.desc())
        .limit(limit)
        .all()
    )
    now = datetime.now(timezone.utc)
    events: list[dict] = []
    for row in rows:
        metadata = {
            "session_id": str(row.id),
            "state": _transfer_session_state(row, now),
            "receiver_device_id": _short_ref(row.receiver_device_id),
            "receiver_device_name": row.receiver_device_name,
            "expires_at": _iso(row.expires_at),
        }
        events.append(
            _activity_event(
                when=row.created_at,
                category="transfers",
                action="transfer_session_created",
                summary="Credential transfer session created",
                metadata=metadata,
            )
        )
        if row.delivered_at:
            events.append(
                _activity_event(
                    when=row.delivered_at,
                    category="transfers",
                    action="transfer_session_delivered",
                    summary="Credential transfer envelope delivered",
                    metadata=metadata,
                )
            )
        if row.consumed_at:
            events.append(
                _activity_event(
                    when=row.consumed_at,
                    category="transfers",
                    action="transfer_session_consumed",
                    summary="Credential transfer envelope consumed",
                    metadata=metadata,
                )
            )
    return events


def _grant_row(g: LifetimeGrantRecord) -> dict:
    return {
        "id": str(g.id),
        "email": g.email,
        "source": g.source,
        "source_ref": g.source_ref,
        "product_id": g.product_id,
        "notes": g.notes,
        "granted_at": _iso(g.granted_at),
        "revoked_at": _iso(g.revoked_at),
        "revoke_reason": getattr(g, "revoke_reason", None),
    }


# ── List + search ────────────────────────────────────────────────────


@router.get("", dependencies=[Depends(_verify_admin)])
def list_users(
    q: str = Query(default="", description="Email substring or display-name substring"),
    is_verified: bool | None = Query(default=None),
    has_premium: bool | None = Query(default=None),
    is_active: bool | None = Query(default=None),
    limit: int = Query(default=50, le=200),
    offset: int = Query(default=0, ge=0),
    db: Session = Depends(get_db),
):
    """Paginated user search. Returns lightweight summary rows.

    The full per-user detail (devices/entitlements/payments) is on the
    `GET /{id}` aggregate endpoint, not here — keeps the search list fast.
    """
    base = db.query(User)
    if q:
        needle = f"%{q.lower().strip()}%"
        base = base.filter(or_(User.email.ilike(needle), User.display_name.ilike(needle)))
    if is_verified is not None:
        base = base.filter(User.is_verified == is_verified)
    if has_premium is not None:
        now = datetime.now(timezone.utc)
        if beta_free_access_ended(now=now):
            base = base.filter(User.has_premium_access == has_premium)
        else:
            active_beta_users = db.query(BetaAccessGrant.torve_user_id).filter(
                BetaAccessGrant.source == BETA_SOURCE_DISCORD,
                BetaAccessGrant.status == "active",
                BetaAccessGrant.expires_at > now,
            )
            if has_premium:
                base = base.filter(or_(User.has_premium_access == True, User.id.in_(active_beta_users)))  # noqa: E712
            else:
                base = base.filter(User.has_premium_access == False, ~User.id.in_(active_beta_users))  # noqa: E712
    if is_active is not None:
        base = base.filter(User.is_active == is_active)

    total = base.count()
    users = (
        base.order_by(User.created_at.desc())
        .offset(offset)
        .limit(limit)
        .all()
    )
    if not users:
        return {"total": total, "users": []}

    user_ids = [u.id for u in users]
    device_counts = dict(
        db.query(Device.user_id, func.count(Device.id))
        .filter(Device.user_id.in_(user_ids), Device.is_active == True)  # noqa: E712
        .group_by(Device.user_id)
        .all()
    )
    ent_counts = dict(
        db.query(UserEntitlement.user_id, func.count(UserEntitlement.id))
        .filter(
            UserEntitlement.user_id.in_(user_ids),
            UserEntitlement.status == "active",
        )
        .group_by(UserEntitlement.user_id)
        .all()
    )
    beta_grants = _latest_beta_grants_by_user(db, user_ids)
    access_states = {u.id: resolve_access_state(db, u.id) for u in users}
    return {
        "total": total,
        "limit": limit,
        "offset": offset,
        "users": [
            _user_summary(
                u,
                device_counts.get(u.id, 0),
                ent_counts.get(u.id, 0),
                _beta_access_summary(beta_grants.get(u.id)),
                access_states.get(u.id),
            )
            for u in users
        ],
    }


# ── Aggregate detail ─────────────────────────────────────────────────


@router.get("/{user_id}", dependencies=[Depends(_verify_admin)])
def get_user_detail(
    user_id: uuid.UUID,
    db: Session = Depends(get_db),
):
    """One-shot view: user + all devices + all entitlements + recent
    payments + matching lifetime-grant ledger rows. Powers the accounts
    UI's expand panel without a fan-out from the browser."""
    u = db.query(User).filter(User.id == user_id).first()
    if not u:
        raise HTTPException(status_code=404, detail="User not found")

    devices = (
        db.query(Device)
        .filter(Device.user_id == user_id)
        .order_by(Device.last_seen_at.desc())
        .all()
    )
    entitlements = (
        db.query(UserEntitlement)
        .filter(UserEntitlement.user_id == user_id)
        .order_by(UserEntitlement.granted_at.desc())
        .all()
    )
    payments = (
        db.query(WebPayment)
        .filter(WebPayment.user_id == user_id)
        .order_by(WebPayment.created_at.desc())
        .limit(50)
        .all()
    )
    grants = (
        db.query(LifetimeGrantRecord)
        .filter(LifetimeGrantRecord.email == u.email)
        .order_by(LifetimeGrantRecord.granted_at.desc())
        .all()
    )
    beta_access = _beta_access_summary(get_latest_beta_grant(db, user_id))
    access_state = resolve_access_state(db, user_id)
    return {
        "user": _user_summary(
            u,
            sum(1 for d in devices if d.is_active),
            sum(1 for e in entitlements if e.status == "active"),
            beta_access,
            access_state,
        ),
        "beta_access": beta_access,
        "devices": [_device_row(d) for d in devices],
        "entitlements": [_entitlement_row(e) for e in entitlements],
        "payments": [_payment_row(p) for p in payments],
        "lifetime_grants": [_grant_row(g) for g in grants],
    }


@router.get("/{user_id}/devices/{device_id}/activity", dependencies=[Depends(_verify_admin)])
def get_device_activity(
    user_id: uuid.UUID,
    device_id: uuid.UUID,
    categories: str | None = Query(
        default=None,
        description="Comma-separated category ids. Defaults to all policy-scoped categories.",
    ),
    limit: int = Query(default=25, ge=1, le=100),
    db: Session = Depends(get_db),
):
    """Admin-only per-device activity rollup.

    This intentionally returns redacted operational events rather than raw
    records: pairing codes, sign-in codes, tokens, encrypted payloads, public
    keys, upstream URLs, and full settings blobs are omitted.
    """
    device = (
        db.query(Device)
        .filter(Device.id == device_id, Device.user_id == user_id)
        .first()
    )
    if not device:
        raise HTTPException(status_code=404, detail="Device not found")

    selected = _selected_activity_categories(categories)
    category_builders = {
        "device": lambda: _device_activity_device_events(device),
        "pairing": lambda: _device_activity_pairing_events(
            db, user_id=user_id, device=device, limit=limit
        ),
        "entitlements": lambda: _device_activity_entitlement_events(
            db, user_id=user_id, device_id=device.id, limit=limit
        ),
        "watch_state": lambda: _device_activity_watch_state_events(
            db, user_id=user_id, device_id=device.id, limit=limit
        ),
        "favorites": lambda: _device_activity_favorite_events(
            db, user_id=user_id, device_id=device.id, limit=limit
        ),
        "settings": lambda: _device_activity_settings_events(
            db, user_id=user_id, device_id=device.id
        ),
        "stream_handoff": lambda: _device_activity_stream_handoff_events(
            db, user_id=user_id, device_id=device.id, limit=limit
        ),
        "transfers": lambda: _device_activity_transfer_events(
            db, user_id=user_id, device=device, limit=limit
        ),
    }

    sections = {}
    all_events = []
    labels = {c["id"]: c["label"] for c in DEVICE_ACTIVITY_CATEGORIES}
    for category_id in selected:
        events = category_builders[category_id]()
        events.sort(key=lambda e: e["at"] or "", reverse=True)
        events = events[:limit]
        sections[category_id] = {
            "label": labels[category_id],
            "count": len(events),
            "events": events,
        }
        all_events.extend(events)

    all_events.sort(key=lambda e: e["at"] or "", reverse=True)
    return {
        "user_id": str(user_id),
        "device": _device_row(device),
        "available_categories": list(DEVICE_ACTIVITY_CATEGORIES),
        "selected_categories": selected,
        "categories": sections,
        "events": all_events[:limit],
        "redactions": [
            "pairing codes",
            "sign-in tokens",
            "encrypted credential envelopes",
            "public keys",
            "upstream URLs",
            "full account settings values",
        ],
    }


# ── Mutations ────────────────────────────────────────────────────────


@router.post("/{user_id}/force-verify", dependencies=[Depends(_verify_admin)])
def force_verify(user_id: uuid.UUID, db: Session = Depends(get_db)):
    u = db.query(User).filter(User.id == user_id).first()
    if not u:
        raise HTTPException(status_code=404, detail="User not found")
    u.is_verified = True
    db.commit()
    db.refresh(u)
    return {"ok": True, "is_verified": u.is_verified}


@router.post("/{user_id}/lock", dependencies=[Depends(_verify_admin)])
def lock_user(user_id: uuid.UUID, db: Session = Depends(get_db)):
    """Lock the account (is_active=False). Blocks login (auth flow
    rejects inactive users) and effectively suspends access. Reversible
    via /unlock."""
    u = db.query(User).filter(User.id == user_id).first()
    if not u:
        raise HTTPException(status_code=404, detail="User not found")
    u.is_active = False
    db.commit()
    return {"ok": True, "is_active": False}


@router.post("/{user_id}/unlock", dependencies=[Depends(_verify_admin)])
def unlock_user(user_id: uuid.UUID, db: Session = Depends(get_db)):
    u = db.query(User).filter(User.id == user_id).first()
    if not u:
        raise HTTPException(status_code=404, detail="User not found")
    u.is_active = True
    db.commit()
    return {"ok": True, "is_active": True}


class DeviceCapBody(BaseModel):
    device_cap_override: int | None = Field(default=None, ge=1, le=100)


@router.patch("/{user_id}/device-cap", dependencies=[Depends(_verify_admin)])
def set_device_cap(
    user_id: uuid.UUID,
    body: DeviceCapBody,
    db: Session = Depends(get_db),
):
    """Set or clear the per-account active-device limit override.

    Null clears the override so the account falls back to the global
    MAX_DEVICES_PER_ACCOUNT setting.
    """
    u = db.query(User).filter(User.id == user_id).first()
    if not u:
        raise HTTPException(status_code=404, detail="User not found")

    u.device_cap_override = body.device_cap_override
    db.commit()
    db.refresh(u)

    active_devices = db.query(Device).filter(
        Device.user_id == user_id,
        Device.is_active == True,  # noqa: E712
    ).count()
    effective_cap = u.device_cap_override or settings.MAX_DEVICES_PER_ACCOUNT
    _log.warning(
        "ADMIN_SET_DEVICE_CAP user=%s cap_override=%s effective_cap=%s",
        user_id, u.device_cap_override, effective_cap,
    )
    return {
        "ok": True,
        "device_cap_override": u.device_cap_override,
        "device_limit": effective_cap,
        "active_device_count": active_devices,
    }


@router.post("/{user_id}/devices/{device_id}/revoke", dependencies=[Depends(_verify_admin)])
def revoke_device(
    user_id: uuid.UUID,
    device_id: uuid.UUID,
    db: Session = Depends(get_db),
):
    """Soft-revoke a single device (is_active=False, revoked_at=now).
    The device row stays for audit; future syncs from that install_id
    won't grant access until the device is recreated by re-login."""
    d = db.query(Device).filter(Device.id == device_id, Device.user_id == user_id).first()
    if not d:
        raise HTTPException(status_code=404, detail="Device not found")
    if d.is_active:
        d.is_active = False
        d.revoked_at = datetime.now(timezone.utc)
        db.commit()
    return {"ok": True, "device": _device_row(d)}


@router.post("/{user_id}/entitlements/{entitlement_id}/revoke", dependencies=[Depends(_verify_admin)])
def revoke_entitlement(
    user_id: uuid.UUID,
    entitlement_id: uuid.UUID,
    reason: str = Query(default="admin_revoked"),
    db: Session = Depends(get_db),
):
    """Revoke a historical entitlement row and refresh legacy cache fields."""
    from app.billing import recompute_user_premium

    e = (
        db.query(UserEntitlement)
        .filter(UserEntitlement.id == entitlement_id, UserEntitlement.user_id == user_id)
        .first()
    )
    if not e:
        raise HTTPException(status_code=404, detail="Entitlement not found")
    if e.status != "revoked":
        e.status = "revoked"
        e.revoked_at = datetime.now(timezone.utc)
        recompute_user_premium(db, user_id)
        db.commit()
    _log.warning("ADMIN_REVOKE_ENTITLEMENT user=%s ent=%s reason=%s", user_id, entitlement_id, reason)
    return {"ok": True, "entitlement": _entitlement_row(e)}


class GrantLifetimeBody(BaseModel):
    notes: str | None = None
    product_id: str | None = None


@router.post("/{user_id}/grant-lifetime", dependencies=[Depends(_verify_admin)])
def grant_lifetime(
    user_id: uuid.UUID,
    body: GrantLifetimeBody,
    db: Session = Depends(get_db),
):
    """Record historical lifetime-grant metadata without changing access."""
    from app.billing import (
        SOURCE_ADMIN,
        _record_lifetime_grant,
    )
    u = db.query(User).filter(User.id == user_id).first()
    if not u:
        raise HTTPException(status_code=404, detail="User not found")
    source_ref = f"admin-portal:{datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S')}"
    row = _record_lifetime_grant(
        db, email=u.email, source=SOURCE_ADMIN, source_ref=source_ref,
        product_id=body.product_id, notes=body.notes,
    )
    db.commit()
    return {
        "ok": True,
        "grant_id": str(row.id) if row else None,
        "entitlement_id": None,
        "access_effect": "none",
    }


class GrantTemporaryBody(BaseModel):
    duration_days: int | None = None
    expires_at: str | None = None  # ISO-8601, e.g. 2026-05-31T23:59:59Z
    notes: str | None = None
    product_id: str | None = None


@router.post("/{user_id}/grant-temporary", dependencies=[Depends(_verify_admin)])
def grant_temporary(
    user_id: uuid.UUID,
    body: GrantTemporaryBody,
    db: Session = Depends(get_db),
):
    """Record a deprecated temporary-grant request without changing access."""
    if (body.duration_days is None) == (body.expires_at is None):
        raise HTTPException(
            status_code=400,
            detail="Provide exactly one of duration_days or expires_at.",
        )
    u = db.query(User).filter(User.id == user_id).first()
    if not u:
        raise HTTPException(status_code=404, detail="User not found")

    now = datetime.now(timezone.utc)
    if body.expires_at:
        try:
            expires = datetime.fromisoformat(body.expires_at.replace("Z", "+00:00"))
        except ValueError:
            raise HTTPException(status_code=400, detail="Invalid expires_at; use ISO-8601.")
        if expires.tzinfo is None:
            expires = expires.replace(tzinfo=timezone.utc)
    else:
        if body.duration_days <= 0:
            raise HTTPException(status_code=400, detail="duration_days must be positive.")
        expires = now + timedelta(days=body.duration_days)

    if expires <= now:
        raise HTTPException(status_code=400, detail="expires_at must be in the future.")

    db.commit()
    _log.warning(
        "ADMIN_GRANT_TEMPORARY_DEPRECATED_FREE_SOFTWARE user=%s expires=%s notes=%s",
        user_id, expires.isoformat(), body.notes or "",
    )
    return {
        "ok": True,
        "entitlement_id": None,
        "expires_at": expires.isoformat(),
        "access_effect": "none",
    }


class ExtendBetaBody(BaseModel):
    duration_days: int | None = Field(default=None, ge=1, le=365)
    expires_at: str | None = None  # ISO-8601, capped to BETA_FREE_ACCESS_END_AT
    reason: str | None = Field(default=None, max_length=300)


class RevokeBetaBody(BaseModel):
    reason: str | None = Field(default="admin_revoked", max_length=300)


def _parse_iso_datetime(value: str, *, field_name: str) -> datetime:
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        raise HTTPException(status_code=400, detail=f"Invalid {field_name}; use ISO-8601.")
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


@router.post("/{user_id}/beta/extend", dependencies=[Depends(_verify_admin)])
def extend_beta_access(
    user_id: uuid.UUID,
    body: ExtendBetaBody,
    db: Session = Depends(get_db),
):
    """Create or extend a discord_beta grant for this account.

    This is intentionally separate from historical paid UserEntitlement rows.
    It no longer changes product access; it only records beta/community state.
    """
    u = db.query(User).filter(User.id == user_id).first()
    if not u:
        raise HTTPException(status_code=404, detail="User not found")

    now = datetime.now(timezone.utc)
    if beta_free_access_ended(now=now):
        raise HTTPException(status_code=403, detail="Beta free access has ended.")
    if body.duration_days is not None and body.expires_at:
        raise HTTPException(status_code=400, detail="Provide duration_days or expires_at, not both.")

    expire_due_grants_for_user(db, user_id, now=now)
    active_grant = get_active_beta_grant(db, user_id, now=now)
    active_expires_at = _as_utc(active_grant.expires_at) if active_grant else None
    base = max(now, active_expires_at) if active_expires_at else now
    if body.expires_at:
        requested_expires_at = _parse_iso_datetime(body.expires_at, field_name="expires_at")
        if active_expires_at and requested_expires_at <= active_expires_at:
            raise HTTPException(status_code=400, detail="expires_at must extend the current beta grant.")
        expires_at = min(requested_expires_at, beta_free_access_end_at())
    else:
        days = body.duration_days if body.duration_days is not None else max(1, int(settings.TORVE_BETA_GRANT_DAYS or 30))
        expires_at = capped_beta_grant_expires_at(now=base, grant_days=days)

    if expires_at <= now:
        raise HTTPException(status_code=403, detail="Beta free access has ended.")

    discord_user_id = active_grant.discord_user_id if active_grant else linked_discord_user_id(db, user_id)
    if active_grant:
        grant = active_grant
        grant.expires_at = expires_at
        grant.updated_at = now
        action = "extended"
    else:
        grant = BetaAccessGrant(
            torve_user_id=user_id,
            discord_user_id=discord_user_id,
            source=BETA_SOURCE_DISCORD,
            status="active",
            starts_at=now,
            expires_at=expires_at,
            created_at=now,
            updated_at=now,
        )
        db.add(grant)
        action = "created"

    db.commit()
    db.refresh(grant)

    role_add_attempted = False
    role_add_failed = False
    if grant.discord_user_id:
        role_add_attempted = True
        result = DiscordBotClient.from_settings().add_beta_role(grant.discord_user_id)
        role_add_failed = not result.ok
        if role_add_failed:
            _log.warning(
                "ADMIN_BETA_ROLE_ADD_FAILED user=%s action=%s status=%s",
                user_id,
                result.action,
                result.status_code,
            )

    _log.warning(
        "ADMIN_BETA_GRANT_PERSISTED target_user=%s source=%s entitlement=%s "
        "status=%s action=%s expires=%s reason_present=%s",
        user_id,
        grant.source,
        grant.id,
        grant.status,
        action,
        expires_at.isoformat(),
        bool(body.reason),
    )
    return {
        "ok": True,
        "action": action,
        "beta_access": _beta_access_summary(grant),
        "role_add_attempted": role_add_attempted,
        "role_add_failed": role_add_failed,
    }


@router.post("/{user_id}/beta/revoke", dependencies=[Depends(_verify_admin)])
def revoke_admin_beta_access(
    user_id: uuid.UUID,
    body: RevokeBetaBody,
    db: Session = Depends(get_db),
):
    """Revoke active discord_beta community-state rows."""
    u = db.query(User).filter(User.id == user_id).first()
    if not u:
        raise HTTPException(status_code=404, detail="User not found")

    reason = sanitize_application_text(body.reason or "admin_revoked", max_length=300) or "admin_revoked"
    grants = revoke_beta_access(db, torve_user_id=user_id, reason=reason)
    db.commit()
    for grant in grants:
        db.refresh(grant)

    discord_user_ids = {
        grant.discord_user_id for grant in grants if grant.discord_user_id
    }
    if not discord_user_ids:
        linked_id = linked_discord_user_id(db, user_id)
        if linked_id:
            discord_user_ids.add(linked_id)

    role_remove_attempted = 0
    role_remove_failed = 0
    discord = DiscordBotClient.from_settings()
    for discord_user_id in sorted(discord_user_ids):
        role_remove_attempted += 1
        result = discord.remove_beta_role(discord_user_id)
        if not result.ok:
            role_remove_failed += 1
            _log.warning(
                "ADMIN_BETA_ROLE_REMOVE_FAILED user=%s action=%s status=%s",
                user_id,
                result.action,
                result.status_code,
            )

    latest = get_latest_beta_grant(db, user_id)
    _log.warning(
        "ADMIN_REVOKE_BETA user=%s grants=%s reason_present=%s",
        user_id,
        len(grants),
        bool(body.reason),
    )
    return {
        "ok": True,
        "revoked_count": len(grants),
        "beta_access": _beta_access_summary(latest),
        "role_remove_attempted": role_remove_attempted,
        "role_remove_failed": role_remove_failed,
    }


@router.post("/{user_id}/refresh-flags", dependencies=[Depends(_verify_admin)])
def refresh_premium_flags(user_id: uuid.UUID, db: Session = Depends(get_db)):
    """Refresh legacy access booleans for the free-software model."""
    from app.billing import recompute_user_premium

    u = db.query(User).filter(User.id == user_id).first()
    if not u:
        raise HTTPException(status_code=404, detail="User not found")
    recompute_user_premium(db, u.id)
    db.commit()
    db.refresh(u)
    return {
        "ok": True,
        "has_premium_access": u.has_premium_access,
        "has_lifetime_access": u.has_lifetime_access,
    }
