"""
Cross-worker event bus using PostgreSQL LISTEN/NOTIFY.

When verify-email runs on worker A, it issues NOTIFY on a pg channel.
Worker B (handling the SSE connection) receives the notification via LISTEN
and delivers it to the local asyncio queue for streaming to the client.

This works across uvicorn workers without adding Redis or other dependencies.
"""

import asyncio
import json
import logging
import threading
import uuid
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timezone

import psycopg2

from app.config import settings

_log = logging.getLogger(__name__)

_PG_CHANNEL = "torve_user_events"
_LISTENER_RECONNECT_DELAY_SECONDS = 2.0


@dataclass
class UserEvent:
    event_type: str
    user_id: uuid.UUID
    timestamp: datetime = field(default_factory=lambda: datetime.now(timezone.utc))

    def to_sse_data(self) -> str:
        return json.dumps({
            "event": self.event_type,
            "user_id": str(self.user_id),
            "timestamp": self.timestamp.isoformat(),
        })

    def to_notify_payload(self) -> str:
        return json.dumps({
            "event_type": self.event_type,
            "user_id": str(self.user_id),
            "timestamp": self.timestamp.isoformat(),
        })

    @classmethod
    def from_notify_payload(cls, payload: str) -> "UserEvent":
        d = json.loads(payload)
        return cls(
            event_type=d["event_type"],
            user_id=uuid.UUID(d["user_id"]),
            timestamp=datetime.fromisoformat(d["timestamp"]),
        )


class EventBus:
    """Cross-process event bus using PostgreSQL LISTEN/NOTIFY.

    - emit() sends NOTIFY on the pg channel (works from any worker)
    - A background listener thread receives notifications and routes
      them to local asyncio subscribers
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._subscribers: dict[uuid.UUID, set[tuple[asyncio.Queue, asyncio.AbstractEventLoop]]] = defaultdict(set)
        self._listener_thread: threading.Thread | None = None
        self._stop_event = threading.Event()

    def start_listener(self) -> None:
        """Start the background pg LISTEN thread. Call once at app startup."""
        if self._listener_thread and self._listener_thread.is_alive():
            return
        self._stop_event.clear()
        self._listener_thread = threading.Thread(
            target=self._listen_loop, daemon=True, name="pg-event-listener"
        )
        self._listener_thread.start()
        _log.info("PostgreSQL event listener started on channel '%s'", _PG_CHANNEL)

    def stop_listener(self) -> None:
        self._stop_event.set()
        if self._listener_thread:
            self._listener_thread.join(timeout=5)

    def _listen_loop(self) -> None:
        """Background thread: connect to pg, LISTEN, and dispatch notifications."""
        # SQLAlchemy URLs ("postgresql+psycopg2://...") carry a driver
        # suffix that psycopg2.connect() can't parse — it expects a libpq
        # DSN (no "+psycopg2"). Strip the suffix before handing the URL
        # to psycopg2 so the listener actually starts. Without this,
        # psycopg2 raises ProgrammingError and the loop reconnects every
        # 2s forever, silently breaking the SSE / pg-NOTIFY path in any
        # deploy where DATABASE_URL uses the SQLAlchemy form.
        # Caught by Backend CI 2026-05-03 (the listener spammed errors
        # in every test setup).
        dsn = settings.DATABASE_URL.replace("postgresql+psycopg2://", "postgresql://", 1)
        while not self._stop_event.is_set():
            conn = None
            try:
                conn = psycopg2.connect(dsn)
                conn.set_isolation_level(psycopg2.extensions.ISOLATION_LEVEL_AUTOCOMMIT)
                cur = conn.cursor()
                cur.execute(f"LISTEN {_PG_CHANNEL};")
                _log.info("Listening on pg channel '%s'", _PG_CHANNEL)

                while not self._stop_event.is_set():
                    # poll with timeout so we can check stop_event
                    import select as _sel
                    if _sel.select([conn], [], [], 1.0) == ([], [], []):
                        continue
                    conn.poll()
                    while conn.notifies:
                        notify = conn.notifies.pop(0)
                        try:
                            event = UserEvent.from_notify_payload(notify.payload)
                            self._deliver_local(event)
                        except Exception:
                            _log.exception("Failed to parse pg notification: %s", notify.payload)

            except psycopg2.OperationalError as exc:
                if not self._stop_event.is_set():
                    _log.warning(
                        "pg listener connection lost, reconnecting in %.0fs: %s",
                        _LISTENER_RECONNECT_DELAY_SECONDS,
                        exc,
                    )
                    self._stop_event.wait(_LISTENER_RECONNECT_DELAY_SECONDS)
            except Exception:
                if not self._stop_event.is_set():
                    _log.exception(
                        "pg listener error, reconnecting in %.0fs",
                        _LISTENER_RECONNECT_DELAY_SECONDS,
                    )
                    self._stop_event.wait(_LISTENER_RECONNECT_DELAY_SECONDS)
            finally:
                if conn:
                    try:
                        conn.close()
                    except Exception:
                        pass

    def _deliver_local(self, event: UserEvent) -> None:
        """Deliver event to local in-process subscribers."""
        with self._lock:
            subs = list(self._subscribers.get(event.user_id, set()))
        count = 0
        for q, loop in subs:
            try:
                loop.call_soon_threadsafe(q.put_nowait, event)
                count += 1
            except RuntimeError:
                pass
            except asyncio.QueueFull:
                _log.warning("SSE queue full for user %s", event.user_id)
        if count:
            _log.info("Delivered %s to %d local subscriber(s) for user %s",
                       event.event_type, count, event.user_id)

    def subscribe(self, user_id: uuid.UUID) -> asyncio.Queue:
        loop = asyncio.get_event_loop()
        q: asyncio.Queue = asyncio.Queue()
        with self._lock:
            self._subscribers[user_id].add((q, loop))
        _log.debug("SSE subscriber added for user %s", user_id)
        return q

    def unsubscribe(self, user_id: uuid.UUID, q: asyncio.Queue) -> None:
        with self._lock:
            subs = self._subscribers.get(user_id)
            if subs:
                to_remove = next(((qq, loop) for qq, loop in subs if qq is q), None)
                if to_remove:
                    subs.discard(to_remove)
                if not subs:
                    del self._subscribers[user_id]
        _log.debug("SSE subscriber removed for user %s", user_id)

    def emit(self, event: UserEvent) -> None:
        """Send NOTIFY to pg channel. All workers will receive it."""
        conn = None
        try:
            conn = psycopg2.connect(settings.DATABASE_URL)
            conn.set_isolation_level(psycopg2.extensions.ISOLATION_LEVEL_AUTOCOMMIT)
            cur = conn.cursor()
            payload = event.to_notify_payload()
            cur.execute(f"NOTIFY {_PG_CHANNEL}, %s", (payload,))
            _log.info("Emitted %s via pg NOTIFY for user %s", event.event_type, event.user_id)
        except Exception:
            _log.exception("Failed to emit pg NOTIFY for %s", event.event_type)
        finally:
            if conn:
                try:
                    conn.close()
                except Exception:
                    pass


# Global singleton — each worker starts its own listener
event_bus = EventBus()
