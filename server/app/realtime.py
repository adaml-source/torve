import asyncio
from collections import defaultdict
from datetime import datetime, timezone
from fastapi import WebSocket
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from .models import EventOutbox


class ConnectionRegistry:
    def __init__(self) -> None:
        self._connections: dict[str, dict[str, WebSocket]] = defaultdict(dict)
        self._lock = asyncio.Lock()

    async def register(self, user_id: str, device_id: str, websocket: WebSocket) -> None:
        async with self._lock:
            self._connections[user_id][device_id] = websocket

    async def unregister(self, user_id: str, device_id: str) -> None:
        async with self._lock:
            user_devices = self._connections.get(user_id)
            if not user_devices:
                return
            user_devices.pop(device_id, None)
            if not user_devices:
                self._connections.pop(user_id, None)

    async def send_event(self, user_id: str, target_device_id: str, event: dict) -> bool:
        async with self._lock:
            websocket = self._connections.get(user_id, {}).get(target_device_id)
        if websocket is None:
            return False
        await websocket.send_json({"type": "event", "event": event})
        return True


async def queue_event(
    session: AsyncSession,
    user_id: str,
    target_device_id: str,
    event_type: str,
    payload: dict,
) -> EventOutbox:
    entry = EventOutbox(
        user_id=user_id,
        target_device_id=target_device_id,
        type=event_type,
        payload_json=payload,
    )
    session.add(entry)
    await session.flush()
    return entry


async def dispatch_event(
    session: AsyncSession,
    registry: ConnectionRegistry,
    user_id: str,
    target_device_id: str,
    event_type: str,
    payload: dict,
) -> EventOutbox:
    outbox_entry = await queue_event(
        session=session,
        user_id=user_id,
        target_device_id=target_device_id,
        event_type=event_type,
        payload=payload,
    )
    event = {
        "id": outbox_entry.id,
        "type": outbox_entry.type,
        "payload": outbox_entry.payload_json,
        "created_at": outbox_entry.created_at.isoformat(),
    }
    delivered = await registry.send_event(user_id=user_id, target_device_id=target_device_id, event=event)
    if delivered:
        outbox_entry.delivered_at = datetime.now(timezone.utc)
    return outbox_entry


async def deliver_pending_events(
    session: AsyncSession,
    registry: ConnectionRegistry,
    user_id: str,
    target_device_id: str,
) -> int:
    result = await session.execute(
        select(EventOutbox)
        .where(
            EventOutbox.user_id == user_id,
            EventOutbox.target_device_id == target_device_id,
            EventOutbox.delivered_at.is_(None),
        )
        .order_by(EventOutbox.created_at.asc()),
    )
    pending = result.scalars().all()
    delivered_count = 0
    for entry in pending:
        event = {
            "id": entry.id,
            "type": entry.type,
            "payload": entry.payload_json,
            "created_at": entry.created_at.isoformat(),
        }
        delivered = await registry.send_event(user_id=user_id, target_device_id=target_device_id, event=event)
        if delivered:
            entry.delivered_at = datetime.now(timezone.utc)
            delivered_count += 1
    return delivered_count
