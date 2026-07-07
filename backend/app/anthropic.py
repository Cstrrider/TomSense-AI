"""Anthropic Messages API client.

Plugs into the same `stream_round`-shaped interface as providers.py so the
agentic loop in chat.py works unchanged. We translate OpenAI's chat-
completions schema (system / user / assistant / tool roles, tool_calls +
tool_call_id) into Anthropic's Messages schema (system at top-level,
tool_use / tool_result content blocks).

Prompt caching: we mark the system prompt and the last tool definition with
`cache_control: {type: "ephemeral"}` so the prefix is cached. Subsequent
rounds of the same chat hit the cache and pay 10% input cost on those
tokens — usually a 30–50% input-cost saving on a multi-round agentic loop.
"""

import json
from typing import Any, AsyncIterator, Optional

from .cf import get_client


ANTHROPIC_BASE_URL = "https://api.anthropic.com/v1"
ANTHROPIC_VERSION = "2023-06-01"


def _headers(api_key: str) -> dict:
    return {
        "x-api-key": api_key,
        "anthropic-version": ANTHROPIC_VERSION,
        "content-type": "application/json",
    }


def _convert_tools(openai_tools: Optional[list[dict]]) -> Optional[list[dict]]:
    """OpenAI tool spec → Anthropic tool spec.

    OpenAI: `[{"type": "function", "function": {name, description, parameters}}]`
    Anthropic: `[{name, description, input_schema}]`

    Cache-control marker on the LAST tool so the whole tools array is cached.
    """
    if not openai_tools:
        return None
    out: list[dict] = []
    for spec in openai_tools:
        fn = spec.get("function") or spec
        out.append({
            "name": fn.get("name", ""),
            "description": fn.get("description", ""),
            "input_schema": fn.get("parameters") or {"type": "object", "properties": {}},
        })
    if out:
        out[-1]["cache_control"] = {"type": "ephemeral"}
    return out


def _convert_messages(openai_messages: list[dict]) -> tuple[list[dict], list[dict]]:
    """OpenAI messages → (system_blocks, anthropic_messages).

    - 'system' role → consolidated into the top-level system parameter.
    - 'user' / 'assistant' → kept, content sometimes promoted to blocks.
    - 'assistant' with `tool_calls` → content is [text?, tool_use, …].
    - Consecutive 'tool' messages → merged into a single user message with
      multiple tool_result blocks (Anthropic requires this).
    """
    system_parts: list[str] = []
    out_msgs: list[dict] = []
    pending_tool_results: list[dict] = []

    def flush_tool_results() -> None:
        nonlocal pending_tool_results
        if pending_tool_results:
            out_msgs.append({"role": "user", "content": pending_tool_results})
            pending_tool_results = []

    for m in openai_messages:
        role = m.get("role")
        content = m.get("content", "")

        if role == "system":
            flush_tool_results()
            if isinstance(content, str) and content:
                system_parts.append(content)
            elif isinstance(content, list):
                for part in content:
                    if isinstance(part, dict) and part.get("text"):
                        system_parts.append(part["text"])

        elif role == "tool":
            tool_call_id = m.get("tool_call_id") or ""
            tool_content = content if isinstance(content, str) else str(content)
            pending_tool_results.append({
                "type": "tool_result",
                "tool_use_id": tool_call_id,
                "content": tool_content,
            })

        elif role == "assistant":
            flush_tool_results()
            blocks: list[dict] = []
            if isinstance(content, str) and content.strip():
                blocks.append({"type": "text", "text": content})
            for tc in (m.get("tool_calls") or []):
                fn = tc.get("function") or {}
                args = fn.get("arguments")
                if isinstance(args, str):
                    try:
                        args = json.loads(args) if args else {}
                    except json.JSONDecodeError:
                        args = {}
                elif args is None:
                    args = {}
                blocks.append({
                    "type": "tool_use",
                    "id": str(tc.get("id") or f"toolu_{len(blocks)}"),
                    "name": str(fn.get("name") or ""),
                    "input": args,
                })
            if not blocks:
                # Anthropic rejects empty assistant turns; emit a placeholder.
                blocks = [{"type": "text", "text": ""}]
            out_msgs.append({"role": "assistant", "content": blocks})

        elif role == "user":
            flush_tool_results()
            if isinstance(content, list):
                # Multi-part content (e.g. vision uploads). Pass through.
                out_msgs.append({"role": "user", "content": content})
            else:
                out_msgs.append({"role": "user", "content": str(content)})

    flush_tool_results()

    if not system_parts:
        return [], out_msgs
    # Cache the whole system block so subsequent rounds amortize it.
    system_blocks = [
        {
            "type": "text",
            "text": "\n\n".join(system_parts),
            "cache_control": {"type": "ephemeral"},
        }
    ]
    return system_blocks, out_msgs


async def stream_round(
    provider: dict,
    model: str,
    messages: list[dict],
    max_tokens: int,
    tools: Optional[list[dict]] = None,
) -> AsyncIterator[tuple[str, Any]]:
    """Streamed chat round against Anthropic's Messages API. Yields the same
    (kind, payload) tuples as providers.stream_round so chat.py's agentic
    loop runs unchanged."""

    api_key = provider.get("api_key") or ""
    base = (provider.get("base_url") or ANTHROPIC_BASE_URL).rstrip("/")
    url = f"{base}/messages"

    system, anth_messages = _convert_messages(messages)
    anth_tools = _convert_tools(tools)

    payload: dict[str, Any] = {
        "model": model,
        "max_tokens": max_tokens,
        "messages": anth_messages,
        "stream": True,
    }
    if system:
        payload["system"] = system
    if anth_tools:
        payload["tools"] = anth_tools

    accumulated_text = ""
    accumulated_tools: dict[int, dict] = {}
    usage: dict[str, int] = {
        "prompt_tokens": 0,
        "completion_tokens": 0,
        "cache_read_tokens": 0,
        "cache_creation_tokens": 0,
    }

    try:
        async with get_client().stream(
            "POST", url, headers=_headers(api_key), json=payload,
        ) as r:
            if not r.is_success:
                body = (await r.aread()).decode(errors="replace")
                print(f"[anthropic stream] {r.status_code}: {body[:400]}")
                yield ("text", f"\n\n[anthropic error {r.status_code}: {body[:200]}]")
                yield ("done", {"tool_calls": [], "content": "", "usage": usage})
                return

            async for raw_line in r.aiter_lines():
                line = raw_line.strip()
                if not line or not line.startswith("data:"):
                    continue
                data_str = line[5:].strip()
                if not data_str:
                    continue
                try:
                    event = json.loads(data_str)
                except json.JSONDecodeError:
                    continue

                ev_type = event.get("type")

                if ev_type == "message_start":
                    u = (event.get("message") or {}).get("usage") or {}
                    cache_read = u.get("cache_read_input_tokens") or 0
                    cache_create = u.get("cache_creation_input_tokens") or 0
                    fresh_input = u.get("input_tokens") or 0
                    # Total input tokens the model actually saw (cached + fresh).
                    usage["prompt_tokens"] = fresh_input + cache_read + cache_create
                    usage["cache_read_tokens"] = cache_read
                    usage["cache_creation_tokens"] = cache_create

                elif ev_type == "content_block_start":
                    idx = event.get("index", 0)
                    block = event.get("content_block") or {}
                    if block.get("type") == "tool_use":
                        accumulated_tools[idx] = {
                            "id": block.get("id") or f"toolu_{idx}",
                            "name": block.get("name") or "",
                            "input_str": "",
                        }

                elif ev_type == "content_block_delta":
                    idx = event.get("index", 0)
                    delta = event.get("delta") or {}
                    dt = delta.get("type")
                    if dt == "text_delta":
                        text = delta.get("text", "")
                        if text:
                            accumulated_text += text
                            yield ("text", text)
                    elif dt == "thinking_delta":
                        thinking = delta.get("thinking", "")
                        if thinking:
                            yield ("reasoning", thinking)
                    elif dt == "input_json_delta":
                        partial = delta.get("partial_json", "")
                        if idx in accumulated_tools:
                            accumulated_tools[idx]["input_str"] += partial

                elif ev_type == "message_delta":
                    u = event.get("usage") or {}
                    if u.get("output_tokens") is not None:
                        usage["completion_tokens"] = u["output_tokens"]

                elif ev_type == "message_stop":
                    break

                elif ev_type == "error":
                    err = (event.get("error") or {}).get("message") or "anthropic error"
                    yield ("text", f"\n\n[anthropic stream error: {err}]")

    except Exception as e:
        print(f"[anthropic stream] error: {e}")
        yield ("text", f"\n\n[stream error: {e}]")

    # Finalize tool_calls — parse the accumulated input JSON strings.
    tool_calls_out: list[dict] = []
    for idx in sorted(accumulated_tools.keys()):
        tc = accumulated_tools[idx]
        truncated = False
        try:
            args = json.loads(tc["input_str"]) if tc["input_str"] else {}
        except json.JSONDecodeError:
            # Args JSON didn't parse — the stream was cut off mid-call (token
            # cap / drop). Flag it so the loop steers to smaller edits instead
            # of dispatching a garbage/empty call (parity with providers.py).
            args = {}
            truncated = True
        tool_calls_out.append({
            "id": tc["id"],
            "name": tc["name"],
            "arguments": args,
            "truncated": truncated,
        })

    usage["total_tokens"] = usage["prompt_tokens"] + usage["completion_tokens"]

    yield ("done", {
        "tool_calls": tool_calls_out,
        "content": accumulated_text,
        "usage": usage,
    })
