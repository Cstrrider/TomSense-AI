"""Cloudflare Workers AI async client + streaming chat with tool-call accumulation.

This module mirrors the logic we proved out in the existing pipeline's
`_stream_round` / `_chat`, but rewritten async + clean.
"""

import json
import re
from typing import Any, AsyncIterator, Optional

import httpx

from .config import settings


# Strip chat-template artifacts that occasionally leak into delta.content
_TEMPLATE_TOKEN_RE = re.compile(
    r"<\|?[a-zA-Z_]+\|?>"
    r"|</?(?:channel|message|im_start|im_end|start_of_turn|end_of_turn)>"
)
# Gemma 4 leaks its reasoning channel LABEL — a bare "thought" on its own line —
# into delta.content (the reasoning itself arrives via delta.reasoning_content
# and renders as a "Thought process" card). Strip a standalone "thought" line so
# it doesn't litter the reply. Anchored to a whole line, so real prose
# ("I had a thought") is untouched.
_CHANNEL_LABEL_RE = re.compile(r"(?im)^[ \t]*thought[ \t]*$\n?")


def strip_template_tokens(s: str) -> str:
    return _CHANNEL_LABEL_RE.sub("", _TEMPLATE_TOKEN_RE.sub("", s))


# CF model id substrings for models that emit hidden reasoning. Keep aligned
# with tool_registry.CODE_MODELS_CATALOG and any reasoning-capable models
# used by non-code-mode chats.
_REASONING_MODEL_HINTS: tuple[str, ...] = (
    "gpt-oss",
    "gemma-4",
    "nemotron-3",
    "qwen3",
    "kimi",
    "deepseek-r1",
    "glm-4.7",
)


def _is_reasoning_model(model: str) -> bool:
    m = model.lower()
    return any(h in m for h in _REASONING_MODEL_HINTS)


# Single shared async client — connection pool reused across all CF calls.
# Lifetime is the FastAPI app's; closed on shutdown via lifespan.
_client: Optional[httpx.AsyncClient] = None


def get_client() -> httpx.AsyncClient:
    global _client
    if _client is None:
        # CF AI Gateway's bot manager 1010-blocks the default python-httpx UA.
        # Set a plausible one globally so every outbound call (direct AI run
        # AND gateway) gets through the same edge filter.
        _client = httpx.AsyncClient(
            timeout=httpx.Timeout(180.0, connect=10.0),
            headers={"User-Agent": "tomsense-backend/0.4 (+tomsense)"},
        )
    return _client


async def close_client() -> None:
    global _client
    if _client is not None:
        await _client.aclose()
        _client = None


# ─── account-wide neuron usage (CF Analytics GraphQL) ──────────────────────

async def fetch_neurons_today() -> dict:
    """Query CF Workers AI neuron usage for today (UTC). Account-wide totals.

    Returns {used, limit, period_start, period_end}. On failure returns
    {error: "..."} but never raises — the frontend can show a fallback.
    """
    from datetime import datetime, timezone
    now = datetime.now(timezone.utc)
    start = datetime(now.year, now.month, now.day, 0, 0, 0, tzinfo=timezone.utc)
    end   = datetime(now.year, now.month, now.day, 23, 59, 59, tzinfo=timezone.utc)

    query = """
    query getNeurons($accountId: string!, $start: string!, $end: string!) {
      viewer {
        accounts(filter: { accountTag: $accountId }) {
          aiInferenceAdaptiveGroups(
            limit: 10000,
            filter: { datetime_geq: $start, datetime_leq: $end }
          ) {
            sum { totalNeurons }
          }
        }
      }
    }
    """
    try:
        r = await get_client().post(
            "https://api.cloudflare.com/client/v4/graphql",
            headers={
                "Authorization": f"Bearer {settings.cf_api_token}",
                "Content-Type": "application/json",
            },
            json={
                "query": query,
                "variables": {
                    "accountId": settings.cf_account_id,
                    "start": start.isoformat(),
                    "end": end.isoformat(),
                },
            },
            timeout=15.0,
        )
        data = r.json()
        if data.get("errors"):
            return {"error": str(data["errors"])[:200], "used": 0,
                    "limit": settings.cf_daily_neuron_limit,
                    "period_start": start.isoformat(),
                    "period_end": end.isoformat()}
        groups = (
            data.get("data", {})
                .get("viewer", {})
                .get("accounts", [{}])[0]
                .get("aiInferenceAdaptiveGroups", [])
        ) or []
        total = sum(int(g.get("sum", {}).get("totalNeurons", 0)) for g in groups)
        return {
            "used": total,
            "limit": settings.cf_daily_neuron_limit,
            "period_start": start.isoformat(),
            "period_end": end.isoformat(),
        }
    except Exception as e:
        return {"error": str(e)[:200], "used": 0,
                "limit": settings.cf_daily_neuron_limit,
                "period_start": start.isoformat(),
                "period_end": end.isoformat()}


async def fetch_gateway_spend_today() -> dict:
    """AI Gateway unified-billing spend for today (UTC).

    Sums `cost` from aiGatewayRequestsAdaptiveGroups, keeping only EXTERNAL
    providers (Google / OpenAI / … — real dollars billed by Cloudflare).
    provider == "workers-ai" rows are excluded: that usage is the neuron
    metric, already surfaced by fetch_neurons_today (and free under the
    daily allocation). Returns {external_cost}; never raises.
    """
    from datetime import datetime, timezone
    today = datetime.now(timezone.utc).strftime("%Y-%m-%d")

    query = """
    query getGatewaySpend($accountId: string!, $s: string!, $e: string!) {
      viewer {
        accounts(filter: { accountTag: $accountId }) {
          aiGatewayRequestsAdaptiveGroups(
            limit: 2000,
            filter: { date_geq: $s, date_leq: $e }
          ) {
            count
            sum { cost }
            dimensions { provider }
          }
        }
      }
    }
    """
    try:
        r = await get_client().post(
            "https://api.cloudflare.com/client/v4/graphql",
            headers={
                "Authorization": f"Bearer {settings.cf_api_token}",
                "Content-Type": "application/json",
            },
            json={
                "query": query,
                "variables": {
                    "accountId": settings.cf_account_id,
                    "s": today,
                    "e": today,
                },
            },
            timeout=15.0,
        )
        data = r.json()
        if data.get("errors"):
            return {"error": str(data["errors"])[:200], "external_cost": 0.0}
        groups = (
            data.get("data", {})
                .get("viewer", {})
                .get("accounts", [{}])[0]
                .get("aiGatewayRequestsAdaptiveGroups", [])
        ) or []
        external = sum(
            float((g.get("sum") or {}).get("cost") or 0)
            for g in groups
            if (g.get("dimensions") or {}).get("provider") != "workers-ai"
        )
        return {"external_cost": external}
    except Exception as e:
        return {"error": str(e)[:200], "external_cost": 0.0}


def build_payload(
    model: str,
    messages: list[dict],
    max_tokens: int,
    tools: Optional[list[dict]] = None,
    stream: bool = True,
) -> dict:
    payload: dict[str, Any] = {
        "model": model,
        "messages": messages,
        "max_tokens": max_tokens,
        "stream": stream,
    }
    # Reasoning models otherwise burn the entire output budget on hidden
    # thinking, leaving no room for the tool call or response.
    if _is_reasoning_model(model):
        payload["reasoning_effort"] = "low"
    if tools:
        payload["tools"] = tools
        payload["tool_choice"] = "auto"
    return payload


async def chat_complete(
    model: str,
    messages: list[dict],
    max_tokens: int,
    tools: Optional[list[dict]] = None,
) -> dict:
    """Non-streaming call. Returns {content, tool_calls, usage}."""
    payload = build_payload(model, messages, max_tokens, tools, stream=False)
    payload["stop"] = [
        "<|end|>", "<|start|>", "<|endoftext|>", "<eot_id>",
        "<|im_end|>", "<|assistant|>", "<|channel|>",
    ]
    r = await get_client().post(
        settings.cf_chat_url,
        headers=settings.cf_headers,
        json=payload,
        timeout=120.0,
    )
    if not r.is_success:
        return {"content": "", "tool_calls": [], "usage": {},
                "error": f"CF {r.status_code}: {r.text[:300]}"}
    data = r.json()
    choice = data.get("choices", [{}])[0]
    msg = choice.get("message", {})
    content = (msg.get("content") or "").strip()
    raw_calls = msg.get("tool_calls") or []
    tool_calls = []
    for tc in raw_calls:
        fn = tc.get("function", {}) or {}
        args = fn.get("arguments", "{}")
        if isinstance(args, str):
            try:
                args = json.loads(args)
            except json.JSONDecodeError:
                args = {}
        elif not isinstance(args, dict):
            args = {"_raw": args}
        tool_calls.append({
            "id": str(tc.get("id") or f"call_{len(tool_calls)}"),
            "name": str(fn.get("name") or ""),
            "arguments": args,
        })
    return {
        "content": content,
        "tool_calls": tool_calls,
        "usage": data.get("usage", {}) or {},
    }


async def stream_round(
    model: str,
    messages: list[dict],
    max_tokens: int,
    tools: Optional[list[dict]] = None,
) -> AsyncIterator[tuple[str, Any]]:
    """
    Streamed chat round with tool-call accumulation.

    Yields, in order:
      ('text', str)   — each content delta (template tokens stripped)
      ('done', dict)  — once at end, with {tool_calls, content, usage}

    CF reasoning models emit `delta.reasoning` / `delta.reasoning_content` —
    silently discarded. Stop tokens are NOT set: setting them makes
    reasoning models like Gemma 4 terminate mid-thought before producing
    real content.
    """
    payload = build_payload(model, messages, max_tokens, tools, stream=True)

    accumulated_text = ""
    accumulated_tools: dict[int, dict] = {}
    usage: dict = {}

    try:
        async with get_client().stream(
            "POST",
            settings.cf_chat_url,
            headers=settings.cf_headers,
            json=payload,
        ) as r:
            if not r.is_success:
                body = (await r.aread()).decode(errors="replace")
                print(f"[stream_round] CF {r.status_code}: {body[:400]}")
                r.raise_for_status()
            async for raw_line in r.aiter_lines():
                line = raw_line.strip()
                if not line:
                    continue
                if line.startswith("data:"):
                    data = line[5:].strip()
                else:
                    data = line
                if data == "[DONE]":
                    break
                try:
                    obj = json.loads(data)
                except json.JSONDecodeError:
                    continue
                for choice in obj.get("choices", []):
                    delta = choice.get("delta", {}) or {}
                    # 1. accumulate tool_calls (defensively str-coerced)
                    for tc_chunk in (delta.get("tool_calls") or []):
                        idx = tc_chunk.get("index", 0)
                        slot = accumulated_tools.setdefault(
                            idx, {"id": "", "name": "", "arguments": ""}
                        )
                        if tc_chunk.get("id") is not None:
                            slot["id"] = str(tc_chunk["id"])
                        fn = tc_chunk.get("function") or {}
                        if fn.get("name") is not None:
                            slot["name"] = str(fn["name"])
                        args_frag = fn.get("arguments")
                        if args_frag is None:
                            pass
                        elif isinstance(args_frag, str):
                            slot["arguments"] += args_frag
                        else:
                            slot["arguments"] = json.dumps(args_frag)
                    # 2. surface content deltas
                    content = delta.get("content")
                    if content is None or content == "":
                        continue
                    if not isinstance(content, str):
                        content = str(content)
                    accumulated_text += content
                    cleaned = strip_template_tokens(content)
                    if cleaned:
                        yield ("text", cleaned)
                if obj.get("usage"):
                    usage = obj["usage"]
    except Exception as e:
        print(f"[stream_round] error: {e}")
        yield ("text", f"\n\n[stream error: {e}]")

    # Parse accumulated args strings
    tool_calls_out = []
    for idx in sorted(accumulated_tools.keys()):
        tc = accumulated_tools[idx]
        args_str = tc["arguments"]
        try:
            args = json.loads(args_str) if args_str else {}
        except json.JSONDecodeError:
            args = {}
        tool_calls_out.append({
            "id": tc["id"] or f"call_{idx}",
            "name": tc["name"],
            "arguments": args,
        })

    yield ("done", {
        "tool_calls": tool_calls_out,
        "content": strip_template_tokens(accumulated_text),
        "usage": usage,
    })
