"""Stripe billing integration helpers.

Stripe is optional at import time so tests and non-billing deployments can
load the backend without the SDK installed. Runtime calls fail closed with a
stable configuration error when Stripe is not available.
"""

from __future__ import annotations

from typing import Any

from app.config import settings
from app.models import User


class StripeBillingConfigError(RuntimeError):
    pass


class StripeCheckoutError(RuntimeError):
    pass


class StripePortalError(RuntimeError):
    pass


class StripeCustomerMissing(RuntimeError):
    pass


def _stripe():
    if not settings.STRIPE_SECRET_KEY.strip():
        raise StripeBillingConfigError("Stripe is not configured.")
    try:
        import stripe  # type: ignore
    except ImportError as exc:  # pragma: no cover - depends on deployment image
        raise StripeBillingConfigError("Stripe SDK is not installed.") from exc
    stripe.api_key = settings.STRIPE_SECRET_KEY.strip()
    return stripe


def _obj_get(value: Any, key: str, default: Any = None) -> Any:
    if isinstance(value, dict):
        return value.get(key, default)
    return getattr(value, key, default)


def _checkout_success_url() -> str:
    if settings.STRIPE_CHECKOUT_SUCCESS_URL.strip():
        return settings.STRIPE_CHECKOUT_SUCCESS_URL.strip()
    return f"{settings.APP_PUBLIC_WEB_URL.rstrip('/')}/billing/success?session_id={{CHECKOUT_SESSION_ID}}"


def _checkout_cancel_url() -> str:
    if settings.STRIPE_CHECKOUT_CANCEL_URL.strip():
        return settings.STRIPE_CHECKOUT_CANCEL_URL.strip()
    return f"{settings.APP_PUBLIC_WEB_URL.rstrip('/')}/billing/cancel"


def _portal_return_url() -> str:
    if settings.STRIPE_PORTAL_RETURN_URL.strip():
        return settings.STRIPE_PORTAL_RETURN_URL.strip()
    return f"{settings.APP_PUBLIC_WEB_URL.rstrip('/')}/account"


def _price_id_for_purchase(purchase_type: str) -> str:
    if purchase_type == "monthly":
        price_id = settings.STRIPE_MONTHLY_PRICE_ID.strip()
    elif purchase_type == "lifetime":
        price_id = settings.STRIPE_LIFETIME_PRICE_ID.strip()
    else:
        price_id = ""
    if not price_id:
        raise StripeBillingConfigError("Stripe price is not configured.")
    return price_id


def create_stripe_checkout_session(user: User, purchase_type: str) -> str:
    stripe = _stripe()
    price_id = _price_id_for_purchase(purchase_type)
    metadata = {
        "torve_user_id": str(user.id),
        "purchase_type": purchase_type,
    }
    try:
        kwargs: dict[str, Any] = {
            "mode": "subscription" if purchase_type == "monthly" else "payment",
            "line_items": [{"price": price_id, "quantity": 1}],
            "success_url": _checkout_success_url(),
            "cancel_url": _checkout_cancel_url(),
            "client_reference_id": str(user.id),
            "customer_email": user.email,
            "metadata": metadata,
            "allow_promotion_codes": True,
        }
        if purchase_type == "monthly":
            kwargs["subscription_data"] = {"metadata": metadata}
        else:
            kwargs["payment_intent_data"] = {"metadata": metadata}
        session = stripe.checkout.Session.create(**kwargs)
    except StripeBillingConfigError:
        raise
    except Exception as exc:  # noqa: BLE001 - sanitize Stripe exceptions at router
        raise StripeCheckoutError("Stripe checkout failed.") from exc

    url = _obj_get(session, "url")
    if not isinstance(url, str) or not url.strip():
        raise StripeCheckoutError("Stripe checkout did not return a URL.")
    return url


def _find_customer_by_email(stripe: Any, email: str) -> str | None:
    try:
        customers = stripe.Customer.list(email=email, limit=1)
    except Exception as exc:  # noqa: BLE001 - sanitize at router
        raise StripePortalError("Stripe customer lookup failed.") from exc
    data = _obj_get(customers, "data", []) or []
    if not data:
        return None
    customer_id = _obj_get(data[0], "id")
    return customer_id if isinstance(customer_id, str) and customer_id else None


def create_stripe_portal_session(user: User) -> str:
    stripe = _stripe()
    customer_id = _find_customer_by_email(stripe, user.email)
    if not customer_id:
        raise StripeCustomerMissing("No Stripe customer exists for this account.")
    try:
        session = stripe.billing_portal.Session.create(
            customer=customer_id,
            return_url=_portal_return_url(),
        )
    except Exception as exc:  # noqa: BLE001 - sanitize at router
        raise StripePortalError("Stripe portal failed.") from exc
    url = _obj_get(session, "url")
    if not isinstance(url, str) or not url.strip():
        raise StripePortalError("Stripe portal did not return a URL.")
    return url


def cancel_stripe_subscription_at_period_end(subscription_id: str) -> None:
    stripe = _stripe()
    try:
        stripe.Subscription.modify(subscription_id, cancel_at_period_end=True)
    except Exception as exc:  # noqa: BLE001 - sanitized by caller
        raise StripePortalError("Stripe subscription cancellation failed.") from exc


def construct_stripe_webhook_event(payload: bytes, signature: str | None) -> Any:
    stripe = _stripe()
    webhook_secret = settings.STRIPE_WEBHOOK_SECRET.strip()
    if not webhook_secret:
        raise StripeBillingConfigError("Stripe webhook is not configured.")
    try:
        return stripe.Webhook.construct_event(payload, signature or "", webhook_secret)
    except Exception as exc:  # noqa: BLE001 - sanitized by router
        raise StripeCheckoutError("Stripe webhook verification failed.") from exc


def retrieve_subscription_period_end(subscription_id: str) -> int | None:
    stripe = _stripe()
    try:
        subscription = stripe.Subscription.retrieve(subscription_id)
    except Exception as exc:  # noqa: BLE001 - sanitized by caller
        raise StripeCheckoutError("Stripe subscription lookup failed.") from exc
    period_end = _obj_get(subscription, "current_period_end")
    return period_end if isinstance(period_end, int) else None
