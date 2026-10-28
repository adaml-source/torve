"""Observability helpers for redacting secrets before telemetry export."""

from __future__ import annotations

import re
from typing import Any


_URL_CREDENTIALS_RE = re.compile(
    r"(?P<scheme>[a-zA-Z][a-zA-Z0-9+.-]*://)"
    r"(?P<user>[^:/@\s'\"<>]+):"
    r"(?P<password>[^@\s'\"<>]+)@"
)

_SENSITIVE_KEY_PARTS = (
    "api_key",
    "apikey",
    "authorization",
    "cookie",
    "jwt",
    "password",
    "private_key",
    "secret",
    "token",
)

_URL_KEY_PARTS = (
    "database_url",
    "dsn",
    "url",
)


def redact_secret_string(value: str) -> str:
    """Mask credentials embedded in URLs while preserving host/db context."""
    return _URL_CREDENTIALS_RE.sub(
        lambda match: f"{match.group('scheme')}{match.group('user')}:***@",
        value,
    )


def redact_sensitive_data(value: Any) -> Any:
    """Recursively redact obvious secrets in Sentry-style event payloads."""
    if isinstance(value, dict):
        redacted: dict[Any, Any] = {}
        for key, item in value.items():
            key_text = str(key).lower()
            if any(part in key_text for part in _SENSITIVE_KEY_PARTS):
                redacted[key] = "[REDACTED]"
            elif any(part in key_text for part in _URL_KEY_PARTS) and isinstance(item, str):
                redacted[key] = redact_secret_string(item)
            else:
                redacted[key] = redact_sensitive_data(item)
        return redacted
    if isinstance(value, list):
        return [redact_sensitive_data(item) for item in value]
    if isinstance(value, tuple):
        return tuple(redact_sensitive_data(item) for item in value)
    if isinstance(value, str):
        return redact_secret_string(value)
    return value


def sentry_before_send(event: dict[str, Any], hint: dict[str, Any]) -> dict[str, Any] | None:
    """Sentry before_send hook that strips secrets from captured locals."""
    del hint
    return redact_sensitive_data(event)
