import uuid
from datetime import datetime, timedelta, timezone

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, text
from sqlalchemy.orm import sessionmaker

from app.config import settings
from app.database import Base
from app.deps import get_db
from app.main import app
from app.models import Device, User, UserEntitlement
from app.security import create_access_token, hash_password

engine = create_engine(settings.DATABASE_URL)
TestSession = sessionmaker(autocommit=False, autoflush=False, bind=engine)


def pytest_sessionfinish(session, exitstatus):
    """Belt-and-braces cleanup: bulk-delete every @test.com row at the
    end of the run. Per-fixture teardown already handles the happy
    path, but tests that create users directly (or fail mid-flight
    before teardown) used to leak — by 2026-04-25 we'd accumulated 521
    orphaned @test.com accounts in production. This hook ensures the
    test DB stays clean regardless of test-author discipline.

    Real signups never use @test.com — verified by inspecting the live
    users table — so this is safe to run unconditionally.
    """
    cleanup = TestSession()
    try:
        cleanup.execute(
            text("DELETE FROM lifetime_grants WHERE email LIKE '%@test.com'")
        )
        cleanup.execute(
            text("DELETE FROM users WHERE email LIKE '%@test.com'")
        )
        cleanup.commit()
    except Exception:
        cleanup.rollback()
    finally:
        cleanup.close()


@pytest.fixture()
def db():
    session = TestSession()
    try:
        yield session
    finally:
        session.close()


@pytest.fixture()
def client(db):
    def _override():
        try:
            yield db
        finally:
            pass

    app.dependency_overrides[get_db] = _override
    with TestClient(app) as c:
        yield c
    app.dependency_overrides.clear()


@pytest.fixture()
def test_user(db):
    user = User(
        id=uuid.uuid4(),
        email=f"test-{uuid.uuid4().hex[:8]}@test.com",
        password_hash=hash_password("TestPass123!"),
        display_name="Test User",
        is_verified=True,
        has_lifetime_access=True,
        has_premium_access=True,
    )
    db.add(user)
    db.flush()
    # Real entitlement row so check_premium_active() resolves correctly
    ent = UserEntitlement(
        user_id=user.id,
        entitlement_type="lifetime_access",
        source="admin_grant",
        source_ref=f"test-fixture-{user.id}",
        status="active",
    )
    db.add(ent)
    db.commit()
    db.refresh(user)
    yield user
    # Cleanup
    db.query(UserEntitlement).filter(UserEntitlement.user_id == user.id).delete()
    db.delete(user)
    db.commit()


@pytest.fixture()
def test_user_b(db):
    user = User(
        id=uuid.uuid4(),
        email=f"test-b-{uuid.uuid4().hex[:8]}@test.com",
        password_hash=hash_password("TestPass123!"),
        display_name="Test User B",
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
        source_ref=f"test-fixture-b-{user.id}",
        status="active",
    )
    db.add(ent)
    db.commit()
    db.refresh(user)
    yield user
    db.query(UserEntitlement).filter(UserEntitlement.user_id == user.id).delete()
    db.delete(user)
    db.commit()


def _make_device(db, user, device_type="tv", platform="firetv", name="Test TV"):
    device = Device(
        user_id=user.id,
        device_type=device_type,
        platform=platform,
        display_name=name,
        installation_id=f"test-{uuid.uuid4().hex[:8]}",
    )
    db.add(device)
    db.commit()
    db.refresh(device)
    return device


@pytest.fixture()
def tv_device(db, test_user):
    dev = _make_device(db, test_user, "tv", "firetv", "Living Room TV")
    yield dev
    db.delete(dev)
    db.commit()


@pytest.fixture()
def phone_device(db, test_user):
    dev = _make_device(db, test_user, "phone", "android", "Pixel 8")
    yield dev
    db.delete(dev)
    db.commit()


@pytest.fixture()
def tv_device_2(db, test_user):
    dev = _make_device(db, test_user, "tv", "firetv", "Bedroom TV")
    yield dev
    db.delete(dev)
    db.commit()


@pytest.fixture()
def phone_device_b(db, test_user_b):
    dev = _make_device(db, test_user_b, "phone", "android", "Other Phone")
    yield dev
    db.delete(dev)
    db.commit()


@pytest.fixture()
def free_user(db):
    """A verified user with NO premium or lifetime access."""
    user = User(
        id=uuid.uuid4(),
        email=f"free-{uuid.uuid4().hex[:8]}@test.com",
        password_hash=hash_password("TestPass123!"),
        display_name="Free User",
        is_verified=True,
        has_lifetime_access=False,
        has_premium_access=False,
    )
    db.add(user)
    db.commit()
    db.refresh(user)
    yield user
    db.delete(user)
    db.commit()


@pytest.fixture()
def free_auth_header(free_user):
    token = create_access_token(str(free_user.id))
    return {"Authorization": f"Bearer {token}"}


@pytest.fixture()
def free_tv_device(db, free_user):
    dev = _make_device(db, free_user, "tv", "firetv", "Free TV")
    yield dev
    db.delete(dev)
    db.commit()


@pytest.fixture()
def free_phone_device(db, free_user):
    dev = _make_device(db, free_user, "phone", "android", "Free Phone")
    yield dev
    db.delete(dev)
    db.commit()


@pytest.fixture()
def auth_header(test_user):
    token = create_access_token(str(test_user.id))
    return {"Authorization": f"Bearer {token}"}


@pytest.fixture()
def auth_header_b(test_user_b):
    token = create_access_token(str(test_user_b.id))
    return {"Authorization": f"Bearer {token}"}
