"""Canonical and simplified state machines for NzbDAV warm/resolve flows.

Canonical states track the full upstream pipeline. The simplified
projection (ready|warming|failed) is the ONLY shape exposed to clients.
"""

# Canonical internal states
IDLE = "idle"
QUEUED = "queued"
SUBMITTING = "submitting"
ACCEPTED = "accepted"
CHECKING = "checking"
REPAIRING = "repairing"
EXTRACTING = "extracting"
READY = "ready"
FAILED = "failed"
EXPIRED = "expired"
CANCELLED = "cancelled"

CANONICAL_STATES = {
    IDLE, QUEUED, SUBMITTING, ACCEPTED, CHECKING,
    REPAIRING, EXTRACTING, READY, FAILED, EXPIRED, CANCELLED,
}

# Simplified user-facing states
SIMPLIFIED_READY = "ready"
SIMPLIFIED_WARMING = "warming"
SIMPLIFIED_FAILED = "failed"

_WARMING_STATES = {
    IDLE, QUEUED, SUBMITTING, ACCEPTED, CHECKING, REPAIRING, EXTRACTING,
}
_FAILED_STATES = {FAILED, EXPIRED, CANCELLED}


def to_simplified(state: str) -> str:
    """Collapse a canonical state into one of ready|warming|failed."""
    if state == READY:
        return SIMPLIFIED_READY
    if state in _FAILED_STATES:
        return SIMPLIFIED_FAILED
    if state in _WARMING_STATES:
        return SIMPLIFIED_WARMING
    # Unknown state: fail closed to "warming" so a stale job won't be mistaken
    # for ready. Clients will poll and eventually the canonical state resolves.
    return SIMPLIFIED_WARMING
