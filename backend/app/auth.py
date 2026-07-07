"""Authentication shim.

In production, Cloudflare Access sits in front of the tunnel and authenticates
the user before any request reaches us. Successful auth injects:
    Cf-Access-Authenticated-User-Email: user@example.com
    Cf-Access-Jwt-Assertion: <signed JWT>

We trust those headers because:
 - cloudflared only adds them after CF Access validates the user
 - the backend isn't exposed on the public internet; the only path in is
   through the tunnel (and through nginx, which passes the headers through)

For local development (no CF Access in front), we fall back to LOCAL_DEV_EMAIL
so the app still works without you having to wire CF Access for everyday work.
"""

import os
from typing import Optional

from fastapi import Header, HTTPException

from . import db


def _local_dev_email() -> str:
    return os.getenv("LOCAL_DEV_EMAIL", "local@dev").strip().lower()


def _extract_email(
    cf_access_email: Optional[str],
    legacy_auth: Optional[str],
) -> Optional[str]:
    if cf_access_email:
        email = cf_access_email.strip().lower()
        if "@" in email:
            return email
    # Allow disabling local-dev fallback in prod by setting REQUIRE_CF_ACCESS=1.
    if os.getenv("REQUIRE_CF_ACCESS", "").lower() in ("1", "true", "yes"):
        return None
    return _local_dev_email()


async def current_user(
    cf_access_email: Optional[str] = Header(default=None, alias="Cf-Access-Authenticated-User-Email"),
    cf_access_email_alt: Optional[str] = Header(default=None, alias="cf-access-authenticated-user-email"),
    authorization: Optional[str] = Header(default=None),
) -> dict:
    """FastAPI dependency. Returns the user dict, creating on first sight."""
    email = _extract_email(cf_access_email or cf_access_email_alt, authorization)
    if not email:
        raise HTTPException(status_code=401, detail="authentication required")
    user = await db.get_or_create_user(email)
    # Roll over monthly counter if needed
    fresh = await db.get_user_with_rollover(user["id"])
    return fresh or user
