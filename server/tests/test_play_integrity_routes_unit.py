"""Standalone HTTP contract tests; no database, credentials, or Google requests."""
import importlib.util
from pathlib import Path
import sys
import types
import unittest
from unittest.mock import patch

from fastapi import FastAPI, HTTPException, Request
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.rate_limits import reset_rate_limits_for_tests
from test_play_integrity_monitor_unit import monitor

deps = types.ModuleType("app.deps")
def user(request: Request):
    if request.headers.get("Authorization") != "Bearer test": raise HTTPException(401)
    return "user-a"
def installation(request: Request):
    return request.headers.get("X-Torve-Installation-Id")
deps.get_current_user_id = user
deps.get_calling_installation_id = installation
spec = importlib.util.spec_from_file_location("isolated_integrity_routes",
    Path(__file__).resolve().parents[1] / "app/routers/play_integrity.py")
routes = importlib.util.module_from_spec(spec)
with patch.dict(sys.modules, {"app.deps": deps, "app.play_integrity_monitor": monitor}):
    spec.loader.exec_module(routes)

app = FastAPI()
app.include_router(routes.router)

class IntegrityRoutesTest(unittest.TestCase):
    def setUp(self):
        reset_rate_limits_for_tests()
        self.client = TestClient(app)
        self.headers = {"Authorization": "Bearer test", "X-Torve-Installation-Id": "installation-a"}
    def tearDown(self): self.client.close()

    def test_auth_and_request_validation(self):
        self.assertEqual(401, self.client.post("/me/integrity-monitor/challenge", json={"event_type": "login"}).status_code)
        self.assertEqual(422, self.client.post("/me/integrity-monitor/challenge", headers=self.headers,
            json={"event_type": "playback"}).status_code)

    def test_missing_or_invalid_verdict_is_accepted_and_logged_without_secrets(self):
        challenge = self.client.post("/me/integrity-monitor/challenge", headers=self.headers,
            json={"event_type": "login"}).json()
        for value, status in [(challenge["challenge"], "token_unavailable"), ("bad-challenge", "invalid_challenge")]:
            with self.assertLogs("uvicorn.error.integrity_monitor", level="INFO") as logs:
                response = self.client.post("/me/integrity-monitor/verdict", headers=self.headers,
                    json={"challenge": value})
            self.assertEqual(200, response.status_code)
            self.assertEqual({"accepted": True, "mode": "monitor_only", "status": status}, response.json())
            self.assertNotIn(value, str(logs.output))
            self.assertNotIn("user-a", str(logs.output))

    def test_rate_limit_prevents_unbounded_challenges(self):
        for _ in range(8):
            self.assertEqual(200, self.client.post("/me/integrity-monitor/challenge", headers=self.headers,
                json={"event_type": "login"}).status_code)
        self.assertEqual(429, self.client.post("/me/integrity-monitor/challenge", headers=self.headers,
            json={"event_type": "login"}).status_code)

if __name__ == "__main__": unittest.main()
