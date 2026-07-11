"""Provider abstraction (Round A).

Wraps any OpenAI-compatible chat-completions endpoint behind a uniform
interface so the rest of the codebase doesn't care whether the model lives
on Cloudflare, OpenAI, OpenRouter, Groq, or local Ollama.

Model strings carry their provider as a prefix:

    <provider_id>::<model_id>

Examples:

    cf::@cf/google/gemma-4-26b-a4b-it           (built-in Cloudflare)
    08fab...d3::gpt-4o                          (user-defined OpenAI)
    08fab...d3::anthropic/claude-3.5-sonnet     (user-defined OpenRouter)

Backward compat: any string without `::` is treated as a Cloudflare model,
so existing chat rows and persona model fields keep working untouched.
"""

import asyncio
import json
from typing import Any, AsyncIterator, Optional

from .cf import get_client, strip_template_tokens
from .config import settings
from . import db


SEP = "::"
CF_BUILTIN_ID = "cf"
ANTHROPIC_BUILTIN_ID = "anthropic"
# Credential-only builtins — not model providers (never in pickers / resolve).
# google-vision powers reverse_image_lookup; audd powers identify_song.
GOOGLE_VISION_BUILTIN_ID = "google-vision"
AUDD_BUILTIN_ID = "audd"


# ─── parsing ────────────────────────────────────────────────────────────────

def parse_model_str(model_str: Optional[str]) -> tuple[str, str]:
    """Split a `provider_id::model_id` string. Empty/None → (cf, default chat).
    Strings without the separator → (cf, model_str)."""
    if not model_str:
        return CF_BUILTIN_ID, settings.model_chat
    if SEP in model_str:
        pid, mid = model_str.split(SEP, 1)
        return (pid or CF_BUILTIN_ID), mid
    return CF_BUILTIN_ID, model_str


# ─── resolution ─────────────────────────────────────────────────────────────

async def _builtin_row_key(user_id: Optional[str], builtin_id: str) -> str:
    """User's stored API key for a builtin provider, "" if no row exists.
    Reads from the unified providers table (post-I1) — the legacy
    `prefs.credentials` JSON storage is migrated lazily on first read of
    /me/credentials in main.py."""
    if not user_id:
        return ""
    try:
        row = await db.get_builtin_provider(user_id, builtin_id)
        if row and row.get("api_key"):
            return str(row["api_key"]).strip()
    except Exception:
        pass
    return ""


async def _cf_builtin_provider(user_id: Optional[str] = None) -> dict:
    """Synthetic provider for built-in Cloudflare Workers AI. api_key resolves
    via:  user's builtin row  →  CF_API_TOKEN env  →  empty.
    The model list is populated client-side from the per-tool defaults."""
    api_key = await _builtin_row_key(user_id, CF_BUILTIN_ID) or settings.cf_api_token
    return {
        "id": CF_BUILTIN_ID,
        "name": "Cloudflare Workers AI",
        "base_url": settings.cf_chat_url.rsplit("/chat/completions", 1)[0],
        "api_key": api_key,
        "kind": "openai-compat",
        "models": [],
        "builtin": True,
    }


async def _anthropic_builtin_provider(user_id: Optional[str] = None) -> Optional[dict]:
    """Synthetic provider for the native Anthropic Messages API. None when
    no key is configured anywhere — the UI then surfaces 'unknown provider'."""
    api_key = (
        await _builtin_row_key(user_id, ANTHROPIC_BUILTIN_ID)
        or settings.anthropic_api_key
    )
    if not api_key:
        return None
    return {
        "id": ANTHROPIC_BUILTIN_ID,
        "name": "Anthropic Claude",
        "base_url": "https://api.anthropic.com/v1",
        "api_key": api_key,
        "kind": "anthropic",
        "models": [],
        "builtin": True,
    }


async def resolve_provider(provider_id: str, user_id: Optional[str] = None) -> Optional[dict]:
    """Look up a provider by id. `cf` → synthetic Cloudflare builtin;
    `anthropic` → synthetic Anthropic builtin (when a key is configured —
    either user pref or env); anything else hits the DB. Returns None when
    not found / not owned by user_id."""
    if not provider_id or provider_id == CF_BUILTIN_ID:
        return await _cf_builtin_provider(user_id)
    if provider_id == ANTHROPIC_BUILTIN_ID:
        return await _anthropic_builtin_provider(user_id)
    return await db.get_provider(provider_id, user_id=user_id)


async def list_user_providers_with_builtin(user_id: str) -> list[dict]:
    """All providers visible to a user — builtin CF first, then Anthropic
    (when configured), then their own user-added providers."""
    out: list[dict] = [await _cf_builtin_provider(user_id)]
    anth = await _anthropic_builtin_provider(user_id)
    if anth:
        out.append(anth)
    out.extend(await db.list_providers(user_id))
    return out


def mask_api_key(key: str) -> str:
    if not key:
        return ""
    if len(key) <= 12:
        return "•" * len(key)
    return f"{key[:4]}…{key[-4:]}"


def redact_provider(p: dict) -> dict:
    """Return a copy of `p` with api_key masked. Use on every read that goes
    out over the wire."""
    out = dict(p)
    out["api_key"] = mask_api_key(p.get("api_key") or "")
    return out


# ─── generic OpenAI-compatible client ───────────────────────────────────────

def _chat_url(provider: dict) -> str:
    base = (provider.get("base_url") or "").rstrip("/")
    # Accept base_urls with or without trailing /chat/completions
    if base.endswith("/chat/completions"):
        return base
    return f"{base}/chat/completions"


def _headers(provider: dict) -> dict:
    return {
        "Authorization": f"Bearer {provider.get('api_key') or ''}",
        "Content-Type": "application/json",
    }


def build_payload(
    model: str, messages: list[dict], max_tokens: int,
    tools: Optional[list[dict]] = None, stream: bool = True,
    temperature: Optional[float] = None, reasoning_effort: Optional[str] = None,
    flatten_vision: bool = False,
    is_reasoning: Optional[bool] = None, sees_images: Optional[bool] = None,
) -> dict:
    # Capability (vision / reasoning) is resolved by the caller via the
    # capabilities registry and passed in as `sees_images` / `is_reasoning`.
    # When a caller doesn't supply them we fall back to the demoted
    # name-substring heuristics so nothing regresses.
    from .cf import _is_reasoning_model, _is_vision_model, flatten_for_text_model
    _sees = sees_images if sees_images is not None else _is_vision_model(model)
    _reasons = is_reasoning if is_reasoning is not None else _is_reasoning_model(model)

    # A text-only model 400s on OpenAI multimodal content (a `content` array
    # with image parts). When the resolved model can't do vision but the
    # conversation carries images, flatten them to a text note so the turn still
    # answers instead of crashing. Gated to callers that opt in (flatten_vision).
    if flatten_vision and not _sees:
        messages = flatten_for_text_model(messages)

    payload: dict[str, Any] = {
        "model": model,
        "messages": messages,
        "max_tokens": max_tokens,
        "stream": stream,
    }
    # Reasoning models burn the token budget unless effort is capped. Caller can
    # override; default stays "low".
    if _reasons:
        payload["reasoning_effort"] = reasoning_effort or "low"
    # Low temperature for deterministic work (code edits); unset = provider
    # default otherwise.
    if temperature is not None:
        payload["temperature"] = temperature
    if tools:
        payload["tools"] = tools
        payload["tool_choice"] = "auto"
    return payload


async def chat_complete(
    provider: dict,
    model: str,
    messages: list[dict],
    max_tokens: int,
    tools: Optional[list[dict]] = None,
) -> dict:
    """Non-streaming chat call against any OpenAI-compatible provider."""
    from . import capabilities
    caps = capabilities.model_capabilities(provider, model)
    payload = build_payload(model, messages, max_tokens, tools, stream=False,
                            flatten_vision=provider.get("id") == CF_BUILTIN_ID,
                            is_reasoning=caps["reasoning"], sees_images=caps["vision"])
    payload["stop"] = [
        "<|end|>", "<|start|>", "<|endoftext|>", "<eot_id>",
        "<|im_end|>", "<|assistant|>", "<|channel|>",
    ]
    r = await get_client().post(
        _chat_url(provider),
        headers=_headers(provider),
        json=payload,
        timeout=120.0,
    )
    if not r.is_success:
        return {"content": "", "tool_calls": [], "usage": {},
                "error": f"{provider.get('name', '?')} {r.status_code}: {r.text[:300]}"}
    data = r.json()
    choice = (data.get("choices") or [{}])[0]
    msg = choice.get("message", {}) or {}
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
    provider: dict,
    model: str,
    messages: list[dict],
    max_tokens: int,
    tools: Optional[list[dict]] = None,
    temperature: Optional[float] = None,
    reasoning_effort: Optional[str] = None,
    _attempt: int = 0,
) -> AsyncIterator[tuple[str, Any]]:
    """Streamed chat round. Routes to the Anthropic Messages API when the
    provider's `kind` is "anthropic"; otherwise hits the OpenAI-compatible
    chat-completions endpoint."""
    if provider.get("kind") == "anthropic":
        # Imported lazily so the OpenAI-compat path doesn't pay for the
        # additional module on every request. (temperature/reasoning_effort are
        # OpenAI-compat tuning; the Anthropic path uses its own defaults.)
        from . import anthropic as _anth
        async for ev in _anth.stream_round(provider, model, messages, max_tokens, tools):
            yield ev
        return

    from . import capabilities
    caps = capabilities.model_capabilities(provider, model)
    payload = build_payload(model, messages, max_tokens, tools, stream=True,
                            temperature=temperature, reasoning_effort=reasoning_effort,
                            is_reasoning=caps["reasoning"], sees_images=caps["vision"])

    accumulated_text = ""
    accumulated_tools: dict[int, dict] = {}
    usage: dict = {}
    finish_reason: Optional[str] = None

    # OpenRouter reports real $ cost in the final usage chunk when asked. Only
    # for openrouter base_urls — an unknown `usage` param could upset stricter
    # OpenAI-compat servers. Harmless elsewhere; we just won't get a cost.
    if "openrouter.ai" in (provider.get("base_url") or ""):
        payload["usage"] = {"include": True}

    try:
        async with get_client().stream(
            "POST",
            _chat_url(provider),
            headers=_headers(provider),
            json=payload,
        ) as r:
            if not r.is_success:
                body = (await r.aread()).decode(errors="replace")
                # Some models (notably the smaller local Ollama ones — Gemma 3
                # 1B, TinyLlama, etc.) advertise no tool support in their
                # modelfile, so Ollama 400s when `tools` is sent. Recover by
                # transparently retrying without tools, since for a regular
                # chat the user just wants an answer — losing web_search /
                # image_gen for that turn is acceptable.
                if (
                    r.status_code == 400
                    and tools
                    and "does not support tools" in body
                ):
                    print(f"[stream_round:{provider.get('name')}] {model} lacks tool support — retrying without tools")
                    async for ev in stream_round(
                        provider, model, messages, max_tokens, tools=None,
                    ):
                        yield ev
                    return
                # Transient capacity/overload — retriable. CF returns 429 code
                # 3040 "Capacity temporarily exceeded, please try again" when a
                # model's GPU pool is momentarily saturated (the Gemma-4 pool
                # does this under load); 5xx are server-side hiccups. The error
                # arrives BEFORE any bytes stream, so a short-backoff retry on
                # the same model is safe (no double output) and usually clears
                # within a second or two. After MAX_RETRIES we fall through to
                # raise — the caller's stall-guard fallback then takes over.
                MAX_RETRIES = 2
                if (r.status_code == 429 or 500 <= r.status_code < 600) and _attempt < MAX_RETRIES:
                    delay = 0.8 * (2 ** _attempt)
                    print(f"[stream_round:{provider.get('name')}] {model} {r.status_code} "
                          f"transient — retry {_attempt + 1}/{MAX_RETRIES} after {delay:.1f}s")
                    await asyncio.sleep(delay)
                    async for ev in stream_round(
                        provider, model, messages, max_tokens, tools,
                        temperature=temperature, reasoning_effort=reasoning_effort,
                        _attempt=_attempt + 1,
                    ):
                        yield ev
                    return
                print(f"[stream_round:{provider.get('name')}] {r.status_code}: {body[:400]}")
                r.raise_for_status()
            async for raw_line in r.aiter_lines():
                line = raw_line.strip()
                if not line:
                    continue
                data = line[5:].strip() if line.startswith("data:") else line
                if data == "[DONE]":
                    break
                try:
                    obj = json.loads(data)
                except json.JSONDecodeError:
                    continue
                for choice in obj.get("choices", []):
                    if choice.get("finish_reason"):
                        finish_reason = choice["finish_reason"]
                    delta = choice.get("delta", {}) or {}
                    # Reasoning models stream their thinking under
                    # delta.reasoning / delta.reasoning_content before any
                    # real content. Surface it as a separate event kind.
                    reasoning = delta.get("reasoning") or delta.get("reasoning_content")
                    if reasoning and isinstance(reasoning, str):
                        yield ("reasoning", reasoning)
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
        # str() of httpx.ReadTimeout is empty — always include the type so the
        # user-visible note and the log say WHAT failed.
        print(f"[stream_round:{provider.get('name')}] error: {type(e).__name__}: {e}")
        yield ("text", f"\n\n[stream error: {type(e).__name__}: {e}]")

    tool_calls_out = []
    for idx in sorted(accumulated_tools.keys()):
        tc = accumulated_tools[idx]
        args_str = tc["arguments"]
        truncated = False
        try:
            args = json.loads(args_str) if args_str else {}
        except json.JSONDecodeError:
            # The args JSON didn't parse — almost always because the model hit
            # the response-token cap mid-call (finish_reason == "length") while
            # writing a huge edit. Flag it so the loop can steer to smaller edits
            # instead of dispatching a garbage/empty call.
            args = {}
            truncated = True
        tool_calls_out.append({
            "id": tc["id"] or f"call_{idx}",
            "name": tc["name"],
            "arguments": args,
            "truncated": truncated,  # args JSON didn't parse → call was cut off
        })

    yield ("done", {
        "tool_calls": tool_calls_out,
        "content": strip_template_tokens(accumulated_text),
        "usage": usage,
        "finish_reason": finish_reason,
    })


# ─── dispatch helpers (parse + resolve + call in one shot) ──────────────────

async def dispatch_chat_complete(
    user_id: Optional[str], model_str: str, messages: list[dict],
    max_tokens: int, tools: Optional[list[dict]] = None,
    fallback_model_str: Optional[str] = None,
) -> dict:
    """One-shot completion. When `fallback_model_str` is given, a call that
    errors, raises, or returns nothing usable (no content AND no tool calls)
    is retried once on the fallback — the non-streaming twin of the
    dispatch_stream_round stall guard. Without a fallback, semantics are
    unchanged (exceptions propagate)."""
    pid, mid = parse_model_str(model_str)
    provider = await resolve_provider(pid, user_id=user_id)
    if not provider:
        result = {"content": "", "tool_calls": [], "usage": {},
                  "error": f"unknown provider {pid!r}"}
    elif not fallback_model_str:
        return await chat_complete(provider, mid, messages, max_tokens, tools)
    else:
        try:
            result = await chat_complete(provider, mid, messages, max_tokens, tools)
        except Exception as e:
            result = {"content": "", "tool_calls": [], "usage": {},
                      "error": f"{type(e).__name__}: {e}"}

    dead = bool(result.get("error")) or (
        not (result.get("content") or "").strip() and not result.get("tool_calls")
    )
    if not dead or not fallback_model_str:
        return result
    fb_pid, fb_mid = parse_model_str(fallback_model_str)
    if not fb_mid or (fb_pid, fb_mid) == (pid, mid):
        return result
    fb_provider = await resolve_provider(fb_pid, user_id=user_id)
    if not fb_provider:
        return result
    why = (result.get("error") or "empty reply")[:120]
    print(f"[fallback] {mid} failed ({why}) — retrying with {fb_mid}")
    # A text-only fallback would 400 on multimodal content — flatten first.
    fb_messages = messages
    if _messages_have_images(messages):
        from . import capabilities
        if not capabilities.model_capabilities(fb_provider, fb_mid)["vision"]:
            from .cf import flatten_for_text_model
            fb_messages = flatten_for_text_model(messages)
    try:
        fb_result = await chat_complete(fb_provider, fb_mid, fb_messages,
                                        max_tokens, tools)
    except Exception as e:
        print(f"[fallback] {fb_mid} also failed: {type(e).__name__}: {e}")
        return result
    return fb_result


def _model_short(mid: str) -> str:
    """Human-ish model name for user-visible notices: last path segment."""
    return mid.rsplit("/", 1)[-1]


def _messages_have_images(messages: list[dict]) -> bool:
    for m in messages:
        c = m.get("content")
        if isinstance(c, list) and any(
            isinstance(p, dict) and p.get("type") == "image_url" for p in c
        ):
            return True
    return False


async def dispatch_stream_round(
    user_id: Optional[str], model_str: str, messages: list[dict],
    max_tokens: int, tools: Optional[list[dict]] = None,
    temperature: Optional[float] = None, reasoning_effort: Optional[str] = None,
    fallback_model_str: Optional[str] = None,
) -> AsyncIterator[tuple[str, Any]]:
    pid, mid = parse_model_str(model_str)
    provider = await resolve_provider(pid, user_id=user_id)
    if not provider:
        yield ("text", f"\n\n[unknown provider {pid!r}]")
        yield ("done", {"tool_calls": [], "content": "", "usage": {}})
        return

    # Stall watchdog, two phases:
    #  - FIRST event bounded by first_token_timeout_s (upstream bricked before
    #    any bytes — observed 2026-07-07 CF gemma-4 brownout, 180-295s of
    #    nothing).
    #  - Every SUBSEQUENT gap bounded by stream_idle_timeout_s (model streams
    #    its thinking, then the stream dies mid-flight — observed 2026-07-10:
    #    4771 chars of reasoning then silence until the 180s httpx read
    #    timeout, ending the round with an empty reply).
    # On a stall with no visible text yet, retry once on the fallback model
    # (per-slot user setting first, global default second) with a visible
    # notice. If text already streamed, truncating honestly beats a retry that
    # would duplicate half an answer.
    _fb_str = fallback_model_str or settings.stall_fallback_model
    fb_pid, fb_mid = parse_model_str(_fb_str)
    first_t = settings.first_token_timeout_s
    idle_t = settings.stream_idle_timeout_s

    gen = stream_round(provider, mid, messages, max_tokens, tools,
                       temperature=temperature, reasoning_effort=reasoning_effort)
    text_seen = ""
    timeout = first_t
    while True:
        try:
            if timeout and timeout > 0:
                ev = await asyncio.wait_for(gen.__anext__(), timeout=timeout)
            else:
                ev = await gen.__anext__()
        except StopAsyncIteration:
            return
        except asyncio.TimeoutError:
            await gen.aclose()
            break
        timeout = idle_t
        if ev[0] == "text":
            text_seen += ev[1]
        if ev[0] == "done":
            # Tag the round with the model that actually answered, so the
            # stats footer shows the right name.
            ev[1]["model"] = model_str
        yield ev

    # ── stalled ─────────────────────────────────────────────────────────────
    phase = "mid-stream" if text_seen else "before any visible output"
    print(f"[stall-guard] {mid} went silent {phase} "
          f"(first={first_t:.0f}s idle={idle_t:.0f}s)")

    if text_seen:
        # Part of the answer is already on the user's screen — a fallback
        # retry would restart from scratch and duplicate it. Close honestly.
        yield ("text", f"\n\n*[{_model_short(mid)} went silent mid-answer — "
                       "response may be incomplete]*")
        yield ("done", {"tool_calls": [], "content": text_seen, "usage": {},
                        "model": model_str})
        return

    fb_provider = await resolve_provider(fb_pid, user_id=user_id) if fb_mid else None
    if not fb_provider or fb_mid == mid:
        yield ("text", f"\n\n*[{_model_short(mid)} stalled and no fallback "
                       "model is available]*")
        yield ("done", {"tool_calls": [], "content": "", "usage": {},
                        "model": model_str})
        return

    # An image turn falling back to a text-only model would 400 on the
    # multimodal content — flatten the images to a text note so the fallback
    # still answers (degraded beats dead).
    from . import capabilities
    fb_messages = messages
    if _messages_have_images(messages) and not capabilities.model_capabilities(
            fb_provider, fb_mid)["vision"]:
        from .cf import flatten_for_text_model
        fb_messages = flatten_for_text_model(messages)

    yield ("text", f"*[{_model_short(mid)} stalled — answering with "
                   f"{_model_short(fb_mid)}]*\n\n")
    fb_gen = stream_round(fb_provider, fb_mid, fb_messages, max_tokens, tools,
                          temperature=temperature,
                          reasoning_effort=reasoning_effort)
    fb_text = ""
    timeout = first_t
    while True:
        try:
            if timeout and timeout > 0:
                ev = await asyncio.wait_for(fb_gen.__anext__(), timeout=timeout)
            else:
                ev = await fb_gen.__anext__()
        except StopAsyncIteration:
            return
        except asyncio.TimeoutError:
            await fb_gen.aclose()
            print(f"[stall-guard] fallback {fb_mid} ALSO went silent — giving up")
            yield ("text", f"\n\n*[{_model_short(fb_mid)} also went silent — "
                           "both models are struggling right now, try again "
                           "in a moment]*")
            yield ("done", {"tool_calls": [], "content": fb_text, "usage": {},
                            "model": _fb_str})
            return
        timeout = idle_t
        if ev[0] == "text":
            fb_text += ev[1]
        if ev[0] == "done":
            ev[1]["model"] = _fb_str
        yield ev


async def test_provider(provider: dict) -> dict:
    """Ping the provider's /models endpoint as a connectivity check.
    Returns {ok: bool, status: int?, error?: str, model_count?: int}."""
    base = (provider.get("base_url") or "").rstrip("/")
    if base.endswith("/chat/completions"):
        base = base[: -len("/chat/completions")]
    url = f"{base}/models"
    try:
        r = await get_client().get(url, headers=_headers(provider), timeout=15.0)
    except Exception as e:
        return {"ok": False, "error": str(e)[:200]}
    if not r.is_success:
        return {"ok": False, "status": r.status_code, "error": r.text[:200]}
    try:
        data = r.json()
        models = data.get("data") if isinstance(data, dict) else data
        n = len(models) if isinstance(models, list) else None
        return {"ok": True, "status": r.status_code, "model_count": n}
    except Exception:
        return {"ok": True, "status": r.status_code, "model_count": None}


async def discover_models(provider: dict) -> list[str]:
    """Fetch the model ids a provider advertises at its OpenAI-shaped /models
    endpoint (returns [] on any failure — discovery is best-effort)."""
    base = (provider.get("base_url") or "").rstrip("/")
    if base.endswith("/chat/completions"):
        base = base[: -len("/chat/completions")]
    try:
        r = await get_client().get(f"{base}/models", headers=_headers(provider), timeout=15.0)
        if not r.is_success:
            return []
        data = r.json()
        rows = data.get("data") if isinstance(data, dict) else data
        if not isinstance(rows, list):
            return []
        ids: list[str] = []
        for row in rows:
            mid = row.get("id") if isinstance(row, dict) else row
            if isinstance(mid, str) and mid:
                ids.append(mid)
        return sorted(set(ids))
    except Exception:
        return []
