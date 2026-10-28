"""Foreground measurements are operational statistics, never evidence of a paid entitlement."""
import uuid
from datetime import datetime, timedelta, timezone
from typing import Literal
from fastapi import HTTPException
from pydantic import BaseModel, Field, model_validator
from sqlalchemy.orm import Session
from app.models import Device, User
from app.device_activity_models import DeviceActivityTotal, DeviceActivitySession, DeviceActivityDay


def utcnow():
    return datetime.now(timezone.utc)


def utc(value):
    return value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value.astimezone(timezone.utc)


def platform_family(platform, device_type=None):
    value = (platform or "").strip().lower().replace("_", "-")
    if "fire" in value: return "firetv"
    if value in {"tvos", "apple-tv", "appletv"}: return "tvos"
    if value in {"ipados", "ipad"} or (value in {"ios", "iphone"} and device_type == "tablet"): return "ipados"
    if value in {"ios", "iphone"}: return "ios"
    if "android" in value: return "android-tv" if device_type == "tv" or "tv" in value else "android"
    if value in {"desktop", "windows", "macos", "linux", "mac"}: return "desktop"
    if value in {"web", "browser"}: return "web"
    return "unknown"


class ActivityReport(BaseModel):
    device_id: uuid.UUID | None = None
    installation_id: str | None = Field(default=None, max_length=255)
    apple_device_id: uuid.UUID | None = None
    platform: Literal["android", "android-tv", "firetv", "ios", "ipados", "tvos", "desktop", "web"]
    session_id: uuid.UUID
    sequence: int = Field(ge=0, le=2_147_483_647)
    foreground_ms: int = Field(ge=0, le=604_800_000)
    playback_ms: int = Field(default=0, ge=0, le=604_800_000)
    device_model: str | None = Field(default=None, max_length=120)
    manufacturer: str | None = Field(default=None, max_length=80)
    os_version: str | None = Field(default=None, max_length=80)
    app_version: str | None = Field(default=None, max_length=50)
    app_build: str | None = Field(default=None, max_length=50)

    @model_validator(mode="after")
    def check_counters(self):
        if self.playback_ms > self.foreground_ms:
            raise ValueError("Playback time cannot exceed visible app time.")
        if not (self.device_id or self.installation_id or self.apple_device_id):
            raise ValueError("A registered device or Apple device identifier is required.")
        return self


def record_activity(db: Session, uid: uuid.UUID, body: ActivityReport) -> dict:
    # One account row lock serializes the first report and later upserts in PostgreSQL.
    user = db.query(User).filter_by(id=uid).with_for_update().first()
    if user is None or not user.is_active:
        raise HTTPException(401, detail="Sign in to an active Torve account.")
    query = db.query(Device).filter_by(user_id=uid)
    device = None
    if body.device_id:
        device = query.filter_by(id=body.device_id).first()
        if device is None: raise HTTPException(404, detail="Device not found.")
    elif body.installation_id:
        device = query.filter_by(installation_id=body.installation_id).first()
        if device is None: raise HTTPException(409, detail="Register this device first.")
    if device is not None and not device.is_active:
        raise HTTPException(403, detail="This device has been removed.")
    if body.apple_device_id and device is None:
        if body.platform not in {"ios", "ipados", "tvos"}:
            raise HTTPException(422, detail="Apple device metadata requires an Apple platform.")
        # Records a client identifier even before checkout; it never activates a paid device slot.
        key = "apple:" + str(body.apple_device_id)
    else:
        key = "registered:" + str(device.id)
    now = utcnow()
    total = db.get(DeviceActivityTotal, (uid, key))
    if total is None:
        total = DeviceActivityTotal(user_id=uid, device_key=key, device_id=device.id if device else None,
            platform=platform_family(device.platform, device.device_type) if device else body.platform,
            first_activity_at=now, last_activity_at=now, foreground_ms=0, playback_ms=0, session_count=0)
        db.add(total); db.flush()
    session = db.get(DeviceActivitySession, (uid, key, body.session_id))
    if session is not None and body.sequence <= session.sequence:
        return {"accepted": False, "duplicate": True, "counted_ms": 0}
    if session is not None and (body.foreground_ms < session.reported_foreground_ms or body.playback_ms < session.reported_playback_ms):
        raise HTTPException(422, detail="Session counters must increase monotonically.")
    # A new session establishes the clock. Historical/offline time is never backfilled.
    if session is None:
        recent = db.query(DeviceActivitySession).filter_by(user_id=uid, device_key=key).filter(DeviceActivitySession.started_at > now - timedelta(hours=1)).count()
        if recent >= 120: raise HTTPException(429, detail="Too many activity sessions.")
        session = DeviceActivitySession(user_id=uid, device_key=key, session_id=body.session_id, sequence=body.sequence,
            reported_foreground_ms=body.foreground_ms, reported_playback_ms=body.playback_ms, started_at=now, last_report_at=now)
        db.add(session); total.session_count += 1
        foreground = playback = 0
    else:
        window = min(120_000, max(0, int((now - utc(total.last_activity_at)).total_seconds() * 1000)))
        foreground = min(body.foreground_ms - session.reported_foreground_ms, window)
        playback = min(body.playback_ms - session.reported_playback_ms, foreground)
        session.sequence = body.sequence
        session.reported_foreground_ms, session.reported_playback_ms = body.foreground_ms, body.playback_ms
        session.last_report_at = now
    total.foreground_ms += foreground; total.playback_ms += playback
    total.last_activity_at = now
    for name in ("device_model", "manufacturer", "os_version", "app_version", "app_build"):
        value = getattr(body, name)
        if value and value.strip(): setattr(total, name, value.strip())
    if device is not None:
        if device.device_type == "tv" and body.manufacturer.lower() == "amazon" and body.platform == "firetv":
            device.platform = "firetv"; total.platform = "firetv"
        device.last_seen_at = now
        if body.app_version: device.app_version = body.app_version.strip()
    # Keep raw session counters for 30 days. Lifetime totals and compact daily sums remain.
    db.query(DeviceActivitySession).filter_by(user_id=uid, device_key=key).filter(DeviceActivitySession.last_report_at < now - timedelta(days=30)).delete(synchronize_session=False)
    if foreground:
        day = db.get(DeviceActivityDay, (uid, key, now.date()))
        if day is None:
            day = DeviceActivityDay(user_id=uid, device_key=key, day=now.date(), foreground_ms=0, playback_ms=0)
            db.add(day)
        day.foreground_ms += foreground; day.playback_ms += playback
    db.flush()
    return {"accepted": True, "duplicate": False, "counted_ms": foreground, "device_key": key}
