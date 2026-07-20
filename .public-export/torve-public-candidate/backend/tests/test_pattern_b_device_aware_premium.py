"""
Tests for legacy Pattern B metadata under free/default account access.

Behavior pinned:
  - Verified user: any active entitlement counts on any device (existing).
  - Unverified user: entitlement counts only on the originating device,
    OR on any device when originating_device_id IS NULL (grandfathered).
  - After verification flips to True, all devices regain access.
  - /me/access-state surfaces a needs_verification=True hint when an
    entitlement is hidden from the calling device because of unverification.
  - account access honors the X-Torve-Installation-Id header.
  - The legacy global check_premium_active() (no requesting_device_id)
    behaves exactly as before — important so device-cap enforcement
    and other internal callers don't accidentally start gating.
"""
from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone

import pytest

from app.billing import (
    ENTITLEMENT_LIFETIME,
    ENTITLEMENT_SUBSCRIPTION,
    check_premium_active,
    grant_entitlement,
    resolve_access_state,
)
from app.models import Device, User, UserEntitlement
from app.security import create_access_token


def _auth(user):
    return {"Authorization": f"Bearer {create_access_token(str(user.id))}"}


def _mk_device(db, user, installation_id: str) -> Device:
    d = Device(
        user_id=user.id,
        device_type="phone",
        platform="android",
        display_name=f"dev-{installation_id[:6]}",
        installation_id=installation_id,
        is_active=True,
    )
    db.add(d)
    db.commit()
    db.refresh(d)
    return d


def _set_verified(db, user, value: bool) -> None:
    user.is_verified = value
    db.commit()


def _reset_user_state(db, user) -> None:
    """Strip the conftest fixture's pre-loaded admin_grant entitlement
    (NULL origin → grandfathered) so each Pattern B test controls its
    own entitlement set. Also resets premium booleans on User."""
    db.query(UserEntitlement).filter(UserEntitlement.user_id == user.id).delete()
    user.has_lifetime_access = False
    user.has_premium_access = False
    db.commit()


def _cleanup(db, user_id):
    db.query(UserEntitlement).filter(UserEntitlement.user_id == user_id).delete()
    db.query(Device).filter(Device.user_id == user_id).delete()
    db.commit()


# ── core free-software access behavior ───────────────────────────────


def test_verified_user_has_premium_on_any_device(client, test_user, db):
    try:
        _reset_user_state(db, test_user); _set_verified(db, test_user, True)
        d_origin = _mk_device(db, test_user, f"orig-{uuid.uuid4().hex[:8]}")
        d_other = _mk_device(db, test_user, f"other-{uuid.uuid4().hex[:8]}")
        grant_entitlement(
            db, test_user.id, "google_play",
            f"gp-test-{uuid.uuid4().hex[:8]}",
            ENTITLEMENT_LIFETIME,
            originating_device_id=d_origin.id,
        )
        db.commit()

        assert check_premium_active(db, test_user.id, requesting_device_id=d_origin.id)
        assert check_premium_active(db, test_user.id, requesting_device_id=d_other.id)
        # Legacy no-device-id path also works.
        assert check_premium_active(db, test_user.id)
    finally:
        _cleanup(db, test_user.id)


def test_unverified_user_access_is_not_limited_to_originating_device(client, test_user, db):
    try:
        _reset_user_state(db, test_user); _set_verified(db, test_user, False)
        d_origin = _mk_device(db, test_user, f"orig-{uuid.uuid4().hex[:8]}")
        d_other = _mk_device(db, test_user, f"other-{uuid.uuid4().hex[:8]}")
        grant_entitlement(
            db, test_user.id, "google_play",
            f"gp-test-{uuid.uuid4().hex[:8]}",
            ENTITLEMENT_LIFETIME,
            originating_device_id=d_origin.id,
        )
        db.commit()

        assert check_premium_active(db, test_user.id, requesting_device_id=d_origin.id)
        assert check_premium_active(db, test_user.id, requesting_device_id=d_other.id)
        assert check_premium_active(db, test_user.id)
    finally:
        _cleanup(db, test_user.id)


def test_grandfathered_null_origin_works_on_any_device(client, test_user, db):
    """Pre-Pattern-B entitlements (originating_device_id IS NULL) are
    grandfathered to all devices, even for unverified users. Critical
    so existing customers don't suddenly lose access on the rollout."""
    try:
        _reset_user_state(db, test_user); _set_verified(db, test_user, False)
        d_a = _mk_device(db, test_user, f"a-{uuid.uuid4().hex[:8]}")
        d_b = _mk_device(db, test_user, f"b-{uuid.uuid4().hex[:8]}")
        grant_entitlement(
            db, test_user.id, "paddle_web",
            f"pad-test-{uuid.uuid4().hex[:8]}",
            ENTITLEMENT_LIFETIME,
            originating_device_id=None,
        )
        db.commit()

        assert check_premium_active(db, test_user.id, requesting_device_id=d_a.id)
        assert check_premium_active(db, test_user.id, requesting_device_id=d_b.id)
    finally:
        _cleanup(db, test_user.id)


def test_verification_unlocks_all_devices_after_grant(client, test_user, db):
    try:
        _reset_user_state(db, test_user); _set_verified(db, test_user, False)
        d_origin = _mk_device(db, test_user, f"orig-{uuid.uuid4().hex[:8]}")
        d_other = _mk_device(db, test_user, f"other-{uuid.uuid4().hex[:8]}")
        grant_entitlement(
            db, test_user.id, "google_play",
            f"gp-test-{uuid.uuid4().hex[:8]}",
            ENTITLEMENT_LIFETIME,
            originating_device_id=d_origin.id,
        )
        db.commit()

        assert check_premium_active(db, test_user.id, requesting_device_id=d_other.id)

        # Verify the email — DO NOT reset state here, that would wipe the
        # entitlement we're testing the unlock-on-verification of.
        _set_verified(db, test_user, True)
        assert check_premium_active(db, test_user.id, requesting_device_id=d_other.id)
    finally:
        _cleanup(db, test_user.id)


def test_subscription_unverified_not_limited_to_originating_device(client, test_user, db):
    try:
        _reset_user_state(db, test_user); _set_verified(db, test_user, False)
        d_origin = _mk_device(db, test_user, f"orig-{uuid.uuid4().hex[:8]}")
        d_other = _mk_device(db, test_user, f"other-{uuid.uuid4().hex[:8]}")
        grant_entitlement(
            db, test_user.id, "google_play",
            f"gp-sub-{uuid.uuid4().hex[:8]}",
            ENTITLEMENT_SUBSCRIPTION,
            expires_at=datetime.now(timezone.utc) + timedelta(days=30),
            auto_renew=True,
            originating_device_id=d_origin.id,
        )
        db.commit()

        assert check_premium_active(db, test_user.id, requesting_device_id=d_origin.id)
        assert check_premium_active(db, test_user.id, requesting_device_id=d_other.id)
    finally:
        _cleanup(db, test_user.id)


# ── /me/access-state keeps legacy verification field inert ───────────


def test_access_state_needs_verification_false_on_other_device(client, test_user, db):
    try:
        _reset_user_state(db, test_user); _set_verified(db, test_user, False)
        d_origin = _mk_device(db, test_user, f"orig-{uuid.uuid4().hex[:8]}")
        d_other = _mk_device(db, test_user, f"other-{uuid.uuid4().hex[:8]}")
        grant_entitlement(
            db, test_user.id, "google_play",
            f"gp-as-{uuid.uuid4().hex[:8]}",
            ENTITLEMENT_LIFETIME,
            originating_device_id=d_origin.id,
        )
        db.commit()

        # Calling from origin device — premium visible, no verification nag.
        state_origin = resolve_access_state(
            db, test_user.id, installation_id=d_origin.installation_id,
        )
        assert state_origin["has_premium_access"] is True
        assert state_origin["needs_verification"] is False

        # Calling from other device — access remains visible, no paid verification nag.
        state_other = resolve_access_state(
            db, test_user.id, installation_id=d_other.installation_id,
        )
        assert state_other["has_premium_access"] is True
        assert state_other["needs_verification"] is False
    finally:
        _cleanup(db, test_user.id)


def test_access_state_needs_verification_false_for_verified_user(client, test_user, db):
    try:
        _reset_user_state(db, test_user); _set_verified(db, test_user, True)
        d_a = _mk_device(db, test_user, f"a-{uuid.uuid4().hex[:8]}")
        d_b = _mk_device(db, test_user, f"b-{uuid.uuid4().hex[:8]}")
        grant_entitlement(
            db, test_user.id, "google_play",
            f"gp-v-{uuid.uuid4().hex[:8]}",
            ENTITLEMENT_LIFETIME,
            originating_device_id=d_a.id,
        )
        db.commit()

        state = resolve_access_state(
            db, test_user.id, installation_id=d_b.installation_id,
        )
        assert state["has_premium_access"] is True
        assert state["needs_verification"] is False
    finally:
        _cleanup(db, test_user.id)


# ── account access no longer binds access to purchase device ───────────


def test_account_access_header_routes_to_originating_device(client, test_user, db):
    """Account-access dependency allows active accounts on any active device."""
    try:
        _reset_user_state(db, test_user); _set_verified(db, test_user, False)
        d_origin = _mk_device(db, test_user, f"orig-{uuid.uuid4().hex[:8]}")
        d_other = _mk_device(db, test_user, f"other-{uuid.uuid4().hex[:8]}")
        grant_entitlement(
            db, test_user.id, "google_play",
            f"gp-rp-{uuid.uuid4().hex[:8]}",
            ENTITLEMENT_LIFETIME,
            originating_device_id=d_origin.id,
        )
        db.commit()

        body = {
            "content_id": "tt000001", "provider_type": "torrentio",
            "source_key": "x", "success": False,
        }

        r_origin = client.post(
            "/me/acceleration/outcome",
            headers={**_auth(test_user), "X-Torve-Installation-Id": d_origin.installation_id},
            json=body,
        )
        r_other = client.post(
            "/me/acceleration/outcome",
            headers={**_auth(test_user), "X-Torve-Installation-Id": d_other.installation_id},
            json=body,
        )

        assert r_origin.status_code == 201, r_origin.text
        assert r_other.status_code == 201, r_other.text
    finally:
        _cleanup(db, test_user.id)
