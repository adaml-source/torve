"""
Test fixtures for Torve backend tests.
Uses SQLite in-memory for fast tests without PostgreSQL.
"""
import asyncio
import os

import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

# Set required env vars before importing app
os.environ.setdefault("DATABASE_URL", "sqlite+aiosqlite:///")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/15")
os.environ.setdefault("JWT_SECRET", "test-secret-key-for-testing-only")
os.environ.setdefault("JWT_ISSUER", "torve-test")

from app.db import Base, get_session  # noqa: E402
from app.main import app  # noqa: E402


@pytest.fixture(scope="session")
def event_loop():
    loop = asyncio.new_event_loop()
    yield loop
    loop.close()


@pytest_asyncio.fixture
async def db_engine():
    engine = create_async_engine("sqlite+aiosqlite:///", echo=False)
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    yield engine
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.drop_all)
    await engine.dispose()


@pytest_asyncio.fixture
async def db_session(db_engine):
    session_factory = async_sessionmaker(db_engine, class_=AsyncSession, expire_on_commit=False)
    async with session_factory() as session:
        yield session


@pytest_asyncio.fixture
async def client(db_engine):
    session_factory = async_sessionmaker(db_engine, class_=AsyncSession, expire_on_commit=False)

    async def override_session():
        async with session_factory() as session:
            yield session

    app.dependency_overrides[get_session] = override_session
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        yield client
    app.dependency_overrides.clear()


DEVICE = {
    "installation_id": "test-device-001",
    "device_name": "Test Phone",
    "device_type": "phone",
    "platform": "google_play_mobile",
}


async def register_user(client: AsyncClient, email: str = "test@example.com", password: str = "TestPass123!") -> dict:
    resp = await client.post("/auth/register", json={
        "email": email,
        "password": password,
        "device": DEVICE,
    })
    assert resp.status_code == 200
    return resp.json()


async def login_user(client: AsyncClient, email: str = "test@example.com", password: str = "TestPass123!") -> dict:
    resp = await client.post("/auth/login", json={
        "email": email,
        "password": password,
        "device": DEVICE,
    })
    assert resp.status_code == 200
    return resp.json()
