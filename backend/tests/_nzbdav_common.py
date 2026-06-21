"""Shared helpers for NzbDAV test modules.

Creates the NzbDAV tables in the test DB on import if they are missing.
This keeps tests runnable without applying the 0023 migration against
production — each test run uses the live DATABASE_URL but only touches
the two new tables plus users/entitlements.
"""
from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone
from typing import Optional

from sqlalchemy import inspect

from app.database import Base, engine
from app.models import Device, NzbdavConfig, User, UserEntitlement, WarmJob  # noqa: F401
from app.nzbdav.release_cache import release_cache
from app.nzbdav.warm_service import concurrency
from app.security import create_access_token, hash_password


def ensure_nzbdav_schema() -> None:
    """Create nzbdav_configs and nzbdav_warm_jobs tables if they don't exist."""
    insp = inspect(engine)
    existing = set(insp.get_table_names())
    to_create = []
    for model in (NzbdavConfig, WarmJob):
        if model.__tablename__ not in existing:
            to_create.append(model.__table__)
    if to_create:
        Base.metadata.create_all(bind=engine, tables=to_create)


def make_premium_user(db, email_prefix: str = "nzbdav-test") -> User:
    user = User(
        id=uuid.uuid4(),
        email=f"{email_prefix}-{uuid.uuid4().hex[:8]}@test.com",
        password_hash=hash_password("TestPass123!"),
        is_verified=True,
        has_lifetime_access=True,
        has_premium_access=True,
    )
    db.add(user)
    db.flush()
    ent = UserEntitlement(
        user_id=user.id,
        entitlement_type="lifetime_access",
        source="admin_grant",
        source_ref=f"nzbdav-fixture-{user.id}",
        status="active",
    )
    db.add(ent)
    db.commit()
    db.refresh(user)
    return user


def cleanup_user(db, user: User) -> None:
    db.query(WarmJob).filter(WarmJob.user_id == user.id).delete()
    db.query(NzbdavConfig).filter(NzbdavConfig.user_id == user.id).delete()
    db.query(Device).filter(Device.user_id == user.id).delete()
    db.query(UserEntitlement).filter(UserEntitlement.user_id == user.id).delete()
    db.commit()
    u = db.query(User).filter(User.id == user.id).first()
    if u:
        db.delete(u)
        db.commit()


def auth_header(user: User) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(str(user.id))}"}


def make_device(db, user: User, installation_id: str | None = None) -> Device:
    dev = Device(
        user_id=user.id,
        device_type="desktop",
        platform="desktop",
        display_name="NzbDAV Test Device",
        installation_id=installation_id or f"nzbdav-{uuid.uuid4().hex[:10]}",
        is_active=True,
    )
    db.add(dev)
    db.commit()
    db.refresh(dev)
    return dev


def auth_device_header(user: User, device: Device) -> dict[str, str]:
    return {
        **auth_header(user),
        "X-Torve-Installation-Id": device.installation_id,
    }


def reset_caches() -> None:
    release_cache.reset()
    concurrency.reset()


def patch_validator_passthrough(monkeypatch) -> None:
    """Patch DNS resolution inside the SSRF validator so tests can use
    fake hostnames without hitting the network. The scheme / private-IP
    checks still run normally — we only short-circuit name resolution.
    """
    import app.nzbdav.client as client_mod

    def _fake_resolve(host: str) -> list[str]:
        import ipaddress
        try:
            return [str(ipaddress.ip_address(host))]
        except ValueError:
            pass
        # Pretend every test hostname maps to a public IP so SSRF allows it.
        return ["8.8.8.8"]  # any globally-routable IP; never actually contacted

    monkeypatch.setattr(client_mod, "_resolve_addresses", _fake_resolve)


# A stable resolvable domain useful for tests that need DNS to succeed
# but don't actually hit the network.
RESOLVABLE_TEST_HOST = "https://example.com"
