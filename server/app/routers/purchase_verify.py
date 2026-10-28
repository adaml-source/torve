"""
Purchase verification endpoints for in-app purchases.

Clients submit purchase tokens/receipts from Google Play or Amazon.
Backend verifies with the respective store API and grants entitlement
only on successful verification.

Production rules:
- Exact product ID match required (no fuzzy matching)
- Missing provider config = fail closed (no grant)
- All verification attempts are recorded for audit
"""
import json
import logging
import os
import uuid
from datetime import datetime, timezone

import httpx
from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from app.billing import (
    ENTITLEMENT_LIFETIME,
    ENTITLEMENT_SUBSCRIPTION,
    classify_amazon_product,
    classify_google_play_product,
    grant_entitlement,
)
from app.config import settings
from app.deps import get_current_user_id, get_db
from app.models import Device, User, UserEntitlement, WebPayment


def _resolve_originating_device_id(
    db: Session, user_id: uuid.UUID, installation_id: str | None,
) -> uuid.UUID | None:
    """Look up an active device row by installation_id scoped to the user.

    Returns the device.id if a matching active device exists, else None.
    Pattern B uses this to bind a fresh entitlement to the device that
    initiated the purchase. Foreign / unknown installation_ids are
    silently ignored (the entitlement falls back to grandfathered =
    works on any device) so the purchase still succeeds.
    """
    if not installation_id:
        return None
    device = (
        db.query(Device)
        .filter(
            Device.user_id == user_id,
            Device.installation_id == installation_id,
            Device.is_active == True,  # noqa: E712
        )
        .first()
    )
    return device.id if device is not None else None

_log = logging.getLogger(__name__)

router = APIRouter(prefix="/me/purchases", tags=["purchases"])

# Compatibility alias for Android clients built before the URL fix that
# moved verify endpoints under the /me prefix. Those old builds POST to
# /purchases/google/verify (dead-monorepo path) and get a silent 404,
# which means a real purchase never reaches the backend and the user's
# entitlement never unlocks until someone notices and manually grants.
# The alias routes the same handler at the old path, so old-client
# purchases go through until every install is on the new build. Remove
# this router after Play Store telemetry shows the legacy version at
# zero installs (expect ~2-4 weeks after rollout).
legacy_router = APIRouter(prefix="/purchases", tags=["purchases-legacy"])

SOURCE_GOOGLE_PLAY = "google_play"
SOURCE_AMAZON = "amazon_appstore"


# ── Schemas ────────────────────────────────────────────────────────────

class GooglePlayVerifyRequest(BaseModel):
    purchase_token: str = Field(max_length=2000)
    product_id: str = Field(max_length=255)
    order_id: str | None = Field(default=None, max_length=255)
    package_name: str | None = Field(default=None, max_length=255)
    # Pattern B: the installation that initiated this purchase. When
    # supplied AND the user is unverified, the entitlement is bound to
    # this device until verification — see check_premium_active /
    # resolve_access_state. Optional for backward-compat with old
    # clients; missing value means the entitlement is treated as
    # grandfathered (works on any device).
    installation_id: str | None = Field(default=None, max_length=255)


class AmazonVerifyRequest(BaseModel):
    receipt_id: str = Field(max_length=2000)
    user_id: str = Field(max_length=255)  # Amazon user ID, not Torve user ID
    product_id: str | None = Field(default=None, max_length=255)
    # See GooglePlayVerifyRequest.installation_id.
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
    """Server-side restore: refresh cached premium booleans from active
    entitlements + restore from lifetime-grant ledger. Does NOT re-verify
    Google Play / Amazon tokens — the BillingClient owns those and the
    client must drive any per-token re-verification itself."""
    restored: bool
    has_premium_access: bool
    has_lifetime_access: bool
    is_verified: bool
    active_entitlements: int
    message: str


# ── Product validation ─────────────────────────────────────────────────

def _validate_google_play_product(product_id: str) -> bool:
    """Validate product_id matches any configured Google Play product (lifetime or subscription).

    In production, requires at least one product ID to be configured.
    In non-production, logs a warning but allows if not configured.
    """
    known = settings.all_google_play_product_ids
    if known:
        return product_id in known
    if settings.APP_ENV == "production":
        _log.error("No Google Play product IDs configured in production. Rejecting all.")
        return False
    _log.warning("No Google Play product IDs configured (non-production). Accepting %s for dev.", product_id)
    return True


def _validate_amazon_product(product_id: str | None) -> bool:
    """Validate product_id matches any configured Amazon product (lifetime or subscription).

    In production, requires at least one product ID to be configured.
    In non-production, logs a warning but allows if not configured.
    """
    if not product_id:
        if settings.APP_ENV == "production":
            return False
        return True
    known = settings.all_amazon_product_ids
    if known:
        return product_id in known
    if settings.APP_ENV == "production":
        _log.error("No Amazon product IDs configured in production. Rejecting all.")
        return False
    return True


# ── Google Play ────────────────────────────────────────────────────────

@router.post("/google-play/verify", response_model=VerifyResponse)
@legacy_router.post("/google/verify", response_model=VerifyResponse, include_in_schema=False)
def verify_google_play(
    body: GooglePlayVerifyRequest,
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> VerifyResponse:
    """Verify a Google Play purchase token and grant entitlement.

    Calls Google's Android Publisher API to validate the token.
    Grants lifetime access only on exact product match and valid purchase state.
    """
    uid = uuid.UUID(user_id)
    order_id = body.order_id or body.purchase_token[:64]
    ref = f"gp_{order_id}"

    # Idempotency
    existing = db.query(WebPayment).filter(WebPayment.paddle_transaction_id == ref).first()
    if existing and existing.entitlement_granted:
        return VerifyResponse(
            verified=True, entitlement_granted=True,
            message="Already verified.", error_code="already_verified",
        )

    # Strict product validation
    if not _validate_google_play_product(body.product_id):
        _log.warning("GP verify: product mismatch. got=%s user=%s", body.product_id, uid)
        _record_payment(db, uid, ref, SOURCE_GOOGLE_PLAY, body.product_id, "rejected_product", False)
        db.commit()
        return VerifyResponse(
            verified=False, entitlement_granted=False,
            message="Product not recognized.", error_code="product_mismatch",
        )

    # Classify first — picks the right Publisher API endpoint
    # (subscriptions and products live under different paths).
    product_class = classify_google_play_product(body.product_id)

    # Verify with Google
    package = body.package_name or getattr(settings, 'GOOGLE_PLAY_PACKAGE_NAME', '')
    verified, detail, inner_code, gp_expires_at, gp_auto_renew = _verify_google_play_token(
        package, body.product_id, body.purchase_token, product_class,
    )

    if not verified:
        _log.warning("GP verification failed user=%s code=%s detail=%s", uid, inner_code, detail)
        _record_payment(db, uid, ref, SOURCE_GOOGLE_PLAY, body.product_id, f"verification_failed:{inner_code}", False)
        db.commit()
        return VerifyResponse(
            verified=False, entitlement_granted=False,
            message="Purchase could not be verified.", error_code=inner_code,
        )

    if product_class == "subscription":
        ent_type = ENTITLEMENT_SUBSCRIPTION
    else:
        ent_type = ENTITLEMENT_LIFETIME

    originating_device_id = _resolve_originating_device_id(db, uid, body.installation_id)
    ent = grant_entitlement(
        db, uid, SOURCE_GOOGLE_PLAY, ref, ent_type,
        product_id=body.product_id,
        originating_device_id=originating_device_id,
        expires_at=gp_expires_at,
        auto_renew=gp_auto_renew,
    )
    _record_payment(db, uid, ref, SOURCE_GOOGLE_PLAY, body.product_id, "completed", ent is not None)
    db.commit()

    # Engagement-moment verification re-send. The buyer is at peak
    # engagement; if they're still unverified, this is the highest-
    # leverage moment to ask. No-op if already verified or a live
    # token still exists.
    from app.routers.auth import maybe_resend_verification
    user_row = db.query(User).filter(User.id == uid).one_or_none()
    if user_row is not None:
        maybe_resend_verification(db, user_row)

    _log.info("GP purchase verified: user=%s order=%s type=%s", uid, order_id, product_class or "lifetime")
    return VerifyResponse(verified=True, entitlement_granted=True, message="Purchase verified.")


# ── Amazon ─────────────────────────────────────────────────────────────

@router.post("/amazon/verify", response_model=VerifyResponse)
@legacy_router.post("/amazon/verify", response_model=VerifyResponse, include_in_schema=False)
def verify_amazon(
    body: AmazonVerifyRequest,
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> VerifyResponse:
    """Verify an Amazon IAP receipt and grant entitlement.

    Calls Amazon's Receipt Verification Service (RVS).
    Validates receipt authenticity and product match before granting.
    """
    uid = uuid.UUID(user_id)
    ref = f"amz_{body.receipt_id[:64]}"

    # Idempotency
    existing = db.query(WebPayment).filter(WebPayment.paddle_transaction_id == ref).first()
    if existing and existing.entitlement_granted:
        return VerifyResponse(verified=True, entitlement_granted=True, message="Already verified.")

    # Strict product validation
    if not _validate_amazon_product(body.product_id):
        _log.warning("Amazon verify: product mismatch. got=%s expected=%s user=%s",
                      body.product_id, settings.AMAZON_PRODUCT_ID, uid)
        _record_payment(db, uid, ref, SOURCE_AMAZON, body.product_id or "unknown", "rejected_product", False)
        db.commit()
        return VerifyResponse(verified=False, entitlement_granted=False, message="Product not recognized.")

    # Verify with Amazon RVS
    verified, detail, rvs_product = _verify_amazon_receipt(body.receipt_id, body.user_id)

    if not verified:
        _log.warning("Amazon verification failed for user %s: %s", uid, detail)
        _record_payment(db, uid, ref, SOURCE_AMAZON, body.product_id or "unknown", "verification_failed", False)
        db.commit()
        return VerifyResponse(verified=False, entitlement_granted=False, message="Receipt could not be verified.")

    # Cross-check: RVS-returned product must match a known product
    actual_product = rvs_product or body.product_id or "unknown"
    if rvs_product and settings.all_amazon_product_ids and rvs_product not in settings.all_amazon_product_ids:
        _log.warning("Amazon RVS product mismatch: rvs=%s known=%s user=%s",
                      rvs_product, settings.all_amazon_product_ids, uid)
        _record_payment(db, uid, ref, SOURCE_AMAZON, rvs_product, "product_mismatch_rvs", False)
        db.commit()
        return VerifyResponse(verified=False, entitlement_granted=False, message="Product verification failed.")

    # Classify product and grant appropriate entitlement
    product_class = classify_amazon_product(actual_product)
    if product_class == "subscription":
        ent_type = ENTITLEMENT_SUBSCRIPTION
    else:
        ent_type = ENTITLEMENT_LIFETIME

    originating_device_id = _resolve_originating_device_id(db, uid, body.installation_id)
    ent = grant_entitlement(
        db, uid, SOURCE_AMAZON, ref, ent_type,
        product_id=actual_product,
        originating_device_id=originating_device_id,
    )
    _record_payment(db, uid, ref, SOURCE_AMAZON, actual_product, "completed", ent is not None)
    db.commit()

    # Mirror Google Play: re-send verification on engagement.
    from app.routers.auth import maybe_resend_verification
    user_row = db.query(User).filter(User.id == uid).one_or_none()
    if user_row is not None:
        maybe_resend_verification(db, user_row)

    _log.info("Amazon purchase verified: user=%s receipt=%s type=%s", uid, body.receipt_id[:16], product_class or "lifetime")
    return VerifyResponse(verified=True, entitlement_granted=True, message="Purchase verified.")


# ── Restore ────────────────────────────────────────────────────────────

@router.post("/restore", response_model=RestoreResponse)
@legacy_router.post("/restore", response_model=RestoreResponse, include_in_schema=False)
def restore_purchases(
    user_id: str = Depends(get_current_user_id),
    db: Session = Depends(get_db),
) -> RestoreResponse:
    """Server-side restore of an authenticated user's premium state.

    Two things happen:
      1. recompute_user_premium — flips the cached has_premium_access /
         has_lifetime_access booleans on the User row to whatever the
         active UserEntitlement rows say. This is the path that fixes
         "I have an active entitlement but the cached flag is stale".
      2. restore_lifetime_if_granted — looks up the lifetime-grants
         ledger by email and reactivates an entitlement if a prior
         grant exists (handles delete-then-resignup).

    What this does NOT do: re-verify any Google Play / Amazon purchase
    tokens. Those tokens live in the BillingClient on-device — the
    client has to enumerate them with queryPurchasesAsync() and POST
    each through /me/purchases/google-play/verify itself.

    Wired at /me/purchases/restore (canonical) and /purchases/restore
    (legacy alias for older Android builds — the live 1.0.38 client was
    POSTing to the legacy path and getting 404s).
    """
    from app.billing import recompute_user_premium, restore_lifetime_if_granted

    uid = uuid.UUID(user_id)
    user = db.query(User).filter(User.id == uid).one_or_none()
    if user is None:
        # Bearer was valid but the user was deleted between token mint
        # and now — nothing to restore.
        raise HTTPException(status_code=404, detail="User not found")

    try:
        restore_lifetime_if_granted(db, user)
    except Exception:
        _log.exception("RESTORE: ledger restore failed for user=%s", uid)
    recompute_user_premium(db, uid)
    db.commit()
    db.refresh(user)

    active_count = db.query(UserEntitlement).filter(
        UserEntitlement.user_id == uid,
        UserEntitlement.status == "active",
    ).count()

    _log.info(
        "RESTORE user=%s premium=%s lifetime=%s verified=%s active_ents=%d",
        uid, user.has_premium_access, user.has_lifetime_access,
        user.is_verified, active_count,
    )
    return RestoreResponse(
        restored=True,
        has_premium_access=user.has_premium_access,
        has_lifetime_access=user.has_lifetime_access,
        is_verified=user.is_verified,
        active_entitlements=active_count,
        message="Premium state refreshed.",
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

def _verify_amazon_receipt(receipt_id: str, amazon_user_id: str) -> tuple[bool, str, str | None]:
    """Verify an Amazon IAP receipt via the Receipt Verification Service.

    Returns (verified, detail_message, product_id_from_rvs).
    The returned product_id should be cross-checked against config.
    """
    secret = settings.AMAZON_APP_SECRET
    if not secret:
        _log.error("AMAZON_APP_SECRET not configured. Cannot verify.")
        return False, "Server not configured for Amazon verification", None

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
                return False, "Receipt ID mismatch in RVS response", None

            if cancel_date:
                _log.warning("Amazon receipt %s has cancelDate=%s", receipt_id[:16], cancel_date)
                return False, "Purchase was canceled", rvs_product

            return True, "Verified", rvs_product

        if resp.status_code == 400:
            return False, "Invalid receipt (RVS 400)", None
        if resp.status_code == 496:
            return False, "Invalid shared secret (RVS 496)", None
        if resp.status_code == 497:
            return False, "Invalid user ID (RVS 497)", None
        if resp.status_code == 500:
            return False, "Amazon RVS internal error (500)", None

        return False, f"RVS returned {resp.status_code}", None

    except httpx.HTTPError as e:
        _log.error("Amazon RVS error: %s", e)
        return False, str(e), None


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
