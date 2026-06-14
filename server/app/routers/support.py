"""In-app support and bug-report endpoints."""
import logging
import json
import re
import uuid
from datetime import datetime, timezone
from typing import Any

from fastapi import APIRouter, Depends, Header, HTTPException, status
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from app.config import settings
from app.deps import get_current_user_id, get_db
from app.mail import send_support_bug_report_email
from app.models import User

_log = logging.getLogger(__name__)

router = APIRouter(prefix="/me/support", tags=["support"])

_MASK = "[REDACTED]"
_REDACTION_RULES = (
    (re.compile(r"(?i)(Authorization\s*:\s*Bearer\s+)([^\s\"']+)"), rf"\1{_MASK}"),
    (re.compile(r"(?i)(Authorization\s*:\s*Basic\s+)([^\s\"']+)"), rf"\1{_MASK}"),
    (re.compile(r"(?i)(Cookie\s*:\s*)([^\n\r]+)"), rf"\1{_MASK}"),
    (re.compile(r"(?i)(access[_-]?token\s*[:=]\s*)([^\s,\"']+)"), rf"\1{_MASK}"),
    (re.compile(r"(?i)(refresh[_-]?token\s*[:=]\s*)([^\s,\"']+)"), rf"\1{_MASK}"),
    (re.compile(r"(?i)(auth[_-]?token\s*[:=]\s*)([^\s,\"']+)"), rf"\1{_MASK}"),
    (re.compile(r"(?i)(session[_-]?token\s*[:=]\s*)([^\s,\"']+)"), rf"\1{_MASK}"),
    (re.compile(r"(?i)(token\s*[:=]\s*)([^\s,\"']+)"), rf"\1{_MASK}"),
    (re.compile(r"(?i)(api[_-]?key\s*[:=]\s*)([^\s,\"']+)"), rf"\1{_MASK}"),
    (re.compile(r"(?i)(secret\s*[:=]\s*)([^\s,\"']+)"), rf"\1{_MASK}"),
    (re.compile(r"(?i)(password\s*[:=]\s*)([^\s,\"']+)"), rf"\1{_MASK}"),
    (re.compile(r"(https?://)([^/\s\"'@]+):([^/\s\"'@]+)@"), rf"\1{_MASK}:{_MASK}@"),
    (re.compile(r"(?i)([?&](?:username|password|token|key|auth|secret|api_key|access_token|refresh_token)=)([^&\s\"']+)"), rf"\1{_MASK}"),
    (re.compile(r"\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b"), _MASK),
)

_SENSITIVE_KEYS = {
    "authorization",
    "cookie",
    "api_key",
    "apikey",
    "key",
    "token",
    "access_token",
    "refresh_token",
    "auth_token",
    "session",
    "session_token",
    "secret",
    "client_secret",
    "password",
    "source_key",
    "stream_url",
    "playback_url",
    "debrid_url",
    "url",
}


class BugReportSubmitRequest(BaseModel):
    issue_type: str = Field(default="Unspecified", max_length=100)
    message: str | None = Field(default=None, max_length=5_000)
    report: str = Field(min_length=1, max_length=120_000)
    platform: str | None = Field(default=None, max_length=80)
    app_version: str | None = Field(default=None, max_length=80)
    appVersion: str | None = Field(default=None, max_length=80)
    buildNumber: str | None = Field(default=None, max_length=40)
    distributionChannel: str | None = Field(default=None, max_length=40)
    device: dict[str, Any] | None = None
    diagnostics: dict[str, Any] | None = None
    logs: list[str] = Field(default_factory=list, max_length=2_000)


class BugReportSubmitResponse(BaseModel):
    report_id: str
    status: str
    support_email: str


def _redact_report(text: str) -> str:
    redacted = text
    for pattern, replacement in _REDACTION_RULES:
        redacted = pattern.sub(replacement, redacted)
    return redacted


def _redact_obj(value: Any) -> Any:
    if isinstance(value, str):
        return _redact_report(value)
    if isinstance(value, list):
        return [_redact_obj(item) for item in value[:2000]]
    if isinstance(value, dict):
        out: dict[str, Any] = {}
        for key, item in value.items():
            key_str = str(key)
            if key_str.lower() in _SENSITIVE_KEYS:
                out[key_str] = _MASK
            else:
                out[key_str] = _redact_obj(item)
        return out
    return value


def _safe_json_dumps(value: Any) -> str:
    return json.dumps(_redact_obj(value), ensure_ascii=False, indent=2, default=str)


@router.post("/bug-report", response_model=BugReportSubmitResponse)
def submit_bug_report(
    body: BugReportSubmitRequest,
    x_torve_installation_id: str | None = Header(default=None, alias="X-Torve-Installation-Id"),
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> BugReportSubmitResponse:
    uid = uuid.UUID(user_id)
    user = db.query(User).filter(User.id == uid).first()
    if user is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found.")
    if not user.is_verified:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Verify your email address before sending support reports.",
        )

    report_id = uuid.uuid4().hex[:10].upper()
    platform = (body.platform or "unknown").strip() or "unknown"
    app_version = (body.appVersion or body.app_version or "unknown").strip() or "unknown"
    build_number = (body.buildNumber or "unknown").strip() or "unknown"
    distribution_channel = (body.distributionChannel or "unknown").strip() or "unknown"
    issue_type = body.issue_type.strip() or "Unspecified"
    generated_at = datetime.now(timezone.utc).isoformat()
    safe_report = _redact_report(body.report)
    safe_message = _redact_report(body.message or "")
    structured_payload = {
        "report_id": report_id,
        "submitted_at": generated_at,
        "user_id": str(user.id),
        "email_verified": bool(user.is_verified),
        "installation_id_present": bool(x_torve_installation_id),
        "platform": platform,
        "appVersion": app_version,
        "buildNumber": build_number,
        "distributionChannel": distribution_channel,
        "issue_type": issue_type,
        "message": safe_message,
        "device": body.device or {},
        "diagnostics": body.diagnostics or {},
        "logs": body.logs[-2000:],
    }

    subject = f"[BUG report] Torve {platform} {report_id}"
    report_text = (
        f"Report ID: {report_id}\n"
        f"Submitted at: {generated_at}\n"
        f"User ID: {user.id}\n"
        f"Email verified: {user.is_verified}\n"
        f"Platform: {platform}\n"
        f"App version: {app_version}\n"
        f"Build number: {build_number}\n"
        f"Distribution channel: {distribution_channel}\n"
        f"Issue type: {issue_type}\n"
        f"Installation header present: {bool(x_torve_installation_id)}\n"
        "\n"
        "## Structured diagnostic payload\n"
        f"{_safe_json_dumps(structured_payload)}\n"
        "\n"
        "## Human-readable report\n"
        "\n"
        f"{safe_report}"
    )

    sent = send_support_bug_report_email(
        to=settings.SUPPORT_EMAIL,
        reply_to=user.email,
        subject=subject,
        report_text=report_text,
    )
    if not sent:
        _log.error("BUG_REPORT_SEND_FAILED report_id=%s user=%s", report_id, user.id)
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Support report could not be sent right now.",
        )

    _log.info("BUG_REPORT_SENT report_id=%s user=%s platform=%s", report_id, user.id, platform)
    return BugReportSubmitResponse(
        report_id=report_id,
        status="sent",
        support_email=settings.SUPPORT_EMAIL,
    )
