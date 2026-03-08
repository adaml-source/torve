"""
Device governance policy for Torve Pro premium access.

This module enforces device-limited premium: owning a premium entitlement
does NOT automatically grant access on every device. Each device must be
activated, and the total number of active devices is capped.

Business rules:
- max 5 active devices per account (configurable via DEVICE_MAX_ACTIVE)
- no TV-specific sub-limit; TVs count the same as phones/tablets
- devices that haven't checked in for 45 days are auto-expired (DEVICE_STALE_DAYS)
- users can self-remove devices to free slots
- max 3 slot-freeing removals per rolling 30-day window (DEVICE_MAX_SWAPS_PER_30D)
"""
import logging
from datetime import datetime, timedelta, timezone

from sqlalchemy import and_, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from .config import get_settings
from .models import Device, DeviceActivationEvent, utcnow

logger = logging.getLogger(__name__)
settings = get_settings()

# ── Policy constants (read from config) ──

MAX_ACTIVE_DEVICES = getattr(settings, "device_max_active", 5)
STALE_DAYS = getattr(settings, "device_stale_days", 45)
MAX_SWAPS_PER_30D = getattr(settings, "device_max_swaps_per_30d", 3)


def _stale_cutoff() -> datetime:
    return utcnow() - timedelta(days=STALE_DAYS)


# ── Stale device pruning ──


async def prune_stale_devices(session: AsyncSession, user_id: str) -> int:
    """
    Mark stale active devices as auto_expired.
    Returns the count of devices pruned.
    """
    cutoff = _stale_cutoff()
    result = await session.execute(
        select(Device).where(
            Device.user_id == user_id,
            Device.activated_at.isnot(None),
            Device.removed_at.is_(None),
            Device.last_seen_at < cutoff,
        )
    )
    stale_devices = list(result.scalars().all())
    for device in stale_devices:
        device.removed_at = utcnow()
        device.removal_reason = "auto_expired"
        session.add(DeviceActivationEvent(
            user_id=user_id,
            device_id=device.id,
            event_type="auto_expired",
            metadata_json={"last_seen_at": device.last_seen_at.isoformat()},
        ))
        logger.info("Auto-expired stale device %s for user %s", device.id, user_id)
    if stale_devices:
        await session.flush()
    return len(stale_devices)


# ── Active device counting ──


async def count_active_devices(session: AsyncSession, user_id: str) -> int:
    result = await session.execute(
        select(func.count(Device.id)).where(
            Device.user_id == user_id,
            Device.activated_at.isnot(None),
            Device.removed_at.is_(None),
        )
    )
    return result.scalar_one()


async def get_active_devices(session: AsyncSession, user_id: str) -> list[Device]:
    result = await session.execute(
        select(Device).where(
            Device.user_id == user_id,
            Device.activated_at.isnot(None),
            Device.removed_at.is_(None),
        ).order_by(Device.last_seen_at.desc())
    )
    return list(result.scalars().all())


# ── Swap-limit enforcement ──


async def count_recent_swaps(session: AsyncSession, user_id: str) -> int:
    """Count slot-freeing device removals in the last 30 days."""
    window_start = utcnow() - timedelta(days=30)
    result = await session.execute(
        select(func.count(DeviceActivationEvent.id)).where(
            DeviceActivationEvent.user_id == user_id,
            DeviceActivationEvent.event_type == "removed",
            DeviceActivationEvent.created_at >= window_start,
        )
    )
    return result.scalar_one()


# ── Device activation ──


async def try_activate_device(
    session: AsyncSession,
    user_id: str,
    device: Device,
    has_entitlement: bool,
) -> dict:
    """
    Attempt to activate a device for premium access.

    Returns a dict with:
      - activated: bool
      - reason: str
      - active_device_count: int
      - stale_devices_pruned: int
      - swaps_remaining: int
    """
    # 1. Prune stale devices first
    pruned = await prune_stale_devices(session, user_id)

    # 2. If no entitlement, device cannot be activated
    if not has_entitlement:
        active_count = await count_active_devices(session, user_id)
        swaps_used = await count_recent_swaps(session, user_id)
        return {
            "activated": False,
            "reason": "no_entitlement",
            "active_device_count": active_count,
            "stale_devices_pruned": pruned,
            "swaps_remaining": max(0, MAX_SWAPS_PER_30D - swaps_used),
        }

    # 3. If already activated, idempotent success
    if device.activated_at is not None and device.removed_at is None:
        device.last_seen_at = utcnow()
        await session.flush()
        active_count = await count_active_devices(session, user_id)
        swaps_used = await count_recent_swaps(session, user_id)
        return {
            "activated": True,
            "reason": _activation_reason(has_entitlement),
            "active_device_count": active_count,
            "stale_devices_pruned": pruned,
            "swaps_remaining": max(0, MAX_SWAPS_PER_30D - swaps_used),
        }

    # 4. Check if under cap
    active_count = await count_active_devices(session, user_id)
    swaps_used = await count_recent_swaps(session, user_id)

    if active_count < MAX_ACTIVE_DEVICES:
        # Activate
        device.activated_at = utcnow()
        device.removed_at = None
        device.removal_reason = None
        device.last_seen_at = utcnow()
        session.add(DeviceActivationEvent(
            user_id=user_id,
            device_id=device.id,
            event_type="activated",
        ))
        await session.flush()
        new_count = await count_active_devices(session, user_id)
        return {
            "activated": True,
            "reason": _activation_reason(has_entitlement),
            "active_device_count": new_count,
            "stale_devices_pruned": pruned,
            "swaps_remaining": max(0, MAX_SWAPS_PER_30D - swaps_used),
        }

    # 5. Cap reached
    session.add(DeviceActivationEvent(
        user_id=user_id,
        device_id=device.id,
        event_type="denied_cap_reached",
    ))
    await session.flush()
    return {
        "activated": False,
        "reason": "device_cap_reached",
        "active_device_count": active_count,
        "stale_devices_pruned": pruned,
        "swaps_remaining": max(0, MAX_SWAPS_PER_30D - swaps_used),
    }


def _activation_reason(has_entitlement: bool) -> str:
    # Simplified — caller knows which entitlement type
    return "active_and_device_active"


# ── Device removal ──


async def remove_device(
    session: AsyncSession,
    user_id: str,
    device_id: str,
) -> dict:
    """
    Remove a device, freeing its premium slot.

    Returns:
      - removed: bool
      - reason: str (ok, not_found, already_removed, swap_limit_reached)
      - swaps_remaining: int
    """
    result = await session.execute(
        select(Device).where(
            Device.id == device_id,
            Device.user_id == user_id,
        )
    )
    device = result.scalar_one_or_none()
    if device is None:
        return {"removed": False, "reason": "not_found", "swaps_remaining": 0}

    # Already removed
    if device.removed_at is not None:
        swaps_used = await count_recent_swaps(session, user_id)
        return {
            "removed": False,
            "reason": "already_removed",
            "swaps_remaining": max(0, MAX_SWAPS_PER_30D - swaps_used),
        }

    # Only count toward swap limit if device was actually activated (freeing a slot)
    was_active = device.activated_at is not None

    if was_active:
        swaps_used = await count_recent_swaps(session, user_id)
        if swaps_used >= MAX_SWAPS_PER_30D:
            session.add(DeviceActivationEvent(
                user_id=user_id,
                device_id=device.id,
                event_type="denied_swap_limit",
                metadata_json={"swaps_used": swaps_used, "max_swaps": MAX_SWAPS_PER_30D},
            ))
            await session.flush()
            return {
                "removed": False,
                "reason": "swap_limit_reached",
                "swaps_remaining": 0,
            }

    device.removed_at = utcnow()
    device.removal_reason = "user_removed"
    session.add(DeviceActivationEvent(
        user_id=user_id,
        device_id=device.id,
        event_type="removed",
        metadata_json={"was_active": was_active},
    ))
    await session.flush()

    swaps_used = await count_recent_swaps(session, user_id)
    return {
        "removed": True,
        "reason": "ok",
        "swaps_remaining": max(0, MAX_SWAPS_PER_30D - swaps_used),
    }


# ── Device rename ──


async def rename_device(
    session: AsyncSession,
    user_id: str,
    device_id: str,
    new_name: str,
) -> Device | None:
    result = await session.execute(
        select(Device).where(
            Device.id == device_id,
            Device.user_id == user_id,
        )
    )
    device = result.scalar_one_or_none()
    if device is None:
        return None
    device.device_name = new_name.strip()[:120]
    await session.flush()
    return device
