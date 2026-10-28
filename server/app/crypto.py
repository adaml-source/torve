"""
Symmetric encryption for integration secrets at rest.

Uses Fernet (AES-128-CBC + HMAC-SHA256) from the cryptography library.
The key is loaded from INTEGRATION_SECRET_KEY env var.

Supports key rotation via MultiFernet: set INTEGRATION_SECRET_KEY to the
current key, and INTEGRATION_SECRET_KEY_PREVIOUS to the old key. Encryption
always uses the current key; decryption tries both. After re-encrypting all
rows with the new key, the old key can be removed.

Ciphertexts are prefixed with "v1:" for version tracking. Legacy ciphertexts
without prefix are treated as v1 for backward compatibility.
"""

import logging

from cryptography.fernet import Fernet, InvalidToken, MultiFernet

from app.config import settings

_log = logging.getLogger(__name__)

_VERSION_PREFIX = "v1:"
_fernet: Fernet | MultiFernet | None = None


def _get_fernet() -> Fernet | MultiFernet:
    global _fernet
    if _fernet is None:
        key = settings.INTEGRATION_SECRET_KEY
        if not key:
            raise RuntimeError(
                "INTEGRATION_SECRET_KEY is not set. "
                "Generate one with: python3 -c 'from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())'"
            )
        current = Fernet(key.encode())
        # Support key rotation: if a previous key is configured, try it on decrypt
        prev_key = getattr(settings, "INTEGRATION_SECRET_KEY_PREVIOUS", "")
        if prev_key:
            previous = Fernet(prev_key.encode())
            _fernet = MultiFernet([current, previous])
            _log.info("Crypto initialized with current + previous key (rotation mode)")
        else:
            _fernet = current
    return _fernet


def encrypt_secret(plaintext: str) -> str:
    """Encrypt a secret string. Returns a versioned ciphertext string."""
    raw = _get_fernet().encrypt(plaintext.encode()).decode()
    return _VERSION_PREFIX + raw


def decrypt_secret(ciphertext: str) -> str:
    """Decrypt a previously encrypted secret string.

    Handles both versioned (v1:...) and legacy (no prefix) ciphertexts.
    """
    # Strip version prefix if present
    if ciphertext.startswith(_VERSION_PREFIX):
        ciphertext = ciphertext[len(_VERSION_PREFIX):]
    try:
        return _get_fernet().decrypt(ciphertext.encode()).decode()
    except InvalidToken:
        _log.error("Failed to decrypt integration secret (invalid token or key)")
        raise ValueError("Failed to decrypt secret. The encryption key may have changed.")
