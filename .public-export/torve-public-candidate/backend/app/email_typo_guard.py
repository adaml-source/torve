"""
Signup-time email typo guard.

Pydantic's EmailStr only enforces RFC-ish syntax — it happily accepts
`user@github.coml` or `user@gmial.com`. The cost of letting a typo
through is real: the verification email never arrives, the account
stays unverified forever, the user eventually rage-quits.

This module catches the narrow set of typos we've actually observed
in production. It is NOT a full email-address-correction engine —
no Levenshtein against the IANA TLD list, no DNS/MX probing. We
prefer false negatives (let the email pass, verification catches it)
over false positives (reject a legitimate `user@museum.travel`).

Returns a suggested correction when the email matches a known-typo
pattern, so the signup handler can include it in the 400 response
("It looks like there may be a typo — did you mean ... ?").
"""
from __future__ import annotations

import re

# Common TLD typos. Key = the bad suffix; value = the intended one.
# Matches case-insensitively and only if the suffix is the LAST
# dot-separated component of the domain.
_TLD_TYPOS: dict[str, str] = {
    "coml": "com",
    "con": "com",
    "cmo": "com",
    "cim": "com",
    "ccom": "com",
    "conm": "com",
    "neet": "net",
    "ne": "net",
    "nt": "net",
    "orgg": "org",
    "og": "org",
    "ogr": "org",
    "ca.com": "com",        # ".ca.com" misfire
}

# Common popular-provider typos. Key = exact bad domain; value = the
# intended one. Full-string match on the domain (case-insensitive).
_PROVIDER_TYPOS: dict[str, str] = {
    "gmai.com": "gmail.com",
    "gmial.com": "gmail.com",
    "gmil.com": "gmail.com",
    "gnail.com": "gmail.com",
    "gmaill.com": "gmail.com",
    "gmeil.com": "gmail.com",
    "gamil.com": "gmail.com",
    "yaho.com": "yahoo.com",
    "yahooo.com": "yahoo.com",
    "yaoo.com": "yahoo.com",
    "hotmial.com": "hotmail.com",
    "hotmai.com": "hotmail.com",
    "hotmeil.com": "hotmail.com",
    "hotmil.com": "hotmail.com",
    "otlook.com": "outlook.com",
    "outloo.com": "outlook.com",
    "outlok.com": "outlook.com",
    "iclould.com": "icloud.com",
    "iclod.com": "icloud.com",
    "icloud.co": "icloud.com",
    "gmx.con": "gmx.com",
    "proton.con": "proton.me",
}

_EMAIL_RE = re.compile(r"^[^@\s]+@([^@\s]+)$")


def suspect_typo(email: str) -> str | None:
    """Return a corrected email if the input matches a known typo
    pattern, otherwise None.

    Idempotent — a corrected email passes through as None (no further
    correction), so calling this twice is safe.
    """
    if not email:
        return None
    m = _EMAIL_RE.match(email.strip())
    if not m:
        return None
    local = email.strip().split("@", 1)[0]
    domain = m.group(1).lower()

    # Full-domain match (gmai.com → gmail.com) wins over TLD-only match.
    if domain in _PROVIDER_TYPOS:
        return f"{local}@{_PROVIDER_TYPOS[domain]}"

    # Trailing-component TLD typo (github.coml → github.com).
    parts = domain.rsplit(".", 1)
    if len(parts) == 2:
        base, tld = parts
        if tld in _TLD_TYPOS:
            return f"{local}@{base}.{_TLD_TYPOS[tld]}"

    return None
