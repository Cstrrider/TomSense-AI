"""Push notifications via ntfy (https://ntfy.sh or self-hosted).

Setup: install the ntfy Android app, subscribe to a random secret topic,
then set in .env:
    NTFY_URL=https://ntfy.sh          (or your self-hosted instance)
    NTFY_TOPIC=tomsense-<random-secret>
    NTFY_TOKEN=                       (optional bearer token, self-hosted auth)

All sends are best-effort fire-and-forget — a notification must never break
the flow that triggered it. No-op when NTFY_TOPIC is unset.
"""

import logging
import os

import httpx

log = logging.getLogger("tomsense")

NTFY_URL = os.getenv("NTFY_URL", "https://ntfy.sh").rstrip("/")
NTFY_TOPIC = os.getenv("NTFY_TOPIC", "").strip()
NTFY_TOKEN = os.getenv("NTFY_TOKEN", "").strip()


def enabled() -> bool:
    return bool(NTFY_TOPIC)


async def push(title: str, body: str = "", *, priority: str = "default",
               tags: str = "", click_url: str = "",
               user_id: str = "", click_path: str = "") -> None:
    """Send one notification. Always queues in-app (when user_id is known) —
    the Capacitor app drains the queue on open/resume and shows local
    notifications. Additionally posts to ntfy when configured (closed-app
    delivery). Logs (never raises) on failure."""
    if user_id:
        try:
            from . import db
            await db.add_app_notification(user_id, title, body, click_path or None)
        except Exception as e:
            log.warning("app notification queue failed: %s", e)
    if not NTFY_TOPIC:
        return
    headers = {"Title": title[:200], "Priority": priority}
    if tags:
        headers["Tags"] = tags
    if click_url:
        headers["Click"] = click_url
    if NTFY_TOKEN:
        headers["Authorization"] = f"Bearer {NTFY_TOKEN}"
    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            r = await client.post(
                f"{NTFY_URL}/{NTFY_TOPIC}",
                content=(body or title)[:4000].encode(),
                headers=headers,
            )
        if r.status_code >= 400:
            log.warning("ntfy push failed: %s %s", r.status_code, r.text[:200])
    except Exception as e:
        log.warning("ntfy push failed: %s", e)
