import hashlib
import json
from pathlib import Path
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.parse
import urllib.request
from http.server import HTTPServer

from credential_receiver import SetupState, handler_for


class CredentialEntryTest(unittest.TestCase):
    def setUp(self):
        self.test_root = (Path.cwd() / "build/apple/torbox-progressive/entry-tests").resolve()
        self.test_root.mkdir(parents=True, exist_ok=True)
        self.directory = tempfile.TemporaryDirectory(dir=self.test_root)
        self.root = Path(self.directory.name)
        self.path = "/usenet-setup/test-secret-token"
        self.config = self.root / "config.json"
        self.config.write_text(json.dumps({"providers": [{"id": "torbox", "host": "nntp.torbox.app", "password": ""}]}))
        self.state = SetupState({"tokenSha256": hashlib.sha256(b"test-secret-token").hexdigest(),
            "csrf": "test-csrf", "expiresAt": time.time()+120, "origin": "https://api.torve.app",
            "username": "fixture-account", "providerConfigPath": str(self.config),
            "receiptPath": str(self.root / "received.json")}, verify=lambda u, p: p == "test-password")
        self.server = HTTPServer(("127.0.0.1", 0), handler_for(self.state))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = "http://127.0.0.1:" + str(self.server.server_port)

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.root.resolve().relative_to(self.test_root)
        self.directory.cleanup()

    def request(self, path=None, password=None, origin="https://api.torve.app", csrf="test-csrf"):
        headers = {"Origin": origin}
        body = None
        if password is not None:
            body = urllib.parse.urlencode({"password": password, "csrf": csrf}).encode()
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        request = urllib.request.Request(self.url + (path or self.path), data=body, headers=headers)
        try:
            response = urllib.request.urlopen(request, timeout=3)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return response.status, response.headers, response.read().decode()

    def test_wrong_link_and_expiry_reject_before_form_or_storage(self):
        self.assertEqual(self.request(path="/usenet-setup/wrong")[0], 404)
        self.assertEqual(self.request(path=self.path+"?leak=1")[0], 404)
        self.state.settings["expiresAt"] = time.time()-1
        self.assertEqual(self.request(password="test-password")[0], 410)
        self.assertFalse(self.state.received.exists())

    def test_valid_password_is_consumed_once_and_never_reflected(self):
        status, headers, body = self.request(password="test-password")
        self.assertEqual(status, 200)
        self.assertIn("no-store", headers["Cache-Control"])
        self.assertEqual(headers["Referrer-Policy"], "no-referrer")
        self.assertNotIn("test-password", body)
        provider = json.loads(self.config.read_text())["providers"][0]
        self.assertEqual(provider["password"], "test-password")
        self.assertTrue(provider["tls"])
        self.assertFalse(provider["insecure_tls"])
        self.assertEqual(self.request(password="different-password")[0], 410)
        self.assertNotIn("<form", self.request()[2])

    def test_origin_csrf_and_protocol_injection_are_rejected(self):
        self.assertEqual(self.request(password="test-password", origin="https://foreign.invalid")[0], 403)
        self.assertEqual(self.request(password="test-password", csrf="wrong")[0], 400)
        self.assertEqual(self.request(password="bad\r\nAUTHINFO USER other")[0], 400)
        self.assertFalse(self.state.received.exists())

    def test_provider_rejection_does_not_save_password(self):
        self.assertEqual(self.request(password="wrong-password")[0], 400)
        self.assertEqual(json.loads(self.config.read_text())["providers"][0]["password"], "")
        self.assertFalse(self.state.received.exists())

    def test_hidden_password_placeholder_is_not_submitted_to_provider(self):
        calls = []
        self.state.verify = lambda u, p: calls.append(p)
        self.assertEqual(self.request(password="********")[0], 400)
        self.assertEqual(calls, [])


if __name__ == "__main__":
    unittest.main()
