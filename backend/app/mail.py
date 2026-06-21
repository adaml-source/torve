"""
Transactional email via Resend (https://resend.com).
Uses httpx to avoid adding the full resend SDK as a dependency.
"""
import logging
from collections.abc import Mapping, Sequence
from html import escape
from typing import Any

import httpx

from app.config import settings

_log = logging.getLogger(__name__)

_RESEND_API_URL = "https://api.resend.com/emails"


def send_email(
    *,
    to: str,
    subject: str,
    html: str,
    text: str,
    reply_to: str | None = None,
) -> bool:
    """Send a single transactional email. Returns True on success."""
    if not settings.RESEND_API_KEY:
        _log.warning("RESEND_API_KEY not set — skipping email to %s", to)
        return False

    payload = {
        "from": settings.MAIL_FROM,
        "to": [to],
        "subject": subject,
        "html": html,
        "text": text,
    }
    if reply_to:
        payload["reply_to"] = reply_to

    try:
        resp = httpx.post(
            _RESEND_API_URL,
            json=payload,
            headers={"Authorization": f"Bearer {settings.RESEND_API_KEY}"},
            timeout=10.0,
        )
        if resp.status_code in (200, 201):
            _log.info("Email sent to %s (subject=%s)", to, subject)
            return True
        _log.error("Resend API error %s: %s", resp.status_code, resp.text)
        return False
    except httpx.HTTPError as exc:
        _log.error("Resend request failed: %s", exc)
        return False


# ── Shared layout ─────────────────────────────────────────────────────────────


def _render_html(
    *,
    headline: str,
    body: str,
    button_text: str,
    button_url: str,
    footer: str,
) -> str:
    """Render the standard Torve transactional email card."""
    return f"""\
<!DOCTYPE html>
<html lang="en">
<head><meta charset="utf-8"></head>
<body style="margin: 0; padding: 0; background-color: #f5f5f5; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;">
  <table width="100%" cellpadding="0" cellspacing="0" style="background-color: #f5f5f5; padding: 40px 0;">
    <tr><td align="center">
      <table width="480" cellpadding="0" cellspacing="0" style="background-color: #ffffff; border-radius: 12px; overflow: hidden;">
        <!-- Header -->
        <tr>
          <td style="padding: 32px 32px 0 32px;">
            <p style="margin: 0 0 24px 0; font-size: 15px; font-weight: 700; letter-spacing: 0.5px; color: #111;">TORVE</p>
          </td>
        </tr>
        <!-- Content -->
        <tr>
          <td style="padding: 0 32px;">
            <h1 style="margin: 0 0 16px 0; font-size: 22px; font-weight: 600; color: #111;">{headline}</h1>
            <p style="margin: 0 0 28px 0; font-size: 15px; line-height: 1.6; color: #333;">{body}</p>
            <!-- Button -->
            <table cellpadding="0" cellspacing="0" style="margin: 0 0 12px 0;">
              <tr>
                <td style="background-color: #111; border-radius: 6px;">
                  <a href="{button_url}" style="display: inline-block; padding: 12px 28px; color: #ffffff; font-size: 15px; font-weight: 600; text-decoration: none;">{button_text}</a>
                </td>
              </tr>
            </table>
            <p style="margin: 0 0 28px 0; font-size: 12px; color: #999; word-break: break-all;">{button_url}</p>
          </td>
        </tr>
        <!-- Footer -->
        <tr>
          <td style="padding: 0 32px 32px 32px;">
            <p style="margin: 0; font-size: 13px; line-height: 1.5; color: #888;">{footer}</p>
          </td>
        </tr>
      </table>
      <p style="margin: 24px 0 0 0; font-size: 12px; color: #aaa;">&mdash; Torve</p>
    </td></tr>
  </table>
</body>
</html>"""


def _render_text(
    *,
    headline: str,
    body: str,
    button_text: str,
    button_url: str,
    footer: str,
) -> str:
    """Render the plain-text fallback."""
    return (
        f"{headline}\n\n"
        f"{body}\n\n"
        f"{button_text}: {button_url}\n\n"
        f"{footer}\n\n"
        f"— Torve"
    )


# ── Transactional emails ──────────────────────────────────────────────────────


def send_verification_email(*, to: str, verify_url: str) -> bool:
    """Email verification — sent on signup and resend-verification."""
    expiry = settings.EMAIL_VERIFICATION_TOKEN_EXPIRE_HOURS
    return send_email(
        to=to,
        subject="Verify your Torve email address",
        html=_render_html(
            headline="Verify your email",
            body=(
                "Thanks for creating your Torve account. Please verify your "
                "email address to confirm your account and finish setup."
            ),
            button_text="Verify email",
            button_url=verify_url,
            footer=(
                f"This link expires in {expiry} hours. "
                "If you did not create a Torve account, you can ignore this email."
            ),
        ),
        text=_render_text(
            headline="Verify your email",
            body=(
                "Thanks for creating your Torve account. Please verify your "
                "email address to confirm your account and finish setup."
            ),
            button_text="Verify email",
            button_url=verify_url,
            footer=(
                f"This link expires in {expiry} hours. "
                "If you did not create a Torve account, you can ignore this email."
            ),
        ),
    )


def send_password_reset_email(*, to: str, reset_url: str) -> bool:
    """Password reset — sent on password-reset/request."""
    expiry = settings.PASSWORD_RESET_TOKEN_EXPIRE_MINUTES
    return send_email(
        to=to,
        subject="Reset your Torve password",
        html=_render_html(
            headline="Reset your password",
            body=(
                "We received a request to reset your Torve password. "
                "Use the button below to choose a new password."
            ),
            button_text="Reset password",
            button_url=reset_url,
            footer=(
                f"This link expires in {expiry} minutes. "
                "If you did not request a password reset, you can ignore this email. "
                "Your password will not change unless you open the link above and create a new one."
            ),
        ),
        text=_render_text(
            headline="Reset your password",
            body=(
                "We received a request to reset your Torve password. "
                "Use the link below to choose a new password."
            ),
            button_text="Reset password",
            button_url=reset_url,
            footer=(
                f"This link expires in {expiry} minutes. "
                "If you did not request a password reset, you can ignore this email. "
                "Your password will not change unless you open the link above and create a new one."
            ),
        ),
    )


def send_welcome_email(*, to: str) -> bool:
    """Welcome — sent after successful email verification."""
    app_url = settings.APP_PUBLIC_WEB_URL
    return send_email(
        to=to,
        subject="Welcome to Torve",
        html=_render_html(
            headline="Welcome to Torve",
            body=(
                "Your account is now verified and ready to use. "
                "You can sign in and start using Torve across your supported devices."
            ),
            button_text="Open Torve",
            button_url=app_url,
            footer="Need help? Reply to this email or contact support@torve.app.",
        ),
        text=_render_text(
            headline="Welcome to Torve",
            body=(
                "Your account is now verified and ready to use. "
                "You can sign in and start using Torve across your supported devices."
            ),
            button_text="Open Torve",
            button_url=app_url,
            footer="Need help? Reply to this email or contact support@torve.app.",
        ),
    )


def send_refund_manual_review_email(
    *,
    to: str,
    request_id: str,
    user_id: str,
    user_email: str | None,
    purchase_type: str,
    policy_reason: str,
    request_reason: str | None,
    stripe_customer_id: str | None,
) -> bool:
    """Internal support notice for refund requests that need manual review."""
    safe_items = {
        "Request ID": request_id,
        "User ID": user_id,
        "User email": user_email or "unknown",
        "Purchase type": purchase_type,
        "Policy reason": policy_reason,
        "Customer ID": stripe_customer_id or "unknown",
        "Customer note": request_reason or "",
    }
    detail_lines = "\n".join(f"{label}: {value}" for label, value in safe_items.items() if value)
    html_items = "".join(
        "<li><strong>{}</strong>: {}</li>".format(escape(label), escape(str(value)))
        for label, value in safe_items.items()
        if value
    )
    subject = f"Manual review needed: refund {request_id} ({purchase_type}, {policy_reason})"
    return send_email(
        to=to,
        subject=subject,
        html=f"""\
<!DOCTYPE html>
<html lang="en">
<head><meta charset="utf-8"></head>
<body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; color: #111;">
  <h1 style="font-size: 20px;">Refund request needs manual review</h1>
  <p>Refund request <strong>{escape(request_id)}</strong> requires manual review because <strong>{escape(policy_reason)}</strong>.</p>
  <ul>{html_items}</ul>
  <p style="color: #666;">No card number, payment fingerprint, provider token, or raw Stripe payload is included in this notification.</p>
</body>
</html>""",
        text=(
            "Refund request needs manual review\n\n"
            f"Refund request {request_id} requires manual review because {policy_reason}.\n\n"
            f"{detail_lines}\n\n"
            "No card number, payment fingerprint, provider token, or raw Stripe payload is included in this notification."
        ),
    )


def _as_mapping(value: Any) -> Mapping[str, Any]:
    return value if isinstance(value, Mapping) else {}


def _first_present(*values: Any) -> str:
    for value in values:
        if value is None:
            continue
        text = str(value).strip()
        if text:
            return text
    return "unknown"


def _bool_label(value: Any) -> str:
    if value is True:
        return "yes"
    if value is False:
        return "no"
    return "unknown"


def _short(value: Any, *, max_len: int = 220) -> str:
    if value is None:
        return "unknown"
    text = str(value).strip()
    if not text:
        return "unknown"
    if len(text) <= max_len:
        return text
    return text[: max_len - 3].rstrip() + "..."


def _html_table(rows: Sequence[tuple[str, Any]]) -> str:
    rendered = []
    for label, value in rows:
        rendered.append(
            "<tr>"
            f"<th>{escape(str(label))}</th>"
            f"<td>{escape(_short(value, max_len=500))}</td>"
            "</tr>"
        )
    return "\n".join(rendered)


def _safe_int(value: Any) -> int | None:
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _count_logs(logs: Any) -> int:
    if isinstance(logs, list):
        return len(logs)
    if isinstance(logs, str) and logs.strip():
        return len(logs.splitlines())
    return 0


def _integration_items(diagnostics: Mapping[str, Any]) -> list[tuple[str, Mapping[str, Any]]]:
    integrations = _as_mapping(diagnostics.get("integrations"))
    items: list[tuple[str, Mapping[str, Any]]] = []
    for name, value in integrations.items():
        if isinstance(value, Mapping) and value.get("status"):
            items.append((str(name), value))
    if items:
        return items

    provider_status = diagnostics.get("provider_status")
    if isinstance(provider_status, list):
        for value in provider_status:
            if isinstance(value, Mapping) and value.get("status"):
                items.append((str(value.get("name") or "provider"), value))
    return items


def _status_rank(status: str) -> int:
    normalized = status.upper()
    if normalized in {"RED", "ERROR", "FAILED", "FAIL"}:
        return 0
    if normalized in {"YELLOW", "WARN", "WARNING", "DEGRADED"}:
        return 1
    if normalized in {"UNKNOWN"}:
        return 2
    if normalized in {"UNCONFIGURED", "DISABLED"}:
        return 3
    if normalized in {"GREEN", "OK", "READY", "CONNECTED"}:
        return 4
    return 2


def _integration_summary(diagnostics: Mapping[str, Any]) -> tuple[list[str], str, str]:
    items = sorted(
        _integration_items(diagnostics),
        key=lambda item: (_status_rank(str(item[1].get("status", ""))), item[0].lower()),
    )
    if not items:
        return [], "<p>No integration status was included.</p>", "No integration status included."

    counts: dict[str, int] = {}
    for _, details in items:
        status = str(details.get("status", "UNKNOWN")).upper()
        counts[status] = counts.get(status, 0) + 1

    rows = []
    text_lines = []
    attention = []
    for name, details in items:
        status = str(details.get("status", "UNKNOWN")).upper()
        message = _first_present(details.get("message"), details.get("error"), details.get("last_error"))
        next_action = details.get("nextAction", details.get("next"))
        rows.append(
            "<tr>"
            f"<td>{escape(name)}</td>"
            f"<td><strong>{escape(status)}</strong></td>"
            f"<td>{escape(_short(message, max_len=260))}</td>"
            f"<td>{escape(_short(next_action, max_len=180))}</td>"
            "</tr>"
        )
        text_lines.append(f"- {name}: {status} - {_short(message)}")
        if _status_rank(status) <= 1:
            attention.append(f"{name} is {status}: {_short(message)}")

    counts_line = ", ".join(f"{status}: {count}" for status, count in sorted(counts.items()))
    html = (
        f"<p><strong>Counts:</strong> {escape(counts_line)}</p>"
        "<table class=\"data\"><tr><th>Name</th><th>Status</th><th>Message</th><th>Next</th></tr>"
        + "\n".join(rows)
        + "</table>"
    )
    text = f"Counts: {counts_line}\n" + "\n".join(text_lines)
    return attention, html, text


def _recent_actions_summary(recent_actions: Any) -> tuple[str, str]:
    if not isinstance(recent_actions, list) or not recent_actions:
        return "<p>No recent actions included.</p>", "No recent actions included."

    rows = []
    text_lines = []
    for action in recent_actions[-12:]:
        if not isinstance(action, Mapping):
            continue
        timestamp = action.get("timestampEpochMs", action.get("timestamp"))
        screen = _first_present(action.get("screen"))
        name = _first_present(action.get("action"))
        target = _first_present(action.get("target"))
        result = _first_present(action.get("result"))
        rows.append(
            "<tr>"
            f"<td>{escape(_short(timestamp, max_len=40))}</td>"
            f"<td>{escape(screen)}</td>"
            f"<td>{escape(name)}</td>"
            f"<td>{escape(target)}</td>"
            f"<td>{escape(result)}</td>"
            "</tr>"
        )
        text_lines.append(f"- {timestamp} {screen} {name} {target} -> {result}")
    if not rows:
        return "<p>No recent actions included.</p>", "No recent actions included."
    html = (
        "<table class=\"data\"><tr><th>Time</th><th>Screen</th><th>Action</th>"
        "<th>Target</th><th>Result</th></tr>"
        + "\n".join(rows)
        + "</table>"
    )
    return html, "\n".join(text_lines)


def _logs_summary(logs: Any) -> tuple[str, str]:
    if isinstance(logs, list):
        lines = [str(line) for line in logs[-30:]]
    elif isinstance(logs, str) and logs.strip():
        lines = logs.splitlines()[-30:]
    else:
        lines = []
    if not lines:
        return "<p>No recent logs included.</p>", "No recent logs included."
    text = "\n".join(lines)
    return f"<pre class=\"logs\">{escape(text)}</pre>", text


def _bug_report_sections(
    *,
    report_id: str,
    user_id: str,
    user_email: str,
    platform: str,
    report_payload: Mapping[str, Any],
    detail_json: str,
) -> tuple[str, str]:
    report = _as_mapping(report_payload.get("report"))
    top_device = _as_mapping(report_payload.get("device"))
    device = _as_mapping(report.get("device"))
    diagnostics = _as_mapping(report.get("diagnostics"))
    app_info = _as_mapping(diagnostics.get("app"))
    account = _as_mapping(diagnostics.get("account"))
    network = _as_mapping(diagnostics.get("network"))
    performance = _as_mapping(diagnostics.get("performance"))
    focus = _as_mapping(diagnostics.get("focus"))
    playback = _as_mapping(diagnostics.get("playback"))
    last_playback = _as_mapping(playback.get("lastPlaybackAttempt"))
    crashes = _as_mapping(diagnostics.get("crashes"))
    memory = _as_mapping(device.get("memory"))

    issue_type = _first_present(report.get("issue_type"))
    message = _first_present(report.get("message"))
    app_version = _first_present(report.get("appVersion"), app_info.get("versionName"))
    build_number = _first_present(report.get("buildNumber"), app_info.get("versionCode"))
    store = _first_present(report.get("distributionChannel"), app_info.get("store"))
    device_label = " ".join(
        part
        for part in [
            _first_present(device.get("manufacturer"), device.get("brand")),
            _first_present(device.get("model")),
        ]
        if part != "unknown"
    ) or "unknown"
    os_label = _first_present(device.get("os"))
    installation_id = _first_present(top_device.get("installation_id"))

    triage: list[str] = []
    last_crash = crashes.get("lastCrash")
    recent_crashes = crashes.get("recentCrashes")
    has_recent_crashes = isinstance(recent_crashes, list) and len(recent_crashes) > 0
    if str(issue_type).lower() == "crash" and not last_crash and not has_recent_crashes:
        triage.append("Issue type is Crash, but no crash stack was captured in this bundle.")
    elif last_crash or has_recent_crashes:
        triage.append("Crash data is attached.")
    else:
        triage.append("No crash captured.")

    if network:
        triage.append(
            "Network: backend reachable={}, transport={}, VPN={}, proxy={}.".format(
                _bool_label(network.get("backendReachable")),
                _first_present(network.get("transport")),
                _bool_label(network.get("vpnActive")),
                _bool_label(network.get("proxyActive")),
            )
        )
    if performance:
        triage.append(
            "Performance: ANR suspected={}, slow_frames={}, frozen_frame_events={}, memory_available_mb={}.".format(
                _bool_label(performance.get("appNotRespondingSuspected")),
                _first_present(performance.get("slowFrames")),
                _first_present(performance.get("frozenFrameEvents")),
                _first_present(memory.get("availableMb")),
            )
        )
    if playback:
        triage.append(
            "Playback: error_code={}, error_message={}, buffer_count={}, total_buffer_ms={}.".format(
                _first_present(last_playback.get("errorCode")),
                _first_present(last_playback.get("errorMessage")),
                _first_present(last_playback.get("bufferCount")),
                _first_present(last_playback.get("totalBufferMs")),
            )
        )
    if focus:
        triage.append(
            "Focus: stuck_suspected={}, current_screen={}, focused_element={}.".format(
                _bool_label(focus.get("focusStuckSuspected")),
                _first_present(focus.get("currentScreen")),
                _first_present(focus.get("currentFocusedElement")),
            )
        )

    integration_attention, integrations_html, integrations_text = _integration_summary(diagnostics)
    triage.extend(integration_attention)
    if not integration_attention and diagnostics:
        triage.append("No red/yellow integration failures were reported.")
    triage.append(f"Logs attached: {_count_logs(report.get('logs'))}.")

    at_a_glance_rows = [
        ("Report ID", report_id),
        ("Issue type", issue_type),
        ("Message", message),
        ("User", f"{user_email} ({user_id})"),
        ("Platform", platform),
        ("App", f"{app_version}, build {build_number}, {store}"),
        ("Device", f"{device_label}, {os_label}"),
        ("Install", installation_id),
        ("Access", f"{_first_present(account.get('accessTier'))}, premium={_bool_label(account.get('hasPremiumAccess'))}, device={_first_present(account.get('deviceActivationState'))}"),
    ]
    triage_html = "".join(f"<li>{escape(line)}</li>" for line in triage)
    triage_text = "\n".join(f"- {line}" for line in triage)
    actions_html, actions_text = _recent_actions_summary(diagnostics.get("recentActions"))
    logs_html, logs_text = _logs_summary(report.get("logs"))

    html = f"""\
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <style>
    body {{ font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; color: #111; line-height: 1.45; }}
    h1 {{ font-size: 22px; margin: 0 0 12px; }}
    h2 {{ font-size: 16px; margin: 24px 0 8px; }}
    table.data {{ border-collapse: collapse; width: 100%; font-size: 13px; }}
    table.data th, table.data td {{ border: 1px solid #ddd; padding: 6px 8px; text-align: left; vertical-align: top; }}
    table.data th {{ background: #f4f4f4; width: 160px; }}
    pre {{ white-space: pre-wrap; font-size: 12px; line-height: 1.45; background: #f6f6f6; padding: 12px; border-radius: 6px; overflow-wrap: anywhere; }}
    pre.logs {{ max-height: 520px; overflow: auto; }}
    .note {{ color: #666; font-size: 13px; }}
  </style>
</head>
<body>
  <h1>Torve bug report</h1>
  <h2>At a Glance</h2>
  <table class="data">{_html_table(at_a_glance_rows)}</table>
  <h2>Triage Signals</h2>
  <ul>{triage_html}</ul>
  <h2>Integration Status</h2>
  {integrations_html}
  <h2>Recent Actions</h2>
  {actions_html}
  <h2>Recent Logs</h2>
  {logs_html}
  <h2>Full Redacted JSON</h2>
  <pre>{escape(detail_json)}</pre>
  <p class="note">Sensitive tokens, passwords, API keys, cookies, auth headers, and credentialed URLs are redacted by the backend before sending.</p>
</body>
</html>"""

    text = (
        "Torve bug report\n\n"
        "At a glance\n"
        + "\n".join(f"{label}: {_short(value, max_len=500)}" for label, value in at_a_glance_rows)
        + "\n\nTriage signals\n"
        + triage_text
        + "\n\nIntegration status\n"
        + integrations_text
        + "\n\nRecent actions\n"
        + actions_text
        + "\n\nRecent logs\n"
        + logs_text
        + "\n\nFull redacted JSON\n"
        + detail_json
        + "\n\nSensitive tokens, passwords, API keys, cookies, auth headers, and credentialed URLs are redacted by the backend before sending."
    )
    return html, text


def send_bug_report_email(
    *,
    to: str,
    report_id: str,
    user_id: str,
    user_email: str,
    platform: str,
    report_payload: dict,
) -> bool:
    """Internal support notice for in-app bug reports."""
    safe_platform = platform.strip() or "unknown"
    subject = f"[BUG report] Torve {safe_platform} {report_id}"
    detail_json = json_dumps_pretty(report_payload)
    html, text = _bug_report_sections(
        report_id=report_id,
        user_id=user_id,
        user_email=user_email,
        platform=safe_platform,
        report_payload=report_payload,
        detail_json=detail_json,
    )
    return send_email(
        to=to,
        subject=subject,
        reply_to=user_email,
        html=html,
        text=text,
    )


def json_dumps_pretty(value: dict) -> str:
    import json

    return json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True, default=str)
