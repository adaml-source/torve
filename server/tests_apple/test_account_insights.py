"""Isolated activity/admin contracts. Never imports the production pytest fixtures."""
import os
os.environ["DATABASE_URL"] = "sqlite:///unused-account-insights.sqlite"
os.environ["JWT_SECRET"] = "isolated-tests-only"
os.environ["APP_ENV"] = "test"
import importlib.util
import io
from datetime import datetime, timedelta, timezone
from pathlib import Path
import unittest
import uuid
from unittest.mock import patch
from fastapi import FastAPI
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, delete, event, inspect
from sqlalchemy.orm import Session
from sqlalchemy.pool import StaticPool
from app.config import settings
from app.models import User, Device
from app import models
from app.device_activity_models import DeviceActivityTotal, DeviceActivitySession, DeviceActivityDay
from app.apple_subscription_models import AppleSubscriptionOwner, AppleSubscriptionTransaction, AppleSubscriptionDevice
from app import device_activity as activity, apple_subscription as apple
from app.routers import admin_account_insights as admin, device_activity as routes
from app.deps import get_db, get_current_user_id


class AccountInsightsTests(unittest.TestCase):
    def setUp(self):
        self.config = patch.multiple(settings, PADDLE_ADMIN_SECRET="isolated-admin", APPLE_SUBSCRIPTION_ENABLED=True,
            APPLE_SUBSCRIPTION_ENVIRONMENT="Production", APPLE_SUBSCRIPTION_SANDBOX_USER_IDS="")
        self.config.start()
        self.now = datetime(2026, 10, 9, 12, tzinfo=timezone.utc)
        self.clock = patch.object(activity, "utcnow", side_effect=lambda: self.now); self.clock.start()
        self.apple_clock = patch.object(apple, "now_ms", side_effect=lambda: int(self.now.timestamp()*1000)); self.apple_clock.start()
        self.engine = create_engine("sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool)
        @event.listens_for(self.engine, "connect")
        def foreign_keys(connection, _): connection.execute("PRAGMA foreign_keys=ON")
        fixture_models = [User, Device, AppleSubscriptionOwner, AppleSubscriptionTransaction,
                          AppleSubscriptionDevice, DeviceActivityTotal, DeviceActivitySession, DeviceActivityDay]
        beta_grant = getattr(models, "BetaAccessGrant", None)
        if beta_grant is not None:
            fixture_models.append(beta_grant)
        for model in fixture_models:
            model.__table__.create(self.engine)
        self.db = Session(self.engine)
        self.uid, self.other, self.device, self.session = (uuid.uuid4() for _ in range(4))
        self.db.add_all([User(id=self.uid, email="owner@isolated.invalid", password_hash="secret-hash", is_active=True, has_premium_access=True, has_lifetime_access=True),
            User(id=self.other, email="other@isolated.invalid", password_hash="unused", is_active=True)])
        self.db.flush()
        self.db.add(Device(id=self.device, user_id=self.uid, device_type="phone", platform="android", installation_id="own-install"))
        self.db.commit()
        app = FastAPI(); app.include_router(admin.router); app.include_router(routes.router)
        app.dependency_overrides[get_db] = lambda: self.db
        app.dependency_overrides[get_current_user_id] = lambda: str(self.uid)
        self.client = TestClient(app)
        self.headers = {"X-Admin-Secret": "isolated-admin"}

    def tearDown(self):
        self.client.close(); self.db.close(); self.engine.dispose()
        self.clock.stop(); self.apple_clock.stop(); self.config.stop()

    def report(self, sequence=0, elapsed=0, **kwargs):
        body = {"device_id": str(self.device), "platform": "android", "session_id": str(self.session), "sequence": sequence,
            "foreground_ms": elapsed, "playback_ms": 0, "device_model": "Pixel", "os_version": "16"}
        body.update(kwargs)
        return self.client.post("/me/device-activity", json=body)

    def detail(self, uid=None):
        return self.client.get(f"/admin/account-insights/{uid or self.uid}", headers=self.headers)

    def test_android_lifetime_and_registration_do_not_grant_apple_premium(self):
        data = self.detail().json()["user"]
        self.assertTrue(data["has_premium_access"])
        self.assertFalse(data["apple_subscription"]["access_eligible"])
        self.assertFalse(apple.access_state(self.db, self.uid, uuid.uuid4())["access_allowed"])

    def test_admin_endpoints_require_secret_and_do_not_expose_passwords(self):
        for route in ("/admin/account-insights", f"/admin/account-insights/{self.uid}"):
            self.assertEqual(self.client.get(route).status_code, 403)
        self.assertNotIn("secret-hash", self.detail().text)

    def test_platform_filter_paginates_the_full_filtered_population(self):
        self.db.add(Device(user_id=self.other, device_type="tv", platform="tvOS")); self.db.commit()
        data = self.client.get("/admin/account-insights?platform=apple&limit=1", headers=self.headers).json()
        self.assertEqual(data["total"], 1); self.assertEqual(data["users"][0]["id"], str(self.other))
        self.assertEqual(self.client.get("/admin/account-insights?platform=bogus", headers=self.headers).status_code, 422)

    def test_historical_duration_is_unavailable_until_a_client_reports(self):
        data = self.detail().json()
        self.assertIsNone(data["devices"][0]["usage"]["foreground_ms"])
        self.assertIsNone(data["user"]["usage"]["measured_since"])

    def test_foreground_duration_playback_and_daily_usage_are_recorded_once(self):
        self.assertEqual(self.report().status_code, 200)
        self.now += timedelta(seconds=30)
        self.report(1, 30000, playback_ms=20000)
        self.assertTrue(self.report(1, 30000).json()["duplicate"])
        data = self.detail().json()
        self.assertEqual(data["user"]["usage"]["foreground_ms"], 30000)
        self.assertEqual(data["user"]["usage"]["playback_ms"], 20000)
        self.assertEqual(data["daily_usage"][0]["foreground_ms"], 30000)
        self.assertEqual(data["devices"][0]["usage"]["device_model"], "Pixel")

    def test_foreign_device_and_removed_device_cannot_report_or_reactivate(self):
        self.client.app.dependency_overrides[get_current_user_id] = lambda: str(self.other)
        self.assertEqual(self.report().status_code, 404)
        self.client.app.dependency_overrides[get_current_user_id] = lambda: str(self.uid)
        self.db.get(Device, self.device).is_active = False; self.db.commit()
        self.assertEqual(self.report().status_code, 403)
        self.assertFalse(self.db.get(Device, self.device).is_active)

    def test_clock_cap_prevents_offline_and_overlapping_duration_inflation(self):
        self.report(); self.now += timedelta(seconds=5)
        self.report(1, 100000)
        self.assertEqual(self.detail().json()["user"]["usage"]["foreground_ms"], 5000)
        self.session = uuid.uuid4(); self.report()
        self.now += timedelta(minutes=10); self.report(1, 600000)
        self.assertEqual(self.detail().json()["user"]["usage"]["foreground_ms"], 125000)

    def test_backwards_counters_and_playback_greater_than_foreground_rejected(self):
        self.report(); self.now += timedelta(seconds=20); self.report(1, 20000)
        self.assertEqual(self.report(2, 10000).status_code, 422)
        self.assertEqual(self.report(2, 21000, playback_ms=22000).status_code, 422)

    def test_pre_checkout_apple_activity_never_activates_or_grants_a_slot(self):
        self.assertEqual(self.report(device_id=None, apple_device_id=str(uuid.uuid4()), platform="ios").status_code, 200)
        self.assertEqual(self.detail().json()["user"]["platforms"]["ios"], 1)
        users = self.client.get("/admin/account-insights?platform=ios", headers=self.headers).json()["users"]
        self.assertEqual([u["id"] for u in users], [str(self.uid)])
        data = self.detail().json()
        self.assertEqual(data["apple_devices"][0]["slot_state"], "not_activated")
        self.assertFalse(data["user"]["apple_subscription"]["access_eligible"])
        self.assertEqual(self.db.query(AppleSubscriptionDevice).count(), 0)

    def test_sandbox_and_production_access_are_labelled_separately(self):
        self.db.add(AppleSubscriptionOwner(user_id=self.uid, environment="Sandbox", original_transaction_id="100")); self.db.flush()
        self.db.add(AppleSubscriptionTransaction(environment="Sandbox", original_transaction_id="100", transaction_id="101",
            product_id=settings.APPLE_SUBSCRIPTION_PRODUCT_ID, expires_at_ms=apple.now_ms()+3600000,
            signed_at_ms=apple.now_ms(), is_upgraded=False)); self.db.commit()
        data = self.detail().json()["user"]["apple_subscription"]
        self.assertTrue(data["sandbox"]["active"]); self.assertFalse(data["production"]["active"]); self.assertFalse(data["access_eligible"])
        with patch.object(settings, "APPLE_SUBSCRIPTION_SANDBOX_USER_IDS", str(self.uid)):
            self.assertTrue(self.detail().json()["user"]["apple_subscription"]["access_eligible"])

    def test_account_deletion_cascades_usage_and_device_personal_data(self):
        self.report(); self.now += timedelta(seconds=30); self.report(1, 30000)
        self.db.execute(delete(User).where(User.id == self.uid)); self.db.commit()
        for model in (DeviceActivityTotal, DeviceActivitySession, DeviceActivityDay): self.assertEqual(self.db.query(model).count(), 0)

    def test_usage_migration_has_postgres_ddl_and_reversible_tables(self):
        from alembic.migration import MigrationContext
        from alembic.operations import Operations
        paths = list((Path(__file__).resolve().parents[1]/"alembic/versions").glob("*_device_activity.py"))
        self.assertEqual(len(paths), 1)
        spec = importlib.util.spec_from_file_location("activity_migration", paths[0])
        migration = importlib.util.module_from_spec(spec); spec.loader.exec_module(migration)
        output = io.StringIO()
        with Operations.context(MigrationContext.configure(dialect_name="postgresql", opts={"as_sql": True, "output_buffer": output})):
            migration.upgrade()
        self.assertIn("CREATE TABLE device_activity_totals", output.getvalue()); self.assertIn("ON DELETE CASCADE", output.getvalue())
        for model in (DeviceActivityDay, DeviceActivitySession, DeviceActivityTotal): model.__table__.drop(self.engine)
        with self.engine.begin() as connection:
            with Operations.context(MigrationContext.configure(connection)):
                migration.upgrade(); migration.downgrade()
        self.assertNotIn("device_activity_totals", inspect(self.engine).get_table_names())
