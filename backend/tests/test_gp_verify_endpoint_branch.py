"""Regression test for the products-vs-subscriptions endpoint split.

Background: the live app submitted a real subscription purchase token
on 2026-04-26, the backend hit
  GET /androidpublisher/v3/applications/{pkg}/purchases/products/...
and Google returned 404 because subscription tokens are only
queryable under
  /purchases/subscriptions/...

This test pins the URL chosen by _verify_google_play_token so a
future refactor can't silently regress the live purchase flow.
"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
from types import SimpleNamespace

import pytest

from app.routers import purchase_verify as pv


class _FakeReadiness:
    ready = True
    reason = ""


class _FakeResp:
    def __init__(self, status_code: int, payload: dict | None = None):
        self.status_code = status_code
        self._payload = payload or {}

    def json(self):
        return self._payload


def _patch_common(monkeypatch, captured_urls, payload, status_code=200):
    monkeypatch.setattr(pv, "_get_google_access_token", lambda _: "fake-token")
    monkeypatch.setattr(
        "app.google_play_readiness.assess_readiness", lambda: _FakeReadiness()
    )

    def _fake_get(url, headers=None, timeout=None):
        captured_urls.append(url)
        return _FakeResp(status_code, payload)

    monkeypatch.setattr(pv.httpx, "get", _fake_get)


def test_subscription_uses_subscriptions_endpoint(monkeypatch):
    """Subscription product class must hit /purchases/subscriptions/..."""
    captured = []
    expiry_ms = int(
        (datetime.now(timezone.utc) + timedelta(days=30)).timestamp() * 1000
    )
    _patch_common(
        monkeypatch, captured,
        payload={"paymentState": 1, "expiryTimeMillis": str(expiry_ms), "autoRenewing": True},
    )

    verified, _, code, expires_at, auto_renew = pv._verify_google_play_token(
        "com.torve.app", "com.torve.pro.subscription", "TOK", "subscription",
    )

    assert verified is True, code
    assert "/purchases/subscriptions/com.torve.pro.subscription/tokens/TOK" in captured[0]
    assert "/purchases/products/" not in captured[0]
    assert expires_at is not None and expires_at > datetime.now(timezone.utc)
    assert auto_renew is True


def test_lifetime_uses_products_endpoint(monkeypatch):
    """One-time product class must keep using /purchases/products/..."""
    captured = []
    _patch_common(monkeypatch, captured, payload={"purchaseState": 0})

    verified, _, code, expires_at, auto_renew = pv._verify_google_play_token(
        "com.torve.app", "com.torve.pro.lifetime", "TOK", "lifetime",
    )

    assert verified is True, code
    assert "/purchases/products/com.torve.pro.lifetime/tokens/TOK" in captured[0]
    assert "/purchases/subscriptions/" not in captured[0]
    assert expires_at is None
    assert auto_renew is None


def test_subscription_pending_payment_rejected(monkeypatch):
    captured = []
    _patch_common(monkeypatch, captured, payload={"paymentState": 0})

    verified, detail, code, *_ = pv._verify_google_play_token(
        "com.torve.app", "com.torve.pro.subscription", "TOK", "subscription",
    )
    assert verified is False
    assert code == "not_verified"
    assert detail == "payment_pending"


def test_subscription_already_expired_rejected(monkeypatch):
    captured = []
    past_ms = int((datetime.now(timezone.utc) - timedelta(days=1)).timestamp() * 1000)
    _patch_common(
        monkeypatch, captured,
        payload={"paymentState": 1, "expiryTimeMillis": str(past_ms)},
    )
    verified, detail, code, *_ = pv._verify_google_play_token(
        "com.torve.app", "com.torve.pro.subscription", "TOK", "subscription",
    )
    assert verified is False
    assert detail == "already_expired"


def test_subscription_404_classified_not_verified(monkeypatch):
    """A subscription token rejected by Google with 404 maps to
    not_verified — the failure mode that hit production."""
    captured = []
    _patch_common(monkeypatch, captured, payload={}, status_code=404)
    verified, detail, code, *_ = pv._verify_google_play_token(
        "com.torve.app", "com.torve.pro.subscription", "TOK", "subscription",
    )
    assert verified is False
    assert code == "not_verified"
    assert detail == "google_404"
