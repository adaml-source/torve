"""
Unit + integration tests for the signup typo guard.

The real-world case that triggered this module: a user registered with
`leohartley@github.coml`. The verification email went to nowhere; the
account stayed stuck as `is_verified=false` forever. Unit tests here
pin the specific patterns we've actually seen plus close neighbours.
Integration tests pin that the /auth/signup endpoint returns a clean
400 with a suggestion, not a 500 and not an opaque generic error.
"""
from __future__ import annotations

import uuid as _uuid

import pytest

from app.email_typo_guard import suspect_typo


# ── Unit: trailing-TLD typos ──────────────────────────────────────────


@pytest.mark.parametrize("bad,good", [
    ("user@github.coml",       "user@github.com"),
    ("someone@example.con",    "someone@example.com"),
    ("a.b@domain.cmo",         "a.b@domain.com"),
    ("x@y.cim",                "x@y.com"),
    ("name@site.ccom",         "name@site.com"),
    ("name@site.conm",         "name@site.com"),
    ("q@r.neet",               "q@r.net"),
    ("q@r.ne",                 "q@r.net"),
    ("q@r.orgg",               "q@r.org"),
    ("q@r.og",                 "q@r.org"),
    ("q@r.ogr",                "q@r.org"),
])
def test_tld_typos_caught(bad: str, good: str) -> None:
    assert suspect_typo(bad) == good


# ── Unit: popular-provider typos ──────────────────────────────────────


@pytest.mark.parametrize("bad,good", [
    ("me@gmai.com",    "me@gmail.com"),
    ("me@gmial.com",   "me@gmail.com"),
    ("me@gmil.com",    "me@gmail.com"),
    ("me@gnail.com",   "me@gmail.com"),
    ("me@gamil.com",   "me@gmail.com"),
    ("me@yaho.com",    "me@yahoo.com"),
    ("me@yahooo.com",  "me@yahoo.com"),
    ("me@hotmial.com", "me@hotmail.com"),
    ("me@hotmil.com",  "me@hotmail.com"),
    ("me@otlook.com",  "me@outlook.com"),
    ("me@outlok.com",  "me@outlook.com"),
    ("me@iclould.com", "me@icloud.com"),
])
def test_provider_typos_caught(bad: str, good: str) -> None:
    assert suspect_typo(bad) == good


# ── Unit: legitimate emails pass ──────────────────────────────────────


@pytest.mark.parametrize("good", [
    "user@gmail.com",
    "user@yahoo.com",
    "user@outlook.com",
    "user@icloud.com",
    "user@github.com",
    "user@proton.me",
    # Obscure-but-legitimate TLDs must pass.
    "user@example.museum",
    "user@example.travel",
    "user@example.co.uk",
    "user@example.io",
    "user@example.dev",
    "user@example.ai",
    "user@example.app",
    # Corporate subdomain cases.
    "user@corp.example.com",
    "user@mail.example.com",
])
def test_valid_emails_pass(good: str) -> None:
    assert suspect_typo(good) is None


# ── Unit: malformed input is safe ─────────────────────────────────────


@pytest.mark.parametrize("bad", [
    "",
    "   ",
    "no-at-sign",
    "@nolocalpart.com",
    "user@",
    "user@@double",
])
def test_malformed_input_returns_none(bad: str) -> None:
    assert suspect_typo(bad) is None


# ── Unit: idempotency — suggested correction itself passes ───────────


def test_correction_is_itself_valid() -> None:
    assert suspect_typo("user@github.coml") == "user@github.com"
    assert suspect_typo("user@github.com") is None


# ── Integration: /auth/signup returns a clean 400 with suggestion ────


def test_signup_rejects_typo_with_400_and_suggestion(client) -> None:
    r = client.post("/auth/signup", json={
        "email": f"leo-{_uuid.uuid4().hex[:8]}@github.coml",
        "password": "TestPass123!",
        "display_name": "Leo",
    })
    assert r.status_code == 400, r.text
    body = r.json()
    assert body["detail"]["code"] == "email_typo_suspected"
    assert body["detail"]["suggestion"].endswith("@github.com")
    assert "typo" in body["detail"]["message"].lower()


def test_signup_accepts_legitimate_email(client, db) -> None:
    email = f"ok-{_uuid.uuid4().hex[:8]}@example.com"
    r = client.post("/auth/signup", json={
        "email": email,
        "password": "TestPass123!",
        "display_name": "Legit",
    })
    assert r.status_code == 201, r.text
    # Cleanup
    from app.models import User, EmailVerificationToken, RefreshToken
    uid = r.json()["user"]["id"]
    db.query(RefreshToken).filter(RefreshToken.user_id == uid).delete()
    db.query(EmailVerificationToken).filter(EmailVerificationToken.user_id == uid).delete()
    db.query(User).filter(User.id == uid).delete()
    db.commit()


def test_signup_rejects_gmail_typo(client) -> None:
    r = client.post("/auth/signup", json={
        "email": f"me-{_uuid.uuid4().hex[:8]}@gmial.com",
        "password": "TestPass123!",
        "display_name": "Gmail Typo",
    })
    assert r.status_code == 400
    assert r.json()["detail"]["suggestion"].endswith("@gmail.com")
