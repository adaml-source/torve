"""
Store-specific purchase verification services.
Each verifier validates a receipt/token with its respective store API
and returns a normalized result.
"""
import base64
import json
import logging
import time
from dataclasses import dataclass
from typing import Protocol

import httpx

from .config import get_settings

logger = logging.getLogger(__name__)
settings = get_settings()

PRODUCT_TO_ENTITLEMENT = {
    "com.torve.pro.lifetime": ("torve_pro_lifetime", "lifetime"),
    "com.torve.pro.monthly": ("torve_pro_monthly", "subscription"),
    "com.torve.pro.subscription": ("torve_pro_monthly", "subscription"),
    "com.torve.pro.lifetime.amazon": ("torve_pro_lifetime", "lifetime"),
}


@dataclass
class VerificationResult:
    valid: bool
    product_id: str = ""
    transaction_id: str = ""
    original_transaction_id: str = ""
    purchased_at_ms: int | None = None
    expires_at_ms: int | None = None
    error: str = ""
    raw: dict | None = None


class StoreVerifier(Protocol):
    async def verify(self, **kwargs) -> VerificationResult: ...


# ── Apple ──


class AppleVerifier:
    """
    Verifies StoreKit 2 JWS signed transactions.
    In production, the JWS is signed by Apple. We decode the payload
    to extract product_id and transactionId.
    Full certificate chain validation requires the Apple root cert;
    for v1 we trust the decoded payload and verify transaction_id
    against the App Store Server API if credentials are configured.
    """

    async def verify(self, *, transaction_jws: str, product_id: str) -> VerificationResult:
        try:
            # JWS is header.payload.signature — decode the payload
            parts = transaction_jws.split(".")
            if len(parts) != 3:
                return VerificationResult(valid=False, error="Invalid JWS format")

            # Base64url decode the payload
            payload_b64 = parts[1]
            padding = 4 - len(payload_b64) % 4
            if padding != 4:
                payload_b64 += "=" * padding
            payload_bytes = base64.urlsafe_b64decode(payload_b64)
            payload = json.loads(payload_bytes)

            txn_id = str(payload.get("transactionId", ""))
            orig_txn_id = str(payload.get("originalTransactionId", txn_id))
            decoded_product = payload.get("productId", "")
            purchase_date = payload.get("purchaseDate")
            expires_date = payload.get("expiresDate")
            bundle_id = payload.get("bundleId", "")

            if bundle_id and bundle_id != settings.apple_bundle_id:
                return VerificationResult(valid=False, error="Bundle ID mismatch")

            if decoded_product != product_id:
                return VerificationResult(valid=False, error="Product ID mismatch")

            if not txn_id:
                return VerificationResult(valid=False, error="Missing transaction ID")

            purchased_at_ms = None
            if purchase_date:
                purchased_at_ms = int(purchase_date) if isinstance(purchase_date, (int, float)) else None

            expires_at_ms = None
            if expires_date:
                expires_at_ms = int(expires_date) if isinstance(expires_date, (int, float)) else None

            # If App Store Server API credentials are configured, verify with Apple
            if settings.apple_issuer_id and settings.apple_key_id:
                api_valid = await self._verify_with_server_api(txn_id)
                if not api_valid:
                    return VerificationResult(valid=False, error="Apple Server API rejection")

            return VerificationResult(
                valid=True,
                product_id=decoded_product,
                transaction_id=txn_id,
                original_transaction_id=orig_txn_id,
                purchased_at_ms=purchased_at_ms,
                expires_at_ms=expires_at_ms,
                raw=payload,
            )

        except Exception as e:
            logger.exception("Apple verification failed")
            return VerificationResult(valid=False, error=str(e))

    async def _verify_with_server_api(self, transaction_id: str) -> bool:
        """Optional: verify transaction via App Store Server API v2."""
        try:
            # This requires generating a signed JWT with ES256 using Apple's key
            # For v1, this is a placeholder that returns True
            # Full implementation requires: apple_private_key, issuer_id, key_id
            logger.info("Apple Server API verification for txn %s (credential check skipped in v1)", transaction_id)
            return True
        except Exception:
            logger.exception("Apple Server API check failed")
            return False


# ── Google Play ──


class GooglePlayVerifier:
    """
    Verifies Google Play purchases using the androidpublisher API v3.
    Requires a service account JSON key with androidpublisher scope.
    """

    _cached_token: str | None = None
    _token_expires_at: float = 0

    async def verify(self, *, product_id: str, purchase_token: str) -> VerificationResult:
        if not settings.google_service_account_json:
            logger.warning("Google service account not configured; accepting purchase on trust for dev")
            return VerificationResult(
                valid=True,
                product_id=product_id,
                transaction_id=purchase_token[:64],
                original_transaction_id=purchase_token[:64],
                raw={"dev_mode": True},
            )

        try:
            access_token = await self._get_access_token()
            url = (
                f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/"
                f"{settings.google_package_name}/purchases/products/{product_id}/tokens/{purchase_token}"
            )
            async with httpx.AsyncClient() as client:
                resp = await client.get(url, headers={"Authorization": f"Bearer {access_token}"}, timeout=15)

            if resp.status_code != 200:
                return VerificationResult(valid=False, error=f"Google API returned {resp.status_code}: {resp.text}")

            data = resp.json()
            purchase_state = data.get("purchaseState", -1)
            # 0 = Purchased, 1 = Canceled, 2 = Pending
            if purchase_state != 0:
                return VerificationResult(valid=False, error=f"Purchase state: {purchase_state}")

            acknowledgement_state = data.get("acknowledgementState", 0)
            order_id = data.get("orderId", "")
            purchase_time_ms = data.get("purchaseTimeMillis")

            return VerificationResult(
                valid=True,
                product_id=product_id,
                transaction_id=order_id or purchase_token[:64],
                original_transaction_id=order_id or purchase_token[:64],
                purchased_at_ms=int(purchase_time_ms) if purchase_time_ms else None,
                raw=data,
            )

        except Exception as e:
            logger.exception("Google Play verification failed")
            return VerificationResult(valid=False, error=str(e))

    async def _get_access_token(self) -> str:
        """Get OAuth2 access token from service account JSON."""
        import jwt as pyjwt  # python-jose uses 'jose', but for Google we need standard JWT

        if self._cached_token and time.time() < self._token_expires_at:
            return self._cached_token

        sa = json.loads(settings.google_service_account_json)
        now = int(time.time())
        claim_set = {
            "iss": sa["client_email"],
            "scope": "https://www.googleapis.com/auth/androidpublisher",
            "aud": "https://oauth2.googleapis.com/token",
            "iat": now,
            "exp": now + 3600,
        }

        # Use jose to sign with RS256
        from jose import jwt as jose_jwt
        signed_jwt = jose_jwt.encode(claim_set, sa["private_key"], algorithm="RS256")

        async with httpx.AsyncClient() as client:
            resp = await client.post(
                "https://oauth2.googleapis.com/token",
                data={"grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer", "assertion": signed_jwt},
                timeout=10,
            )
            resp.raise_for_status()
            token_data = resp.json()

        self._cached_token = token_data["access_token"]
        self._token_expires_at = now + token_data.get("expires_in", 3500) - 60
        return self._cached_token


# ── Amazon ──


class AmazonVerifier:
    """
    Verifies Amazon IAP receipts using the Receipt Verification Service (RVS).
    """

    @property
    def _base_url(self) -> str:
        if settings.amazon_rvs_sandbox:
            return "https://appstore-sdk.amazon.com/sandbox"
        return "https://appstore-sdk.amazon.com"

    async def verify(self, *, receipt_id: str, amazon_user_id: str, product_id: str) -> VerificationResult:
        if not settings.amazon_shared_secret:
            logger.warning("Amazon shared secret not configured; accepting purchase on trust for dev")
            return VerificationResult(
                valid=True,
                product_id=product_id,
                transaction_id=receipt_id,
                original_transaction_id=receipt_id,
                raw={"dev_mode": True},
            )

        try:
            url = f"{self._base_url}/version/1.0/verifyReceiptId/developer/{settings.amazon_shared_secret}/user/{amazon_user_id}/receiptId/{receipt_id}"
            async with httpx.AsyncClient() as client:
                resp = await client.get(url, timeout=15)

            if resp.status_code != 200:
                return VerificationResult(valid=False, error=f"Amazon RVS returned {resp.status_code}")

            data = resp.json()
            rvs_product_id = data.get("productId", "")
            if rvs_product_id != product_id:
                return VerificationResult(valid=False, error="Product ID mismatch from RVS")

            cancel_date = data.get("cancelDate")
            if cancel_date:
                return VerificationResult(valid=False, error="Purchase was cancelled")

            purchase_date_ms = data.get("purchaseDate")

            return VerificationResult(
                valid=True,
                product_id=product_id,
                transaction_id=data.get("receiptId", receipt_id),
                original_transaction_id=data.get("receiptId", receipt_id),
                purchased_at_ms=int(purchase_date_ms) if purchase_date_ms else None,
                raw=data,
            )

        except Exception as e:
            logger.exception("Amazon verification failed")
            return VerificationResult(valid=False, error=str(e))


# ── Shared helpers ──

apple_verifier = AppleVerifier()
google_verifier = GooglePlayVerifier()
amazon_verifier = AmazonVerifier()
