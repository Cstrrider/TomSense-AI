"""TomSense as an MCP *server* — the inverse of mcp.py (the client).

Exposes TomSense's user-state tools (RAG search, memories, chat search/read)
over streamable-HTTP JSON-RPC so other MCP clients — Claude Code, claude.ai —
can reach what TomSense knows.

Auth: `MCP_SERVER_KEY` (setup.sh generates it), accepted as `?key=<secret>`
or `Authorization: Bearer <secret>`. This endpoint does NOT use CF Access
(claude.ai's connector can't do Access) — key-only, so keep the key strong.
Unset key = endpoint disabled. All calls act as the OWNER user (single-user
deploy; the key IS the identity).

From Claude Code on the LAN:   http://<host>:8001/mcp?key=<secret>
From claude.ai: needs a CF Access bypass policy for the /mcp path on the
tunnel domain, then https://<domain>/mcp?key=<secret>.
"""

import logging
import os
from typing import Any, Optional

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from . import db, rag

log = logging.getLogger("tomsense")

router = APIRouter()

MCP_SERVER_KEY = os.getenv("MCP_SERVER_KEY", "").strip()
_OWNER_EMAIL = os.getenv("LOCAL_DEV_EMAIL", os.getenv("TOMSENSE_OWNER_EMAIL", ""))

_PROTOCOL_VERSION = "2025-03-26"

TOOLS: list[dict] = [
    {
        "name": "search_docs",
        "description": (
            "Semantic search over the TomSense user's uploaded documents "
            "(PDFs, docs, transcripts). Returns the top matching chunks "
            "with filenames and scores."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "What to look for."},
                "k": {"type": "integer", "description": "Results (1-10, default 5)."},
            },
            "required": ["query"],
        },
    },
    {
        "name": "list_memories",
        "description": "The user's long-term memory facts (preferences, biography, ongoing projects) as maintained by TomSense.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "remember",
        "description": "Save a new durable fact to the user's TomSense memory.",
        "inputSchema": {
            "type": "object",
            "properties": {"content": {"type": "string", "description": "The fact (≤500 chars)."}},
            "required": ["content"],
        },
    },
    {
        "name": "forget",
        "description": "Delete memories containing a substring (case-insensitive).",
        "inputSchema": {
            "type": "object",
            "properties": {"query": {"type": "string", "description": "Substring of the memory to remove."}},
            "required": ["query"],
        },
    },
    {
        "name": "search_chats",
        "description": "Search the user's TomSense chat history by title/content. Returns chat ids, titles and matching snippets.",
        "inputSchema": {
            "type": "object",
            "properties": {"query": {"type": "string"}},
            "required": ["query"],
        },
    },
    {
        "name": "get_chat",
        "description": "Fetch a TomSense chat's messages by chat id (from search_chats).",
        "inputSchema": {
            "type": "object",
            "properties": {"chat_id": {"type": "string"}},
            "required": ["chat_id"],
        },
    },
]


async def _owner_id() -> Optional[str]:
    if not _OWNER_EMAIL:
        return None
    try:
        user = await db.get_or_create_user(_OWNER_EMAIL)
        return user["id"]
    except Exception as e:
        log.warning("mcp_server owner lookup failed: %s", e)
        return None


async def _call_tool(name: str, args: dict, user_id: str) -> str:
    if name == "search_docs":
        hits = await rag.search(user_id, str(args.get("query") or ""),
                                k=max(1, min(10, int(args.get("k") or 5))))
        if not hits:
            return "No matching document chunks."
        return "\n\n".join(
            f"[{i}] {h['filename']} (score {h['score']:.3f})\n{h['text'][:1500]}"
            for i, h in enumerate(hits, 1)
        )
    if name == "list_memories":
        mems = await db.list_memories(user_id)
        return "\n".join(f"- {m['content']}" for m in mems) or "(no memories)"
    if name == "remember":
        content = str(args.get("content") or "").strip()[:500]
        if not content:
            return "Error: content required"
        await db.add_memory(user_id, content)
        return f"Saved: {content}"
    if name == "forget":
        q = str(args.get("query") or "").strip()
        n = await db.delete_memories_matching(user_id, q)
        return f"Deleted {n} memorie(s) matching {q!r}."
    if name == "search_chats":
        results = await db.search_chats(user_id, str(args.get("query") or ""))
        if not results:
            return "No matching chats."
        out = []
        for c in results[:10]:
            snippets = "; ".join((h.get("snippet") or "")[:100] for h in (c.get("hits") or [])[:2])
            out.append(f"- {c['id']} · {c.get('title') or '(untitled)'}"
                       + (f" — {snippets}" if snippets else ""))
        return "\n".join(out)
    if name == "get_chat":
        chat = await db.get_chat(str(args.get("chat_id") or ""), user_id=user_id)
        if not chat:
            return "Chat not found."
        lines = [f"# {chat.get('title') or 'Chat'}"]
        for m in (chat.get("messages") or [])[-40:]:
            c = m.get("content")
            if isinstance(c, str) and m.get("role") in ("user", "assistant"):
                lines.append(f"[{m['role']}] {c[:1500]}")
        return "\n\n".join(lines)[:30_000]
    return f"Unknown tool: {name}"


def _authed(request: Request) -> bool:
    if not MCP_SERVER_KEY:
        return False
    if request.query_params.get("key") == MCP_SERVER_KEY:
        return True
    auth = request.headers.get("authorization", "")
    return auth.removeprefix("Bearer ").strip() == MCP_SERVER_KEY


def _rpc_error(rpc_id: Any, code: int, message: str, status: int = 200) -> JSONResponse:
    return JSONResponse(
        {"jsonrpc": "2.0", "id": rpc_id, "error": {"code": code, "message": message}},
        status_code=status,
    )


@router.post("/mcp")
async def mcp_endpoint(request: Request):
    if not MCP_SERVER_KEY:
        return JSONResponse({"detail": "MCP server disabled — set MCP_SERVER_KEY"}, status_code=503)
    if not _authed(request):
        return JSONResponse({"detail": "unauthorized"}, status_code=401)
    try:
        msg = await request.json()
    except Exception:
        return _rpc_error(None, -32700, "parse error", status=400)
    if isinstance(msg, list):  # batch — handle the first only (we're simple)
        msg = msg[0] if msg else {}
    method = msg.get("method") or ""
    rpc_id = msg.get("id")
    params = msg.get("params") or {}

    if method == "initialize":
        return JSONResponse({"jsonrpc": "2.0", "id": rpc_id, "result": {
            "protocolVersion": params.get("protocolVersion") or _PROTOCOL_VERSION,
            "capabilities": {"tools": {}},
            "serverInfo": {"name": "tomsense", "version": "1.0"},
        }})
    if method.startswith("notifications/"):
        return JSONResponse(None, status_code=202)
    if method == "tools/list":
        return JSONResponse({"jsonrpc": "2.0", "id": rpc_id,
                             "result": {"tools": TOOLS}})
    if method == "tools/call":
        user_id = await _owner_id()
        if not user_id:
            return _rpc_error(rpc_id, -32000, "owner user not resolvable")
        name = (params.get("name") or "").strip()
        args = params.get("arguments") or {}
        try:
            text = await _call_tool(name, args, user_id)
        except Exception as e:
            log.warning("mcp_server tool %s failed: %s", name, e)
            return JSONResponse({"jsonrpc": "2.0", "id": rpc_id, "result": {
                "isError": True,
                "content": [{"type": "text", "text": f"tool error: {e}"}],
            }})
        return JSONResponse({"jsonrpc": "2.0", "id": rpc_id, "result": {
            "content": [{"type": "text", "text": text}],
        }})
    if method == "ping":
        return JSONResponse({"jsonrpc": "2.0", "id": rpc_id, "result": {}})
    return _rpc_error(rpc_id, -32601, f"method not found: {method}")
