"""
Transactional email via Resend (https://resend.com).
Uses httpx to avoid adding the full resend SDK as a dependency.
"""
import html as _html
import logging

import httpx

from app.config import settings

_log = logging.getLogger(__name__)

_RESEND_API_URL = "https://api.resend.com/emails"


def send_email(*, to: str, subject: str, html: str, text: str, reply_to: str | None = None) -> bool:
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
        payload["reply_to"] = [reply_to]

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


def send_support_bug_report_email(
    *,
    to: str,
    reply_to: str,
    subject: str,
    report_text: str,
) -> bool:
    """Forward an in-app bug report to support.

    The client already redacts its diagnostics bundle, and the support
    endpoint redacts again before calling this helper. This function only
    renders the resulting plain text into an email-safe envelope.
    """
    escaped = _html.escape(report_text)
    html = f"""\
<!DOCTYPE html>
<html lang="en">
<head><meta charset="utf-8"></head>
<body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; color: #111;">
  <h1 style="font-size: 18px;">Torve bug report</h1>
  <p style="font-size: 13px; color: #555;">Submitted from the in-app support flow.</p>
  <pre style="white-space: pre-wrap; word-break: break-word; background: #f5f5f5; border-radius: 8px; padding: 16px; font-size: 12px; line-height: 1.45;">{escaped}</pre>
</body>
</html>"""
    return send_email(
        to=to,
        reply_to=reply_to,
        subject=subject,
        html=html,
        text=report_text,
    )
