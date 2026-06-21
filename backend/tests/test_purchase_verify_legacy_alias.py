"""
Compat-alias tests for the pre-sprint Android client URL paths.

Background: older builds POST to /purchases/google/verify and
/purchases/amazon/verify (the dead-monorepo shape). Production uses
/me/purchases/{google-play,amazon}/verify. A legacy_router aliases the
old paths to the same handlers so old-client purchases still work
until Play Store auto-update reaches every install.

These tests pin that behaviour so future router refactors don't quietly
break old clients again.
"""
import uuid as _uuid

from app.models import User
from app.security import create_access_token, hash_password


def _make_user(db):
    user = User(
        email=f"legacy_{_uuid.uuid4().hex[:8]}@test.com",
        password_hash=hash_password("testpass"),
    )
    db.add(user)
    db.commit()
    db.refresh(user)
    return user


def _auth(user_id):
    return {"Authorization": f"Bearer {create_access_token(str(user_id))}"}


def test_legacy_google_play_path_reaches_handler(client, db, monkeypatch):
    """POST /purchases/google/verify must hit the same handler as the
    canonical /me/purchases/google-play/verify, so old clients that
    pre-date the URL fix still trigger real verification."""
    monkeypatch.setattr("app.config.settings.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON", "")
    user = _make_user(db)

    r_legacy = client.post(
        "/purchases/google/verify",
        json={"purchase_token": "tok", "product_id": "com.pirate.fake", "order_id": "legacy-gp-1"},
        headers=_auth(user.id),
    )
    r_canonical = client.post(
        "/me/purchases/google-play/verify",
        json={"purchase_token": "tok", "product_id": "com.pirate.fake", "order_id": "canon-gp-1"},
        headers=_auth(user.id),
    )

    # Both paths return the same handler response shape — 200 with
    # verified=False + error_code="product_mismatch" in non-configured env.
    assert r_legacy.status_code == 200, r_legacy.text
    assert r_canonical.status_code == 200, r_canonical.text
    assert r_legacy.json()["verified"] == r_canonical.json()["verified"]
    assert r_legacy.json().get("error_code") == r_canonical.json().get("error_code")
    # No 404 — the previous silent-drop bug is gone.
    assert r_legacy.status_code != 404


def test_legacy_amazon_path_reaches_handler(client, db):
    """Same shape for Amazon: the alias accepts the pre-sprint URL."""
    user = _make_user(db)
    r = client.post(
        "/purchases/amazon/verify",
        json={"receipt_id": "abc", "user_id": "amz_user", "product_id": "com.pirate.fake"},
        headers=_auth(user.id),
    )
    # Handler reachable — no 404. Exact verified flag depends on
    # product-match state; we only pin that the route exists.
    assert r.status_code != 404, r.text


def test_legacy_google_path_requires_auth(client):
    r = client.post(
        "/purchases/google/verify",
        json={"purchase_token": "tok", "product_id": "x", "order_id": "x"},
    )
    # FastAPI HTTPBearer returns 403 on missing creds (not 404 — route
    # is registered).
    assert r.status_code in (401, 403)


def test_legacy_router_not_in_openapi(client):
    """The compat paths are intentionally hidden from /openapi.json
    (include_in_schema=False) so SDK generators don't bake in the
    legacy URL for new consumers."""
    r = client.get("/openapi.json")
    if r.status_code != 200:
        # openapi is disabled in production env (expected) — nothing to check.
        return
    paths = r.json().get("paths", {}).keys()
    assert "/me/purchases/google-play/verify" in paths
    assert "/purchases/google/verify" not in paths
    assert "/purchases/amazon/verify" not in paths
