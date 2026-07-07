"""MCP (Model Context Protocol) client — streamable HTTP transport.

Users register remote MCP servers (Settings → MCP servers); their tools are
merged into the chat model's tool list as `mcp__<slug>__<tool>` and dispatched
back over JSON-RPC. Covers the modern "streamable HTTP" transport: plain JSON
responses AND single-shot SSE responses (data: lines). Long-lived SSE server
push is NOT supported — tools are request/response here.

Handshake per the spec: initialize → notifications/initialized, carrying the
Mcp-Session-Id header when the server issues one. Sessions and tool lists are
cached for 5 minutes per server.
"""

import json
import logging
import re
import time
from typing import Any, Optional

import httpx

log = logging.getLogger("tomsense")

_PROTOCOL_VERSION = "2025-03-26"
_CACHE_TTL = 300.0
_TIMEOUT = httpx.Timeout(30.0, connect=10.0)

# server_id → {"at": monotonic, "session": str|None, "tools": [...]}
_CACHE: dict[str, dict] = {}


def slugify(name: str) -> str:
    """Server name → tool-name-safe slug (mcp__<slug>__<tool>)."""
    return re.sub(r"[^a-z0-9]+", "_", (name or "srv").lower()).strip("_")[:24] or "srv"


def _headers(server: dict, session: Optional[str] = None) -> dict:
    h = {
        "Content-Type": "application/json",
        # Streamable HTTP requires the client to accept both response modes.
        "Accept": "application/json, text/event-stream",
    }
    if server.get("auth_header"):
        h["Authorization"] = server["auth_header"]
    if session:
        h["Mcp-Session-Id"] = session
    return h


def _parse_response(r: httpx.Response, want_id: int) -> Optional[dict]:
    """JSON-RPC result from either a JSON body or a single-shot SSE stream."""
    ctype = r.headers.get("content-type", "")
    if "text/event-stream" in ctype:
        # Take the last data: line whose JSON id matches the request.
        result = None
        for line in r.text.splitlines():
            if not line.startswith("data:"):
                continue
            try:
                msg = json.loads(line[5:].strip())
            except json.JSONDecodeError:
                continue
            if msg.get("id") == want_id:
                result = msg
        return result
    try:
        body = r.json()
    except Exception:
        return None
    if isinstance(body, list):  # batched
        for msg in body:
            if msg.get("id") == want_id:
                return msg
        return None
    return body


async def _rpc(client: httpx.AsyncClient, server: dict, method: str,
               params: Optional[dict] = None, *, rpc_id: int = 1,
               session: Optional[str] = None,
               notification: bool = False) -> tuple[Optional[dict], Optional[str]]:
    """One JSON-RPC exchange. Returns (result_message, session_id_seen)."""
    payload: dict[str, Any] = {"jsonrpc": "2.0", "method": method}
    if params is not None:
        payload["params"] = params
    if not notification:
        payload["id"] = rpc_id
    r = await client.post(server["url"], json=payload,
                          headers=_headers(server, session))
    new_session = r.headers.get("mcp-session-id") or session
    if notification:
        return None, new_session
    if r.status_code >= 400:
        raise RuntimeError(f"MCP {method} → HTTP {r.status_code}: {r.text[:200]}")
    msg = _parse_response(r, rpc_id)
    if msg is None:
        raise RuntimeError(f"MCP {method}: unparsable response")
    if msg.get("error"):
        raise RuntimeError(f"MCP {method}: {msg['error'].get('message', msg['error'])}")
    return msg, new_session


async def _connect_and_list(server: dict) -> dict:
    """initialize → initialized → tools/list. Returns cache entry."""
    async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
        init, session = await _rpc(client, server, "initialize", {
            "protocolVersion": _PROTOCOL_VERSION,
            "capabilities": {},
            "clientInfo": {"name": "tomsense", "version": "1.0"},
        }, rpc_id=1)
        try:
            await _rpc(client, server, "notifications/initialized",
                       {}, session=session, notification=True)
        except Exception:
            pass  # some servers don't require it
        listing, session = await _rpc(client, server, "tools/list", {},
                                      rpc_id=2, session=session)
        tools = ((listing or {}).get("result") or {}).get("tools") or []
    return {"at": time.monotonic(), "session": session, "tools": tools}


async def get_tool_specs(server: dict) -> list[dict]:
    """OpenAI-shaped tool specs for one server (cached). Empty on error —
    an unreachable MCP server must never break a chat turn."""
    sid = server["id"]
    entry = _CACHE.get(sid)
    if entry is None or time.monotonic() - entry["at"] > _CACHE_TTL:
        try:
            entry = await _connect_and_list(server)
            _CACHE[sid] = entry
        except Exception as e:
            log.warning("MCP %s tools/list failed: %s", server.get("name"), e)
            _CACHE[sid] = {"at": time.monotonic(), "session": None, "tools": []}
            return []
    slug = slugify(server.get("name") or sid)
    specs = []
    for t in entry["tools"][:40]:
        name = t.get("name") or ""
        if not name:
            continue
        specs.append({
            "type": "function",
            "function": {
                "name": f"mcp__{slug}__{name}"[:64],
                "description": (t.get("description") or name)[:1000],
                "parameters": t.get("inputSchema") or {"type": "object", "properties": {}},
            },
        })
    return specs


async def call_tool(server: dict, tool_name: str, arguments: dict) -> str:
    """tools/call → flattened text result (capped)."""
    sid = server["id"]
    entry = _CACHE.get(sid) or {}
    session = entry.get("session")
    try:
        async with httpx.AsyncClient(timeout=httpx.Timeout(120.0, connect=10.0)) as client:
            msg, _ = await _rpc(client, server, "tools/call", {
                "name": tool_name,
                "arguments": arguments or {},
            }, rpc_id=3, session=session)
    except Exception as e:
        # Session may have expired — retry once with a fresh handshake.
        try:
            _CACHE.pop(sid, None)
            await get_tool_specs(server)
            session = (_CACHE.get(sid) or {}).get("session")
            async with httpx.AsyncClient(timeout=httpx.Timeout(120.0, connect=10.0)) as client:
                msg, _ = await _rpc(client, server, "tools/call", {
                    "name": tool_name,
                    "arguments": arguments or {},
                }, rpc_id=3, session=session)
        except Exception:
            return f"MCP tool error: {e}"
    result = (msg or {}).get("result") or {}
    if result.get("isError"):
        prefix = "MCP tool reported an error: "
    else:
        prefix = ""
    parts = []
    for item in result.get("content") or []:
        t = item.get("type")
        if t == "text":
            parts.append(item.get("text") or "")
        elif t == "image":
            parts.append(f"[image result: {item.get('mimeType', 'image')} — not displayed]")
        elif t == "resource":
            res = item.get("resource") or {}
            parts.append(res.get("text") or f"[resource: {res.get('uri', '?')}]")
    text = "\n".join(p for p in parts if p) or json.dumps(result)[:2000]
    return (prefix + text)[:8000]


async def dispatch_mcp(name: str, args: dict, user_id: str, db_module) -> str:
    """Route an mcp__<slug>__<tool> call to the matching enabled server."""
    m = re.match(r"^mcp__([a-z0-9_]+)__(.+)$", name)
    if not m:
        return f"Unknown MCP tool: {name}"
    slug, tool = m.group(1), m.group(2)
    try:
        servers = await db_module.list_mcp_servers(user_id)
    except Exception as e:
        return f"MCP error: {e}"
    for s in servers:
        if s.get("enabled") and slugify(s.get("name") or s["id"]) == slug:
            print(f"[tool:mcp] {s['name']} → {tool}")
            return await call_tool(s, tool, args)
    return f"MCP server for '{slug}' not found or disabled"
