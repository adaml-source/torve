"""
Deprecated purchase verification endpoints.

Torve product access is free by default. These routes remain for temporary
client compatibility and audit, but they do not verify stores or grant
access-controlling entitlements.
"""
import json
import logging
import os
import uuid
from datetime import datetime, timezone

import httpx
from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from app.billing import ENTITLEMENT_LIFETIME, ENTITLEMENT_SUBSCRIPTION
from app.config import settings
from app.deps import get_current_user_id, get_db
from app.models import User, UserEntitlement, WebPayment
from app.rate_limits import enforce_rate_limit


_log = logging.getLogger(__name__)

router = APIRouter(prefix="/me/purchases", tags=["purchases"])

# Compatibility aliases for older clients that still POST purchase receipts.
# They now return the same deprecated/free-software response as the /me routes.
legacy_router = APIRouter(prefix="/purchases", tags=["purchases-legacy"])

SOURCE_GOOGLE_PLAY = "google_play"
SOURCE_AMAZON = "amazon_appstore"
DEPRECATED_PURCHASE_MESSAGE = "Purchase verification is no longer required; Torve access is free."


# ── Schemas ────────────────────────────────────────────────────────────

class GooglePlayVerifyRequest(BaseModel):
    purchase_token: str = Field(max_length=2000)
    product_id: str = Field(max_length=255)
    order_id: str | None = Field(default=None, max_length=255)
    package_name: str | None = Field(default=None, max_length=255)
    # Legacy field accepted for backward compatibility; ignored for access.
    installation_id: str | None = Field(default=None, max_length=255)


class AmazonVerifyRequest(BaseModel):
    receipt_id: str = Field(max_length=2000)
    user_id: str = Field(max_length=255)  # Amazon user ID, not Torve user ID
    product_id: str | None = Field(default=None, max_length=255)
    # Legacy field accepted for backward compatibility; ignored for access.
    installation_id: str | None = Field(default=None, max_length=255)


class VerifyResponse(BaseModel):
    verified: bool
    entitlement_granted: bool
    message: str
    # Additive — optional on write, always present on failure paths. Lets
    # ops distinguish config/auth/product/upstream/verification failures.
    # None on success. Stable token set:
    #   config_missing             — server config for this store missing
    #   service_account_failure    — SA JSON broken or OAuth fetch failed
    #   product_mismatch           — product_id not in configured set
    #   upstream_unreachable       — Google API 5xx or network error
    #   not_verified               — Google returned purchaseState != 0
    #   already_verified           — idempotent replay; nothing to do
    error_code: str | None = None


class RestoreResponse(BaseModel):
    """Deprecated restore response retained for client compatibility."""
    restored: bool
    has_premium_access: bool
    has_lifetime_access: bool
    is_verified: bool
    active_entitlements: int
    message: str


# ── Google Play ────────────────────────────────────────────────────────

@router.post("/google-play/verify", response_model=VerifyResponse)
@legacy_router.post("/google/verify", response_model=VerifyResponse, include_in_schema=False)
def verify_google_play(
    body: GooglePlayVerifyRequest,
    request: Request,
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> VerifyResponse:
    """Deprecated compatibility endpoint; does not grant access."""
    uid = uuid.UUID(user_id)
    enforce_rate_limit(
        category="purchase_verify_google_play",
        request=request,
        user_id=user_id,
        limit=60,
        window_seconds=300,
    )
    order_id = body.order_id or body.purchase_token[:64]
    ref = f"gp_{order_id}"
    _record_payment(db, uid, ref, SOURCE_GOOGLE_PLAY, body.product_id, "deprecated_free_software", False)
    db.commit()

    _log.info("GP_PURCHASE_VERIFY_DEPRECATED user=%s order=%s", uid, order_id)
    return VerifyResponse(
        verified=True,
        entitlement_granted=False,
        message=DEPRECATED_PURCHASE_MESSAGE,
        error_code="deprecated_free_software",
    )


# ── Amazon ─────────────────────────────────────────────────────────────

@router.post("/amazon/verify", response_model=VerifyResponse)
@legacy_router.post("/amazon/verify", response_model=VerifyResponse, include_in_schema=False)
def verify_amazon(
    body: AmazonVerifyRequest,
    request: Request,
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> VerifyResponse:
    """Deprecated compatibility endpoint; does not grant access."""
    uid = uuid.UUID(user_id)
    enforce_rate_limit(
        category="purchase_verify_amazon",
        request=request,
        user_id=user_id,
        limit=60,
        window_seconds=300,
    )
    ref = f"amz_{body.receipt_id[:64]}"
    _record_payment(db, uid, ref, SOURCE_AMAZON, body.product_id or "unknown", "deprecated_free_software", False)
    db.commit()

    _log.info("AMAZON_PURCHASE_VERIFY_DEPRECATED user=%s receipt=%s", uid, body.receipt_id[:16])
    return VerifyResponse(
        verified=True,
        entitlement_granted=False,
        message=DEPRECATED_PURCHASE_MESSAGE,
        error_code="deprecated_free_software",
    )


# ── Restore ────────────────────────────────────────────────────────────

@router.post("/restore", response_model=RestoreResponse)
@legacy_router.post("/restore", response_model=RestoreResponse, include_in_schema=False)
def restore_purchases(
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> RestoreResponse:
    """Deprecated compatibility endpoint; access no longer needs restore."""
    uid = uuid.UUID(user_id)
    user = db.query(User).filter(User.id == uid).one_or_none()
    if user is None:
        # Bearer was valid but the user was deleted between token mint
        # and now — nothing to restore.
        raise HTTPException(status_code=404, detail="User not found")

    active_count = db.query(UserEntitlement).filter(
        UserEntitlement.user_id == uid,
        UserEntitlement.status == "active",
    ).count()

    _log.info(
        "RESTORE_DEPRECATED_FREE_SOFTWARE user=%s verified=%s active_ents=%d",
        uid, user.is_verified, active_count,
    )
    return RestoreResponse(
        restored=True,
        has_premium_access=bool(user.is_active),
        has_lifetime_access=False,
        is_verified=user.is_verified,
        active_entitlements=active_count,
        message="Purchase restore is no longer required; Torve access is free.",
    )


# ── Google Play API verification ───────────────────────────────────────

def _verify_google_play_token(
    package_name: str,
    product_id: str,
    purchase_token: str,
    product_class: str | None,
) -> tuple[bool, str, str | None, datetime | None, bool | None]:
    """Verify a Google Play purchase token via the Android Publisher API.

    Returns (verified, detail_for_log, error_code, expires_at, auto_renew).
    expires_at and auto_renew are populated for subscriptions and None for
    one-time products. error_code is one of: None (on success),
      "config_missing", "service_account_failure", "upstream_unreachable",
      "not_verified".

    Subscriptions and one-time products use DIFFERENT API endpoints —
    /purchases/subscriptions/... vs /purchases/products/.... A subscription
    token sent to the products endpoint returns 404, which is what
    happened to the first live test purchase of com.torve.pro.subscription.

    Never includes JSON contents, private keys, or tokens in any return
    value or log line — only HTTP status codes and opaque codes.
    """
    from app.google_play_readiness import assess_readiness
    readiness = assess_readiness()
    if not readiness.ready:
        return False, f"not_ready:{readiness.reason}", "config_missing", None, None

    creds_path = settings.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON
    if not package_name:
        package_name = getattr(settings, 'GOOGLE_PLAY_PACKAGE_NAME', '')

    try:
        access_token = _get_google_access_token(creds_path)
        if not access_token:
            return False, "access_token_empty", "service_account_failure", None, None

        if product_class == "subscription":
            url = (
                f"https://androidpublisher.googleapis.com/androidpublisher/v3"
                f"/applications/{package_name}/purchases/subscriptions/{product_id}/tokens/{purchase_token}"
            )
        else:
            url = (
                f"https://androidpublisher.googleapis.com/androidpublisher/v3"
                f"/applications/{package_name}/purchases/products/{product_id}/tokens/{purchase_token}"
            )
        resp = httpx.get(
            url,
            headers={"Authorization": f"Bearer {access_token}"},
            timeout=15.0,
        )

        if resp.status_code in (401, 403):
            return False, f"google_{resp.status_code}", "service_account_failure", None, None
        if 500 <= resp.status_code < 600:
            return False, f"google_{resp.status_code}", "upstream_unreachable", None, None
        if resp.status_code != 200:
            return False, f"google_{resp.status_code}", "not_verified", None, None

        data = resp.json()

        if product_class == "subscription":
            # Sub response shape (v3): paymentState 0=pending, 1=received,
            # 2=free trial, 3=pending deferred upgrade. Treat anything but 0
            # as paid/active. expiryTimeMillis is the unix-ms expiry.
            payment_state = data.get("paymentState")
            if payment_state == 0:
                return False, "payment_pending", "not_verified", None, None
            expiry_ms = data.get("expiryTimeMillis")
            if not expiry_ms:
                return False, "no_expiry", "not_verified", None, None
            try:
                expires_at = datetime.fromtimestamp(int(expiry_ms) / 1000, tz=timezone.utc)
            except (TypeError, ValueError):
                return False, "bad_expiry", "not_verified", None, None
            if expires_at <= datetime.now(timezone.utc):
                return False, "already_expired", "not_verified", None, None
            auto_renew = bool(data.get("autoRenewing", False))
            return True, "ok", None, expires_at, auto_renew

        # One-time product
        purchase_state = data.get("purchaseState", -1)
        if purchase_state != 0:
            return False, f"purchase_state={purchase_state}", "not_verified", None, None
        return True, "ok", None, None, None

    except httpx.TimeoutException:
        return False, "timeout", "upstream_unreachable", None, None
    except httpx.HTTPError as e:
        return False, f"http_{type(e).__name__}", "upstream_unreachable", None, None
    except Exception as e:  # noqa: BLE001 — never let an unexpected path leak
        _log.error("GP verify unexpected error class=%s", type(e).__name__)
        return False, "unexpected", "service_account_failure", None, None


def _get_google_access_token(creds_path: str) -> str | None:
    """Get an OAuth2 access token from a Google service account JSON file.

    Uses the JWT grant flow to obtain a short-lived access token
    for the Android Publisher API scope.
    """
    try:
        from jose import jwt as jose_jwt
        import time

        if not os.path.exists(creds_path):
            _log.error("Service account file not found: %s", creds_path)
            return None

        with open(creds_path, 'r') as f:
            sa = json.load(f)

        now = int(time.time())
        payload = {
            "iss": sa["client_email"],
            "scope": "https://www.googleapis.com/auth/androidpublisher",
            "aud": "https://oauth2.googleapis.com/token",
            "iat": now,
            "exp": now + 3600,
        }

        signed_jwt = jose_jwt.encode(payload, sa["private_key"], algorithm="RS256")

        resp = httpx.post(
            "https://oauth2.googleapis.com/token",
            data={
                "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
                "assertion": signed_jwt,
            },
            timeout=15.0,
        )

        if resp.status_code == 200:
            return resp.json().get("access_token")

        _log.error("Google token exchange failed: %d %s", resp.status_code, resp.text[:80])
        return None

    except ImportError:
        _log.error("python-jose with RS256 support required for Google Play verification")
        return None
    except Exception as e:
        _log.error("Google auth error: %s", e)
        return None


# ── Amazon RVS verification ────────────────────────────────────────────

def _verify_amazon_receipt(receipt_id: str, amazon_user_id: str) -> tuple[bool, str, str | None, str | None]:
    """Verify an Amazon IAP receipt via the Receipt Verification Service.

    Returns (verified, detail_message, product_id_from_rvs, error_code).
    The returned product_id should be cross-checked against config.
    """
    secret = settings.AMAZON_APP_SECRET
    if not secret:
        _log.error("AMAZON_APP_SECRET not configured. Cannot verify.")
        return False, "config_missing", None, "config_missing"

    # Amazon RVS v1.0 endpoint
    rvs_base = "https://appstore-sdk.amazon.com"
    if settings.APP_ENV != "production":
        rvs_base = "https://appstore-sdk.amazon.com"  # Amazon has no sandbox RVS

    url = f"{rvs_base}/version/1.0/verifyReceiptId/developer/{secret}/user/{amazon_user_id}/receiptId/{receipt_id}"

    try:
        resp = httpx.get(url, timeout=15.0)

        if resp.status_code == 200:
            data = resp.json()
            rvs_receipt = data.get("receiptId")
            rvs_product = data.get("productId")
            rvs_type = data.get("productType")
            cancel_date = data.get("cancelDate")

            if rvs_receipt != receipt_id:
                return False, "receipt_mismatch", None, "not_verified"

            if cancel_date:
                _log.warning("Amazon receipt %s has cancelDate=%s", receipt_id[:16], cancel_date)
                return False, "receipt_cancelled", rvs_product, "entitlement_revoked"

            return True, "ok", rvs_product, None

        if resp.status_code == 400:
            return False, "rvs_400", None, "not_verified"
        if resp.status_code == 496:
            return False, "rvs_496", None, "service_account_failure"
        if resp.status_code == 497:
            return False, "rvs_497", None, "not_verified"
        if resp.status_code == 500:
            return False, "rvs_500", None, "upstream_unreachable"

        return False, f"rvs_{resp.status_code}", None, "upstream_unreachable"

    except httpx.HTTPError as e:
        _log.error("Amazon RVS error class=%s", type(e).__name__)
        return False, "http_error", None, "upstream_unreachable"


# ── Payment recording ──────────────────────────────────────────────────

def _record_payment(
    db: Session,
    user_id: uuid.UUID,
    ref: str,
    source: str,
    product_id: str,
    payment_status: str,
    entitlement_granted: bool | None,
) -> None:
    """Record a store purchase verification attempt for audit."""
    existing = db.query(WebPayment).filter(WebPayment.paddle_transaction_id == ref).first()
    if existing:
        existing.status = payment_status
        if entitlement_granted is not None:
            existing.entitlement_granted = entitlement_granted
        existing.updated_at = datetime.now(timezone.utc)
        return

    payment = WebPayment(
        paddle_transaction_id=ref,
        user_id=user_id,
        product_id=product_id,
        price_id=source,
        amount="0",
        currency="USD",
        status=payment_status,
        entitlement_granted=entitlement_granted or False,
        paddle_event_type=f"{source}_verify",
    )
    db.add(payment)
