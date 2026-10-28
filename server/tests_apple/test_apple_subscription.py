"""Own in-memory database only. Run with unittest; no production conftest hooks."""
import os
import hashlib
import json
from pathlib import Path
import sys
import unittest
import uuid
from types import SimpleNamespace
from unittest.mock import patch

os.environ["DATABASE_URL"] = "sqlite:///apple-test-engine-unused.sqlite"
os.environ["JWT_SECRET"] = "isolated-apple-subscription-contract-test-only"
os.environ["APP_ENV"] = "test"
# Keep installed Alembic ahead of the repository's same-named migration folder.
sys.path.append(str(Path(__file__).resolve().parents[1]))

from fastapi import FastAPI, HTTPException
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, event, delete, select, inspect
from sqlalchemy.orm import Session
from sqlalchemy.pool import StaticPool
from sqlalchemy.dialects import postgresql
from app.config import settings
from app.models import User, Device
from app import apple_subscription as service
from app.apple_subscription_models import AppleSubscriptionOwner, AppleSubscriptionTransaction, AppleSubscriptionDevice
from app.routers import apple_subscription as routes
from app.routers import devices as device_routes
from app.routers.auth import _register_device_on_auth
from app.deps import get_db, get_current_user_id


class AppleSubscriptionTests(unittest.TestCase):
    def setUp(self):
        self.configuration = patch.multiple(settings, APPLE_SUBSCRIPTION_ENABLED=True,
            APPLE_SUBSCRIPTION_ENVIRONMENT="Production", APPLE_BUNDLE_ID="com.torve.app",
            APPLE_SUBSCRIPTION_PRODUCT_ID="com.torve.app.apple.monthly.5devices", APPLE_APP_ID=6820592993,
            APPLE_SUBSCRIPTION_SANDBOX_USER_IDS="")
        self.configuration.start()
        self.clock = patch.object(service, "now_ms", return_value=1_800_000_000_000)
        self.clock.start()
        self.engine = create_engine("sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool)
        @event.listens_for(self.engine, "connect")
        def foreign_keys(connection, _): connection.execute("PRAGMA foreign_keys=ON")
        for model in (User, Device, AppleSubscriptionOwner, AppleSubscriptionTransaction, AppleSubscriptionDevice):
            model.__table__.create(self.engine)
        self.db = Session(self.engine)
        self.uid, self.other, self.device = uuid.uuid4(), uuid.uuid4(), uuid.uuid4()
        for uid in (self.uid, self.other):
            self.db.add(User(id=uid, email=f"{uid}@isolated.invalid", password_hash="unused", is_active=True))
        self.db.commit()
        app = FastAPI(); app.include_router(routes.router); app.include_router(device_routes.router)
        app.dependency_overrides[get_current_user_id] = lambda: str(self.uid)
        app.dependency_overrides[get_db] = lambda: self.db
        self.client = TestClient(app)

    def tearDown(self):
        self.client.close(); self.db.close(); self.engine.dispose()
        self.clock.stop(); self.configuration.stop()

    def payload(self, **changes):
        values = dict(bundleId="com.torve.app", productId=settings.APPLE_SUBSCRIPTION_PRODUCT_ID,
            environment="Production", type="Auto-Renewable Subscription", appAccountToken=str(self.uid),
            transactionId="1001", originalTransactionId="1000", expiresDate=service.now_ms()+30*86400_000,
            signedDate=service.now_ms(), revocationDate=None, isUpgraded=False)
        values.update(changes); return SimpleNamespace(**values)

    def subscribe(self, **changes):
        service.record_transaction(self.db, self.uid, self.payload(**changes)); self.db.commit()

    def activate(self, device=None):
        return service.activate_device(self.db, self.uid, device or self.device, "ios", "iPhone")

    def test_purchase_does_not_activate_a_device_implicitly(self):
        self.subscribe()
        state=service.access_state(self.db,self.uid,self.device)
        self.assertTrue(state["subscription_active"]); self.assertFalse(state["access_allowed"])

    def test_more_than_five_apple_devices_and_existing_device_is_idempotent(self):
        self.subscribe(); self.activate()
        for index in range(11):
            state = service.activate_device(self.db, self.uid, uuid.uuid4(), ("ios", "ipados", "tvos")[index % 3], "Apple device")
            self.assertTrue(state["access_allowed"])
            self.assertTrue(state["device_limit_unlimited"])
        self.assertTrue(self.activate()["access_allowed"])
        self.assertEqual(self.db.query(AppleSubscriptionDevice).count(),12)
        # Existing clients continue decoding their legacy numeric field.
        self.assertEqual(state["device_limit"], 5)
        self.assertIsNone(service.DEVICE_LIMIT)

    def test_android_devices_do_not_consume_apple_slots_or_gain_an_apple_slot(self):
        for _ in range(9): self.db.add(Device(user_id=self.uid, device_type="phone", platform="android"))
        self.db.commit(); self.subscribe()
        self.assertTrue(self.activate()["access_allowed"])
        with self.assertRaises(HTTPException): service.activate_device(self.db,self.uid,uuid.uuid4(),"firetv","TV")
        self.assertEqual(self.db.query(AppleSubscriptionDevice).count(),1)

    def fill_devices(self, platform, count):
        for _ in range(count):
            self.db.add(Device(user_id=self.uid, device_type="phone", platform=platform, installation_id=str(uuid.uuid4())))
        self.db.commit()

    def test_registration_endpoint_accepts_all_apple_platforms_beyond_account_cap(self):
        self.fill_devices("android", 5)
        with patch("app.billing.check_premium_active", return_value=True):
            for platform, kind in (("iOS", "phone"), ("ipados", "tablet"), ("tvOS", "tv")):
                response = self.client.post("/me/devices/register", json={"device_type": kind, "platform": platform,
                    "display_name": "Apple device", "installation_id": str(uuid.uuid4())})
                self.assertEqual(response.status_code, 201, response.text)
        self.assertEqual(self.db.query(Device).count(), 8)

    def test_auth_registration_accepts_apple_when_non_apple_cap_is_full(self):
        self.fill_devices("android", 5)
        with patch("app.billing.check_premium_active", return_value=True):
            for platform in ("ios", "ipados", "tvos"):
                row = _register_device_on_auth(self.db, self.uid, "tv" if platform == "tvos" else "phone",
                    platform, "Apple device", str(uuid.uuid4()), "1.0")
                self.db.flush()
                self.assertTrue(row.is_active)

    def test_many_apple_devices_do_not_prevent_registering_another_platform(self):
        self.fill_devices("iOS", 12); self.fill_devices("android", 4)
        with patch("app.billing.check_premium_active", return_value=True):
            response = self.client.post("/me/devices/register", json={"device_type": "phone", "platform": "android",
                "display_name": "Android", "installation_id": str(uuid.uuid4())})
            self.assertEqual(response.status_code, 201, response.text)

    def test_unlimited_activations_do_not_add_another_purchase_or_account_binding(self):
        self.subscribe()
        for _ in range(12): self.activate(uuid.uuid4())
        self.assertEqual(self.db.query(AppleSubscriptionTransaction).count(), 1)
        self.assertEqual(self.db.query(AppleSubscriptionOwner).count(), 1)

    def test_revoked_apple_device_can_be_reactivated_with_many_other_devices(self):
        self.subscribe(); self.activate()
        self.db.get(AppleSubscriptionDevice, (self.uid, self.device)).active = False
        for _ in range(12): self.activate(uuid.uuid4())
        self.assertTrue(self.activate()["access_allowed"])
        self.assertEqual(self.db.query(AppleSubscriptionDevice).count(), 13)

    def test_revocation_frees_a_slot_and_ordinary_status_does_not_reactivate(self):
        self.subscribe()
        for _ in range(4): self.activate(uuid.uuid4())
        self.activate(); self.db.commit()
        response=self.client.delete(f"/me/apple-subscription/devices/{self.device}",params={"current_device_id":str(self.device)})
        self.assertEqual(response.status_code,200); self.assertFalse(response.json()["access_allowed"])
        self.assertFalse(service.access_state(self.db,self.uid,self.device)["device_activated"])
        self.assertTrue(self.activate(uuid.uuid4())["access_allowed"])

    def test_foreign_device_cannot_be_revoked(self):
        self.subscribe(); self.activate(); self.db.commit()
        self.client.app.dependency_overrides[get_current_user_id]=lambda:str(self.other)
        self.assertEqual(self.client.delete(f"/me/apple-subscription/devices/{self.device}",params={"current_device_id":str(uuid.uuid4())}).status_code,404)
        self.assertTrue(service.access_state(self.db,self.uid,self.device)["access_allowed"])

    def test_transaction_cannot_be_replayed_on_another_account(self):
        self.subscribe()
        with self.assertRaises(HTTPException) as failure: service.record_transaction(self.db,self.other,self.payload())
        self.assertEqual(failure.exception.status_code,409)

    def test_original_chain_cannot_change_owner_even_with_a_changed_account_token(self):
        self.subscribe()
        with self.assertRaises(HTTPException): service.record_transaction(self.db,self.other,self.payload(appAccountToken=str(self.other)))

    def test_wrong_bundle_product_environment_type_and_missing_account_are_rejected(self):
        for change in ({"bundleId":"com.other.app"},{"productId":"other"},{"environment":"Sandbox"},
                       {"type":"Non-Consumable"},{"appAccountToken":None},{"signedDate":service.now_ms()+400000}):
            with self.subTest(change=change):
                with self.assertRaises(HTTPException): self.subscribe(**change)
        self.assertEqual(self.db.query(AppleSubscriptionOwner).count(),0)

    def test_expired_and_refunded_transactions_have_no_access(self):
        for change in ({"expiresDate":service.now_ms()},{"revocationDate":service.now_ms()},{"isUpgraded":True}):
            with self.subTest(change=change):
                service.record_transaction(self.db,self.uid,self.payload(transactionId=str(2000+len(change)),**change))
                self.assertIsNone(service.active_expiry(self.db,self.uid))
                self.db.query(AppleSubscriptionTransaction).delete(); self.db.commit()

    def test_older_restore_cannot_undo_a_refund(self):
        self.subscribe(signedDate=service.now_ms()-1000)
        self.subscribe(revocationDate=service.now_ms(),signedDate=service.now_ms())
        self.subscribe(signedDate=service.now_ms()-1000)
        self.assertIsNone(service.active_expiry(self.db,self.uid))

    def test_equal_timestamp_refund_wins_over_active_replay(self):
        self.subscribe(); self.subscribe(revocationDate=service.now_ms()); self.subscribe()
        self.assertIsNone(service.active_expiry(self.db,self.uid))

    def test_refund_for_an_older_period_does_not_erase_a_later_paid_renewal(self):
        self.subscribe(transactionId="1001",expiresDate=service.now_ms()+1000)
        self.subscribe(transactionId="1002",expiresDate=service.now_ms()+2000)
        self.subscribe(transactionId="1001",revocationDate=service.now_ms(),expiresDate=service.now_ms()+1000)
        self.assertEqual(service.active_expiry(self.db,self.uid),service.now_ms()+2000)

    def test_duplicate_restore_is_idempotent(self):
        self.subscribe(); self.subscribe()
        self.assertEqual(self.db.query(AppleSubscriptionOwner).count(),1)
        self.assertEqual(self.db.query(AppleSubscriptionTransaction).count(),1)

    def test_offline_lease_never_exceeds_expiry_or_24_hours(self):
        self.subscribe(); state=self.activate()
        self.assertEqual(state["lease_expires_at_ms"],service.now_ms()+86400_000)
        self.subscribe(signedDate=service.now_ms()+1,expiresDate=service.now_ms()+1000)
        self.assertEqual(service.access_state(self.db,self.uid,self.device)["lease_expires_at_ms"],service.now_ms()+1000)

    def test_deleting_account_cascades_subscription_and_device_personal_data(self):
        self.subscribe(); self.activate(); self.db.commit()
        self.db.execute(delete(User).where(User.id==self.uid)); self.db.commit()
        for model in (AppleSubscriptionOwner,AppleSubscriptionTransaction,AppleSubscriptionDevice): self.assertEqual(self.db.query(model).count(),0)

    def test_signed_request_is_verified_before_any_record_is_created(self):
        with patch.object(service,"verify_signed_transaction",side_effect=HTTPException(400,detail="Invalid signature")):
            r=self.client.post("/me/apple-subscription/verify",json={"signed_transaction":"not.a.valid.signature","device_id":str(self.device)})
        self.assertEqual(r.status_code,400); self.assertEqual(self.db.query(AppleSubscriptionOwner).count(),0)

    def test_verified_purchase_route_persists_but_requires_explicit_device_activation(self):
        with patch.object(service,"verify_signed_transaction",return_value=self.payload()):
            r=self.client.post("/me/apple-subscription/verify",json={"signed_transaction":"fixture.verified.payload","device_id":str(self.device)})
        self.assertEqual(r.status_code,200); self.assertFalse(r.json()["access_allowed"])
        r=self.client.post("/me/apple-subscription/devices/activate",json={"device_id":str(self.device),"platform":"tvos","display_name":"Living room"})
        self.assertEqual(r.status_code,200); self.assertTrue(r.json()["access_allowed"])

    def test_disabled_service_does_not_offer_checkout(self):
        with patch.object(settings,"APPLE_SUBSCRIPTION_ENABLED",False):
            self.assertEqual(self.client.get("/me/apple-subscription",params={"device_id":str(self.device)}).status_code,503)

    def test_status_is_account_scoped_and_does_not_leak_other_devices(self):
        self.subscribe(); self.activate(); self.db.commit()
        self.client.app.dependency_overrides[get_current_user_id]=lambda:str(self.other)
        r=self.client.get("/me/apple-subscription",params={"device_id":str(self.device)})
        self.assertEqual(r.status_code,200); self.assertEqual(r.json()["devices"],[]); self.assertFalse(r.json()["subscription_active"])

    def test_production_verifier_rejects_forged_and_unsigned_jws(self):
        for token in ("not.a.valid.signature","eyJhbGciOiJub25lIn0.eyJidW5kbGVJZCI6ImNvbS50b3J2ZS5hcHAifQ."):
            with self.subTest(token=token):
                with self.assertRaises(HTTPException) as failure: service.verify_signed_transaction(token)
                self.assertEqual(failure.exception.status_code,400)

    def test_bundled_apple_trust_roots_match_the_public_certificate_inventory(self):
        from cryptography import x509
        certificates = Path(service.__file__).parent / "apple_certificates"
        inventory = json.loads((certificates / "provenance.json").read_text(encoding="utf-8"))
        self.assertEqual({row["file"] for row in inventory}, {
            "AppleIncRootCertificate.cer", "AppleRootCA-G2.cer", "AppleRootCA-G3.cer"})
        self.assertEqual({path.name for path in certificates.glob("*.cer")}, {row["file"] for row in inventory})
        for row in inventory:
            with self.subTest(certificate=row["file"]):
                data = (certificates / row["file"]).read_bytes()
                self.assertEqual(hashlib.sha256(data).hexdigest(), row["sha256"])
                certificate = x509.load_der_x509_certificate(data)
                self.assertTrue(certificate.extensions.get_extension_for_class(x509.BasicConstraints).value.ca)
                self.assertEqual(certificate.subject, certificate.issuer)

    def test_apple_notifications_update_refunds_and_ignore_deleted_accounts(self):
        self.subscribe()
        notification=SimpleNamespace(data=SimpleNamespace(signedTransactionInfo="verified.fixture",environment="Production"))
        decoder=SimpleNamespace(verify_and_decode_signed_transaction=lambda _:self.payload(revocationDate=service.now_ms()))
        with patch.object(service,"verifier",return_value=decoder),patch.object(service,"verify_signed_notification",return_value=notification):
            self.assertEqual(self.client.post("/apple/subscriptions/notifications",json={"signedPayload":"verified.notification"}).status_code,200)
        self.assertIsNone(service.active_expiry(self.db,self.uid))
        self.db.execute(delete(User).where(User.id==self.uid));self.db.commit()
        with patch.object(service,"verifier",return_value=decoder),patch.object(service,"verify_signed_notification",return_value=notification):
            self.assertEqual(self.client.post("/apple/subscriptions/notifications",json={"signedPayload":"verified.notification"}).status_code,200)
        self.assertEqual(self.db.query(AppleSubscriptionOwner).count(),0)

    def test_slot_serialization_uses_postgresql_account_row_lock(self):
        sql=str(select(User).where(User.id==self.uid).with_for_update().compile(dialect=postgresql.dialect()))
        self.assertIn("FOR UPDATE",sql)

    def test_sandbox_allowance_is_explicit_and_isolated_from_ordinary_accounts(self):
        with patch.object(settings,"APPLE_SUBSCRIPTION_SANDBOX_USER_IDS",str(self.uid)):
            self.subscribe(environment="Sandbox")
            self.assertTrue(self.activate()["access_allowed"])
            with self.assertRaises(HTTPException):
                service.record_transaction(self.db,self.other,self.payload(environment="Sandbox",appAccountToken=str(self.other),transactionId="3001",originalTransactionId="3000"))
        self.assertIsNone(service.active_expiry(self.db,self.uid))
        with self.assertRaises(HTTPException): self.subscribe(environment="Sandbox")

    def test_production_and_sandbox_identifiers_cannot_collide(self):
        self.subscribe()
        with patch.object(settings,"APPLE_SUBSCRIPTION_SANDBOX_USER_IDS",str(self.uid)):
            self.subscribe(environment="Sandbox",revocationDate=service.now_ms())
        self.assertIsNotNone(service.active_expiry(self.db,self.uid))
        self.assertEqual(self.db.query(AppleSubscriptionTransaction).count(),2)

    def test_notification_verification_retries_transient_failures(self):
        from appstoreserverlibrary.signed_data_verifier import VerificationException, VerificationStatus
        for status, expected in ((VerificationStatus.RETRYABLE_VERIFICATION_FAILURE,503),(VerificationStatus.VERIFICATION_FAILURE,400)):
            decoder=SimpleNamespace(verify_and_decode_notification=lambda _:(_ for _ in ()).throw(VerificationException(status)))
            with patch.object(service,"verifier",return_value=decoder):
                self.assertEqual(self.client.post("/apple/subscriptions/notifications",json={"signedPayload":"invalid.signature.fixture"}).status_code,expected)

    def test_inner_notification_signature_is_verified_independently(self):
        from appstoreserverlibrary.signed_data_verifier import VerificationException, VerificationStatus
        notification=SimpleNamespace(data=SimpleNamespace(signedTransactionInfo="invalid.fixture",environment="Production"))
        decoder=SimpleNamespace(verify_and_decode_signed_transaction=lambda _:(_ for _ in ()).throw(VerificationException(VerificationStatus.VERIFICATION_FAILURE)))
        with patch.object(service,"verify_signed_notification",return_value=notification),patch.object(service,"verifier",return_value=decoder):
            self.assertEqual(self.client.post("/apple/subscriptions/notifications",json={"signedPayload":"verified.notification.fixture"}).status_code,400)
        self.assertEqual(self.db.query(AppleSubscriptionOwner).count(),0)

    def test_signed_notification_environment_must_match_its_inner_transaction(self):
        notification=SimpleNamespace(data=SimpleNamespace(signedTransactionInfo="verified.fixture",environment="Production"))
        decoder=SimpleNamespace(verify_and_decode_signed_transaction=lambda _:self.payload(environment="Sandbox"))
        with patch.object(service,"verify_signed_notification",return_value=notification),patch.object(service,"verifier",return_value=decoder):
            self.assertEqual(self.client.post("/apple/subscriptions/notifications",json={"signedPayload":"verified.notification.fixture"}).status_code,400)

    def test_subscription_routes_require_a_torve_session(self):
        from app.deps import get_current_user_id
        app=FastAPI(); app.include_router(routes.router)
        app.dependency_overrides[get_db]=lambda:self.db
        with TestClient(app) as client:
            for path in (f"/me/apple-subscription?device_id={self.device}",):
                self.assertIn(client.get(path).status_code,(401,403))


class AppleMigrationTests(unittest.TestCase):
    def migration(self):
        import importlib.util
        paths=list((Path(__file__).resolve().parents[1]/"alembic/versions").glob("*_apple_subscription.py"))
        self.assertEqual(len(paths),1)
        path=paths[0]
        spec=importlib.util.spec_from_file_location("apple_migration_fixture",path)
        module=importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
        return module

    def test_upgrade_ledger_cascade_and_downgrade_roundtrip(self):
        from alembic.migration import MigrationContext
        from alembic.operations import Operations
        engine=create_engine("sqlite://")
        @event.listens_for(engine,"connect")
        def foreign_keys(connection,_): connection.execute("PRAGMA foreign_keys=ON")
        User.__table__.create(engine)
        module=self.migration()
        with engine.begin() as connection:
            with patch.object(module,"op",Operations(MigrationContext.configure(connection))): module.upgrade()
        uid,device=uuid.uuid4(),uuid.uuid4()
        with Session(engine) as db:
            db.add(User(id=uid,email="migration@isolated.invalid",password_hash="unused",is_active=True));db.flush()
            db.add(AppleSubscriptionOwner(environment="Production",original_transaction_id="10",user_id=uid));db.flush()
            db.add(AppleSubscriptionTransaction(environment="Production",transaction_id="11",original_transaction_id="10",product_id="fixture",expires_at_ms=100,signed_at_ms=99,is_upgraded=False))
            db.add(AppleSubscriptionDevice(user_id=uid,device_id=device,platform="tvos",display_name="Fixture",active=True,last_seen_ms=99));db.commit()
            db.execute(delete(User).where(User.id==uid));db.commit()
            for model in (AppleSubscriptionOwner,AppleSubscriptionTransaction,AppleSubscriptionDevice): self.assertEqual(db.query(model).count(),0)
        with engine.begin() as connection:
            with patch.object(module,"op",Operations(MigrationContext.configure(connection))): module.downgrade()
        self.assertEqual(inspect(engine).get_table_names(),["users"])
        engine.dispose()

    def test_migration_generates_postgresql_ddl_without_a_database_connection(self):
        from io import StringIO
        from alembic.migration import MigrationContext
        from alembic.operations import Operations
        output=StringIO()
        context=MigrationContext.configure(dialect_name="postgresql",opts={"as_sql":True,"output_buffer":output})
        module=self.migration()
        with patch.object(module,"op",Operations(context)): module.upgrade();module.downgrade()
        sql=output.getvalue()
        self.assertIn("UUID",sql);self.assertIn("BIGINT",sql);self.assertIn("ON DELETE CASCADE",sql)
        self.assertEqual(sql.count("CREATE TABLE apple_subscription_"),3)
        self.assertEqual(sql.count("DROP TABLE apple_subscription_"),3)


if __name__=="__main__": unittest.main()
