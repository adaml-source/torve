"""Isolated two-device Family contracts. Never uses production fixtures."""
import os
import sys
import json
import uuid
import unittest
from pathlib import Path
from datetime import timedelta
from unittest.mock import patch
os.environ.setdefault("DATABASE_URL", "sqlite:///family-tests-unused.sqlite")
os.environ.setdefault("JWT_SECRET", "family-isolated-test-secret-only")
os.environ.setdefault("APP_ENV", "test")
sys.path.append(str(Path(__file__).resolve().parents[1]))
from cryptography.fernet import Fernet
from fastapi import FastAPI
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import Session
from sqlalchemy.pool import StaticPool
from sqlalchemy.ext.compiler import compiles
from sqlalchemy.dialects.postgresql import JSONB
from jose import JWTError
from app import crypto
from app.config import settings
from app.deps import get_current_user_id, get_db, require_premium
from app.models import User, RefreshToken, AccountSettings, UserIntegration
from app.family_policy import FamilyAccountPolicy, now
from app.routers import family, account_settings, integrations
from app.security import decode_access_token

@compiles(JSONB, "sqlite")
def sqlite_json(*args, **kwargs): return "JSON"

class FamilyPolicyTests(unittest.TestCase):
    def setUp(self):
        self.engine=create_engine("sqlite://", connect_args={"check_same_thread":False}, poolclass=StaticPool)
        for model in (User, RefreshToken, AccountSettings, UserIntegration, FamilyAccountPolicy): model.__table__.create(self.engine)
        self.db=Session(self.engine);self.uid=uuid.uuid4();self.other=uuid.uuid4();self.owner=self.uid
        for uid in (self.uid,self.other): self.db.add(User(id=uid,email=f"owner-{uid}@family.test",password_hash="unused",is_active=True,is_verified=True))
        self.db.commit()
        app=FastAPI()
        for route in (family,account_settings,integrations): app.include_router(route.router)
        app.dependency_overrides[get_current_user_id]=lambda:str(self.owner)
        app.dependency_overrides[require_premium]=lambda:str(self.owner)
        app.dependency_overrides[get_db]=lambda:self.db
        # Production also verifies that credential restore comes from a registered device.
        import app.deps as deps
        from types import SimpleNamespace
        if hasattr(deps, "require_active_device"):
            app.dependency_overrides[deps.require_active_device] = lambda: SimpleNamespace(user_id=str(self.owner), device_id=uuid.UUID(int=1))
        self.client=TestClient(app)
        self.headers={"Authorization":"Bearer normal-account-session", "X-Torve-Installation-Id":"device-a"}
        self.key=patch.object(settings,"INTEGRATION_SECRET_KEY",Fernet.generate_key().decode()).start()
        patch.object(crypto,"_fernet",None).start()
    def tearDown(self):
        patch.stopall();self.client.close();self.db.close();self.engine.dispose()
    def put(self,version=0,kids=None,grant=None,pin="1234",enabled=True):
        headers=dict(self.headers)
        if grant:headers["X-Torve-Parent-Grant"]=grant
        data={"expectedVersion":version,"configuration":{"enabled":enabled,"accountId":str(self.owner),"kids":kids or {},"version":version}}
        if pin is not None:data["pin"]=pin
        return self.client.put("/me/family",json=data,headers=headers)
    def enable(self):
        r=self.put(kids={"child":{"maximumAge":12,"allowedSources":["catalogue"]}})
        self.assertEqual(200,r.status_code,r.text);return r.json()["grant"]
    def test_two_devices_load_same_rules_with_independent_unlocks(self):
        grant=self.enable()
        b={**self.headers,"X-Torve-Installation-Id":"device-b"}
        snap=self.client.get("/me/family",headers=b)
        self.assertEqual(12,snap.json()["configuration"]["kids"]["child"]["maximumAge"])
        self.assertNotIn("grant",snap.json());self.assertEqual("no-store",snap.headers["cache-control"])
        stolen=self.client.put("/me/family",json={"expectedVersion":1,"configuration":{"enabled":False}},headers={**b,"X-Torve-Parent-Grant":grant})
        self.assertEqual(403,stolen.status_code)
        unlocked=self.client.post("/me/family/authorize",json={"pin":"1234"},headers=b)
        self.assertEqual(200,unlocked.status_code)
        with self.assertRaises(JWTError):decode_access_token(grant)
    def test_server_version_rejects_stale_changes(self):
        grant=self.enable()
        changed=self.put(1,{"child":{"maximumAge":6,"allowedSources":[]}},grant,pin=None)
        self.assertEqual(200,changed.status_code,changed.text)
        stale=self.put(1,{},grant,pin=None,enabled=False)
        self.assertEqual(409,stale.status_code)
        self.assertEqual(6,self.client.get("/me/family",headers=self.headers).json()["configuration"]["kids"]["child"]["maximumAge"])
    def test_pin_attempt_limit_is_shared_and_persistent(self):
        self.enable()
        for _ in range(5):self.assertEqual(403,self.client.post("/me/family/authorize",json={"pin":"9999"},headers=self.headers).status_code)
        self.assertEqual(429,self.client.post("/me/family/authorize",json={"pin":"1234"},headers={**self.headers,"X-Torve-Installation-Id":"device-b"}).status_code)
    def test_email_recovery_is_one_use_and_revokes_prior_parent_grants(self):
        grant=self.enable()
        with patch("app.routers.family.mail.send_email",return_value=True) as email,patch("app.routers.family.secrets.randbelow",return_value=76543):
            r=self.client.post("/me/family/recovery/request",headers=self.headers)
        self.assertEqual(200,r.status_code,r.text);self.assertNotIn("076543",r.text)
        self.assertEqual(self.db.get(User,self.uid).email,email.call_args.kwargs["to"])
        self.assertIn("076543",email.call_args.kwargs["text"])
        stored=self.db.get(FamilyAccountPolicy,self.uid)
        self.assertNotIn("076543",stored.recovery_hash);self.assertNotEqual("1234",stored.pin_hash)
        body={"code":"076543","pin":"5678"}
        self.assertEqual(200,self.client.post("/me/family/recovery/confirm",headers=self.headers,json=body).status_code)
        self.assertEqual(403,self.put(2,{},grant,pin=None,enabled=False).status_code)
        self.assertEqual(403,self.client.post("/me/family/recovery/confirm",headers=self.headers,json=body).status_code)
        self.assertEqual(200,self.client.post("/me/family/authorize",json={"pin":"5678"},headers=self.headers).status_code)
    def test_recovery_rejects_expired_codes_and_unverified_owner(self):
        self.enable()
        owner=self.db.get(User,self.uid);owner.is_verified=False;self.db.commit()
        self.assertEqual(403,self.client.post("/me/family/recovery/request",headers=self.headers).status_code)
        owner.is_verified=True;self.db.commit()
        with patch("app.routers.family.mail.send_email",return_value=True):self.client.post("/me/family/recovery/request",headers=self.headers)
        row=self.db.get(FamilyAccountPolicy,self.uid);row.recovery_expires=now()-timedelta(seconds=1);self.db.commit()
        self.assertEqual(403,self.client.post("/me/family/recovery/confirm",headers=self.headers,json={"code":"000000","pin":"5678"}).status_code)
    def test_recovery_code_is_invalid_after_account_email_changes(self):
        self.enable()
        with patch("app.routers.family.mail.send_email",return_value=True),patch("app.routers.family.secrets.randbelow",return_value=123456):
            self.client.post("/me/family/recovery/request",headers=self.headers)
        owner=self.db.get(User,self.uid);owner.email="new-owner@family.test";self.db.commit()
        r=self.client.post("/me/family/recovery/confirm",headers=self.headers,json={"code":"123456","pin":"5678"})
        self.assertEqual(403,r.status_code)

    def test_accounts_are_isolated(self):
        self.enable();self.owner=self.other
        self.assertFalse(self.client.get("/me/family",headers=self.headers).json()["configuration"]["enabled"])
    def test_profiles_and_secret_settings_cannot_bypass_family_api(self):
        self.enable()
        record=json.dumps({"profile":"child","kind":"profile","id":"child","changedAt":1,"data":{"name":"Changed"}})
        denied=self.client.patch("/me/account-settings",headers=self.headers,json={"settings":{"household_v1:child:profile:child":record}})
        self.assertEqual(403,denied.status_code,denied.text)
        allowed=self.client.patch("/me/account-settings",headers=self.headers,json={"settings":{"family_parent_pin_v1":"1234","family_device_configuration_v1":"disabled","language":"de"}})
        self.assertEqual(200,allowed.status_code,allowed.text)
        self.assertNotIn("family_parent_pin_v1",allowed.json()["settings"])
        self.assertEqual("de",allowed.json()["settings"]["language"])
    def add_connection(self,service="DEBRID_API_KEY_TORBOX"):
        result=self.client.put("/me/integrations/"+service,headers=self.headers,json={"integration_type":service,"storage_mode":"account","credentials":{"api_key":"SECRET_CANARY"},"config":{}})
        self.assertEqual(200,result.status_code,result.text)
    def test_selected_connection_copy_is_encrypted_scoped_and_requires_parent(self):
        self.add_connection();grant=self.enable()
        body={"sourceProfile":"default","targetProfile":"child","services":["DEBRID_API_KEY_TORBOX"]}
        self.assertEqual(403,self.client.post("/me/family/connections/copy",headers=self.headers,json=body).status_code)
        result=self.client.post("/me/family/connections/copy",headers={**self.headers,"X-Torve-Parent-Grant":grant},json=body)
        self.assertEqual(200,result.status_code,result.text);self.assertNotIn("SECRET_CANARY",result.text)
        target=self.db.query(UserIntegration).filter_by(user_id=self.uid,integration_type="DEBRID_API_KEY_TORBOX::p:child").one()
        self.assertNotIn("SECRET_CANARY",target.encrypted_credentials)
        self.assertEqual(409,self.client.post("/me/family/connections/copy",headers={**self.headers,"X-Torve-Parent-Grant":grant},json=body).status_code)
        # Child gets only the explicitly copied connection, never the parent's slot.
        child={**self.headers,"X-Torve-Profile-Id":"child"}
        self.assertEqual(200,self.client.get("/me/integrations/DEBRID_API_KEY_TORBOX::p:child/credentials",headers=child).status_code)
        self.assertEqual(403,self.client.get("/me/integrations/DEBRID_API_KEY_TORBOX/credentials",headers=child).status_code)
    def test_copy_is_opt_in_and_leaves_other_services_untouched(self):
        self.add_connection()
        r=self.client.post("/me/family/connections/copy",headers=self.headers,json={"sourceProfile":"default","targetProfile":"second","services":["DEBRID_API_KEY_TORBOX"]})
        self.assertEqual(200,r.status_code,r.text)
        self.assertEqual(2,self.db.query(UserIntegration).count())
    def test_copied_trakt_refreshes_share_latest_encrypted_tokens_without_reentry(self):
        service="TRAKT_TOKENS"
        r=self.client.put("/me/integrations/"+service,headers=self.headers,json={"integration_type":service,"storage_mode":"account","credentials":{"access_token":"access-1","refresh_token":"refresh-1","client_id":"custom-client","client_secret":"custom-client-secret"},"config":{}})
        self.assertEqual(200,r.status_code,r.text)
        grant=self.enable()
        copied=self.client.post("/me/family/connections/copy",headers={**self.headers,"X-Torve-Parent-Grant":grant},json={"sourceProfile":"default","targetProfile":"child","services":[service]})
        self.assertEqual(200,copied.status_code,copied.text)
        child={**self.headers,"X-Torve-Profile-Id":"child"}
        rotated=self.client.post("/me/family/connections/trakt/rotate",headers=child,json={"profile":"child","previousRefreshToken":"refresh-1","credentials":{"accessToken":"access-2","refreshToken":"refresh-2"}})
        self.assertEqual(200,rotated.status_code,rotated.text)
        adult={**self.headers,"X-Torve-Parent-Grant":grant}
        latest=self.client.get("/me/integrations/TRAKT_TOKENS/credentials",headers=adult)
        self.assertEqual("refresh-2",latest.json()["credentials"]["refreshToken"])
        self.assertNotIn("refresh_token",latest.json()["credentials"])
        self.assertNotIn("access_token",latest.json()["credentials"])
        self.assertEqual("custom-client",latest.json()["credentials"]["client_id"])
        self.assertEqual("custom-client-secret",latest.json()["credentials"]["client_secret"])
        stale=self.client.post("/me/family/connections/trakt/rotate",headers=child,json={"profile":"child","previousRefreshToken":"refresh-1","credentials":{"accessToken":"access-stale","refreshToken":"refresh-stale"}})
        self.assertEqual("refresh-2",stale.json()["credentials"]["refreshToken"])
        fresh=self.client.get("/me/integrations/TRAKT_TOKENS::p:child/credentials",headers=child)
        self.assertEqual("access-2",fresh.json()["credentials"]["accessToken"])

    def test_navigation_records_ignore_stale_out_of_order_writes(self):
        key="household_v1:default:navigation:search_saved_lists_v1"
        def update(stamp,value):return self.client.patch("/me/account-settings",headers=self.headers,json={"settings":{key:json.dumps({"profile":"default","kind":"navigation","id":"search_saved_lists_v1","changedAt":stamp,"data":{"value":value}})}})
        self.assertEqual(200,update(2,"new").status_code)
        self.assertEqual("new",json.loads(update(1,"old").json()["settings"][key])["data"]["value"])

if __name__=="__main__":unittest.main()
