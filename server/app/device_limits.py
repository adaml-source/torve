"""Apple devices are exempt from the account's non-Apple device allowance."""
from sqlalchemy import func
from app.models import Device

APPLE_PLATFORMS = ("ios", "ipados", "tvos", "iphone", "ipad", "appletv", "apple_tv")


def is_apple_platform(platform: str | None) -> bool:
    return (platform or "").strip().lower() in APPLE_PLATFORMS


def capped_devices_query(db, user_id):
    return db.query(Device).filter(
        Device.user_id == user_id, Device.is_active.is_(True),
        func.lower(func.trim(func.coalesce(Device.platform, ""))).notin_(APPLE_PLATFORMS),
    )
