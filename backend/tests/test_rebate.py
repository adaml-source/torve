"""Tests for rebate code system: redemption, security, throttling, normalization, idempotency."""
import uuid
from datetime import datetime, timedelta, timezone

from app.models import RebateCode, RebateRedemption, User, UserEntitlement
from app.rebate_service import (
    _hash_code, check_admin_ip,
    create_rebate_codes, normalize_code, redeem_code,
)
from app.security import create_access_token, hash_password


def _make_user(db, email=None, premium=False):
    user = User(
        email=email or f"rebate-{uuid.uuid4().hex[:8]}@test.com",
        password_hash=hash_password("testpass123"),
        has_premium_access=premium,
        has_lifetime_access=premium,
    )
    db.add(user)
    db.commit()
    db.refresh(user)
    return user


def _make_code(db, **kwargs):
    pairs = create_rebate_codes(db, count=1, **kwargs)
    db.commit()
    return pairs[0]


def _cleanup_user(db, user):
    db.query(RebateRedemption).filter(RebateRedemption.user_id == user.id).delete()
    db.query(UserEntitlement).filter(UserEntitlement.user_id == user.id).delete()
    db.query(User).filter(User.id == user.id).delete()
    db.commit()


def _cleanup_code(db, code_row):
    db.query(RebateRedemption).filter(RebateRedemption.rebate_code_id == code_row.id).delete()
    db.query(RebateCode).filter(RebateCode.id == code_row.id).delete()
    db.commit()


def _cleanup_all_attempts(db, user_id):
    """Clean up rate limit tracking records for the user."""
    db.query(RebateRedemption).filter(RebateRedemption.user_id == user_id).delete()
    db.commit()


# ── Normalization ─────────────────────────────────────────────────────────

def test_normalize_strips_whitespace():
    assert normalize_code("  TRV-LIFE-ABCD-1234  ") == "TRV-LIFE-ABCD-1234"

def test_normalize_uppercases():
    assert normalize_code("trv-life-abcd-1234") == "TRV-LIFE-ABCD-1234"

def test_normalize_removes_internal_spaces():
    assert normalize_code("TRV - LIFE - ABCD - 1234") == "TRV-LIFE-ABCD-1234"

def test_normalize_converts_unicode_dashes():
    assert normalize_code("TRV\u2014LIFE\u2013ABCD\u2012EFGH") == "TRV-LIFE-ABCD-EFGH"

def test_normalize_strips_zero_width_chars():
    assert normalize_code("TRV\u200B-LIFE\u200C-ABCD-EFGH") == "TRV-LIFE-ABCD-EFGH"

def test_normalize_handles_tabs():
    assert normalize_code("TRV-LIFE-ABCD-EFGH\t") == "TRV-LIFE-ABCD-EFGH"

def test_normalized_code_hashes_consistently(monkeypatch):
    monkeypatch.setattr("app.config.settings.REBATE_CODE_HMAC_SECRET", "test")
    a = _hash_code("TRV-LIFE-ABCD-1234")
    b = _hash_code("  trv - life - abcd - 1234  ")
    c = _hash_code("TRV\u2014LIFE\u2013ABCD\u20121234")
    assert a == b == c


# ── Dedicated HMAC secret ────────────────────────────────────────────────

def test_uses_dedicated_hmac_secret(monkeypatch):
    monkeypatch.setattr("app.config.settings.REBATE_CODE_HMAC_SECRET", "test-secret-A")
    h1 = _hash_code("TRV-LIFE-TEST-CODE")
    monkeypatch.setattr("app.config.settings.REBATE_CODE_HMAC_SECRET", "test-secret-B")
    h2 = _hash_code("TRV-LIFE-TEST-CODE")
    assert h1 != h2

def test_does_not_use_jwt_secret(monkeypatch):
    monkeypatch.setattr("app.config.settings.REBATE_CODE_HMAC_SECRET", "rebate-key")
    monkeypatch.setattr("app.config.settings.JWT_SECRET", "jwt-key")
    h = _hash_code("TRV-LIFE-TEST-CODE")
    monkeypatch.setattr("app.config.settings.JWT_SECRET", "different-jwt-key")
    h2 = _hash_code("TRV-LIFE-TEST-CODE")
    assert h == h2


# ── Redemption ────────────────────────────────────────────────────────────

def test_successful_redemption(client, db, monkeypatch):
    monkeypatch.setattr("app.config.settings.REBATE_CODE_HMAC_SECRET", "test")
    user = _make_user(db)
    _cleanup_all_attempts(db, user.id)
    raw, code_row = _make_code(db, campaign_name="test")
    token = create_access_token(str(user.id))

    r = client.post("/me/rebate-codes/redeem", json={"code": raw},
                     headers={"Authorization": f"Bearer {token}"})
    assert r.status_code == 200
    assert r.json()["success"] is True

    db.refresh(user)
    assert user.has_lifetime_access is False
    assert user.has_premium_access is False
    assert db.query(UserEntitlement).filter(UserEntitlement.user_id == user.id).count() == 0

    _cleanup_code(db, code_row)
    _cleanup_user(db, user)


def test_same_code_cannot_be_redeemed_twice(client, db, monkeypatch):
    monkeypatch.setattr("app.config.settings.REBATE_CODE_HMAC_SECRET", "test")
    u1 = _make_user(db)
    u2 = _make_user(db)
    _cleanup_all_attempts(db, u1.id)
    _cleanup_all_attempts(db, u2.id)
    raw, code_row = _make_code(db)

    r1 = client.post("/me/rebate-codes/redeem", json={"code": raw},
                      headers={"Authorization": f"Bearer {create_access_token(str(u1.id))}"})
    assert r1.json()["success"] is True

    r2 = client.post("/me/rebate-codes/redeem", json={"code": raw},
                      headers={"Authorization": f"Bearer {create_access_token(str(u2.id))}"})
    assert r2.json()["success"] is False
    assert r2.json()["error_code"] == "already_redeemed"

    _cleanup_code(db, code_row)
    _cleanup_user(db, u1)
    _cleanup_user(db, u2)


def test_user_cannot_redeem_two_codes(client, db, monkeypatch):
    monkeypatch.setattr("app.config.settings.REBATE_CODE_HMAC_SECRET", "test")
    user = _make_user(db)
    _cleanup_all_attempts(db, user.id)
    raw1, code1 = _make_code(db)
    raw2, code2 = _make_code(db)
    token = create_access_token(str(user.id))

    r1 = client.post("/me/rebate-codes/redeem", json={"code": raw1},
                      headers={"Authorization": f"Bearer {token}"})
    assert r1.json()["success"] is True

    r2 = client.post("/me/rebate-codes/redeem", json={"code": raw2},
                      headers={"Authorization": f"Bearer {token}"})
    assert r2.json()["success"] is False
    assert r2.json()["error_code"] == "rebate_already_used_by_account"

    _cleanup_code(db, code1)
    _cleanup_code(db, code2)
    _cleanup_user(db, user)


def test_revoked_code_fails(client, db, monkeypatch):
    monkeypatch.setattr("app.config.settings.REBATE_CODE_HMAC_SECRET", "test")
    user = _make_user(db)
    _cleanup_all_attempts(db, user.id)
    raw, code_row = _make_code(db)
    code_row.revoked_at = datetime.now(timezone.utc)
    db.commit()

    r = client.post("/me/rebate-codes/redeem", json={"code": raw},
                     headers={"Authorization": f"Bearer {create_access_token(str(user.id))}"})
    assert r.json()["error_code"] == "code_revoked"

    _cleanup_code(db, code_row)
    _cleanup_user(db, user)


def test_expired_code_fails(client, db, monkeypatch):
    monkeypatch.setattr("app.config.settings.REBATE_CODE_HMAC_SECRET", "test")
    user = _make_user(db)
    _cleanup_all_attempts(db, user.id)
    raw, code_row = _make_code(db)
    code_row.expires_at = datetime.now(timezone.utc) - timedelta(hours=1)
    db.commit()

    r = client.post("/me/rebate-codes/redeem", json={"code": raw},
                     headers={"Authorization": f"Bearer {create_access_token(str(user.id))}"})
    assert r.json()["error_code"] == "code_expired"

    _cleanup_code(db, code_row)
    _cleanup_user(db, user)


def test_allowed_email_mismatch_fails(client, db, monkeypatch):
    monkeypatch.setattr("app.config.settings.REBATE_CODE_HMAC_SECRET", "test")
    user = _make_user(db, email="wrong@test.com")
    _cleanup_all_attempts(db, user.id)
    raw, code_row = _make_code(db, allowed_email="correct@test.com")

    r = client.post("/me/rebate-codes/redeem", json={"code": raw},
                     headers={"Authorization": f"Bearer {create_access_token(str(user.id))}"})
    assert r.json()["error_code"] == "invalid_or_ineligible_code"

    _cleanup_code(db, code_row)
    _cleanup_user(db, user)


def test_allowed_email_domain_mismatch_fails(client, db, monkeypatch):
    monkeypatch.setattr("app.config.settings.REBATE_CODE_HMAC_SECRET", "test")
    user = _make_user(db, email="user@wrongdomain.com")
    _cleanup_all_attempts(db, user.id)
    raw, code_row = _make_code(db, allowed_email_domain="correctdomain.com")

    r = client.post("/me/rebate-codes/redeem", json={"code": raw},
                     headers={"Authorization": f"Bearer {create_access_token(str(user.id))}"})
    assert r.json()["error_code"] == "invalid_or_ineligible_code"

    _cleanup_code(db, code_row)
    _cleanup_user(db, user)


def test_existing_lifetime_record_does_not_block_rebate_record(client, db, monkeypatch):
    monkeypatch.setattr("app.config.settings.REBATE_CODE_HMAC_SECRET", "test")
    user = _make_user(db, premium=True)
    _cleanup_all_attempts(db, user.id)
    ent = UserEntitlement(
        user_id=user.id, entitlement_type="lifetime_access",
        source="admin_grant", source_ref=f"test-{uuid.uuid4().hex[:8]}",
        status="active",
    )
    db.add(ent)
    db.commit()

    raw, code_row = _make_code(db)

    r = client.post("/me/rebate-codes/redeem", json={"code": raw},
                     headers={"Authorization": f"Bearer {create_access_token(str(user.id))}"})
    assert r.json()["success"] is True
    assert r.json()["error_code"] is None

    db.refresh(code_row)
    assert code_row.redeemed_count == 1

    _cleanup_code(db, code_row)
    db.query(RebateRedemption).filter(RebateRedemption.user_id == user.id).delete()
    db.query(UserEntitlement).filter(UserEntitlement.user_id == user.id).delete()
    db.query(User).filter(User.id == user.id).delete()
    db.commit()


def test_invalid_code_returns_safe_error(client, db, monkeypatch):
    monkeypatch.setattr("app.config.settings.REBATE_CODE_HMAC_SECRET", "test")
    user = _make_user(db)
    _cleanup_all_attempts(db, user.id)

    r = client.post("/me/rebate-codes/redeem",
                     json={"code": "TRV-LIFE-FAKE-CODE-DOES-NOTX"},
                     headers={"Authorization": f"Bearer {create_access_token(str(user.id))}"})
    assert r.json()["success"] is False
    assert r.json()["error_code"] == "invalid_or_ineligible_code"

    _cleanup_user(db, user)


def test_requires_auth(client):
    r = client.post("/me/rebate-codes/redeem", json={"code": "TRV-LIFE-XXXX-XXXX-XXXX-XXXX"})
    assert r.status_code in (401, 403)


# ── Idempotency: retry after success ─────────────────────────────────────

def test_retry_after_success_returns_stable_response(client, db, monkeypatch):
    """Post-commit retry: same user, same code should return stable recorded response."""
    monkeypatch.setattr("app.config.settings.REBATE_CODE_HMAC_SECRET", "test")
    user = _make_user(db)
    _cleanup_all_attempts(db, user.id)
    raw, code_row = _make_code(db)
    token = create_access_token(str(user.id))

    r1 = client.post("/me/rebate-codes/redeem", json={"code": raw},
                      headers={"Authorization": f"Bearer {token}"})
    assert r1.json()["success"] is True
    assert r1.json()["error_code"] is None

    # Retry same code
    r2 = client.post("/me/rebate-codes/redeem", json={"code": raw},
                      headers={"Authorization": f"Bearer {token}"})
    assert r2.json()["success"] is True
    assert r2.json()["error_code"] == "rebate_already_recorded"

    _cleanup_code(db, code_row)
    _cleanup_user(db, user)


# ── DB-backed rate limiting ──────────────────────────────────────────────

def test_db_rate_limit_per_user(client, db, monkeypatch):
    """After 5 attempts in the window, user gets throttled."""
    monkeypatch.setattr("app.config.settings.REBATE_CODE_HMAC_SECRET", "test")
    user = _make_user(db)
    _cleanup_all_attempts(db, user.id)
    token = create_access_token(str(user.id))

    # 5 invalid attempts
    for i in range(5):
        r = client.post("/me/rebate-codes/redeem",
                         json={"code": f"TRV-LIFE-FAKE-{i:04d}-XXXX-XXXX"},
                         headers={"Authorization": f"Bearer {token}"})
        assert r.json()["error_code"] != "rate_limited", f"Blocked too early on attempt {i+1}"

    # 6th should be rate limited
    r = client.post("/me/rebate-codes/redeem",
                     json={"code": "TRV-LIFE-FAKE-0006-XXXX-XXXX"},
                     headers={"Authorization": f"Bearer {token}"})
    assert r.json()["error_code"] == "rate_limited"

    _cleanup_user(db, user)


# ── Admin IP allowlist ───────────────────────────────────────────────────

def test_admin_ip_allowlist_disabled():
    """When allowlist is empty, all IPs are allowed."""
    assert check_admin_ip("1.2.3.4") is True
    assert check_admin_ip(None) is True

def test_admin_ip_allowlist_allowed(monkeypatch):
    monkeypatch.setattr("app.config.settings.ADMIN_IP_ALLOWLIST", "10.0.0.1,192.168.1.0/24")
    assert check_admin_ip("10.0.0.1") is True
    assert check_admin_ip("192.168.1.55") is True

def test_admin_ip_allowlist_denied(monkeypatch):
    monkeypatch.setattr("app.config.settings.ADMIN_IP_ALLOWLIST", "10.0.0.1,192.168.1.0/24")
    assert check_admin_ip("8.8.8.8") is False
    assert check_admin_ip("172.16.0.1") is False


# ── Admin endpoints ──────────────────────────────────────────────────────

def test_admin_create_never_stores_plaintext(client, db, monkeypatch):
    monkeypatch.setattr("app.config.settings.PADDLE_ADMIN_SECRET", "test_secret")
    monkeypatch.setattr("app.config.settings.REBATE_CODE_HMAC_SECRET", "test")

    r = client.post("/admin/rebate-codes",
                     json={"count": 2, "campaign_name": "test_campaign"},
                     headers={"X-Admin-Secret": "test_secret"})
    assert r.status_code == 200
    assert r.json()["created"] == 2

    for c in r.json()["codes"]:
        assert c["raw_code"].startswith("TRV-LIFE-")
        code_row = db.query(RebateCode).filter(RebateCode.id == c["id"]).first()
        assert "TRV-LIFE" not in (code_row.code_hash or "")

    for c in r.json()["codes"]:
        db.query(RebateCode).filter(RebateCode.id == c["id"]).delete()
    db.commit()


def test_admin_list_never_exposes_raw_codes(client, db, monkeypatch):
    monkeypatch.setattr("app.config.settings.PADDLE_ADMIN_SECRET", "test_secret")
    monkeypatch.setattr("app.config.settings.REBATE_CODE_HMAC_SECRET", "test")

    client.post("/admin/rebate-codes",
                json={"count": 1, "campaign_name": "list_test"},
                headers={"X-Admin-Secret": "test_secret"})

    r = client.get("/admin/rebate-codes?campaign=list_test",
                    headers={"X-Admin-Secret": "test_secret"})
    assert r.status_code == 200
    for c in r.json()["codes"]:
        assert "raw_code" not in c

    db.query(RebateCode).filter(RebateCode.campaign_name == "list_test").delete()
    db.commit()


def test_access_state_ignores_rebate_redemption_for_tier(client, db, monkeypatch):
    monkeypatch.setattr("app.config.settings.REBATE_CODE_HMAC_SECRET", "test")
    user = _make_user(db)
    _cleanup_all_attempts(db, user.id)
    raw, code_row = _make_code(db)
    token = create_access_token(str(user.id))

    r = client.get("/me/access-state", headers={"Authorization": f"Bearer {token}"})
    assert r.json()["access_tier"] == "free"

    client.post("/me/rebate-codes/redeem", json={"code": raw},
                headers={"Authorization": f"Bearer {token}"})

    r = client.get("/me/access-state", headers={"Authorization": f"Bearer {token}"})
    assert r.json()["has_premium_access"] is True
    assert r.json()["access_tier"] == "free"
    assert r.json()["source"] is None

    _cleanup_code(db, code_row)
    _cleanup_user(db, user)


# ── Time-limited rebate codes ──────────────────────────────────────────


def test_rebate_code_with_duration_records_only(client, db, monkeypatch):
    """Duration metadata is historical and does not create a subscription."""
    monkeypatch.setattr("app.config.settings.REBATE_CODE_HMAC_SECRET", "test")
    user = _make_user(db)
    _cleanup_all_attempts(db, user.id)
    raw, code_row = _make_code(db, campaign_name="trial-30d", grant_duration_days=30)
    token = create_access_token(str(user.id))

    r = client.post("/me/rebate-codes/redeem", json={"code": raw},
                    headers={"Authorization": f"Bearer {token}"})
    assert r.status_code == 200
    body = r.json()
    assert body["success"] is True
    assert "free" in body["message"].lower()

    db.refresh(user)
    assert user.has_lifetime_access is False
    assert user.has_premium_access is False

    ent = db.query(UserEntitlement).filter(UserEntitlement.user_id == user.id).first()
    assert ent is None

    _cleanup_code(db, code_row)
    _cleanup_user(db, user)


def test_rebate_code_without_duration_records_only(client, db, monkeypatch):
    """Codes with no duration no longer grant lifetime."""
    monkeypatch.setattr("app.config.settings.REBATE_CODE_HMAC_SECRET", "test")
    user = _make_user(db)
    _cleanup_all_attempts(db, user.id)
    raw, code_row = _make_code(db, campaign_name="legacy-lifetime")
    token = create_access_token(str(user.id))

    r = client.post("/me/rebate-codes/redeem", json={"code": raw},
                    headers={"Authorization": f"Bearer {token}"})
    assert r.status_code == 200
    assert r.json()["success"] is True
    assert "free" in r.json()["message"].lower()

    db.refresh(user)
    assert user.has_lifetime_access is False
    assert db.query(UserEntitlement).filter(UserEntitlement.user_id == user.id).count() == 0

    _cleanup_code(db, code_row)
    _cleanup_user(db, user)


def test_admin_create_endpoint_persists_grant_duration(client, db, monkeypatch):
    """The admin POST endpoint accepts grant_duration_days and persists
    it to the row. Regression for the missing db.commit() that used to
    silently roll back every admin-created code."""
    monkeypatch.setattr("app.config.settings.REBATE_CODE_HMAC_SECRET", "test")
    monkeypatch.setattr("app.config.settings.PADDLE_ADMIN_SECRET", "test-admin-secret")

    r = client.post(
        "/admin/rebate-codes",
        headers={"X-Admin-Secret": "test-admin-secret"},
        json={"count": 1, "campaign_name": "regression-30d", "grant_duration_days": 30},
    )
    assert r.status_code == 200
    payload = r.json()
    assert payload["created"] == 1
    assert payload["codes"][0]["grant_duration_days"] == 30

    code_id = uuid.UUID(payload["codes"][0]["id"])
    row = db.query(RebateCode).filter(RebateCode.id == code_id).first()
    assert row is not None, "row must persist beyond the request — pre-2026-04-27 bug"
    assert row.grant_duration_days == 30

    db.delete(row); db.commit()
