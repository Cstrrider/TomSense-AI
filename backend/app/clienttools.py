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

# call_id -> Future awaiting the device's result.
_PENDING: dict[str, asyncio.Future] = {}


async def request_client_tool(call_id: str, timeout: float = 120.0) -> str:
    """Register a pending client-tool call and block until the device POSTs
    its result to /chat/tool_result, or the timeout elapses.

    Always returns a string (the tool result the model reads) — on timeout or
    cancellation it returns an explanatory message so the model can react
    gracefully rather than the turn erroring out.
    """
    loop = asyncio.get_running_loop()
    fut: asyncio.Future = loop.create_future()
    _PENDING[call_id] = fut
    try:
        return await asyncio.wait_for(fut, timeout=timeout)
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
        _PENDING.pop(call_id, None)


def resolve_client_tool(call_id: str, result: str) -> bool:
    """Called by POST /chat/tool_result — wakes the awaiting run_chat.

    Returns False when no call is pending for that id (already resolved,
    timed out, or never existed).
    """
    fut = _PENDING.get(call_id)
    if fut is None or fut.done():
        return False
    fut.set_result(result)
    return True
