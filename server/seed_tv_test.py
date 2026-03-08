"""
Seed script for TV smoke test.
Creates a premium account with 5 active devices, leaving slot for TV as 6th.
Run: python seed_tv_test.py
Then start server: python -m uvicorn app.main:app --host 0.0.0.0 --port 8080
"""
import asyncio
import os
import sys
import json
import base64

# MUST set env vars BEFORE importing anything from app
os.environ["DATABASE_URL"] = "sqlite+aiosqlite:///tvtest.db"
os.environ["REDIS_URL"] = "redis://localhost:6379/15"
os.environ["JWT_SECRET"] = "tv-test-secret-key-for-local-testing-32chars"
os.environ["JWT_ISSUER"] = "torve-test"
os.environ["ACCESS_TOKEN_TTL_MINUTES"] = "120"
os.environ["DEVICE_MAX_ACTIVE"] = "5"

# Clear lru_cache before first import
from app.config import get_settings
get_settings.cache_clear()

from httpx import AsyncClient, ASGITransport
from app.db import Base, engine, SessionLocal, get_session
from app.main import app
from sqlalchemy.ext.asyncio import AsyncSession


async def main():
    # Delete old DB file
    if os.path.exists("tvtest.db"):
        os.remove("tvtest.db")
        print("[OK] Removed old tvtest.db")

    # Create tables using the app's engine (which now points to SQLite)
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    print("[OK] Database tables created")

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        email = "tvtest@torve.app"
        password = "TvTest123!"

        # Register with device 1
        device1 = {
            "installation_id": "seed-phone-001",
            "device_name": "Seed Phone 1",
            "device_type": "phone",
            "platform": "google_play_mobile",
        }
        resp = await client.post("/auth/register", json={
            "email": email,
            "password": password,
            "device": device1,
        })
        assert resp.status_code == 200, f"Register failed: {resp.text}"
        token1 = resp.json()["tokens"]["access_token"]
        print(f"[OK] Registered user: {email}")

        # Purchase premium
        payload = {
            "transactionId": "txn_tvtest_seed",
            "originalTransactionId": "txn_tvtest_seed",
            "productId": "com.torve.pro.lifetime",
            "bundleId": "com.torve.app",
            "purchaseDate": 1700000000000,
        }
        encoded = base64.urlsafe_b64encode(json.dumps(payload).encode()).rstrip(b"=").decode()
        jws = f"header.{encoded}.signature"
        resp = await client.post(
            "/purchases/apple/verify",
            json={"transaction_jws": jws, "product_id": "com.torve.pro.lifetime", "platform": "ios"},
            headers={"Authorization": f"Bearer {token1}"},
        )
        assert resp.status_code == 200, f"Purchase failed: {resp.text}"
        print("[OK] Premium purchased (lifetime)")

        # Activate device 1
        resp = await client.get("/me/access-state", headers={"Authorization": f"Bearer {token1}"})
        assert resp.json()["premium"]["premium_access"] is True
        print("[OK] Device 1 activated")

        # Add and activate devices 2-5
        for i in range(2, 6):
            device = {
                "installation_id": f"seed-phone-{i:03d}",
                "device_name": f"Seed Phone {i}",
                "device_type": "phone",
                "platform": "google_play_mobile",
            }
            resp = await client.post("/auth/login", json={
                "email": email,
                "password": password,
                "device": device,
            })
            assert resp.status_code == 200, f"Login device {i} failed: {resp.text}"
            t = resp.json()["tokens"]["access_token"]

            resp = await client.get("/me/access-state", headers={"Authorization": f"Bearer {t}"})
            assert resp.json()["premium"]["premium_access"] is True, f"Device {i} activation failed"
            print(f"[OK] Device {i} activated")

        # Verify final state
        resp = await client.get("/me/access-state", headers={"Authorization": f"Bearer {token1}"})
        state = resp.json()
        count = state["device"]["active_device_count"]
        print(f"\n[RESULT] Active devices: {count}/5")
        assert count == 5, f"Expected 5 active devices, got {count}"

        print("\n========================================")
        print("  SEED COMPLETE - TV SMOKE TEST READY")
        print("========================================")
        print(f"  Email:    {email}")
        print(f"  Password: {password}")
        print(f"  Devices:  5/5 active")
        print(f"  Next TV login will be BLOCKED")
        print()
        print("  Start server with:")
        print("    cd server")
        print("    DATABASE_URL=sqlite+aiosqlite:///tvtest.db \\")
        print("    REDIS_URL=redis://localhost:6379/15 \\")
        print("    JWT_SECRET=tv-test-secret-key-for-local-testing-32chars \\")
        print("    python -m uvicorn app.main:app --host 0.0.0.0 --port 8080")
        print("========================================")


if __name__ == "__main__":
    asyncio.run(main())
