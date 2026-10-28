"""Own in-memory database; no production fixtures or credentials."""
import copy
import json
import os
from pathlib import Path
import sys
import unittest
import uuid
from unittest.mock import patch

os.environ.setdefault("DATABASE_URL", "sqlite:///cloud-backup-tests-unused.sqlite")
os.environ.setdefault("JWT_SECRET", "cloud-backup-isolated-test-only")
os.environ.setdefault("APP_ENV", "test")
sys.path.append(str(Path(__file__).resolve().parents[1]))

from cryptography.fernet import Fernet
from fastapi import FastAPI
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, event
from sqlalchemy.orm import Session
from sqlalchemy.pool import StaticPool
from app import crypto
from app.cloud_backup import CloudBackup, MAX_BYTES
from app.config import settings
from app.deps import get_current_user_id, get_db
from app.models import User, RefreshToken
from app.routers import cloud_backup as routes


class CloudBackupTests(unittest.TestCase):
    def setUp(self):
        self.engine = create_engine("sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool)
        @event.listens_for(self.engine, "connect")
        def fk(connection, _): connection.execute("PRAGMA foreign_keys=ON")
        for model in (User, RefreshToken, CloudBackup): model.__table__.create(self.engine)
        self.db = Session(self.engine)
        self.uid, self.other = uuid.uuid4(), uuid.uuid4()
        for uid in (self.uid, self.other):
            self.db.add(User(id=uid, email=f"{uid}@backup.test", password_hash="unused", is_active=True, is_verified=True))
        self.db.commit()
        self.owner = self.uid
        app = FastAPI(); app.include_router(routes.router)
        app.dependency_overrides[get_current_user_id] = lambda: str(self.owner)
        app.dependency_overrides[get_db] = lambda: self.db
        self.client = TestClient(app)
        self.apple = patch("app.apple_subscription.active_expiry", return_value=1_900_000_000_000).start()
        self.legacy = patch("app.billing.check_premium_active", return_value=False).start()
        self.key = patch.object(settings, "INTEGRATION_SECRET_KEY", Fernet.generate_key().decode()).start()
        self.crypto = patch.object(crypto, "_fernet", None).start()

    def tearDown(self):
        patch.stopall(); self.client.close(); self.db.close(); self.engine.dispose()

    def body(self):
        return {"request_id": str(uuid.uuid4()), "platform": "ios", "reason": "manual", "payload": {
            "schemaVersion": 1, "exportedAt": 1_800_000_000_000,
            "records": [
                {"profile": "default", "kind": "profile", "id": "default", "changedAt": 1, "data": {"name": "Default", "avatar": 0, "protected": False, "createdAt": 1}},
                {"profile": "default", "kind": "history", "id": "show-s1e1", "changedAt": 1, "data": {"mediaId": "1399", "mediaType": "series", "title": "Library title", "watchedAt": 1}},
            ], "preferences": [{"profile": "default", "key": "theme_mode", "value": "DARK"}]
        }}

    def create(self, body=None):
        result = self.client.post("/me/backups", json=body or self.body())
        self.assertEqual(201, result.status_code, result.text)
        return result.json()

    def test_encrypted_roundtrip_counts_and_no_store(self):
        saved = self.create()
        row = self.db.get(CloudBackup, uuid.UUID(saved["id"]))
        self.assertTrue(row.encrypted_payload.startswith("v1:"))
        self.assertNotIn("Library title", row.encrypted_payload)
        result = self.client.get("/me/backups/" + saved["id"])
        self.assertEqual(200, result.status_code)
        self.assertEqual("no-store", result.headers["cache-control"])
        self.assertEqual("Library title", result.json()["payload"]["records"][1]["data"]["title"])
        self.assertEqual(1, saved["counts"]["history"])

    def test_other_account_cannot_read_or_delete_backup(self):
        saved = self.create(); self.owner = self.other
        self.assertEqual([], self.client.get("/me/backups").json()["backups"])
        self.assertEqual(404, self.client.get("/me/backups/" + saved["id"]).status_code)
        self.assertEqual(404, self.client.delete("/me/backups/" + saved["id"]).status_code)
        self.assertIsNotNone(self.db.get(CloudBackup, uuid.UUID(saved["id"])))

    def test_credentials_private_urls_and_unsupported_fields_are_rejected_without_echo(self):
        inputs = []
        for field in ("integrationSecrets", "addons", "iptvPlaylists"):
            body = self.body(); body["payload"][field] = [{"value": "SECRET_CANARY"}]; inputs.append(body)
        body = self.body(); body["payload"]["records"][1]["data"]["poster"] = "https://private.test/SECRET_CANARY"; inputs.append(body)
        body = self.body(); body["payload"]["records"][1]["id"] = "https://tv.test/live/SECRET_CANARY/1"; inputs.append(body)
        body = self.body(); body["payload"]["preferences"][0]["value"] = '{"nested":{"API_KEY":"SECRET_CANARY"}}'; inputs.append(body)
        body = self.body(); body["payload"]["preferences"][0]["key"] = "panda_indexer_subkeys"; inputs.append(body)
        body = self.body(); body["payload"]["preferences"][0]["value"] = "https%3A%2F%2Fprivate.test/SECRET_CANARY"; inputs.append(body)
        for body in inputs:
            result = self.client.post("/me/backups", json=body)
            self.assertEqual(422, result.status_code)
            self.assertNotIn("SECRET_CANARY", result.text)
        self.assertEqual(0, self.db.query(CloudBackup).count())

    def test_idempotency_and_retention_keep_ten_versions(self):
        body = self.body(); first = self.create(body)
        self.assertEqual(first["id"], self.create(body)["id"])
        body["payload"]["exportedAt"] += 1
        self.assertEqual(409, self.client.post("/me/backups", json=body).status_code)
        for _ in range(11): self.create()
        self.assertEqual(10, self.db.query(CloudBackup).count())
        self.assertEqual(10, len(self.client.get("/me/backups").json()["backups"]))

    def test_premium_required_to_write_but_existing_backups_remain_readable(self):
        saved = self.create(); self.apple.return_value = None
        self.assertEqual(403, self.client.post("/me/backups", json=self.body()).status_code)
        self.assertEqual(200, self.client.get("/me/backups/" + saved["id"]).status_code)

    def test_android_and_firetv_backups_are_free_but_remain_account_owned(self):
        self.apple.return_value = None
        for platform in ("android", "firetv"):
            body = self.body(); body["platform"] = platform
            saved = self.create(body)
            self.assertEqual(platform, saved["platform"])
            self.owner = self.other
            self.assertEqual(404, self.client.get("/me/backups/" + saved["id"]).status_code)
            self.owner = self.uid
        self.assertEqual(2, self.db.query(CloudBackup).count())

    def test_free_clients_still_require_a_verified_account_and_valid_platform(self):
        self.apple.return_value = None
        body = self.body(); body["platform"] = "unknown-client"
        self.assertEqual(422, self.client.post("/me/backups", json=body).status_code)
        body["platform"] = "android"
        user = self.db.get(User, self.uid); user.is_verified = False; self.db.commit()
        self.assertEqual(403, self.client.post("/me/backups", json=body).status_code)
        self.assertEqual(0, self.db.query(CloudBackup).count())

    def test_unverified_and_inactive_accounts_cannot_use_backup(self):
        user = self.db.get(User, self.uid); user.is_verified = False; self.db.commit()
        self.assertEqual(403, self.client.get("/me/backups").status_code)
        user.is_active = False; self.db.commit()
        self.assertEqual(401, self.client.get("/me/backups").status_code)

    def test_account_deletion_cascades_and_request_size_is_bounded(self):
        self.create()
        self.assertEqual(413, self.client.post("/me/backups", content=b" " * (MAX_BYTES + 1)).status_code)
        self.db.delete(self.db.get(User, self.uid)); self.db.commit()
        self.assertEqual(0, self.db.query(CloudBackup).count())

    def test_unsupported_schema_and_duplicate_profiles_rejected(self):
        body = self.body(); body["payload"]["schemaVersion"] = 2
        self.assertEqual(422, self.client.post("/me/backups", json=body).status_code)
        body = self.body(); body["payload"]["records"].append(copy.deepcopy(body["payload"]["records"][0]))
        self.assertEqual(422, self.client.post("/me/backups", json=body).status_code)
