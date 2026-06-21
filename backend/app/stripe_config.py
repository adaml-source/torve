"""Stripe billing configuration and redacted readiness helpers."""
from __future__ import annotations

import logging
from dataclasses import dataclass

from app.config import settings

_log = logging.getLogger(__name__)


class StripeConfigError(RuntimeError):
    def __init__(self, error_code: str, message: str):
        super().__init__(message)
        self.error_code = error_code
        self.message = message


@dataclass(frozen=True)
class StripeReadiness:
    configured: bool
    secret_key_present: bool
    webhook_secret_present: bool
    monthly_price_present: bool
    lifetime_price_present: bool
    tax_enabled: bool
    status: str
    reason: str | None = None

    def as_public_dict(self) -> dict:
        return {
            "configured": self.configured,
            "secret_key_present": self.secret_key_present,
            "webhook_secret_present": self.webhook_secret_present,
            "monthly_price_present": self.monthly_price_present,
            "lifetime_price_present": self.lifetime_price_present,
            "tax_enabled": self.tax_enabled,
        }


def _present(value: str | None) -> bool:
    return bool((value or "").strip())


def _secret_key_valid(value: str) -> bool:
    return value.startswith("sk_test_") or value.startswith("sk_live_")


def _webhook_secret_valid(value: str) -> bool:
    return value.startswith("whsec_")


def _price_valid(value: str) -> bool:
    return value.startswith("price_")


def get_stripe_readiness() -> StripeReadiness:
    secret = settings.STRIPE_SECRET_KEY.strip()
    webhook = settings.STRIPE_WEBHOOK_SECRET.strip()
    monthly = settings.stripe_monthly_price_id.strip()
    lifetime = settings.stripe_lifetime_price_id.strip()

    secret_present = _present(secret)
    webhook_present = _present(webhook)
    monthly_present = _present(monthly)
    lifetime_present = _present(lifetime)

    missing = []
    invalid = []
    if not secret_present:
        missing.append("secret_key")
    elif not _secret_key_valid(secret):
        invalid.append("secret_key")
    if not webhook_present:
        missing.append("webhook_secret")
    elif not _webhook_secret_valid(webhook):
        invalid.append("webhook_secret")
    if not monthly_present:
        missing.append("monthly_price")
    elif not _price_valid(monthly):
        invalid.append("monthly_price")
    if not lifetime_present:
        missing.append("lifetime_price")
    elif not _price_valid(lifetime):
        invalid.append("lifetime_price")

    tax_enabled = bool(settings.STRIPE_TAX_ENABLED)
    if missing:
        return StripeReadiness(
            configured=False,
            secret_key_present=secret_present,
            webhook_secret_present=webhook_present,
            monthly_price_present=monthly_present,
            lifetime_price_present=lifetime_present,
            tax_enabled=tax_enabled,
            status="not_configured",
            reason="missing_" + "_".join(missing),
        )
    if invalid:
        return StripeReadiness(
            configured=False,
            secret_key_present=secret_present,
            webhook_secret_present=webhook_present,
            monthly_price_present=monthly_present,
            lifetime_price_present=lifetime_present,
            tax_enabled=tax_enabled,
            status="misconfigured",
            reason="invalid_" + "_".join(invalid),
        )
    if settings.APP_ENV == "production" and not tax_enabled:
        _log.warning("Stripe configured but STRIPE_TAX_ENABLED is false in production")
        return StripeReadiness(
            configured=True,
            secret_key_present=True,
            webhook_secret_present=True,
            monthly_price_present=True,
            lifetime_price_present=True,
            tax_enabled=False,
            status="degraded",
            reason="tax_disabled_in_production",
        )
    return StripeReadiness(
        configured=True,
        secret_key_present=True,
        webhook_secret_present=True,
        monthly_price_present=True,
        lifetime_price_present=True,
        tax_enabled=tax_enabled,
        status="ready",
    )


def require_stripe_checkout_configured() -> None:
    readiness = get_stripe_readiness()
    if readiness.status in ("not_configured", "misconfigured"):
        raise StripeConfigError(
            "stripe_not_configured",
            "Stripe billing is not configured.",
        )


def require_stripe_webhook_configured() -> None:
    webhook = settings.STRIPE_WEBHOOK_SECRET.strip()
    if not webhook or not _webhook_secret_valid(webhook):
        raise StripeConfigError(
            "stripe_not_configured",
            "Stripe webhook verification is not configured.",
        )
