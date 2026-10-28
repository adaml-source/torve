"""Temporary, single-use TorBox NNTP credential entry on the user's own server.

Run behind the existing HTTPS proxy. The listener is loopback-only; the secret
URL expires and successful submission consumes it. No request/password logging.
"""
from __future__ import annotations

import hashlib
import hmac
import html
import json
import os
from pathlib import Path
import socket
import ssl
import sys
import tempfile
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs


def verify_nntp(username: str, password: str) -> bool:
    with socket.create_connection(("nntp.torbox.app", 563), timeout=8) as raw:
        with ssl.create_default_context().wrap_socket(raw, server_hostname="nntp.torbox.app") as conn:
            with conn.makefile("rwb") as stream:
                def command(value: str | None = None) -> bytes:
                    if value is not None:
                        stream.write((value + "\r\n").encode("utf-8"))
                        stream.flush()
                    return stream.readline(2048)[:3]

                if command() not in (b"200", b"201"):
                    return False
                code = command("AUTHINFO USER " + username)
                if code == b"381":
                    code = command("AUTHINFO PASS " + password)
                accepted = code == b"281"
                command("QUIT")
                return accepted


def atomic_private_json(path: Path, data: dict) -> None:
    descriptor, temporary = tempfile.mkstemp(prefix=".setup-", dir=path.parent)
    try:
        os.chmod(temporary, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as file:
            json.dump(data, file)
            file.flush()
            os.fsync(file.fileno())
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)


class SetupState:
    def __init__(self, settings: dict, verify=verify_nntp):
        self.settings = settings
        self.verify = verify
        self.attempts = 0
        self.last_attempt = 0.0
        self.received = Path(settings["receiptPath"])

    def allowed(self, path: str) -> bool:
        prefix = "/usenet-setup/"
        if not path.startswith(prefix) or "?" in path:
            return False
        digest = hashlib.sha256(path[len(prefix):].encode()).hexdigest()
        return hmac.compare_digest(digest, self.settings["tokenSha256"])

    def expired(self) -> bool:
        return time.time() >= self.settings["expiresAt"]

    def save(self, password: str) -> None:
        path = Path(self.settings["providerConfigPath"])
        config = json.loads(path.read_text())
        provider = config["providers"][0]
        assert provider["id"] == "torbox" and provider["host"] == "nntp.torbox.app"
        provider.update({"username": self.settings["username"], "password": password,
                         "port": 563, "tls": True, "insecure_tls": False, "enabled": True})
        atomic_private_json(path, config)
        atomic_private_json(self.received, {"received": True, "nntpAuthenticated": True,
                                           "receivedAt": int(time.time())})


def handler_for(state: SetupState):
    class Handler(BaseHTTPRequestHandler):
        server_version = "TorveSetup"
        sys_version = ""

        def log_message(self, *args):
            pass

        def reply(self, status: int, message: str, form=False):
            form_html = ""
            if form:
                form_html = f"""<form method="post" autocomplete="off">
<input type="hidden" name="csrf" value="{html.escape(state.settings['csrf'])}">
<label for="password">TorBox Usenet password</label>
<input id="password" name="password" type="password" autocomplete="off" required maxlength="256" autocapitalize="none" spellcheck="false">
<button type="submit">Connect TorBox</button></form>
<p class="hint">Use the password shown under TorBox Tools → View Usenet Connection Details.</p>"""
            document = f"""<!doctype html><html lang="en"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Torve · Connect TorBox</title>
<style>body{{margin:0;background:#101114;color:#f4f4f5;font:17px/1.5 system-ui,sans-serif}}main{{max-width:440px;margin:8vh auto;padding:28px}}h1{{font-size:28px;line-height:1.2}}p{{color:#c2c4ca}}label{{display:block;margin:26px 0 8px}}input[type=password]{{box-sizing:border-box;width:100%;padding:14px;border:1px solid #626570;border-radius:10px;background:#202228;color:white;font:inherit}}button{{width:100%;margin-top:16px;padding:14px;border:0;border-radius:10px;background:#ffcf3e;color:#171715;font:600 17px system-ui;cursor:pointer}}.hint{{font-size:14px}}.brand{{color:#ffcf3e;font-weight:700;letter-spacing:3px}}</style>
<main><div class="brand">TORVE</div><h1>Connect TorBox streaming</h1>
<p role="status">{html.escape(message)}</p>{form_html}
<p class="hint">Your password goes directly to your Torve server. This link expires after 20 minutes and works once.</p></main></html>""".encode()
            self.send_response(status)
            for key, value in {
                "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store, private",
                "Referrer-Policy": "no-referrer", "X-Content-Type-Options": "nosniff",
                "X-Frame-Options": "DENY", "Connection": "close",
                "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'",
                "Content-Length": str(len(document)),
            }.items():
                self.send_header(key, value)
            self.end_headers()
            self.wfile.write(document)
            self.close_connection = True

        def gate(self) -> bool:
            if not state.allowed(self.path):
                self.reply(404, "Page not found.")
                return False
            if state.expired():
                self.reply(410, "This setup link has expired. Ask for a new link.")
                return False
            if state.received.exists():
                self.reply(200 if self.command == "GET" else 410,
                           "TorBox accepted your password. You can return to the conversation; playback testing is next.")
                return False
            return True

        def do_GET(self):
            if self.gate():
                self.reply(200, "Paste your TorBox Usenet password to enable the streaming test.", form=True)

        def do_POST(self):
            if not self.gate():
                return
            if self.headers.get("Origin") != state.settings["origin"]:
                self.reply(403, "Please submit from this setup page.")
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
            except ValueError:
                length = 0
            if not 0 < length <= 2048 or self.headers.get_content_type() != "application/x-www-form-urlencoded":
                self.reply(400, "Invalid form submission.")
                return
            try:
                values = parse_qs(self.rfile.read(length).decode("utf-8"), strict_parsing=True, max_num_fields=3)
                csrf = values["csrf"][0]
                password = values["password"][0]
                if len(values["password"]) != 1 or not hmac.compare_digest(csrf, state.settings["csrf"]):
                    raise ValueError()
            except (ValueError, KeyError, UnicodeError, TypeError):
                self.reply(400, "Please reload the page and try again.")
                return
            if not 1 <= len(password) <= 256 or "\r" in password or "\n" in password or password in ("********", "[redacted]"):
                self.reply(400, "Paste the actual Usenet password rather than the hidden placeholder.", form=True)
                return
            if state.attempts >= 5 or time.monotonic() - state.last_attempt < 5:
                self.reply(429, "Please wait a moment before trying again.", form=True)
                return
            state.attempts += 1
            state.last_attempt = time.monotonic()
            try:
                accepted = state.verify(state.settings["username"], password)
            except Exception:
                self.reply(502, "TorBox could not be reached. Your password has not been saved.", form=True)
                return
            if not accepted:
                self.reply(400, "TorBox did not accept that Usenet password. Please check it and try again.", form=True)
                return
            try:
                state.save(password)
            except Exception:
                self.reply(500, "The server could not save the connection. Please return to the conversation.")
                return
            self.reply(200, "TorBox accepted your password. You can return to the conversation; playback testing is next.")

    return Handler


if __name__ == "__main__":
    settings = json.loads(Path(sys.argv[1]).read_text())
    server = HTTPServer(("127.0.0.1", 8099), handler_for(SetupState(settings)))
    server.timeout = 10
    while time.time() < settings["expiresAt"] + 60:
        server.handle_request()
    server.server_close()
