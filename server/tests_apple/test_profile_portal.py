"""Profile portal contracts against an isolated in-memory database; no customer fixtures."""
import json
import unittest
from unittest.mock import patch
from fastapi import FastAPI
from fastapi.testclient import TestClient
import test_family_policy as family_tests
from app.models import AccountSettings, UserIntegration
from app.crypto import decrypt_secret
from app.routers import family, web_proxy


class ProfilePortalTests(unittest.TestCase):
    setUp = family_tests.FamilyPolicyTests.setUp
    tearDown = family_tests.FamilyPolicyTests.tearDown
    put = family_tests.FamilyPolicyTests.put
    enable = family_tests.FamilyPolicyTests.enable
    add_connection = family_tests.FamilyPolicyTests.add_connection
    base = "/me/family/profiles"

    def list(self, headers=None):
        return self.client.get(self.base, headers=headers or self.headers)

    def create(self, name="Kids", headers=None, **extras):
        return self.client.post(self.base, headers=headers or self.headers, json={"name": name, **extras})

    def edit(self, profile, headers=None, **extras):
        body = {key: profile[key] for key in ("name", "avatar", "rating")}
        body.update(expectedChangedAt=profile["changedAt"], expectedFamilyVersion=0)
        body.update(extras)
        return self.client.put(self.base + "/" + profile["id"], json=body, headers=headers or self.headers)

    def test_default_and_created_profiles_use_existing_household_sync(self):
        initial = self.list()
        self.assertEqual(200, initial.status_code, initial.text)
        self.assertEqual("no-store", initial.headers["cache-control"].split(", ")[-1])
        self.assertEqual("default", initial.json()["profiles"][0]["id"])
        result = self.create(rating="G", pin="2468")
        self.assertEqual(201, result.status_code, result.text)
        profile = result.json()
        self.assertTrue(profile["protected"])
        self.assertNotIn("2468", result.text)
        settings = self.client.get("/me/account-settings", headers={**self.headers, "X-Torve-Installation-Id": "device-b"}).json()["settings"]
        record = json.loads(settings[f"household_v1:{profile['id']}:profile:{profile['id']}"])
        self.assertEqual("G", record["data"]["rating"])
        self.assertTrue(record["data"]["protected"])
        connection = self.db.query(UserIntegration).filter_by(user_id=self.uid, integration_type="HOUSEHOLD_PIN:"+profile["id"]).one()
        self.assertNotIn("2468", connection.encrypted_credentials)
        self.assertEqual("2468", json.loads(decrypt_secret(connection.encrypted_credentials))["pin"])

    def test_profile_preferences_are_scoped_and_unrelated_values_survive(self):
        self.assertEqual(422,self.create(avatar=8).status_code)
        child = self.create().json()
        result = self.edit(child, preferences={"theme_mode":"LIGHT","app_language":"GERMAN","stream_cached_only":"true"},
            expectedPreferences={"theme_mode":None,"app_language":None,"stream_cached_only":None})
        self.assertEqual(200,result.status_code,result.text)
        settings = self.client.get("/me/account-settings",headers=self.headers).json()["settings"]
        self.assertEqual("LIGHT",settings[f"profilepref_v1:{child['id']}:theme_mode"])
        self.assertNotIn("theme_mode",settings)
        default = next(p for p in self.list().json()["profiles"] if p["id"]=="default")
        self.assertNotIn("theme_mode",default["preferences"])
        result = self.edit(default,preferences={"app_language":"de"},expectedPreferences={"app_language":None})
        self.assertEqual(200,result.status_code,result.text)
        settings = self.client.get("/me/account-settings",headers=self.headers).json()["settings"]
        self.assertEqual("de",settings["language"])
        self.assertEqual("GERMAN",settings[f"profilepref_v1:{child['id']}:app_language"])

    def test_revisions_reject_lost_updates_from_app_or_another_browser(self):
        child = self.create().json()
        first = self.edit(child,name="First")
        self.assertEqual(200,first.status_code,first.text)
        self.assertEqual(409,self.edit(child,name="Stale").status_code)
        current = first.json()["profile"]
        self.client.patch("/me/account-settings",headers=self.headers,json={"settings":{f"profilepref_v1:{child['id']}:theme_mode":"DARK"}})
        conflict = self.edit(current,preferences={"theme_mode":"LIGHT"},expectedPreferences={"theme_mode":None})
        self.assertEqual(409,conflict.status_code,conflict.text)
        self.assertEqual("First",next(p for p in self.list().json()["profiles"] if p["id"]==child["id"])["name"])

    def test_parent_authorization_protects_reads_edits_creation_and_removal(self):
        child = self.create().json()
        grant = self.enable()
        self.assertEqual(403,self.list().status_code)
        self.assertEqual(403,self.create("Unauthorized").status_code)
        self.assertEqual(403,self.edit(child,name="Unauthorized").status_code)
        headers={**self.headers,"X-Torve-Parent-Grant":grant}
        self.assertEqual(200,self.list(headers).status_code)
        result=self.edit(child,headers=headers,expectedFamilyVersion=1,kidsRules={"maximumAge":0,"allowedSources":["catalogue","extension:approved"],"blockedTitles":["MOVIE:863"]})
        self.assertEqual(200,result.status_code,result.text)
        state=self.client.get("/me/family",headers=self.headers).json()["configuration"]
        self.assertEqual(0,state["kids"][child["id"]]["maximumAge"])
        self.assertEqual(["MOVIE:863"],state["kids"][child["id"]]["blockedTitles"])
        self.assertEqual(409,self.edit(result.json()["profile"],headers=headers,expectedFamilyVersion=1).status_code)

    def test_other_accounts_cannot_see_or_edit_profiles(self):
        child=self.create().json();self.owner=self.other
        self.assertEqual(["default"],[p["id"] for p in self.list().json()["profiles"]])
        self.assertEqual(404,self.edit(child).status_code)

    def test_secret_device_and_unknown_preferences_are_rejected(self):
        child=self.create().json()
        for key in ("omdb_api_key","auth_access_token","movie_download_path","family_parent_pin_v1","anything"):
            result=self.edit(child,preferences={key:"SECRET_CANARY"},expectedPreferences={key:None})
            self.assertEqual(422,result.status_code,result.text)
        self.assertNotIn("SECRET_CANARY",str(self.db.query(AccountSettings).one().settings))

    def test_pin_change_and_removal_publish_revisions_without_returning_pin(self):
        child=self.create(pin="1234").json()
        changed=self.edit(child,pin="5678")
        self.assertEqual(200,changed.status_code,changed.text)
        self.assertNotIn("5678",changed.text)
        cleared=self.edit(changed.json()["profile"],clearPin=True)
        self.assertFalse(cleared.json()["profile"]["protected"])
        self.assertEqual(0,self.db.query(UserIntegration).filter_by(integration_type="HOUSEHOLD_PIN:"+child["id"]).count())
        record=json.loads(self.db.query(AccountSettings).one().settings[f"household_v1:{child['id']}:profile:{child['id']}"])
        self.assertEqual(3,record["data"]["pinRevision"])

    def test_delete_writes_app_tombstone_and_cannot_remove_default(self):
        child=self.create().json()
        result=self.client.post(self.base+"/"+child["id"]+"/delete",headers=self.headers,json={"expectedChangedAt":child["changedAt"],"expectedFamilyVersion":0})
        self.assertEqual(200,result.status_code,result.text)
        self.assertEqual(["default"],[p["id"] for p in self.list().json()["profiles"]])
        record=json.loads(self.db.query(AccountSettings).one().settings[f"household_v1:{child['id']}:profile:{child['id']}"])
        self.assertTrue(record["deleted"])
        self.assertEqual(422,self.client.post(self.base+"/default/delete",headers=self.headers,json={"expectedChangedAt":0,"expectedFamilyVersion":0}).status_code)

    def test_schema_matches_app_allowlist_and_never_contains_credentials(self):
        from pathlib import Path
        expected=Path(__file__).with_name("profile-preference-keys.json")
        keys={f["key"] for f in self.list().json()["preferenceSchema"]}
        self.assertEqual(set(json.loads(expected.read_text())),keys)
        self.assertNotIn("omdb_api_key",keys)

    def test_selected_connection_copy_preserves_source_and_does_not_expose_keys(self):
        child=self.create().json()
        self.add_connection("OMDB_API_KEY");self.add_connection("DEBRID_API_KEY_TORBOX")
        before={r.integration_type:r.encrypted_credentials for r in self.db.query(UserIntegration).all()}
        result=self.client.post("/me/family/connections/copy",headers=self.headers,json={"sourceProfile":"default","targetProfile":child["id"],"services":["OMDB_API_KEY"]})
        self.assertEqual(200,result.status_code,result.text)
        self.assertNotIn("SECRET_CANARY",result.text)
        rows={r.integration_type:r for r in self.db.query(UserIntegration).all()}
        self.assertEqual(before["OMDB_API_KEY"],rows["OMDB_API_KEY"].encrypted_credentials)
        self.assertIn("OMDB_API_KEY::p:"+child["id"],rows)
        self.assertNotIn("DEBRID_API_KEY_TORBOX::p:"+child["id"],rows)

    def test_cookie_proxy_forwards_parent_context_and_binds_to_session(self):
        import httpx
        from app.deps import get_db
        original=httpx.AsyncClient
        internal=self.client.app
        forwarded=[]
        class InternalClient:
            async def __aenter__(inner): return inner
            async def __aexit__(inner,*args): pass
            async def request(inner,**kwargs):
                forwarded.append(kwargs["headers"])
                async with original(transport=httpx.ASGITransport(app=internal),base_url="http://test") as client:
                    return await client.request(**kwargs)
        portal=FastAPI();portal.include_router(web_proxy.router);portal.dependency_overrides[get_db]=lambda:self.db
        with TestClient(portal) as browser, patch.object(web_proxy,"_get_user_id_from_cookies",return_value=(str(self.owner),None,None)),patch.object(httpx,"AsyncClient",side_effect=lambda **kw:InternalClient()):
            browser.cookies.set("torve_csrf","session-a")
            headers={"X-CSRF-Token":"session-a","X-Torve-Installation-Id":"forged-device"}
            enabled=browser.put("/web/api/me/family",json={"expectedVersion":0,"configuration":{"enabled":True},"pin":"1234"},headers=headers)
            self.assertEqual(200,enabled.status_code,enabled.text)
            proof=enabled.json()["grant"]
            self.assertNotEqual("forged-device",forwarded[-1]["X-Torve-Installation-Id"])
            unlocked={**headers,"X-Torve-Parent-Grant":proof,"X-Torve-Profile-Id":"default"}
            self.assertEqual(200,browser.get("/web/api/me/family/profiles",headers=unlocked).status_code)
            self.assertEqual("private, no-store",browser.get("/web/api/me/family/profiles",headers=unlocked).headers["cache-control"])
            self.assertEqual(403,browser.get("/web/api/me/family/profiles").status_code)
            self.assertEqual(403,browser.post("/web/api/me/family/profiles",json={"name":"Missing CSRF"}).status_code)
            browser.cookies.set("torve_csrf","session-b")
            self.assertEqual(403,browser.get("/web/api/me/family/profiles",headers=unlocked).status_code)


if __name__ == "__main__": unittest.main()
