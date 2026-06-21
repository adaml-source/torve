"""Authenticated in-app support reporting."""
from __future__ import annotations

import logging
import re
import uuid
from collections.abc import Mapping, Sequence
from datetime import datetime, timezone
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Request, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from jose import JWTError
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy.orm import Session

from app import mail
from app.config import settings
from app.deps import get_calling_installation_id, get_db
from app.models import User
from app.rate_limits import enforce_rate_limit
from app.security import decode_access_token

_log = logging.getLogger(__name__)

router = APIRouter(prefix="/me/support", tags=["support"])
_bearer_401 = HTTPBearer(auto_error=False)

_QUERY_SECRET_RE = re.compile(
    r"(?i)\b((?:access[_-]?token|refresh[_-]?token|id[_-]?token|api[_-]?key|"
    r"apikey|client[_-]?secret|password|secret|authorization|auth|token|key)=)"
    r"([^&\s]+)"
)
_BEARER_RE = re.compile(r"(?i)\b(bearer\s+)[A-Za-z0-9._~+/=-]+")
_JWT_RE = re.compile(r"\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b")
_URL_USERINFO_RE = re.compile(r"(?i)(https?://)[^/\s:@]+:[^/\s@]+@")
_COMMON_SECRET_RE = re.compile(
    r"\b(?:sk|rk)_(?:live|test)_[A-Za-z0-9_]+\b|\bre_[A-Za-z0-9_]{8,}\b"
)
_EPOCH_MS_RE = re.compile(r"\bepoch_ms=(\d+)\b")
_PROVIDER_STATUS_RE = re.compile(
    r"^-\s*(?P<name>.+?)\s+\[(?P<status>[A-Z_]+)\]\s+"
    r"lastChecked=(?P<last_checked>\d+)\s+msg=(?P<message>.*?)\s+next=(?P<next>.*)$"
)


class BugReportRequest(BaseModel):
    model_config = ConfigDict(extra="allow", populate_by_name=True)

    message: str | None = Field(default=None, max_length=12000)
    platform: str | None = Field(default=None, max_length=80)
    app_version: str | None = Field(default=None, alias="appVersion", max_length=80)
    build_number: str | None = Field(default=None, alias="buildNumber", max_length=80)
    distribution_channel: str | None = Field(
        default=None,
        alias="distributionChannel",
        max_length=80,
    )
    device: dict[str, Any] | None = None
    diagnostics: dict[str, Any] | None = None
    logs: str | list[str] | None = None


class BugReportResponse(BaseModel):
    status: str
    report_id: str
    support_email: str


def _get_current_user_id_401(
    credentials: HTTPAuthorizationCredentials | None = Depends(_bearer_401),
) -> str:
    if credentials is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing bearer token",
            headers={"WWW-Authenticate": "Bearer"},
        )
    try:
        return decode_access_token(credentials.credentials)
    except JWTError:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or expired access token",
            headers={"WWW-Authenticate": "Bearer"},
        )


def _redact_string(value: str) -> str:
    redacted = _BEARER_RE.sub(r"\1[REDACTED]", value)
    redacted = _QUERY_SECRET_RE.sub(r"\1[REDACTED]", redacted)
    redacted = _JWT_RE.sub("[REDACTED]", redacted)
    redacted = _URL_USERINFO_RE.sub(r"\1[REDACTED]@", redacted)
    return _COMMON_SECRET_RE.sub("[REDACTED]", redacted)


def _is_sensitive_key(key: str) -> bool:
    normalized = _normalize_key(key)
    if normalized in {
        "authorization",
        "auth",
        "cookie",
        "credentials",
        "credential",
        "password",
        "passwd",
        "secret",
        "api_key",
        "apikey",
        "private_key",
        "access_token",
        "refresh_token",
        "id_token",
        "auth_token",
        "bearer_token",
        "jwt",
    }:
        return True
    if normalized.endswith("_token") or normalized.endswith("_secret"):
        return True
    if "password" in normalized or "api_key" in normalized or "private_key" in normalized:
        return True
    return False


def _redact_value(value: Any, *, depth: int = 0) -> Any:
    if depth > 12:
        return "[TRUNCATED]"
    if isinstance(value, Mapping):
        out: dict[str, Any] = {}
        for key, item in value.items():
            safe_key = str(key)
            if _is_sensitive_key(safe_key):
                out[safe_key] = "[REDACTED]"
            else:
                out[safe_key] = _redact_value(item, depth=depth + 1)
        return out
    if isinstance(value, str):
        return _redact_string(value)
    if isinstance(value, Sequence) and not isinstance(value, (bytes, bytearray)):
        items = list(value)
        redacted_items = [_redact_value(item, depth=depth + 1) for item in items[:200]]
        if len(items) > 200:
            redacted_items.append(f"[TRUNCATED {len(items) - 200} ITEMS]")
        return redacted_items
    return value


def _safe_platform(value: Any) -> str:
    platform = str(value or "").strip() or "unknown"
    platform = re.sub(r"[^A-Za-z0-9_. -]+", "", platform)
    platform = re.sub(r"\s+", " ", platform).strip()
    return platform[:80] or "unknown"


def _report_platform(raw: dict[str, Any]) -> str:
    if raw.get("platform"):
        return _safe_platform(raw["platform"])
    device = raw.get("device")
    if isinstance(device, Mapping) and device.get("platform"):
        return _safe_platform(device["platform"])
    return "unknown"


def _is_missing(value: Any) -> bool:
    return value is None or value == "" or value == {}


def _normalize_key(key: str) -> str:
    key = re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", key.strip())
    normalized = re.sub(r"[^a-z0-9]+", "_", key.lower())
    return normalized.strip("_")


def _coerce_diag_value(value: str) -> Any:
    cleaned = value.strip()
    if cleaned.lower() == "true":
        return True
    if cleaned.lower() == "false":
        return False
    if cleaned == "(none)":
        return None
    return cleaned


def _markdown_sections(text: str) -> dict[str, str]:
    sections: dict[str, list[str]] = {}
    current: str | None = None
    for line in text.splitlines():
        if line.startswith("## "):
            current = line[3:].strip()
            sections.setdefault(current, [])
            continue
        if current is not None:
            sections[current].append(line)
    return {key: "\n".join(lines).strip() for key, lines in sections.items()}


def _section_first_text(section: str) -> str | None:
    for line in section.splitlines():
        stripped = line.strip()
        if stripped:
            return stripped[:2000]
    return None


def _parse_bullet_kv(section: str) -> dict[str, Any]:
    parsed: dict[str, Any] = {}
    for line in section.splitlines():
        stripped = line.strip()
        if not stripped.startswith("- ") or ":" not in stripped:
            continue
        key, value = stripped[2:].split(":", 1)
        normalized_key = _normalize_key(key)
        if normalized_key:
            parsed[normalized_key] = _coerce_diag_value(value)
    return parsed


def _extract_epoch_ms(section: str) -> int | None:
    match = _EPOCH_MS_RE.search(section)
    if not match:
        return None
    try:
        return int(match.group(1))
    except ValueError:
        return None


def _parse_provider_status(section: str) -> list[dict[str, Any]]:
    statuses: list[dict[str, Any]] = []
    for line in section.splitlines():
        match = _PROVIDER_STATUS_RE.match(line.strip())
        if not match:
            continue
        statuses.append(
            {
                "name": match.group("name").strip(),
                "status": match.group("status").strip(),
                "last_checked": int(match.group("last_checked")),
                "message": _coerce_diag_value(match.group("message")),
                "next": _coerce_diag_value(match.group("next")),
            }
        )
    return statuses


def _extract_structured_diagnostics(report_text: str) -> dict[str, Any]:
    sections = _markdown_sections(report_text)
    app_info = _parse_bullet_kv(sections.get("App", ""))
    device_info = _parse_bullet_kv(sections.get("Device", ""))
    provider_status = _parse_provider_status(sections.get("Provider status", ""))
    transfer_section = next(
        (value for key, value in sections.items() if key.startswith("Transfer")),
        "",
    )
    transfer_info = _parse_bullet_kv(transfer_section)

    diagnostics: dict[str, Any] = {}
    generated_at = _extract_epoch_ms(sections.get("Diagnostics", ""))
    if generated_at is not None:
        diagnostics["generated_at_epoch_ms"] = generated_at
    if app_info:
        diagnostics["app"] = app_info
    if device_info:
        diagnostics["device"] = device_info
    if provider_status:
        diagnostics["provider_status"] = provider_status
    if transfer_info:
        diagnostics["transfer"] = transfer_info
    last_failure = _section_first_text(sections.get("Last failure", ""))
    if last_failure:
        diagnostics["last_failure"] = last_failure
    return diagnostics


def _enrich_report_payload(raw_payload: dict[str, Any]) -> dict[str, Any]:
    report_text = raw_payload.get("report")
    if not isinstance(report_text, str) or not report_text.strip():
        return raw_payload

    sections = _markdown_sections(report_text)
    diagnostics = _extract_structured_diagnostics(report_text)
    app_info = diagnostics.get("app") if isinstance(diagnostics.get("app"), dict) else {}
    device_info = diagnostics.get("device") if isinstance(diagnostics.get("device"), dict) else {}

    enriched = dict(raw_payload)
    if _is_missing(enriched.get("device")) and device_info:
        enriched["device"] = device_info
    if _is_missing(enriched.get("diagnostics")) and diagnostics:
        enriched["diagnostics"] = diagnostics
    if _is_missing(enriched.get("issue_type")):
        issue_type = _section_first_text(sections.get("Issue type", ""))
        if issue_type:
            enriched["issue_type"] = issue_type
    if _is_missing(enriched.get("message")):
        what_happened = _section_first_text(sections.get("What happened", ""))
        if what_happened:
            enriched["message"] = what_happened
    if _is_missing(enriched.get("distributionChannel")) and app_info.get("store"):
        enriched["distributionChannel"] = app_info["store"]
    if _is_missing(enriched.get("buildNumber")) and app_info.get("version_code"):
        enriched["buildNumber"] = str(app_info["version_code"])
    if _is_missing(enriched.get("appVersion")) and app_info.get("version_name"):
        enriched["appVersion"] = str(app_info["version_name"])
    return enriched


@router.post("/bug-report", response_model=BugReportResponse)
def create_bug_report(
    body: BugReportRequest,
    request: Request,
    user_id: str = Depends(_get_current_user_id_401),
    installation_id: str | None = Depends(get_calling_installation_id),
    db: Session = Depends(get_db),
) -> BugReportResponse:
    uid = uuid.UUID(user_id)
    user = db.query(User).filter(User.id == uid).first()
    if user is None or not user.is_active:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid account",
            headers={"WWW-Authenticate": "Bearer"},
        )
    if not user.is_verified:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail={
                "error_code": "email_verification_required",
                "message": "Verify your email address before sending support reports.",
            },
        )

    enforce_rate_limit(
        category="support_bug_report",
        request=request,
        user_id=user_id,
        device_id=installation_id,
        limit=10,
        window_seconds=3600,
    )

    raw_payload = _enrich_report_payload(body.model_dump(mode="json", by_alias=True))
    platform = _report_platform(raw_payload)
    report_id = uuid.uuid4().hex
    redacted_payload = _redact_value(raw_payload)
    report_payload = {
        "report_id": report_id,
        "received_at": datetime.now(timezone.utc).isoformat(),
        "account": {
            "user_id": str(user.id),
            "email": user.email,
            "is_verified": user.is_verified,
        },
        "device": {
            "installation_id": installation_id,
        },
        "report": redacted_payload,
    }

    if not mail.send_bug_report_email(
        to=settings.SUPPORT_EMAIL,
        report_id=report_id,
        user_id=str(user.id),
        user_email=user.email,
        platform=platform,
        report_payload=report_payload,
    ):
        _log.error(
            "BUG_REPORT_SEND_FAILED report_id=%s user=%s platform=%s support_email=%s "
            "resend_configured=%s",
            report_id,
            uid,
            platform,
            settings.SUPPORT_EMAIL,
            bool(settings.RESEND_API_KEY),
        )
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={
                "error_code": "bug_report_send_failed",
                "message": "Support email is temporarily unavailable.",
            },
        )

    _log.warning(
        "BUG_REPORT_SENT report_id=%s user=%s platform=%s support_email=%s",
        report_id,
        uid,
        platform,
        settings.SUPPORT_EMAIL,
    )
    return BugReportResponse(
        status="sent",
        report_id=report_id,
        support_email=settings.SUPPORT_EMAIL,
    )
