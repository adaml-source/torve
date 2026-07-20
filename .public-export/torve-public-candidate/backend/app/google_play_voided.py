"""Google Play voided-purchase reconciliation.

Google Play refunds do not arrive through the normal purchase verification
endpoint. The Android Publisher Voided Purchases API is the server-side feed
for orders that should have access revoked.
"""
from __future__ import annotations

import logging
from datetime import datetime, timedelta, timezone
from typing import Any

import httpx
from sqlalchemy.orm import Session

from app.billing import (
    ENTITLEMENT_LIFETIME,
    ENTITLEMENT_SUBSCRIPTION,
    SOURCE_GOOGLE_PLAY,
    expire_subscription,
    revoke_entitlement,
)
from app.config import settings
from app.models import UserEntitlement, WebPayment
from app.routers.purchase_verify import _get_google_access_token

_log = logging.getLogger(__name__)


class GooglePlayVoidedPurchaseError(Exception):
    def __init__(self, error_code: str, message: str, status_code: int = 502):
        super().__init__(message)
        self.error_code = error_code
        self.message = message
        self.status_code = status_code


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _dt_to_ms(value: datetime) -> int:
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    return int(value.timestamp() * 1000)


def _ms_to_dt(value: Any) -> datetime | None:
    try:
        return datetime.fromtimestamp(int(value) / 1000, tz=timezone.utc)
    except (TypeError, ValueError, OSError):
        return None


def _source_refs_for_voided_purchase(voided: dict[str, Any]) -> list[str]:
    refs: list[str] = []
    order_id = str(voided.get("orderId") or "").strip()
    purchase_token = str(voided.get("purchaseToken") or "").strip()
    if order_id:
        refs.append(f"gp_{order_id}")
    if purchase_token:
        refs.append(f"gp_{purchase_token[:64]}")
        refs.append(f"gp_{purchase_token}")
    return list(dict.fromkeys(refs))


def _voided_purchase_out(
    voided: dict[str, Any],
    *,
    matched_refs: list[str],
    revoked_entitlement_ids: list[str],
    expired_entitlement_ids: list[str],
    updated_payment_ids: list[str],
) -> dict:
    purchase_token = str(voided.get("purchaseToken") or "")
    voided_at = _ms_to_dt(voided.get("voidedTimeMillis"))
    return {
        "order_id": voided.get("orderId"),
        "purchase_token_prefix": purchase_token[:12] if purchase_token else None,
        "voided_at": voided_at.isoformat() if voided_at else None,
        "voided_source": voided.get("voidedSource"),
        "voided_reason": voided.get("voidedReason"),
        "voided_quantity": voided.get("voidedQuantity"),
        "matched_refs": matched_refs,
        "revoked_entitlement_ids": revoked_entitlement_ids,
        "expired_entitlement_ids": expired_entitlement_ids,
        "updated_payment_ids": updated_payment_ids,
    }


def fetch_google_play_voided_purchases(
    *,
    start_time: datetime,
    end_time: datetime | None = None,
    include_subscriptions: bool = True,
    include_quantity_based_partial_refund: bool = True,
    max_pages: int = 20,
) -> list[dict[str, Any]]:
    """Fetch voided purchases from Google Play for the configured package."""
    package_name = settings.GOOGLE_PLAY_PACKAGE_NAME
    creds_path = settings.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON
    if not package_name or not creds_path:
        raise GooglePlayVoidedPurchaseError(
            "google_play_config_missing",
            "Google Play package name or service account is not configured.",
            503,
        )

    access_token = _get_google_access_token(creds_path)
    if not access_token:
        raise GooglePlayVoidedPurchaseError(
            "google_play_auth_failed",
            "Could not authenticate to Google Play.",
            503,
        )

    url = (
        "https://androidpublisher.googleapis.com/androidpublisher/v3"
        f"/applications/{package_name}/purchases/voidedpurchases"
    )
    params: dict[str, str | int | bool] = {
        "startTime": _dt_to_ms(start_time),
        "endTime": _dt_to_ms(end_time or _now()),
        "maxResults": 1000,
        # 1 returns both in-app products and subscriptions; 0 is in-app only.
        "type": 1 if include_subscriptions else 0,
        "includeQuantityBasedPartialRefund": include_quantity_based_partial_refund,
    }
    headers = {"Authorization": f"Bearer {access_token}"}
    rows: list[dict[str, Any]] = []

    for _ in range(max_pages):
        try:
            resp = httpx.get(url, headers=headers, params=params, timeout=15.0)
        except httpx.TimeoutException as exc:
            raise GooglePlayVoidedPurchaseError(
                "google_play_voided_timeout",
                "Google Play voided-purchase lookup timed out.",
                502,
            ) from exc
        except httpx.HTTPError as exc:
            raise GooglePlayVoidedPurchaseError(
                "google_play_voided_unreachable",
                "Google Play voided-purchase lookup failed.",
                502,
            ) from exc

        if resp.status_code in (401, 403):
            raise GooglePlayVoidedPurchaseError(
                "google_play_permission_denied",
                "Google Play service account cannot read voided purchases.",
                503,
            )
        if 500 <= resp.status_code < 600:
            raise GooglePlayVoidedPurchaseError(
                "google_play_voided_upstream_error",
                "Google Play voided-purchase lookup failed upstream.",
                502,
            )
        if resp.status_code != 200:
            raise GooglePlayVoidedPurchaseError(
                "google_play_voided_failed",
                "Google Play voided-purchase lookup failed.",
                502,
            )

        payload = resp.json()
        rows.extend(payload.get("voidedPurchases") or [])
        next_page = (payload.get("tokenPagination") or {}).get("nextPageToken")
        if not next_page:
            break
        params["token"] = next_page
    return rows


def apply_google_play_voided_purchase(
    db: Session,
    voided: dict[str, Any],
    *,
    dry_run: bool = True,
) -> dict:
    """Apply one voided purchase to historical payment/entitlement records."""
    refs = _source_refs_for_voided_purchase(voided)
    if not refs:
        return _voided_purchase_out(
            voided,
            matched_refs=[],
            revoked_entitlement_ids=[],
            expired_entitlement_ids=[],
            updated_payment_ids=[],
        )

    entitlements = (
        db.query(UserEntitlement)
        .filter(
            UserEntitlement.source == SOURCE_GOOGLE_PLAY,
            UserEntitlement.source_ref.in_(refs),
        )
        .all()
    )
    payments = (
        db.query(WebPayment)
        .filter(WebPayment.paddle_transaction_id.in_(refs))
        .all()
    )
    matched_refs = sorted({e.source_ref for e in entitlements} | {p.paddle_transaction_id for p in payments})

    revoked_ids: list[str] = []
    expired_ids: list[str] = []
    if not dry_run:
        for ent in entitlements:
            if ent.entitlement_type == ENTITLEMENT_LIFETIME:
                revoked = revoke_entitlement(
                    db,
                    SOURCE_GOOGLE_PLAY,
                    ent.source_ref,
                    ENTITLEMENT_LIFETIME,
                )
                if revoked is not None:
                    revoked_ids.append(str(revoked.id))
            elif ent.entitlement_type == ENTITLEMENT_SUBSCRIPTION:
                expired = expire_subscription(db, SOURCE_GOOGLE_PLAY, ent.source_ref)
                if expired is not None:
                    expired_ids.append(str(expired.id))

        refunded_at = _ms_to_dt(voided.get("voidedTimeMillis")) or _now()
        event_at = _now()
        for payment in payments:
            payment.status = "refunded"
            payment.refunded_at = refunded_at
            payment.revoked_at = event_at
            payment.entitlement_granted = False
            payment.last_event_type = "google_play_voided_purchase"
            payment.last_event_at = event_at
            payment.updated_at = event_at
        if payments:
            db.flush()

    return _voided_purchase_out(
        voided,
        matched_refs=matched_refs,
        revoked_entitlement_ids=revoked_ids,
        expired_entitlement_ids=expired_ids,
        updated_payment_ids=[str(p.id) for p in payments] if not dry_run else [],
    )


def sync_google_play_voided_purchases(
    db: Session,
    *,
    lookback_days: int = 30,
    dry_run: bool = True,
) -> dict:
    if lookback_days < 1 or lookback_days > 30:
        raise GooglePlayVoidedPurchaseError(
            "google_play_voided_lookback_invalid",
            "Google Play voided-purchase lookback must be between 1 and 30 days.",
            400,
        )

    end_time = _now()
    start_time = end_time - timedelta(days=lookback_days)
    rows = fetch_google_play_voided_purchases(start_time=start_time, end_time=end_time)
    applied = [apply_google_play_voided_purchase(db, row, dry_run=dry_run) for row in rows]
    matched = [row for row in applied if row["matched_refs"]]

    summary = {
        "ok": True,
        "dry_run": dry_run,
        "lookback_days": lookback_days,
        "fetched": len(rows),
        "matched": len(matched),
        "revoked_entitlements": sum(len(row["revoked_entitlement_ids"]) for row in applied),
        "expired_entitlements": sum(len(row["expired_entitlement_ids"]) for row in applied),
        "updated_payments": sum(len(row["updated_payment_ids"]) for row in applied),
        "items": applied,
    }
    _log.warning(
        "GOOGLE_PLAY_VOIDED_SYNC dry_run=%s fetched=%d matched=%d revoked=%d expired=%d payments=%d",
        dry_run,
        summary["fetched"],
        summary["matched"],
        summary["revoked_entitlements"],
        summary["expired_entitlements"],
        summary["updated_payments"],
    )
    return summary
