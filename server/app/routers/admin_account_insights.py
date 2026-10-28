"""Admin account/platform insights. Apple access has its own verified ledger."""
import uuid
from collections import Counter, defaultdict
from datetime import datetime, timedelta, timezone
from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy import case, func, or_, select
from sqlalchemy.orm import Session
from app.config import settings
from app.deps import get_db
from app.models import Device, User
from app import apple_subscription as apple
from app.apple_subscription_models import AppleSubscriptionOwner, AppleSubscriptionTransaction, AppleSubscriptionDevice
from app.device_activity import platform_family, utc
from app.device_activity_models import DeviceActivityTotal, DeviceActivityDay
from app.routers import admin_users

router = APIRouter(prefix="/admin/account-insights", tags=["admin"], dependencies=[Depends(admin_users._verify_admin)])
PLATFORMS = {"android", "android-tv", "firetv", "ios", "ipados", "tvos", "desktop", "web", "unknown"}


def iso(value):
    return utc(value).isoformat() if value else None


def ms_iso(value):
    return datetime.fromtimestamp(value / 1000, timezone.utc).isoformat() if value else None


def platform_expression():
    p = func.lower(func.coalesce(Device.platform, ""))
    return case(
        (p.like("%fire%"), "firetv"),
        (p.in_(["tvos", "apple-tv", "appletv"]), "tvos"),
        (or_(p.in_(["ipados", "ipad"]), (p.in_(["ios", "iphone"]) & (Device.device_type == "tablet"))), "ipados"),
        (p.in_(["ios", "iphone"]), "ios"),
        ((p.like("%android%") & or_(p.like("%tv%"), Device.device_type == "tv")), "android-tv"),
        (p.like("%android%"), "android"),
        (p.in_(["desktop", "windows", "macos", "linux", "mac"]), "desktop"),
        (p.in_(["web", "browser"]), "web"), else_="unknown")


def active_apple_user_query(environment):
    return select(AppleSubscriptionOwner.user_id).join(AppleSubscriptionTransaction,
        (AppleSubscriptionTransaction.environment == AppleSubscriptionOwner.environment) &
        (AppleSubscriptionTransaction.original_transaction_id == AppleSubscriptionOwner.original_transaction_id)).where(
        AppleSubscriptionOwner.environment == environment,
        AppleSubscriptionTransaction.product_id == settings.APPLE_SUBSCRIPTION_PRODUCT_ID,
        AppleSubscriptionTransaction.expires_at_ms > apple.now_ms(),
        AppleSubscriptionTransaction.revoked_at_ms.is_(None), AppleSubscriptionTransaction.is_upgraded.is_(False))


def environment_state(rows, environment):
    matching = [t for t in rows if t.environment == environment and t.product_id == settings.APPLE_SUBSCRIPTION_PRODUCT_ID]
    active = [t for t in matching if t.expires_at_ms > apple.now_ms() and t.revoked_at_ms is None and not t.is_upgraded]
    latest = max(matching, key=lambda t: t.signed_at_ms, default=None)
    expiry = max((t.expires_at_ms for t in active), default=None)
    return {"active": bool(active), "status": "active" if active else ("refunded" if latest and latest.revoked_at_ms else "expired" if latest else "none"),
        "expires_at": ms_iso(expiry or (latest.expires_at_ms if latest else None)),
        "last_verified_at": ms_iso(latest.signed_at_ms) if latest else None}


def _collect(db, ids):
    grouped = {key: defaultdict(list) for key in ("devices", "totals", "days", "slots", "transactions")}
    beta_lookup = getattr(admin_users, "_latest_beta_grants_by_user", None)
    grouped["beta"] = beta_lookup(db, ids) if beta_lookup and ids else {}
    if not ids: return grouped
    for key, model in (("devices", Device), ("totals", DeviceActivityTotal), ("slots", AppleSubscriptionDevice)):
        for row in db.query(model).filter(model.user_id.in_(ids)).all(): grouped[key][row.user_id].append(row)
    for row in db.query(DeviceActivityDay).filter(DeviceActivityDay.user_id.in_(ids), DeviceActivityDay.day >= (datetime.now(timezone.utc) - timedelta(days=30)).date()).all():
        grouped["days"][row.user_id].append(row)
    for uid, row in db.query(AppleSubscriptionOwner.user_id, AppleSubscriptionTransaction).join(AppleSubscriptionTransaction,
        (AppleSubscriptionTransaction.environment == AppleSubscriptionOwner.environment) &
        (AppleSubscriptionTransaction.original_transaction_id == AppleSubscriptionOwner.original_transaction_id)).filter(AppleSubscriptionOwner.user_id.in_(ids)).all():
        grouped["transactions"][uid].append(row)
    return grouped


def summary(user, collected):
    devices, totals, slots, transactions = (collected[k][user.id] for k in ("devices", "totals", "slots", "transactions"))
    platforms = Counter(platform_family(d.platform, d.device_type) for d in devices)
    # Account devices and paid slots are different identifiers; only add Apple slots
    # with no matching stable device identifier. Never count Android in Apple slots.
    known_apple = {str(d.stable_device_id).lower() for d in devices if platform_family(d.platform, d.device_type) in {"ios", "ipados", "tvos"} and d.stable_device_id}
    apple_identities = defaultdict(set)
    for s in slots: apple_identities[s.platform].add(str(s.device_id).lower())
    for t in totals:
        if t.device_key.startswith("apple:"): apple_identities[t.platform].add(t.device_key.removeprefix("apple:").lower())
    # Registered devices and Apple purchase slots have separate identifiers.
    # Keep platform counts conservative instead of counting both representations twice.
    for family, identities in apple_identities.items(): platforms[family] = max(platforms[family], len(identities))
    last = [utc(d.last_seen_at) for d in devices if d.last_seen_at] + [utc(t.last_activity_at) for t in totals]
    last += [datetime.fromtimestamp(s.last_seen_ms / 1000, timezone.utc) for s in slots]
    production, sandbox = environment_state(transactions, "Production"), environment_state(transactions, "Sandbox")
    testers = {s.strip().lower() for s in settings.APPLE_SUBSCRIPTION_SANDBOX_USER_IDS.split(",") if s.strip()}
    sandbox_allowed = str(user.id).lower() in testers
    beta_helper = getattr(admin_users, "_beta_access_summary", None)
    return {"id": str(user.id), "email": user.email, "display_name": user.display_name,
        "is_active": user.is_active, "is_verified": user.is_verified,
        "has_premium_access": user.has_premium_access, "has_lifetime_access": user.has_lifetime_access,
        "device_cap_override": user.device_cap_override, "created_at": iso(user.created_at), "updated_at": iso(user.updated_at),
        "device_count": sum(d.is_active for d in devices), "platforms": dict(platforms), "last_seen_at": iso(max(last)) if last else None,
        "first_registered_platform": platform_family(min(devices, key=lambda d: utc(d.created_at)).platform, min(devices, key=lambda d: utc(d.created_at)).device_type) if devices else None,
        "usage": {"foreground_ms": sum(t.foreground_ms for t in totals), "playback_ms": sum(t.playback_ms for t in totals),
            "session_count": sum(t.session_count for t in totals), "measured_since": iso(min((utc(t.first_activity_at) for t in totals), default=None)),
            "measured_devices": len(totals), "definition": "Measured visible app time since the upgraded client first reported. Background time and pre-upgrade history are excluded. Concurrent devices are summed."},
        "apple_subscription": {"production": production, "sandbox": sandbox, "sandbox_allowed": sandbox_allowed,
            "service_enabled": settings.APPLE_SUBSCRIPTION_ENABLED, "product_id": settings.APPLE_SUBSCRIPTION_PRODUCT_ID,
            "device_limit": apple.DEVICE_LIMIT, "device_limit_unlimited": True, "activated_devices": sum(s.active for s in slots),
            "access_eligible": bool(settings.APPLE_SUBSCRIPTION_ENABLED and user.is_active and (production["active"] or (sandbox_allowed and sandbox["active"])))},
        "device_limit": user.device_cap_override or settings.MAX_DEVICES_PER_ACCOUNT,
        "beta_access": beta_helper(collected["beta"].get(user.id)) if beta_helper else None}


@router.get("")
def list_accounts(q: str | None = None, platform: str | None = None, apple_status: str | None = None,
    is_verified: bool | None = None, is_active: bool | None = None, has_premium: bool | None = None,
    limit: int = Query(25, ge=1, le=100), offset: int = Query(0, ge=0), db: Session = Depends(get_db)):
    query = db.query(User)
    if q:
        pattern = "%" + q.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        query = query.filter(or_(User.email.ilike(pattern, escape="\\"), User.display_name.ilike(pattern, escape="\\")))
    for name, value in (("is_verified", is_verified), ("is_active", is_active), ("has_premium_access", has_premium)):
        if value is not None: query = query.filter(getattr(User, name) == value)
    if platform:
        if platform not in PLATFORMS | {"apple"}: raise HTTPException(422, detail="Unknown platform filter.")
        families = ["ios", "ipados", "tvos"] if platform == "apple" else [platform]
        device_users = select(Device.user_id).where(platform_expression().in_(families))
        slot_users = select(AppleSubscriptionDevice.user_id).where(AppleSubscriptionDevice.platform.in_(families))
        measured_users = select(DeviceActivityTotal.user_id).where(DeviceActivityTotal.platform.in_(families))
        query = query.filter(or_(User.id.in_(device_users), User.id.in_(slot_users), User.id.in_(measured_users)))
    if apple_status:
        if apple_status not in {"production", "sandbox", "none"}: raise HTTPException(422, detail="Unknown Apple status filter.")
        production = User.id.in_(active_apple_user_query("Production"))
        sandbox = User.id.in_(active_apple_user_query("Sandbox"))
        query = query.filter(production if apple_status == "production" else sandbox if apple_status == "sandbox" else ~production & ~sandbox)
    count = query.count()
    users = query.order_by(User.created_at.desc(), User.id).offset(offset).limit(limit).all()
    collected = _collect(db, [u.id for u in users])
    return {"total": count, "offset": offset, "limit": limit, "users": [summary(u, collected) for u in users]}


@router.get("/{user_id}")
def account_detail(user_id: uuid.UUID, db: Session = Depends(get_db)):
    user = db.get(User, user_id)
    if user is None: raise HTTPException(404, detail="User not found.")
    collected = _collect(db, [user_id])
    totals = {t.device_key: t for t in collected["totals"][user_id]}
    def metrics(key):
        t = totals.get(key)
        return {"foreground_ms": t.foreground_ms if t else None, "playback_ms": t.playback_ms if t else None,
            "session_count": t.session_count if t else None, "measured_since": iso(t.first_activity_at) if t else None,
            "last_activity_at": iso(t.last_activity_at) if t else None,
            **{name: getattr(t, name) if t else None for name in ("device_model", "manufacturer", "os_version", "app_version", "app_build")}}
    devices = [{**admin_users._device_row(d), "platform_family": platform_family(d.platform, d.device_type),
        "usage": metrics("registered:" + str(d.id))} for d in collected["devices"][user_id]]
    slots = {str(s.device_id): s for s in collected["slots"][user_id]}
    apple_keys = {k.removeprefix("apple:") for k in totals if k.startswith("apple:")} | set(slots)
    apple_devices = []
    for key in apple_keys:
        s, t = slots.get(key), totals.get("apple:" + key)
        apple_devices.append({"device_id": key, "platform": s.platform if s else t.platform,
            "display_name": s.display_name if s else "Apple device (before activation)", "active": s.active if s else False,
            "slot_state": "activated" if s and s.active else "removed" if s else "not_activated",
            "last_seen_at": ms_iso(s.last_seen_ms) if s else iso(t.last_activity_at), "usage": metrics("apple:" + key)})
    daily = defaultdict(lambda: {"foreground_ms": 0, "playback_ms": 0})
    for day in collected["days"][user_id]:
        daily[str(day.day)]["foreground_ms"] += day.foreground_ms; daily[str(day.day)]["playback_ms"] += day.playback_ms
    transactions = sorted(collected["transactions"][user_id], key=lambda t: t.signed_at_ms, reverse=True)[:25]
    return {"user": summary(user, collected), "devices": devices, "apple_devices": apple_devices,
        "daily_usage": [{"day": day, **values} for day, values in sorted(daily.items())],
        "apple_transactions": [{"environment": t.environment, "transaction_id": t.transaction_id, "original_transaction_id": t.original_transaction_id,
            "product_id": t.product_id, "expires_at": ms_iso(t.expires_at_ms), "verified_at": ms_iso(t.signed_at_ms),
            "refunded_at": ms_iso(t.revoked_at_ms), "upgraded": t.is_upgraded} for t in transactions]}


@router.post("/{user_id}/apple-devices/{device_id}/revoke")
def revoke_apple_device(user_id: uuid.UUID, device_id: uuid.UUID, db: Session = Depends(get_db)):
    user = db.query(User).filter_by(id=user_id).with_for_update().first()
    if user is None: raise HTTPException(404, detail="User not found.")
    device = db.get(AppleSubscriptionDevice, (user_id, device_id))
    if device is None: raise HTTPException(404, detail="Apple device not found.")
    device.active = False
    db.commit()
    return {"ok": True, "subscription_cancelled": False}
