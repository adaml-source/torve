"""Pins the LAN-secret wrap contract (Prompt 12 hardening).

The wrap layer's job is twofold: encrypt at rest when configured, and
fail loud (not silent) when production is missing a key. Without that,
a broken config silently downgrades to plaintext or to 401 loops.
"""
from __future__ import annotations

import importlib

import pytest
from cryptography.fernet import Fernet


def _fresh_module(monkeypatch, *, key: str | None, env: str | None) -> object:
    if key is None:
        monkeypatch.delenv("TORVE_LAN_SECRET_WRAP_KEY", raising=False)
    else:
        monkeypatch.setenv("TORVE_LAN_SECRET_WRAP_KEY", key)
    if env is None:
        monkeypatch.delenv("TORVE_ENV", raising=False)
    else:
        monkeypatch.setenv("TORVE_ENV", env)
    # Re-import so the module's process-lifetime warning latch resets.
    import app.secret_wrap as mod  # type: ignore[import-not-found]

    importlib.reload(mod)
    return mod


def test_round_trip_encrypts_with_fernet(monkeypatch) -> None:
    key = Fernet.generate_key().decode()
    mod = _fresh_module(monkeypatch, key=key, env="prod")
    wrapped = mod.wrap("super-secret-AAAA")
    assert wrapped.startswith(mod.WRAP_PREFIX), "wrapped values must be tagged"
    assert "super-secret-AAAA" not in wrapped, "ciphertext must not contain the plaintext"
    assert mod.unwrap(wrapped) == "super-secret-AAAA"


def test_unwrap_passes_through_legacy_plaintext(monkeypatch) -> None:
    # Existing dev DBs may have plaintext rows. Unwrap must not error
    # on them — it returns the value unchanged so reads keep working.
    key = Fernet.generate_key().decode()
    mod = _fresh_module(monkeypatch, key=key, env=None)
    legacy = "round-trip-secret-no-prefix"
    assert mod.unwrap(legacy) == legacy


def test_production_without_key_refuses_writes(monkeypatch) -> None:
    mod = _fresh_module(monkeypatch, key=None, env="prod")
    with pytest.raises(mod.WrapUnavailable):
        mod.wrap("x")


def test_dev_without_key_falls_through_to_plaintext(monkeypatch) -> None:
    mod = _fresh_module(monkeypatch, key=None, env=None)
    out = mod.wrap("dev-secret")
    assert out == "dev-secret", "dev mode keeps plaintext for working local DBs"
    assert not out.startswith(mod.WRAP_PREFIX)


def test_unwrap_with_no_key_but_wrapped_value_fails_loud(monkeypatch) -> None:
    # The DB has wrapped rows but this process has no key — config bug.
    # Must raise rather than silently returning ciphertext as if it were
    # the secret.
    key = Fernet.generate_key().decode()
    mod = _fresh_module(monkeypatch, key=key, env=None)
    wrapped = mod.wrap("the-secret")
    # Now drop the key.
    mod = _fresh_module(monkeypatch, key=None, env=None)
    with pytest.raises(mod.WrapUnavailable):
        mod.unwrap(wrapped)


def test_wrong_key_on_unwrap_raises(monkeypatch) -> None:
    # Key rotation without re-wrap migration → unwrap with the new key
    # must raise rather than return garbage.
    old = Fernet.generate_key().decode()
    new = Fernet.generate_key().decode()
    mod = _fresh_module(monkeypatch, key=old, env=None)
    wrapped = mod.wrap("rotation-test")
    mod = _fresh_module(monkeypatch, key=new, env="prod")
    with pytest.raises(mod.WrapUnavailable):
        mod.unwrap(wrapped)


def test_invalid_key_in_production_fails_to_load(monkeypatch) -> None:
    # A malformed wrap key in production must raise on first use
    # rather than silently fall through to plaintext.
    mod = _fresh_module(monkeypatch, key="not-a-valid-fernet-key", env="prod")
    with pytest.raises(RuntimeError):
        mod.wrap("x")


def test_invalid_key_in_dev_logs_and_falls_through(monkeypatch) -> None:
    mod = _fresh_module(monkeypatch, key="not-a-valid-fernet-key", env=None)
    # Should NOT raise in dev; falls back to plaintext store.
    assert mod.wrap("dev-fallback") == "dev-fallback"


def test_wrap_unwrap_handles_empty_and_none(monkeypatch) -> None:
    key = Fernet.generate_key().decode()
    mod = _fresh_module(monkeypatch, key=key, env="prod")
    assert mod.wrap("") == ""
    assert mod.unwrap(None) is None
    assert mod.unwrap("") == ""


def test_is_wrap_configured_reports_state(monkeypatch) -> None:
    mod = _fresh_module(monkeypatch, key=None, env=None)
    assert mod.is_wrap_configured() is False
    key = Fernet.generate_key().decode()
    mod = _fresh_module(monkeypatch, key=key, env=None)
    assert mod.is_wrap_configured() is True
