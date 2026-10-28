"""Standalone, no database: python tests/test_play_integrity_monitor_unit.py."""
import base64
import importlib.util
import json
from pathlib import Path
import sys
import time
import types
import unittest
from unittest.mock import patch

import httpx
from jose import jwt

# Deliberately do not load production settings, database, or tests/conftest.py.
config = types.ModuleType("app.config")
config.settings = types.SimpleNamespace(JWT_SECRET="test-only-secret-not-for-production",
    PLAY_INTEGRITY_MONITOR_ENABLED=True, PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER=588484757557,
    GOOGLE_PLAY_SERVICE_ACCOUNT_JSON="unused-test-file", PLAY_INTEGRITY_SIGNING_CERT_SHA256="ab" * 32)
source = Path(__file__).resolve().parents[1] / "app/play_integrity_monitor.py"
spec = importlib.util.spec_from_file_location("isolated_integrity_monitor", source)
monitor = importlib.util.module_from_spec(spec)
with patch.dict(sys.modules, {"app.config": config}):
    spec.loader.exec_module(monitor)


class IntegrityMonitorTest(unittest.TestCase):
    def setUp(self):
        self.challenge = monitor.create_challenge("user-a", "installation-a", "login")
        self.now = int(time.time() * 1000)
        self.payload = {"tokenPayloadExternal": {
            "requestDetails": {"requestPackageName": "com.torve.app",
                "requestHash": self.challenge["request_hash"], "timestampMillis": str(self.now)},
            "appIntegrity": {"appRecognitionVerdict": "PLAY_RECOGNIZED", "packageName": "com.torve.app",
                "certificateSha256Digest": [base64.urlsafe_b64encode(bytes.fromhex("ab" * 32)).decode().rstrip("=")]},
            "accountDetails": {"appLicensingVerdict": "LICENSED"},
            "deviceIntegrity": {"deviceRecognitionVerdict": ["MEETS_DEVICE_INTEGRITY"]}}}

    def evaluate(self):
        return monitor.evaluate_verdict(self.payload, self.challenge["request_hash"], self.now)

    def test_valid_google_verdict_is_sanitized(self):
        result = self.evaluate()
        self.assertEqual(result, dict(status="observed", app_integrity="PLAY_RECOGNIZED",
            play_install="LICENSED", device_integrity=["MEETS_DEVICE_INTEGRITY"], certificate_match=True))
        self.assertNotIn(self.challenge["request_hash"], json.dumps(result))

    def test_challenge_bound_to_user_installation_and_purpose(self):
        value = self.challenge["challenge"]
        monitor.verify_challenge(value, "user-a", "installation-a")
        for user, installation in [("user-b", "installation-a"), ("user-a", "installation-b")]:
            self.assertEqual("invalid_challenge", monitor.observe(value, "token", user, installation)["status"])
        data = jwt.get_unverified_claims(value)
        data["aud"] = "other-purpose"
        forged = jwt.encode(data, config.settings.JWT_SECRET, algorithm="HS256")
        self.assertEqual("invalid_challenge", monitor.observe(forged, "token", "user-a", "installation-a")["status"])

    def test_expired_or_modified_challenges_never_call_google(self):
        expired = monitor.create_challenge("user-a", "installation-a", "login", int(time.time()) - 600)
        with patch.object(monitor, "decode_token") as decode:
            for challenge in [expired["challenge"], "invalid.signature.value"]:
                self.assertEqual("invalid_challenge", monitor.observe(challenge, "token", "user-a", "installation-a")["status"])
            decode.assert_not_called()

    def test_request_hash_package_and_time_must_match(self):
        request = self.payload["tokenPayloadExternal"]["requestDetails"]
        for key, value in [("requestHash", "wrong"), ("requestPackageName", "other.app"),
                           ("timestampMillis", self.now - 301000), ("timestampMillis", self.now + 31000)]:
            with patch.dict(request, {key: value}):
                self.assertEqual("invalid_request_binding", self.evaluate()["status"])

    def test_certificate_is_server_owned_and_recognized_package_checked(self):
        app = self.payload["tokenPayloadExternal"]["appIntegrity"]
        for changes in [{"certificateSha256Digest": ["cd" * 32]}, {"packageName": "other.app"}]:
            with patch.dict(app, changes):
                self.assertEqual("invalid_app_identity", self.evaluate()["status"])
        self.assertEqual("ab" * 32, monitor.normalized_certificate(":".join(["AB"] * 32)))

    def test_negative_verdicts_remain_observations(self):
        data = self.payload["tokenPayloadExternal"]
        data["appIntegrity"] = {"appRecognitionVerdict": "UNRECOGNIZED_VERSION"}
        data["accountDetails"] = {"appLicensingVerdict": "UNLICENSED"}
        data["deviceIntegrity"] = {}
        result = self.evaluate()
        self.assertEqual("observed", result["status"])
        self.assertEqual("UNLICENSED", result["play_install"])
        self.assertEqual([], result["device_integrity"])
        self.assertNotIn("allowed", result)

    def test_missing_token_and_google_failures_are_nonfatal_and_sanitized(self):
        args = (self.challenge["challenge"], "token", "user-a", "installation-a")
        self.assertEqual({"status": "token_unavailable"}, monitor.observe(args[0], None, *args[2:]))
        response = httpx.Response(403, request=httpx.Request("POST", "https://example.test"))
        for exception, expected in [(RuntimeError("secret-detail"), "verification_unavailable"),
            (httpx.HTTPStatusError("secret-detail", request=response.request, response=response), "google_rejected")]:
            with patch.object(monitor, "decode_token", side_effect=exception):
                result = monitor.observe(*args)
                self.assertEqual(expected, result["status"])
                self.assertNotIn("secret-detail", json.dumps(result))

    def test_disabled_mode_does_not_contact_google(self):
        with patch.object(config.settings, "PLAY_INTEGRITY_MONITOR_ENABLED", False), patch.object(monitor, "decode_token") as decode:
            self.assertEqual({"status": "disabled"}, monitor.observe("unused", "token", "user-a", None))
            decode.assert_not_called()

    def test_standard_verdict_is_not_cached(self):
        with patch.object(monitor, "decode_token", side_effect=[self.payload, {"tokenPayloadExternal": {}}]) as decode:
            args = (self.challenge["challenge"], "token", "user-a", "installation-a")
            self.assertEqual("observed", monitor.observe(*args)["status"])
            self.assertEqual("invalid_request_binding", monitor.observe(*args)["status"])
            self.assertEqual(2, decode.call_count)


if __name__ == "__main__":
    unittest.main()
