"""Rendezvous for client-fulfilled tools.

Some tools — location, calendar, health — can only run on the user's device.
The model calls them like any other tool; `run_chat` emits a `client_tool`
SSE event, the browser/app executes the capability and POSTs the result back
to `/chat/tool_result`, and `run_chat` resumes — all inside the one streaming
request (the SSE keepalive holds the connection open while we wait).

The backend runs as a single uvicorn process (see Dockerfile — no `--workers`),
so an in-memory dict of pending futures is sufficient; the SSE request and the
`/chat/tool_result` request share one event loop.
"""

import asyncio
import uuid
from dataclasses import dataclass
from typing import Optional

# Tools the model may call but the SERVER never executes — they are fulfilled
# by the device. Kept here (not tools.py DISPATCH) so chat.py can intercept
# them before the normal server-side dispatch path.
CLIENT_TOOL_NAMES = frozenset({
    "get_location", "get_calendar", "get_health",
    "create_calendar_event", "set_reminder", "start_timer", "set_alarm",
    # Android device-action tools
    "launch_app", "make_call", "send_sms", "open_maps",
    "open_url", "share_text", "get_contacts", "open_settings",
    "set_volume", "set_brightness", "media_control",
    "get_device_status", "play_music",
})

@dataclass
class _Pending:
    future: asyncio.Future
    # Who is allowed to answer this call. The approve_edit / deploy_project
    # gates ride the same rendezvous, so answering someone else's call means
    # approving their live rebuild — the ticket must be owner-bound.
    user_id: Optional[str]
    run_id: Optional[str]


# ticket -> pending call. The ticket is minted HERE, server-side, and is the
# only id the device ever sees. Deliberately NOT the model-supplied tool-call
# id: those are `call_0`, `call_1`, `recovered_write_file`, … so two concurrent
# runs collide and one turn's approval resolves the other's pending edit.
_PENDING: dict[str, _Pending] = {}


def open_client_call(user_id: Optional[str] = None, run_id: Optional[str] = None) -> str:
    """Register a pending client-tool call and return its ticket.

    Split from the await so the caller can put the ticket into the SSE event
    before blocking on the answer, with no window where the device could reply
    to an unregistered ticket.
    """
    loop = asyncio.get_running_loop()
    ticket = uuid.uuid4().hex
    _PENDING[ticket] = _Pending(loop.create_future(), user_id, run_id)
    return ticket


async def await_client_call(ticket: str, timeout: float = 120.0) -> str:
    """Block until the device POSTs a result for `ticket`, or time out.

    Always returns a string (the tool result the model reads) — on timeout or
    cancellation it returns an explanatory message so the model can react
    gracefully rather than the turn erroring out.
    """
    pending = _PENDING.get(ticket)
    if pending is None:
        return "(Internal error: this tool call was never registered.)"
    try:
        return await asyncio.wait_for(pending.future, timeout=timeout)
    except asyncio.TimeoutError:
        return (
            "(No response from the user's device — this tool is unavailable "
            "right now. Tell the user you couldn't reach it and ask them to "
            "check the app's permissions.)"
        )
    except asyncio.CancelledError:
        # SSE connection dropped before the device answered.
        return "(The request was cancelled before the device responded.)"
    finally:
        _PENDING.pop(ticket, None)


def resolve_client_tool(
    ticket: str, result: str, user_id: Optional[str] = None
) -> bool:
    """Called by POST /chat/tool_result — wakes the awaiting run_chat.

    Returns False when no call is pending for that ticket (already resolved,
    timed out, never existed) or when `user_id` isn't the caller who opened
    it. A mismatch is reported as "not pending" rather than "forbidden" so a
    guessed ticket can't be distinguished from an expired one.
    """
    pending = _PENDING.get(ticket)
    if pending is None or pending.future.done():
        return False
    if pending.user_id is not None and user_id != pending.user_id:
        return False
    pending.future.set_result(result)
    return True
