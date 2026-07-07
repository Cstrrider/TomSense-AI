"""Symmetric encryption-at-rest for API keys stored in Postgres.

Uses Fernet (AES-128-CBC + HMAC-SHA256 from `cryptography.fernet`). The master
key lives in env (`TOMSENSE_ENCRYPTION_KEY`) — kept out of the database so a
plain DB dump doesn't yield readable secrets.

Output format: `fernet:<urlsafe-base64-ciphertext>`. The prefix lets
`decrypt()` distinguish encrypted values from pre-S5 plaintext rows, so the
column can hold a mix during migration without a schema-wide flag.

Behavior when no key is configured: encrypt() returns the plaintext
unchanged, so dev environments without a key keep working. A loud startup
warning fires in main.py to surface this.
"""

import logging
from typing import Optional

from cryptography.fernet import Fernet, InvalidToken

from .config import settings


log = logging.getLogger("tomsense")

_PREFIX = "fernet:"
_cached: Optional[Fernet] = None
# Set when a NON-EMPTY key failed to parse — distinguishes "operator never
# configured a key" (dev, warned at startup) from "key is set but broken"
# (would silently store secrets in plaintext without a loud error).
_key_invalid = False


def _fernet() -> Optional[Fernet]:
    global _cached, _key_invalid
    if _cached is not None:
        return _cached
    if _key_invalid:
        return None
    key = (settings.encryption_key or "").strip()
    if not key:
        return None
    try:
        _cached = Fernet(key.encode() if isinstance(key, str) else key)
    except Exception as e:
        _key_invalid = True
        log.error(
            "SECURITY: TOMSENSE_ENCRYPTION_KEY is set but INVALID (%s) — "
            "stored API keys will be written in PLAINTEXT until fixed. "
            "Expected a Fernet key: openssl rand 32 | base64 | tr '+/' '-_'",
            e,
        )
    return _cached


def is_configured() -> bool:
    return _fernet() is not None


def key_is_invalid() -> bool:
    """True when a non-empty TOMSENSE_ENCRYPTION_KEY failed to parse."""
    _fernet()  # ensure the key has been evaluated
    return _key_invalid


def encrypt(plaintext: str) -> str:
    """Wrap `plaintext` for DB storage. Empty / None passes through. If no
    master key is configured, returns plaintext unchanged so dev still works
    (a warning fires at startup to flag this)."""
    if not plaintext:
        return plaintext
    if plaintext.startswith(_PREFIX):
        return plaintext  # already encrypted
    f = _fernet()
    if not f:
        return plaintext
    return _PREFIX + f.encrypt(plaintext.encode()).decode()


def decrypt(stored: str) -> str:
    """Unwrap a DB value. Pre-S5 plaintext rows (no prefix) pass through
    as-is, so the column can be migrated lazily."""
    if not stored or not stored.startswith(_PREFIX):
        return stored
    f = _fernet()
    if not f:
        # Encrypted blob but no key to decrypt — surface as the literal
        # ciphertext so the caller's API request will obviously fail
        # rather than silently succeeding with the wrong value.
        return stored
    try:
        return f.decrypt(stored[len(_PREFIX):].encode()).decode()
    except InvalidToken:
        return stored
