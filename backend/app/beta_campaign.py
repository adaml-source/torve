"""Fixed Discord beta campaign window helpers."""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

from app.config import settings

DEFAULT_BETA_SIGNUP_CLOSE_AT = "2026-07-01T23:59:59+02:00"
DEFAULT_BETA_FREE_ACCESS_END_AT = "2026-07-31T23:59:59+02:00"
BERLIN_TZ = ZoneInfo("Europe/Berlin")


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


def parse_campaign_datetime(value: str | None, fallback: str) -> datetime:
    raw = str(value or fallback).strip() or fallback
    try:
        parsed = datetime.fromisoformat(raw.replace("Z", "+00:00"))
    except ValueError:
        parsed = datetime.fromisoformat(fallback)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def beta_signup_close_at() -> datetime:
    return parse_campaign_datetime(settings.BETA_SIGNUP_CLOSE_AT, DEFAULT_BETA_SIGNUP_CLOSE_AT)


def beta_free_access_end_at() -> datetime:
    return parse_campaign_datetime(settings.BETA_FREE_ACCESS_END_AT, DEFAULT_BETA_FREE_ACCESS_END_AT)


def beta_signup_close_at_display() -> str:
    return beta_signup_close_at().astimezone(BERLIN_TZ).isoformat()


def beta_free_access_end_at_display() -> str:
    return beta_free_access_end_at().astimezone(BERLIN_TZ).isoformat()


def beta_signup_closed(*, now: datetime | None = None) -> bool:
    current = (now or utcnow()).astimezone(timezone.utc)
    return current > beta_signup_close_at()


def beta_free_access_ended(*, now: datetime | None = None) -> bool:
    current = (now or utcnow()).astimezone(timezone.utc)
    return current > beta_free_access_end_at()


def beta_application_blocked_reason(*, now: datetime | None = None) -> str | None:
    current = (now or utcnow()).astimezone(timezone.utc)
    if current > beta_free_access_end_at():
        return "beta_access_ended"
    if current > beta_signup_close_at():
        return "beta_signup_closed"
    return None


def capped_beta_grant_expires_at(*, now: datetime, grant_days: int) -> datetime:
    requested = now + timedelta(days=max(1, int(grant_days or 1)))
    return min(requested, beta_free_access_end_at())
