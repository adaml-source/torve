"""
Server-Sent Events endpoint for real-time account status updates.

Clients connect to GET /me/events with a Bearer token.
The server holds the connection open and pushes events
(e.g. EMAIL_VERIFIED) as they occur for the authenticated user.

The client should NOT trust the event payload as source of truth.
On receiving an event, the client must re-fetch authoritative state
via /auth/refresh or a /me endpoint.
"""

import asyncio
import logging
import uuid

from fastapi import APIRouter, Depends, Request
from fastapi.responses import StreamingResponse

from app.deps import get_current_user_id
from app.events import UserEvent, event_bus

_log = logging.getLogger(__name__)

router = APIRouter(prefix="/me", tags=["events"])

_HEARTBEAT_INTERVAL = 30  # seconds — keeps connection alive through proxies


@router.get("/events", include_in_schema=True)
async def stream_events(
    request: Request,
    user_id: str = Depends(get_current_user_id),
) -> StreamingResponse:
    """SSE stream of account-level events for the authenticated user.

    Event types:
    - EMAIL_VERIFIED: email verification completed (on any device)
    - ACCOUNT_UPDATED: generic account state change
    - PLAYLISTS_UPDATED: playlist/source metadata changed

    Clients must re-fetch authoritative state on receipt, not trust
    the event payload directly.
    """
    uid = uuid.UUID(user_id)

    async def event_generator():
        queue = event_bus.subscribe(uid)
        try:
            _log.info("SSE connection opened for user %s", uid)
            while True:
                # Check if client disconnected
                if await request.is_disconnected():
                    _log.info("SSE client disconnected for user %s", uid)
                    break

                try:
                    event: UserEvent = await asyncio.wait_for(
                        queue.get(), timeout=_HEARTBEAT_INTERVAL
                    )
                    yield f"event: {event.event_type}\ndata: {event.to_sse_data()}\n\n"
                except asyncio.TimeoutError:
                    # Send heartbeat comment to keep connection alive
                    yield ": heartbeat\n\n"
        finally:
            event_bus.unsubscribe(uid, queue)
            _log.info("SSE connection closed for user %s", uid)

    return StreamingResponse(
        event_generator(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",  # Disable nginx buffering for SSE
        },
    )
