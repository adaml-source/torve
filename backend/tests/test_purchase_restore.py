"""Tests for deprecated /me/purchases/restore + legacy /purchases/restore."""
from __future__ import annotations

import uuid as _uuid
from datetime import datetime, timedelta, timezone

from app.models import LifetimeGrantRecord, User, UserEntitlement
from app.security import create_access_token, hash_password


def _auth(user_id):
    return {"Authorization": f"Bearer {create_access_token(str(user_id))}"}


def _make_user(db, email_suffix=""):
    user = User(
        email=f"restore_{_uuid.uuid4().hex[:8]}{email_suffix}@test.com",
        password_hash=hash_password("testpass"),
        is_verified=True,
    )
    db.add(user)
    db.commit()
    db.refresh(user)
    return user


def test_restore_requires_auth(client):
    r = client.post("/me/purchases/restore")
    # HTTPBearer returns 403 with no header, 401 with malformed.
    assert r.status_code in (401, 403)


def test_restore_legacy_alias_requires_auth(client):
    """The legacy /purchases/restore alias must enforce the same auth."""
    r = client.post("/purchases/restore")
    assert r.status_code in (401, 403)


def test_restore_does_not_depend_on_stale_premium_flag(client, db):
    """Historical entitlements do not control restored/default access."""
    user = _make_user(db)
    db.add(UserEntitlement(
        user_id=user.id,
        entitlement_type="lifetime_access",
        source="paddle_web",
        source_ref=f"stale-{user.id}",
        status="active",
    ))
    user.has_lifetime_access = False
    user.has_premium_access = False
    db.commit()

    r = client.post("/me/purchases/restore", headers=_auth(user.id))
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["restored"] is True
    assert body["has_premium_access"] is True
    assert body["has_lifetime_access"] is False
    assert body["active_entitlements"] >= 1

    # Cleanup
    db.query(UserEntitlement).filter(UserEntitlement.user_id == user.id).delete()
    db.delete(user); db.commit()


def test_restore_ignores_lifetime_grant_ledger_for_access(client, db):
    """Ledger rows are historical only and do not re-provision access."""
    user = _make_user(db)
    db.add(LifetimeGrantRecord(
        email=user.email,
        source="paddle_web",
        source_ref=f"ledger-{user.id}",
        product_id="pro_lifetime",
    ))
    db.commit()

    r = client.post("/me/purchases/restore", headers=_auth(user.id))
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["has_premium_access"] is True
    assert body["has_lifetime_access"] is False

    ent_count = db.query(UserEntitlement).filter(
        UserEntitlement.user_id == user.id,
        UserEntitlement.entitlement_type == "lifetime_access",
        UserEntitlement.status == "active",
    ).count()
    assert ent_count == 0

    db.query(UserEntitlement).filter(UserEntitlement.user_id == user.id).delete()
    db.query(LifetimeGrantRecord).filter(LifetimeGrantRecord.email == user.email).delete()
    db.delete(user); db.commit()


def test_restore_no_entitlements_returns_default_access(client, db):
    """No entitlements, no ledger row — restore is a no-op with free access."""
    user = _make_user(db)
    r = client.post("/me/purchases/restore", headers=_auth(user.id))
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["restored"] is True
    assert body["has_premium_access"] is True
    assert body["has_lifetime_access"] is False
    assert body["active_entitlements"] == 0

    db.delete(user); db.commit()


def test_restore_does_not_snowball_ledger_or_entitlements(client, db):
    """Regression for the 2026-04-26 adam.losonczy snowball bug.

    Before the fix, every Refresh-Access tap on a user with a revoked
    lifetime entitlement created (a) a new entitlement with a 'restored:'
    chained source_ref AND (b) a new ledger row whose ref then became the
    target of the next restore — exponential growth.

    Restoring N times against the same un-revoked ledger row must
    produce at most ONE active lifetime entitlement and must NOT add
    new ledger rows."""
    user = _make_user(db)
    db.add(LifetimeGrantRecord(
        email=user.email,
        source="paddle_web",
        source_ref=f"original-{user.id}",
        product_id="pro_lifetime",
    ))
    db.commit()

    headers = _auth(user.id)
    for _ in range(5):
        r = client.post("/me/purchases/restore", headers=headers)
        assert r.status_code == 200

    active_lifetime_count = db.query(UserEntitlement).filter(
        UserEntitlement.user_id == user.id,
        UserEntitlement.entitlement_type == "lifetime_access",
        UserEntitlement.status == "active",
    ).count()
    assert active_lifetime_count == 0, "restore should not create entitlements"

    ledger_rows = db.query(LifetimeGrantRecord).filter(
        LifetimeGrantRecord.email == user.email,
    ).count()
    assert ledger_rows == 1, "no new ledger rows should be written by restore"

    db.query(UserEntitlement).filter(UserEntitlement.user_id == user.id).delete()
    db.query(LifetimeGrantRecord).filter(LifetimeGrantRecord.email == user.email).delete()
    db.delete(user); db.commit()


def test_restore_legacy_alias_works_for_old_clients(client, db):
    """Old Android builds that POST /purchases/restore (no /me prefix)
    must reach the same handler. This is the path that was returning
    404 in the live 1.0.38 client."""
    user = _make_user(db)
    db.add(UserEntitlement(
        user_id=user.id,
        entitlement_type="subscription_monthly",
        source="google_play",
        source_ref=f"sub-{user.id}",
        status="active",
        expires_at=datetime.now(timezone.utc) + timedelta(days=30),
        auto_renew=True,
    ))
    db.commit()

    r = client.post("/purchases/restore", headers=_auth(user.id))
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["has_premium_access"] is True
    assert body["has_lifetime_access"] is False
    assert body["active_entitlements"] >= 1

    db.query(UserEntitlement).filter(UserEntitlement.user_id == user.id).delete()
    db.delete(user); db.commit()
