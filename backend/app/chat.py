"""Agentic chat loop: stream a response from CF, dispatch any tool calls,
loop until the model is done. Yields SSE-shaped strings to the client.
"""

import html
import json
import os
import re
import time
from datetime import datetime
from typing import AsyncIterator

from . import mounts
from .clienttools import CLIENT_TOOL_NAMES, request_client_tool
from .code_hints import active_hint_keys, apply_code_hints
from .config import settings
from .providers import dispatch_stream_round
from .tools import (
    ALLOWED_TOOL_NAMES,
    CODE_TOOL_SPECS,
    TOOL_SPECS,
    dispatch,
    resolve_image_model,
)
from .tools_code import _call as _sandbox_call
from .tools_code import CODE_DISPATCH, EDIT_TOOL_NAMES, preview_edit
from .deploy import deploy as run_deploy, deploy_plan, DEPLOY_TARGETS, target_keys
from .tools_code import set_secret_env
from .verify import run_verification

# Valid tool names for reasoning-channel recovery (#9).
_CODE_TOOL_NAMES = set(CODE_DISPATCH) | {
    "search_docs", "web_search", "fetch_page", "ask_user", "spawn_subagent",
}
# Tools a subagent must NOT have: it can't ask the user (it runs autonomously)
# and can't spawn further subagents (one level of nesting only).
_SUBAGENT_EXCLUDED_TOOLS = {"spawn_subagent", "ask_user", "deploy_project"}
_MAX_SUBAGENT_ROUNDS = 18      # a focused subtask shouldn't need the full 40
_MAX_SUBAGENTS_PER_TURN = 4    # bound total fan-out per parent turn


# ─────────────────────────────────────────────────────────────────────────────
# System prompt
# ─────────────────────────────────────────────────────────────────────────────

def system_prompt() -> str:
    now = datetime.utcnow().strftime("%A %B %d %Y %H:%M UTC")
    return (
        now + "\n"
        "You are TomSense. Answer concept / explanation / casual questions "
        "directly from your knowledge. Reach for tools only when:\n"
        "  - The fact is time-sensitive (current prices, scores, news, "
        "recent events, who-won-the-recent-X) → web_search.\n"
        "  - The user wants a multi-source comparison or 'how has X "
        "changed over the last N years' style overview → deep_research.\n"
        "  - The user wants code built (full program / real algorithm) → "
        "consult_coder. Snippets you can write yourself.\n"
        "  - Math / data / simulations → code_interpreter.\n"
        "  - 'Draw / paint / image of …' (no image attached) → "
        "generate_image. Text-to-image only.\n"
        "  - User attached one or more images AND asks to modify / edit / "
        "restyle / combine them → edit_image. All attached images are "
        "forwarded as references; the editing model decides what to do. "
        "But questions ABOUT an attached image (what/who is this, what "
        "color, read the text, describe it) are NOT edits — you can see "
        "the image yourself; answer directly, NO tool call.\n"
        "  - For BOTH image tools, set hd=true when the user types `/HD`, "
        "mentions 'HD', '4K', 'high definition', 'highest quality', or "
        "'best quality'. hd=true routes to their preferred HD model "
        "(typically a pricier, higher-fidelity option). Otherwise leave "
        "hd=false (cheaper, faster default).\n"
        "  - The user references 'the doc', 'that PDF', 'the file I "
        "uploaded', or asks something likely in their personal docs → "
        "search_docs. The tool returns chunks with filenames; cite them.\n"
        "  - The user states a durable fact about themselves (location, "
        "allergies, projects, names, preferences, recurring tools) → "
        "remember(fact). Use 'forget(substring)' when they retract one.\n"
        "  - The answer depends on WHERE the user is — weather, 'near me', "
        "nearby places, travel time, local time → get_location.\n"
        "  - The user asks about their schedule, what's coming up, or "
        "whether they're free → get_calendar.\n"
        "  - The user asks about their steps, sleep, heart rate, activity, "
        "or fitness → get_health.\n"
        "  - The user wants to schedule something → create_calendar_event.\n"
        "  - 'Remind me to …' at a time → set_reminder. 'Set a timer for N "
        "minutes' → start_timer. 'Wake me / set an alarm for …' → set_alarm.\n"
        "When deep_research or consult_coder return output, present it "
        "verbatim with a one-line intro — don't rewrite it. Format with markdown.\n\n"
        "CRITICAL: NEVER write `![...](/generated/...)` image markdown in your "
        "responses. Image markdown is ONLY ever produced by the generate_image "
        "and edit_image tools — if you fabricate one, the user sees a broken "
        "image. If a tool returns an error like 'Image editing needs an image', "
        "RELAY THE ERROR to the user verbatim and ask them what to do — do not "
        "pretend it succeeded."
    )


def code_system_prompt() -> str:
    """Base system prompt for code-mode chats — applies to any coding task.

    Task-specific guidance (docker deployment, niche languages, …) lives in
    `code_hints.CODE_HINTS` and is appended on-demand by `apply_code_hints`,
    so a 'rename a function' chat doesn't pay the ~5 KB docker section in
    its input tokens or risk picking up checklist behavior on a non-docker
    task. See `code_hints.py` for the registry and how to add a new hint.
    """
    now = datetime.utcnow().strftime("%A %B %d %Y %H:%M UTC")
    # Name the actual project mounts (from .mounts.json) instead of a
    # hardcoded list — the set changes via Files → Mounts and a stale
    # prompt misleads the model about what's real source vs. scratch.
    try:
        _mounted = ", ".join(m["name"] for m in mounts.load_config())
    except Exception:
        _mounted = ""
    return (
        now + "\n"
        "You are TomSense in code mode — an autonomous coding agent working in a "
        "sandboxed Linux workspace. The current directory IS the workspace root.\n\n"
        "You ACT by calling tools — you never just describe what you would do. "
        "For ANY task, your first move is a tool call: list_dir or read_file to "
        "look around, or write_file / edit_file / run_bash to make a change. "
        "When the user asks you to do something, perform it with tools — do not "
        "reply with an explanation alone.\n\n"
        "Tools: list_dir, read_file, and grep to explore; write_file and "
        "edit_file to change files; run_bash for git, dependency installs, "
        "builds, and tests.\n\n"
        "Sandbox capabilities: outbound internet works (curl, HTTPS). pip "
        "installs from PyPI and npm from the public registry. python3, node, "
        "g++/gcc/make, git, ripgrep, openssl, unzip, pandoc, JDK 17, and the "
        "docker CLI (for `docker compose config` validation only — no daemon "
        "access) are preinstalled. If a task wants a URL fetched or a "
        "package installed, just do it via run_bash — do NOT decline "
        "preemptively or assume the sandbox is offline. For current docs, "
        "error messages, or library versions, use web_search + fetch_page "
        "instead of guessing URLs.\n\n"
        "Long-running processes: run_bash KILLS its process when the call "
        "returns — use run_background for dev servers, watchers, or long "
        "builds. It keeps running between your calls: reach it from run_bash "
        "at localhost:<port> (same container), watch it with background_logs, "
        "and background_stop it when you're done verifying.\n\n"
        "User's projects are live-mounted under `/workspace/projects/` — "
        "these are NOT throwaway scratch dirs, they are the user's real "
        "source code from the host filesystem"
        + (f" (currently mounted: {_mounted})" if _mounted else "")
        + ". Edits land on disk immediately.\n"
        "  - Use the `git` tool (op=status/diff/log/branch/add/commit/checkout, "
        "scoped with cwd) for version control — it saves you from raw shell. "
        "Always run git status before editing to see the branch + uncommitted "
        "changes.\n"
        "  - For any non-trivial change, create or switch to a feature branch "
        "first: git checkout with create=true, branch='feat/<short-name>'.\n"
        "  - Do NOT push or commit without the user asking — leave the diff for "
        "their review. (git push/reset/rebase aren't in the git tool by design; "
        "use run_bash only if the user explicitly asks.)\n"
        "  - `/workspace/` (outside `projects/`) is your scratch area — use "
        "it for experiments, generated files, etc. Don't pollute the "
        "real repos with throwaway code.\n\n"
        "Working rules:\n"
        "  - Explore before you edit. Read a file before you change it.\n"
        "  - Don't re-read what you've already read. Tool results stay in this "
        "conversation — if you read a file earlier and haven't changed it, you "
        "still have its contents; scroll back instead of reading it again.\n"
        "  - To find WHERE a function/class/method is DEFINED, use find_symbol "
        "(go-to-definition) — it returns the file:line + signature without "
        "reading files. To understand a file's shape, use outline before "
        "reading it. For free-text / usages / non-symbol matches, use grep. "
        "In all cases: get the file:line, then read_file with offset/limit to "
        "pull just that region. Only read a file end-to-end when you genuinely "
        "need the whole thing (e.g. about to rewrite it).\n"
        "  - read_file output prefixes every line with a `NNN→` line number. "
        "That prefix is DISPLAY ONLY — it is not in the file. NEVER include "
        "it in edit_file old_string/new_string, write_file content, or "
        "apply_patch hunks; copy the text after the arrow.\n"
        "  - Prefer edit_file over write_file for changes to an existing file — "
        "write_file replaces the whole file.\n"
        "  - edit_file's old_string must match the file exactly and uniquely; "
        "include enough surrounding context, or use replace_all.\n"
        "  - When a change touches several spots or several files at once, use "
        "apply_patch — one context-based patch instead of many edit_file calls. "
        "It locates hunks by surrounding context (no line numbers) and applies "
        "atomically.\n"
        "  - After changing code, verify it: call diagnostics on the file/project "
        "to type-check it (tsc/pyright/go vet), and run the build or tests with "
        "run_bash. Fix what diagnostics reports before claiming done.\n"
        "  - To work on an existing repo, git clone it into the workspace with "
        "run_bash.\n"
        "  - If a command fails, read the error and fix the cause — do not "
        "blindly repeat the same failing command.\n"
        "  - For a task with several steps, call update_plan first with your "
        "plan, then update it as you finish steps — it keeps you on track and "
        "shows the user progress.\n"
        "  - Keep going until the task is done, one concrete step at a time. "
        "Then stop and give a short summary of what you changed.\n"
        + ((
            "  - Editing files does NOT make them live — the running app serves "
            "a pre-built bundle. After your changes are complete and verified, "
            f"call deploy_project to rebuild and restart it (targets: "
            f"{target_keys()}). The user must approve each deploy. Deploy at "
            "the END, once, after the work is done and type-checks — not after "
            "every single edit.\n"
        ) if DEPLOY_TARGETS else "")
        + "  - For a big task with separable, well-scoped pieces, you can "
        "spawn_subagent to delegate a piece to a fresh agent that shares this "
        "workspace and reports back. Write the subtask as COMPLETE standalone "
        "instructions (it has no memory of this chat). Don't delegate trivial "
        "one-step edits — just do those yourself.\n"
        "  - When the user's request is BROAD or vague, or could reasonably be "
        "done in materially different ways (e.g. 'make it better', 'improve "
        "this', 'clean it up', 'review and fix'), call ask_user FIRST — before "
        "exploring or editing — to confirm scope and approach, offering 2-4 "
        "concrete options. Also ask at any genuine mid-task fork (two valid "
        "approaches, a destructive/irreversible action). ask_user stops your "
        "turn and waits for the user, then you continue with their answer. "
        "Don't ask about trivia you can decide yourself — but for an open-ended "
        "request, ASK rather than guessing the direction.\n"
        "  - Be concise. Don't paste large file contents back to the user — your "
        "tool calls are already shown to them."
    )


# ─────────────────────────────────────────────────────────────────────────────
# Message sanitization
# ─────────────────────────────────────────────────────────────────────────────

# Matches one trailing stats footer block. Current emit format is:
#   \n\n---\n*<model(s)> | ↑<n> ↓<n> ⏱<n>s <n>t/s*
# We strip ANY trailing "---" rule followed by an italicized line, so the
# regex stays robust against minor format tweaks.
_STATS_FOOTER_RE = re.compile(
    r"\s*\n+-{3,}\s*\n+\*[^*\n]+\*\s*$"
)
_BASE64_IMAGE_RE = re.compile(
    r"!\[Generated Image\]\(data:image/[^;]+;base64,[A-Za-z0-9+/=]+\)"
)
# Matches markdown image refs pointing at our /generated/ mount. Used to
# extract a prior image URL when the user says "edit the image" without
# attaching one to the new turn.
_GENERATED_IMAGE_REF_RE = re.compile(
    r"!\[[^\]]*\]\((/generated/[^)\s]+\.(?:png|jpg|jpeg|webp))\)"
)
# Tool-call chip + reasoning blocks emitted into assistant content. MUST be
# stripped from history before re-feeding the model — otherwise it sees the
# raw <details type="tool_call"> / <details type="reasoning"> pattern and
# learns to HALLUCINATE it as plain text instead of emitting real tool calls
# / reasoning. Reasoning especially must not re-enter context (it bloats
# tokens and confuses the model).
_TOOL_CHIP_RE = re.compile(
    r'<details type="(?:tool_call|reasoning)".*?</details>', re.DOTALL
)
# The ask_user card the loop emits (a fenced ```ask-user block the frontend
# turns into an interactive question). In HISTORY it's reduced to a plain
# "Asked the user:" line — same reasoning as the tool-chip strip: if the model
# re-sees the raw block it learns to FABRICATE it as text instead of calling
# the tool. The user's actual answer is the following user message.
_ASK_USER_BLOCK_RE = re.compile(r"```ask-user\s*\n(.*?)\n```", re.DOTALL)


def _ask_user_block(question: str, options: list) -> str:
    """Render an ask_user call as a fenced block the frontend renders as a
    question card with tappable options. Persisted as part of the assistant
    message, so it survives reload and shows in the transcript."""
    payload: dict = {"question": (question or "").strip()}
    opts = [str(o).strip() for o in (options or []) if str(o).strip()][:6]
    if opts:
        payload["options"] = opts
    return "\n\n```ask-user\n" + json.dumps(payload) + "\n```\n"


def _deask_history(text: str) -> str:
    """Reduce a persisted ask-user card to a plain line for re-feeding to the
    model (so it doesn't mimic the raw block)."""
    def _sub(m: re.Match) -> str:
        try:
            q = json.loads(m.group(1)).get("question", "")
        except (ValueError, TypeError):
            q = ""
        return f"[Asked the user: {q}]" if q else "[Asked the user a question]"
    return _ASK_USER_BLOCK_RE.sub(_sub, text)


def _strip_tool_chips(text: str) -> str:
    """Remove tool-call/reasoning chip markup the model sometimes HALLUCINATES
    into its own text. sanitize_messages does this for incoming history, but the
    agentic loop appends the model's per-round text to the live context WITHOUT
    re-sanitizing — so one stray `<details type="tool_call">` snowballs into the
    model mimicking the format every round (observed: 86k-char reply, 186k input
    tokens in 9 rounds). Strip it before the text re-enters context / is
    persisted."""
    if not text or "<details" not in text:
        return text or ""
    out = _TOOL_CHIP_RE.sub("", text)
    return re.sub(r"\n{3,}", "\n\n", out).strip()


def _strip_hallucinated_chips(text: str) -> str:
    """Persist-time strip. REAL chips — emitted by tool_chip()/reasoning_block(),
    marked data-step="1" — are KEPT, so a run's steps survive completion AND
    reload. Only UNMARKED chips are removed: markup the model hallucinated into
    its prose (it streams live raw, before the per-round _strip_tool_chips, so it
    reaches `accumulated`). Keeping real chips in the DB is safe — sanitize_messages
    still strips ALL chips from model context on the way back in, so no snowball,
    and the model can never learn to fake data-step because it never sees a chip."""
    if not text or "<details" not in text:
        return text or ""

    def _repl(m: re.Match) -> str:
        block = m.group(0)
        head = block[: block.find(">") + 1]  # the opening <details …> tag
        return block if 'data-step="1"' in head else ""

    out = _TOOL_CHIP_RE.sub(_repl, text)
    return re.sub(r"\n{3,}", "\n\n", out).strip()
# Any inline generated/edited-image markdown (the copy yielded outside the
# chip). Removed entirely from history — leaving even a placeholder gives the
# model something to mimic.
_INLINE_IMAGE_MD_RE = re.compile(
    r"!\[(?:Generated|Edited) Image\]\([^)]*\)"
)
# The transient "⏳ Generating image…" progress line.
_PROGRESS_LINE_RE = re.compile(r"\*⏳[^*\n]*\*")
# A whole line that is nothing but one italic span — these are the model-label
# lines (*nano-banana-2*, *flux-2-klein-4b img2img · 2 ref*, etc.) emitted
# under generated images. Stripped from history so the model doesn't learn to
# fabricate model names in its replies.
_STANDALONE_ITALIC_LINE_RE = re.compile(
    r"^[ \t]*\*[^*\n]{1,90}\*[ \t]*$", re.MULTILINE
)


def _last_generated_image_path(messages: list[dict]) -> str | None:
    """Walk assistant messages newest-first and return the on-disk path of
    the most recent /generated/{file}.png URL. Returns None when nothing
    has been generated in this conversation yet."""
    for m in reversed(messages):
        if m.get("role") != "assistant":
            continue
        content = m.get("content")
        if not isinstance(content, str):
            continue
        match = list(_GENERATED_IMAGE_REF_RE.finditer(content))
        if not match:
            continue
        url = match[-1].group(1)  # last image referenced in this assistant turn
        rel = url[len("/generated/"):]
        # GENERATED_DIR lives in tools.py but we know it's /data/generated_images
        path = os.path.join("/data/generated_images", rel)
        if os.path.exists(path):
            return path
    return None


def sanitize_messages(messages: list[dict]) -> list[dict]:
    """Strip artifacts from assistant history before re-feeding it to the
    model. Preserves multimodal list-content (text + image_url parts) so the
    vision path reaches Gemma intact.

    Why each strip matters:
    - stats footer: without it the model echoes the stats line in new replies.
    - base64 image: prior generated image bytes blow up token usage.
    - tool-call chip + inline image markdown: if the model SEES the raw
      `<details type="tool_call">` / `![Generated Image](...)` pattern in
      history, it learns to mimic it as plain text — fabricating fake tool
      calls and broken image URLs instead of emitting real tool calls.
    - progress line: the transient "⏳ Generating…" text is noise.
    """
    clean: list[dict] = []
    for m in messages:
        role = m.get("role", "")
        content = m.get("content")

        if isinstance(content, list):
            # Multimodal user content (vision input). Pass through as-is — CF
            # Workers AI / Gemma 4 accept OpenAI-shaped content arrays. Skip
            # text cleanup since it only matters for text-only assistant content.
            out = {**m, "content": content}
            clean.append(out)
            continue

        if role == "assistant" and isinstance(content, str):
            # Reduce a prior assistant turn to JUST its narrative text. Any
            # structural artifact left behind becomes a pattern the model
            # copies — so strip them all, leaving nothing mimic-able.
            content = _BASE64_IMAGE_RE.sub("", content)
            content = _TOOL_CHIP_RE.sub("", content)
            content = _deask_history(content)
            content = _INLINE_IMAGE_MD_RE.sub("", content)
            content = _PROGRESS_LINE_RE.sub("", content)
            prev = None
            while content != prev:
                prev = content
                content = _STATS_FOOTER_RE.sub("", content).rstrip()
            content = _STANDALONE_ITALIC_LINE_RE.sub("", content)
            # Collapse the blank lines the strips leave behind.
            content = re.sub(r"\n{3,}", "\n\n", content).strip()

        if content is None and role != "tool":
            continue
        out = {**m, "content": content or ""}
        clean.append(out)
    return clean


# ─────────────────────────────────────────────────────────────────────────────
# Stats footer rendered after the response
# ─────────────────────────────────────────────────────────────────────────────

MODEL_SHORT = {
    "gemma-4-26b-a4b-it":               "Gemma 4",
    "gpt-oss-20b":                      "GPT-OSS 20B",
    "gpt-oss-120b":                     "GPT-OSS 120B",
    "llama-3.2-3b-instruct":            "Llama 3.2 3B",
    "llama-3.1-8b-instruct":            "Llama 3.1 8B",
    "llama-3.3-70b-instruct-fp8-fast":  "Llama 3.3 70B",
    "llama-4-scout-17b-16e-instruct":   "Llama 4 Scout",
}


def short_name(model: str, provider_names: dict | None = None) -> str:
    # Split a `provider_id::model_id` prefix. Otherwise a model id with no "/"
    # (e.g. an OpenRouter "gpt-4o") would render as the raw
    # "provider-uuid::gpt-4o" string in the stats footer.
    prov = None
    if "::" in model:
        pid, model = model.split("::", 1)
        # Disambiguate custom-provider models: the same model name can exist on
        # CF and OpenRouter (e.g. Gemma 4), so tag the provider when it isn't
        # the CF builtin. Needs the id→name map resolved by the caller.
        if pid and pid != "cf" and provider_names:
            prov = provider_names.get(pid)
    key = model.split("/")[-1]
    name = MODEL_SHORT.get(key, key)
    return f"{name} ({prov})" if prov else name


def stats_footer(models_used: set, usage: dict, elapsed: float, summarized: int = 0,
                 provider_names: dict | None = None) -> str:
    if not models_used:
        return ""
    names = ", ".join(short_name(m, provider_names) for m in sorted(models_used))
    ptok = usage.get("prompt_tokens", 0)
    ctok = usage.get("completion_tokens", 0)
    tps = round(ctok / elapsed, 0) if elapsed > 0 and ctok > 0 else 0
    extra = f" · summarized {summarized} turn{'s' if summarized != 1 else ''}" if summarized > 0 else ""
    return f"*{names} | ↑{ptok} ↓{ctok} ⏱{round(elapsed,1)}s {int(tps)}t/s{extra}*"


# ─────────────────────────────────────────────────────────────────────────────
# Tool chip — a collapsible <details> block surfacing what the model did
# ─────────────────────────────────────────────────────────────────────────────

def tool_chip(tc: dict, result: str) -> str:
    name = tc["name"]
    args = tc.get("arguments") or {}
    args_str = html.escape(json.dumps(args))
    if name == "web_search":
        summary = f"Searched: {args.get('query', '')}"
    elif name == "fetch_page":
        summary = f"Fetched: {args.get('url', '')[:80]}"
    elif name == "deep_research":
        summary = f"Researched: {args.get('query', '')[:80]}"
    elif name == "consult_coder":
        summary = f"Consulted coder: {args.get('task', '')[:80]}"
    elif name == "code_interpreter":
        snippet = (args.get("code") or "").replace("\n", " ⏎ ")[:80]
        summary = f"Ran code: {snippet}"
    elif name == "generate_image":
        summary = f"Generated image: {args.get('prompt', '')[:80]}"
    elif name == "edit_image":
        summary = f"Edited image: {args.get('prompt', '')[:80]}"
    elif name == "get_location":
        summary = "Checked your location"
    elif name == "get_calendar":
        summary = "Checked your calendar"
    elif name == "get_health":
        summary = "Checked your health data"
    elif name == "list_dir":
        summary = f"📁 {args.get('path', '.')}"
    elif name == "read_file":
        summary = f"Read {args.get('path', '')}"
    elif name == "write_file":
        summary = f"Wrote {args.get('path', '')}"
    elif name == "edit_file":
        summary = f"Edited {args.get('path', '')}"
    elif name == "grep":
        summary = f"grep: {(args.get('pattern') or '')[:60]}"
    elif name == "run_bash":
        summary = f"$ {(args.get('command') or '')[:70]}"
    elif name == "run_background":
        summary = f"▶ bg: {(args.get('command') or '')[:60]}"
    elif name == "background_logs":
        summary = f"bg logs: {args.get('id') or 'all'}"
    elif name == "background_stop":
        summary = f"■ bg stop: {args.get('id') or ''}"
    else:
        summary = f"Called {name}"

    body = (result[:400] + "…") if len(result) > 400 else result
    # Collapse blank lines: a <details> chip is a CommonMark HTML block that
    # ends at the first blank line, so a blank line in the body (a read_file
    # showing a file's import gap, a diff hunk) would terminate the block early
    # and swallow everything after it — including the turn's final summary.
    body = re.sub(r"\n[ \t]*\n+", "\n", body)
    # data-step="1" marks this as a REAL chip (vs. one the model hallucinated
    # into its prose). The persist-time strip keeps marked chips so the run's
    # steps survive reload; the model never sees it (sanitize_messages strips
    # all chips from context) so it can't learn to fake the marker.
    return (
        f'\n<details type="tool_call" data-step="1" name="{name}" arguments="{args_str}">\n'
        f"<summary>{html.escape(summary)}</summary>\n"
        f"{html.escape(body)}\n"
        f"</details>\n"
    )


def reasoning_block(text: str) -> str:
    """Wrap a reasoning model's thinking in a collapsible <details> block.
    Rendered collapsed by the frontend; stripped from history by
    sanitize_messages so it never re-enters the model's context."""
    body = html.escape(re.sub(r"\n[ \t]*\n+", "\n", text.strip()))
    return (
        f'\n<details type="reasoning" data-step="1">\n'
        f"<summary>💭 Thought process</summary>\n"
        f"{body}\n"
        f"</details>\n\n"
    )


# ─────────────────────────────────────────────────────────────────────────────
# Agentic loop — yields raw text chunks for SSE
# ─────────────────────────────────────────────────────────────────────────────

# Claim-detection for the fabrication-visibility footer. Used ONLY to decide
# whether to warn when a turn changed no files: if the reply *claims* it edited
# code (a claim verb near a filename) but no write_file/edit_file actually ran,
# the change didn't happen. Read-only/review/Q&A turns match neither and stay
# quiet — no false alarms.
_EDIT_CLAIM_RE = re.compile(
    r"\b(creat|add(?:ed|ing)?|updat|modif|wrote|writ|implement|refactor|rewr|"
    r"edit(?:ed|ing)?|fix(?:ed)?|append|insert|remov|delet)\w*\b",
    re.I,
)
_FILEISH_RE = re.compile(
    r"`[^`\n]+\.\w{1,6}`|\b[\w./-]+\.(?:ts|tsx|js|jsx|mjs|cjs|py|go|rs|java|kt|"
    r"json|css|scss|html|md|ya?ml|sql|prisma|sh|toml|env)\b",
    re.I,
)


# V4A patch file headers. apply_patch carries no `path` argument — the files it
# changes live in these headers — so this is how we record patch edits for the
# file-change footer / working set (matches the sandbox parser in sandbox/app.py).
_PATCH_FILE_RE = re.compile(r"^\*\*\* (?:Add|Update|Delete) File: (.+)$", re.MULTILINE)


def _file_change_footer(changed: list[str], text: str) -> str:
    """Ground-truth footer for code mode: what was ACTUALLY written/edited this
    turn. Closes the gap the old `tools_dispatched == 0` guard missed — a model
    could run read_file, then claim edits it never made (tools_dispatched > 0,
    so no warning fired). Here we list the real file changes; if there were
    none but the reply claims some, we flag it."""
    uniq: list[str] = []
    for f in changed:
        f = (f or "").strip()
        if f and f not in uniq:
            uniq.append(f)
    if uniq:
        lines = "\n".join(f"> - `{f}`" for f in uniq)
        return f"\n\n> 🔧 **Files changed this turn ({len(uniq)}):**\n{lines}"
    t = text or ""
    if _EDIT_CLAIM_RE.search(t) and _FILEISH_RE.search(t):
        return ("\n\n> ⚠️ **No files were written or edited this turn** — no "
                "write_file/edit_file call ran, yet the reply describes code "
                "changes. Those changes were NOT made; treat them as not done.")
    return ""


def _balanced_json_objects(s: str) -> list[str]:
    """Extract top-level {...} spans from text (brace-balanced). Used to find a
    tool call the model wrote as prose."""
    objs, depth, start = [], 0, None
    for i, ch in enumerate(s):
        if ch == "{":
            if depth == 0:
                start = i
            depth += 1
        elif ch == "}" and depth > 0:
            depth -= 1
            if depth == 0 and start is not None:
                objs.append(s[start:i + 1])
                start = None
    return objs


def _recover_tool_call(reasoning: str, text: str) -> list | None:
    """#9 — recover a tool call the model emitted as TEXT/reasoning instead of a
    real tool_call (some models route calls into the reasoning channel, where
    they're otherwise lost). Only called on a code round that produced NO real
    tool call AND no visible answer, so we're not hijacking a genuine reply.
    Returns a [{id,name,arguments}] list, or None."""
    blob = (reasoning or "") + "\n" + (text or "")
    if "{" not in blob:
        return None
    for raw in _balanced_json_objects(blob):
        try:
            obj = json.loads(raw)
        except (ValueError, TypeError):
            continue
        if not isinstance(obj, dict):
            continue
        name = obj.get("name") or obj.get("tool") or obj.get("function")
        if not isinstance(name, str) or name not in _CODE_TOOL_NAMES:
            continue
        args = obj.get("arguments")
        if args is None:
            args = obj.get("parameters") or obj.get("args") or {}
        if isinstance(args, str):
            try:
                args = json.loads(args)
            except (ValueError, TypeError):
                args = {}
        if not isinstance(args, dict):
            continue
        return [{"id": f"recovered_{name}", "name": name, "arguments": args}]
    return None


def _compact_history(msgs: list[dict], keep_recent: int = 6, budget: int = 80_000) -> int:
    """#10 — in-loop compaction. A long code run piles up large tool results
    (file reads, build logs) with no eviction, eventually blowing the context
    window. When the total size of tool-result messages exceeds `budget` chars,
    elide the OLDEST ones (keeping the last `keep_recent` intact) by shortening
    their content — never removing the message, so assistant/tool_call pairing
    stays valid. The system prompt + working-set + user/assistant turns are
    untouched. Returns the number of messages elided."""
    tool_idxs = [i for i, m in enumerate(msgs) if m.get("role") == "tool"]
    if len(tool_idxs) <= keep_recent:
        return 0
    total = sum(len(msgs[i].get("content") or "") for i in tool_idxs)
    if total <= budget:
        return 0
    elided = 0
    for i in tool_idxs[:-keep_recent]:
        c = msgs[i].get("content") or ""
        if c.startswith("[earlier tool output elided"):
            continue
        msgs[i]["content"] = "[earlier tool output elided to save context — re-read the file if you need it again]"
        total -= len(c)
        elided += 1
        if total <= budget:
            break
    return elided


async def run_chat(
    messages: list[dict],
    model: str = None,
    max_tokens: int = None,
    start_time: float = None,
    tool_context: dict = None,
    persona: str = None,
    stats_out: dict = None,
    summarized: int = 0,
    code_mode: bool = False,
    working_set_block: str = None,
    touched_out: list = None,
    subagent: bool = False,
    reasoning_effort: str = None,
) -> AsyncIterator[str | dict]:
    """Yields token chunks for SSE.

    Most yields are plain text strings. A yield of a dict is a structured
    control event (currently only `client_tool`) the SSE endpoint forwards
    verbatim rather than wrapping as a text chunk.

    `start_time` (monotonic seconds) is used to compute the wall-clock figure
    in the stats footer.

    `tool_context` is opaque per-request state forwarded to tools that need it
    (currently only generate_image, which reads `uploads`).

    `persona` is the per-chat custom-instructions string. Appended after the
    default system prompt so tool-routing rules stay intact.

    `summarized` is the number of older turns folded into an auto-summary for
    this request; surfaced in the stats footer so the user can see when the
    summarizer kicked in. 0 means no summary ran.
    """
    model = model or (settings.model_coder if code_mode else settings.model_chat)
    max_tokens = max_tokens or (
        settings.max_tokens_coder if code_mode else settings.max_tokens_chat
    )

    # id→name map for the stats footer, so a custom-provider model (OpenRouter
    # Gemma 4) is distinguished from the same-named CF builtin. Best-effort.
    provider_names: dict = {}
    _uid = (tool_context or {}).get("user_id")
    if _uid and not subagent:
        try:
            from .providers import list_user_providers_with_builtin
            provider_names = {
                p["id"]: p["name"] for p in await list_user_providers_with_builtin(_uid)
            }
        except Exception:
            provider_names = {}

    # Pluck the most recent generated-image path off the conversation so
    # edit_image can fall back to it when the user references "the image"
    # without re-uploading.
    prior_img = _last_generated_image_path(messages)
    if prior_img and tool_context is not None and "prior_image_path" not in tool_context:
        tool_context = {**tool_context, "prior_image_path": prior_img}
    elif prior_img and tool_context is None:
        tool_context = {"prior_image_path": prior_img}

    # Make the user's decrypted secrets available to /exec for THIS request
    # (task-local; injected as shell env, redacted from output). Set once here.
    set_secret_env((tool_context or {}).get("secret_env") if code_mode else None)

    msgs = sanitize_messages(messages)
    if not any(m.get("role") == "system" for m in msgs):
        if code_mode:
            # Code mode uses the coding-agent prompt; user memories aren't
            # relevant to a coding task, so they're skipped. The base prompt
            # holds rules that apply to ANY coding task — task-specific
            # blocks (docker deployment, niche languages, …) are appended
            # by apply_code_hints on demand, and re-checked each round so a
            # hint can fire mid-conversation when a tool result first
            # reveals the task type. See code_hints.py.
            sys = apply_code_hints(code_system_prompt(), msgs)
            # Working-set memory: the current contents of files this chat has
            # already opened, so the agent doesn't burn rounds re-reading them.
            # Contents are pulled fresh per turn by the caller (never cached),
            # so this can't go stale. Appended after hints; has no hint marker,
            # so the per-round apply_code_hints leaves it intact.
            if working_set_block and working_set_block.strip():
                sys = sys + "\n\n" + working_set_block.strip()
            _secret_names = sorted((tool_context or {}).get("secret_env") or {})
            if _secret_names:
                sys = sys + (
                    "\n\n# Secret vault\n"
                    "These secrets are available in your run_bash shell as "
                    "environment variables, injected at runtime. Their values are "
                    "HIDDEN from you — you never see the plaintext, and any value "
                    "that shows up in command output is redacted. Use them BY NAME, "
                    "e.g. curl -H \"Authorization: Bearer $" + _secret_names[0] + "\" …  "
                    "NEVER echo, print, cat, or otherwise try to reveal a secret's "
                    "value. Available: " + ", ".join("$" + n for n in _secret_names) + "."
                )
            if subagent:
                sys = sys + (
                    "\n\n# You are a subagent\n"
                    "You were spawned by another agent to handle ONE focused "
                    "subtask, described in the user message below. You share the "
                    "same workspace. Complete the whole subtask yourself — you "
                    "cannot ask the user questions and cannot spawn further "
                    "subagents. When done, end with a SHORT summary: exactly which "
                    "files you created/changed and anything the parent agent needs "
                    "to know (e.g. a new function's name/signature, a caveat, or "
                    "why you couldn't finish)."
                )
        else:
            sys = system_prompt()
            memories = (tool_context or {}).get("memories") or []
            if memories:
                mem_block = "\n".join(f"- {m}" for m in memories)
                sys = (
                    sys
                    + "\n\n# Things you know about the user (from prior chats)\n"
                    + mem_block
                    + "\n\nFold these in naturally when relevant. Use `remember` to "
                      "save NEW durable facts and `forget` to remove them. Do not "
                      "re-save facts that are already in the list above."
                )
        if persona and persona.strip():
            sys = sys + "\n\n# Custom instructions for this chat\n" + persona.strip()
        msgs = [{"role": "system", "content": sys}] + msgs

    start = start_time if start_time is not None else time.monotonic()
    models_used: set = set()
    total_usage = {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0}

    # Code mode: a longer agentic loop, only the coding tools, and a roomier
    # cap on tool output fed back to the model (file reads / build logs).
    # 24k chars (~6k tokens) lets typical source files land in ONE read — at
    # 12k a 16.5k-char page.tsx got cut off and the model re-read it in pieces,
    # burning rounds. Files larger than this should be paged with offset/limit.
    rounds = settings.max_tool_rounds_code if code_mode else settings.max_tool_rounds
    # Per-user override of the code-mode round cap (Coder settings → Max steps).
    if code_mode:
        _mr = (tool_context or {}).get("max_rounds_code")
        if isinstance(_mr, int) and _mr > 0:
            rounds = min(_mr, 100)
    if subagent:
        rounds = min(rounds, _MAX_SUBAGENT_ROUNDS)
    active_specs = CODE_TOOL_SPECS if code_mode else TOOL_SPECS
    # Remote MCP tools (main.py resolves the user's enabled servers per turn).
    mcp_specs = (tool_context or {}).get("mcp_specs") or []
    if mcp_specs:
        active_specs = list(active_specs) + list(mcp_specs)
    if subagent:
        # A subagent runs autonomously and can't recurse — strip ask_user +
        # spawn_subagent from its toolset.
        active_specs = [s for s in active_specs
                        if s["function"]["name"] not in _SUBAGENT_EXCLUDED_TOOLS]
    subagents_spawned = 0  # bounded fan-out per turn (see _MAX_SUBAGENTS_PER_TURN)
    tool_result_cap = 24000 if code_mode else 3000
    empty_retries = 0  # code-mode misfires: reasoned, then no tool call / no text
    tools_dispatched = 0  # code mode: guards a "did it" claim with no real action
    changed_files: list[str] = []  # paths actually written/edited this turn (ground truth)
    # diff-approval gate — pref AND the master switch must both be on. The
    # switch defaults OFF so a stale client re-saving review_edits=true can't
    # silently make every edit pay the approval timeout. See settings.code_review_gate.
    review_edits = bool((tool_context or {}).get("review_edits")) and settings.code_review_gate
    # Auto-verify is on unless the user turned it off (Coder settings).
    verify_edits = (tool_context or {}).get("verify_edits", True) is not False
    verify_attempts = 0  # post-edit verification cycles forced (bounded, see MAX_VERIFY)
    MAX_VERIFY = 2
    total_misfires = 0   # #7: GLOBAL misfire count — empty_retries resets on progress,
    MAX_TOTAL_MISFIRES = 12  # this doesn't, so a real/misfire/real/misfire stall still ends
    last_call_sig = None  # #7: detect the model repeating the identical tool call
    repeat_count = 0

    for round_num in range(rounds):
        # Re-evaluate task hints each round so a hint can fire mid-conversation
        # when a tool result first reveals the task type (e.g. round 1 lists
        # a directory and finds docker-compose.yml — round 2 gets the docker
        # deployment block injected). apply_code_hints is idempotent via its
        # <task-rules name="..."> marker.
        if code_mode and msgs and msgs[0].get("role") == "system":
            msgs[0]["content"] = apply_code_hints(msgs[0]["content"], msgs[1:])
            _hint_keys = active_hint_keys(msgs[0]["content"])
            _hint_str = f" hints=[{','.join(_hint_keys)}]" if _hint_keys else ""
            # #10: keep the growing tool-result history from blowing the window.
            _elided = _compact_history(msgs)
            if _elided:
                print(f"[chat] compacted history — elided {_elided} old tool result(s)")
        else:
            _hint_str = ""
        print(f"[chat] round {round_num+1}/{rounds} model={model}{_hint_str}")
        round_start = time.monotonic()

        text = ""
        tool_calls: list[dict] = []
        usage: dict = {}
        served_model = model  # which model actually answered (stall guard may swap it)
        reasoning_buf = ""
        reasoning_flushed = False

        async for kind, payload in dispatch_stream_round(
            (tool_context or {}).get("user_id"), model, msgs, max_tokens, tools=active_specs,
            temperature=(0.3 if code_mode else None),  # #6: deterministic code edits
            reasoning_effort=reasoning_effort,
        ):
            if kind == "reasoning":
                reasoning_buf += payload
            elif kind == "text":
                # Flush the collected reasoning as one collapsible block the
                # moment real content starts.
                if reasoning_buf and not reasoning_flushed:
                    yield reasoning_block(reasoning_buf)
                    reasoning_flushed = True
                yield payload                         # ← live token stream
            elif kind == "done":
                text = payload["content"]
                tool_calls = payload["tool_calls"]
                usage = payload["usage"]
                served_model = payload.get("model") or model
        # Round produced reasoning but no content (e.g. straight to a tool
        # call) — still surface the thinking.
        if reasoning_buf and not reasoning_flushed:
            yield reasoning_block(reasoning_buf)

        # Strip any chip markup the model hallucinated into its text BEFORE it's
        # appended to the loop context / used for footers — stops the round-over
        # -round snowball. (The live stream above already went out raw, but with
        # this the model never re-sees chips, so it stops emitting them.)
        text = _strip_tool_chips(text)

        models_used.add(served_model)
        for k in total_usage:
            total_usage[k] += usage.get(k, 0)

        # Per-round timing + token breakdown, so a slow run can be diagnosed
        # ("round 3: 55s for 6k tokens, almost all reasoning") rather than
        # guessed at. reasoning/text char counts show where the time went.
        # When Anthropic prompt-caching is in play, cache=R/W reveals how
        # many input tokens hit the cache (R) vs. were newly cached (W).
        _tool_names = ",".join(tc["name"] for tc in tool_calls) or "-"
        _cache_r = usage.get("cache_read_tokens") or 0
        _cache_w = usage.get("cache_creation_tokens") or 0
        _cache_str = f" cache=R{_cache_r}/W{_cache_w}" if (_cache_r or _cache_w) else ""
        print(
            f"[chat] round {round_num+1}/{rounds} done in "
            f"{time.monotonic() - round_start:.1f}s | "
            f"↑{usage.get('prompt_tokens', 0)} ↓{usage.get('completion_tokens', 0)}"
            f"{_cache_str} "
            f"| reasoning={len(reasoning_buf)}c text={len((text or '').strip())}c "
            f"tools={len(tool_calls)}[{_tool_names}]"
        )

        # #9: if a code round produced no real tool call AND no visible answer,
        # the model may have written the call into its reasoning channel — try
        # to recover it before treating the round as a dead misfire.
        if code_mode and not tool_calls and not (text or "").strip():
            _rec = _recover_tool_call(reasoning_buf, text)
            if _rec is not None:
                print(f"[chat] recovered tool call '{_rec[0]['name']}' from reasoning channel")
                tool_calls = _rec

        if not tool_calls:
            # A code-mode round that produced neither a tool call nor any
            # visible text is a misfire — the model reasoned, then stalled.
            # Nudge it to actually act rather than returning a blank reply.
            if code_mode and not (text or "").strip():
                # gpt-oss intermittently emits a tool call into its reasoning
                # channel instead of actually calling it; a generous budget
                # (misfires cost ~1s each) rides out the bad rounds. empty_retries
                # resets on progress, so total_misfires (#7) is the GLOBAL backstop
                # that stops a real/misfire/real/misfire stall from running to 40.
                total_misfires += 1
                if total_misfires <= MAX_TOTAL_MISFIRES and empty_retries < 6:
                    empty_retries += 1
                    msgs.append({
                        "role": "user",
                        "content": "You haven't called any tool yet — nothing "
                                   "has actually happened. To create files, run "
                                   "code, or change anything you MUST call a "
                                   "tool (write_file, run_bash, etc.). Never "
                                   "claim to have done something you have not. "
                                   "Call a tool now.",
                    })
                    continue
                yield (
                    "I couldn't make progress on that — try rephrasing it or "
                    "giving a more specific instruction."
                )
            # Post-edit verification (#3): the model thinks it's done and it
            # changed files — run a project-appropriate check before letting it
            # off. On failure, feed the errors back and force another round
            # (bounded by MAX_VERIFY so a pre-existing/stuck error can't loop).
            # This closes the "builds-green-but-actually-broken" gap that
            # advisory hints kept missing.
            if code_mode and changed_files and verify_edits:
                try:
                    verdict = await run_verification(
                        changed_files, lambda p: _sandbox_call("/exec", p)
                    )
                except Exception:
                    verdict = None
                if verdict and verdict["status"] == "fail":
                    if verify_attempts < MAX_VERIFY:
                        verify_attempts += 1
                        print(f"[chat] verify FAIL ({verdict['label']}) "
                              f"attempt {verify_attempts}/{MAX_VERIFY} — forcing a fix round")
                        msgs.append({
                            "role": "user",
                            "content": (
                                f"⚠ Automated check `{verdict['label']}` FAILED on "
                                f"your edits (attempt {verify_attempts}/{MAX_VERIFY}):\n\n"
                                f"{verdict['output']}\n\n"
                                "Fix these errors. Do not claim the task is done "
                                "until this check passes."
                            ),
                        })
                        continue
                    # Out of fix attempts — terminate, but tell the truth.
                    yield (
                        f"\n\n> ⚠️ **`{verdict['label']}` still failing** after "
                        f"{MAX_VERIFY} fix attempts — the code above likely does "
                        f"not compile:\n```\n{verdict['output'][:1500]}\n```"
                    )
                elif verdict and verdict["status"] == "pass":
                    yield f"\n\n> ✓ **Verified:** `{verdict['label']}` passed."
            # Ground-truth footer: list the files actually written/edited this
            # turn (and flag claimed-but-unmade changes). Replaces the old
            # all-or-nothing "No tools ran" check, which stayed silent whenever
            # ANY tool ran — so a read-then-fabricate turn slipped through.
            if code_mode:
                _fcf = _file_change_footer(changed_files, text or "")
                if _fcf:
                    yield _fcf
            # Done. Emit the stats footer.
            print(
                f"[chat] run complete: {round_num+1} round(s) in "
                f"{time.monotonic() - start:.1f}s | "
                f"↑{total_usage['prompt_tokens']} ↓{total_usage['completion_tokens']} "
                f"| tools_dispatched={tools_dispatched}"
            )
            footer = "" if subagent else stats_footer(models_used, total_usage, time.monotonic() - start, summarized=summarized, provider_names=provider_names)
            if footer:
                yield f"\n\n---\n{footer}"
            if stats_out is not None:
                stats_out.update(total_usage)
            return

        # A productive round (the model actually called a tool) clears the
        # misfire budget, so intermittent misfires between real progress don't
        # accumulate toward the give-up limit.
        empty_retries = 0

        # #7: detect the IDENTICAL tool call(s) issued round after round — a
        # stuck loop making no progress. Nudge once, then hard-stop.
        _sig = json.dumps(
            sorted((tc["name"], json.dumps(tc["arguments"], sort_keys=True, default=str))
                   for tc in tool_calls)
        )
        if _sig == last_call_sig:
            repeat_count += 1
        else:
            repeat_count, last_call_sig = 0, _sig
        if repeat_count == 2:
            msgs.append({
                "role": "user",
                "content": "You've issued the same tool call several times with no "
                           "new result. Stop repeating it — try a different approach, "
                           "or if you're done, stop and summarize what you changed.",
            })
        elif repeat_count >= 4:
            yield ("\n\n> ⚠️ **Stopping** — the model repeated the same action "
                   "without making progress.")
            print(f"[chat] run aborted: identical tool call repeated x{repeat_count + 1}")
            footer = "" if subagent else stats_footer(models_used, total_usage, time.monotonic() - start, summarized=summarized, provider_names=provider_names)
            if footer:
                yield f"\n\n---\n{footer}"
            if stats_out is not None:
                stats_out.update(total_usage)
            return

        # Append assistant message that contained the tool calls
        msgs.append({
            "role": "assistant",
            "content": text or "",
            "tool_calls": [
                {
                    "id": tc["id"],
                    "type": "function",
                    "function": {"name": tc["name"], "arguments": json.dumps(tc["arguments"])},
                }
                for tc in tool_calls
            ],
        })

        # ask_user: the model needs a decision/clarification before it can
        # continue. Render an interactive question card and END the turn here —
        # the user's next message (a tapped option or free text) drives the
        # continuation, and the working-set + plan carry the task context across
        # the turn. Asking means "stop and wait", so any other tool calls in the
        # same round are intentionally ignored.
        _ask = next((tc for tc in tool_calls if tc["name"] == "ask_user"), None)
        if code_mode and _ask is not None:
            _args = _ask["arguments"] or {}
            yield _ask_user_block(_args.get("question") or "", _args.get("options") or [])
            _fcf = _file_change_footer(changed_files, text or "")
            if _fcf:
                yield _fcf
            print(f"[chat] ask_user — turn paused for user input after "
                  f"{round_num+1} round(s)")
            footer = "" if subagent else stats_footer(models_used, total_usage, time.monotonic() - start, summarized=summarized, provider_names=provider_names)
            if footer:
                yield f"\n\n---\n{footer}"
            if stats_out is not None:
                stats_out.update(total_usage)
            return

        # Dispatch each tool and yield a chip
        for tc in tool_calls:
            if tc["name"] not in ALLOWED_TOOL_NAMES:
                print(f"[chat] skipping unknown tool '{tc['name']}'")
                continue

            # Truncated tool call: the model blew the response-token cap mid-call
            # (almost always rewriting a whole large file via edit_file/write_file).
            # The args are garbage, so DON'T dispatch — that wastes a ~2-min round
            # on a failed edit and can wedge the client. Steer to small edits.
            if tc.get("truncated"):
                print(f"[chat] truncated tool call '{tc['name']}' — steering to smaller edits")
                yield tool_chip(tc, "⚠ Edit too large — it exceeded the response limit and was cut off (not applied).")
                msgs.append({
                    "role": "tool",
                    "tool_call_id": tc["id"],
                    "content": (
                        "Your last tool call was CUT OFF at the response-length "
                        "limit — its arguments were incomplete, so nothing was "
                        "applied. You are trying to write too much at once. Do NOT "
                        "rewrite the whole file. Make a SMALL, focused change: use "
                        "apply_patch with just the few hunks that change, or "
                        "edit_file on one specific region. Split large work across "
                        "several small calls."
                    ),
                })
                tools_dispatched += 1
                continue

            tools_dispatched += 1

            # Device-fulfilled tool: emit a client_tool control event, then
            # block (still inside this one streaming request — the SSE
            # keepalive holds the connection) until the app POSTs the result
            # back to /chat/tool_result.
            if tc["name"] in CLIENT_TOOL_NAMES:
                call_id = tc["id"]
                yield {
                    "type": "client_tool",
                    "call_id": call_id,
                    "name": tc["name"],
                    "arguments": tc["arguments"] or {},
                }
                # Truncate BEFORE the chip so both the chip and the model see
                # the same capped result (parity with server-side tools).
                tool_result = (await request_client_tool(call_id))[:3000]
                yield tool_chip(tc, tool_result)
                msgs.append({
                    "role": "tool",
                    "tool_call_id": tc["id"],
                    "content": tool_result,
                })
                continue

            # spawn_subagent: delegate a focused subtask to a fresh, bounded
            # agent that shares this workspace. We run a nested code-mode loop,
            # stream its work inline (the user sees it happen), and feed a
            # concise summary back to the parent as the tool result.
            if code_mode and not subagent and tc["name"] == "spawn_subagent":
                _task = str((tc["arguments"] or {}).get("task") or "").strip()
                if not _task:
                    msgs.append({"role": "tool", "tool_call_id": tc["id"],
                                 "content": "Error: spawn_subagent requires a 'task'."})
                    continue
                if subagents_spawned >= _MAX_SUBAGENTS_PER_TURN:
                    msgs.append({"role": "tool", "tool_call_id": tc["id"],
                                 "content": f"Subagent limit reached "
                                 f"({_MAX_SUBAGENTS_PER_TURN}/turn). Do the rest "
                                 "yourself or finish and report."})
                    continue
                subagents_spawned += 1
                print(f"[chat] spawning subagent {subagents_spawned}: {_task[:80]}")
                yield f"\n\n> 🤖 **Subagent {subagents_spawned}** — {_task[:120]}\n\n"
                _sub_acc = ""
                # Subagent edits are NOT routed through the parent's approval UI
                # (it runs autonomously); the parent still sees every change.
                _sub_ctx = {**(tool_context or {}), "review_edits": False}
                try:
                    async for _sc in run_chat(
                        [{"role": "user", "content": _task}],
                        model=model, max_tokens=max_tokens, tool_context=_sub_ctx,
                        code_mode=True, subagent=True, touched_out=touched_out,
                    ):
                        if isinstance(_sc, str):
                            _sub_acc += _sc
                            yield _sc
                        # subagent has no client_tool/ask_user — ignore stray dicts
                except Exception as e:
                    _sub_acc += f"\n[subagent error: {e}]"
                    print(f"[chat] subagent error: {e}")
                # Feed the parent a clean summary: strip chips/footer, tail-cap
                # (the subagent's closing summary is at the end).
                _result = _STATS_FOOTER_RE.sub("", _strip_tool_chips(_sub_acc)).strip()
                if len(_result) > 4000:
                    _result = "…\n" + _result[-4000:]
                yield "\n\n> 🤖 *Subagent finished.*\n\n"
                msgs.append({"role": "tool", "tool_call_id": tc["id"],
                             "content": _result or "(subagent produced no output)"})
                continue

            # deploy_project: ship edits live (rebuild + restart a whitelisted
            # project). MANDATORY approval on EVERY deploy — independent of the
            # diff-approval "review edits" gate — and a no-response ABORTS. A
            # deploy is higher-consequence than an edit, so the fail-safe is
            # "don't ship" (the OPPOSITE of the edit gate's auto-apply). The
            # sandbox can't reach host docker, so this backend interception is
            # the only path from the model to a restart; the target is a fixed
            # server-side whitelist. Subagents may not deploy.
            if code_mode and tc["name"] == "deploy_project":
                _proj = str((tc["arguments"] or {}).get("project") or "").strip()
                if subagent:
                    msgs.append({"role": "tool", "tool_call_id": tc["id"],
                                 "content": "Subagents cannot deploy. Finish and let the parent deploy."})
                    continue
                if _proj not in DEPLOY_TARGETS:
                    msgs.append({"role": "tool", "tool_call_id": tc["id"],
                                 "content": f"Unknown deploy target '{_proj}'. Allowed: {target_keys()}."})
                    continue
                call_id = tc["id"]
                # Reuse the approval rendezvous + card (approve_edit event).
                yield {
                    "type": "approve_edit",
                    "call_id": call_id,
                    "name": "deploy_project",
                    "paths": [f"Deploy: {_proj}"],
                    "diff": deploy_plan(_proj),
                }
                decision = (await request_client_tool(
                    call_id, timeout=float(settings.code_review_timeout)
                )).strip().lower()
                if not decision.startswith("approve"):
                    # Reject OR no-response → do NOT deploy. Approval is mandatory.
                    print(f"[chat] deploy '{_proj}' NOT approved (decision={decision!r}) — skipping")
                    yield tool_chip(tc, f"✗ Deploy of {_proj} not approved — nothing rebuilt or restarted.")
                    msgs.append({"role": "tool", "tool_call_id": tc["id"], "content": (
                        f"The deploy of '{_proj}' was NOT approved, so nothing was built or "
                        "restarted. Do not retry automatically — ask the user how to proceed.")})
                    continue
                tools_dispatched += 1
                yield f"\n\n> 🚀 *Deploying {_proj} — rebuilding and restarting…*\n\n"
                _ok, _log = await run_deploy(_proj)
                print(f"[chat] deploy '{_proj}' {'OK' if _ok else 'FAILED'}")
                yield tool_chip(tc, _log)
                msgs.append({"role": "tool", "tool_call_id": tc["id"], "content": _log[:3000]})
                continue

            # Diff-approval gate: when the user has "Review edits" on, an edit
            # tool pauses BEFORE writing. We dry-run it to get the diff, emit an
            # approve_edit event, and block (same rendezvous as client tools)
            # until the user taps Apply/Reject. On reject we skip the write and
            # tell the model; on approve we fall through to the real call below.
            # If the preview errors or is empty, we DON'T gate — the real call
            # surfaces the error / there's nothing to approve.
            if code_mode and review_edits and tc["name"] in EDIT_TOOL_NAMES:
                _diff, _perr, _paths = await preview_edit(tc["name"], tc["arguments"] or {})
                if _perr is None and _diff and _diff.strip():
                    call_id = tc["id"]
                    yield {
                        "type": "approve_edit",
                        "call_id": call_id,
                        "name": tc["name"],
                        "paths": _paths,
                        "diff": _diff[:8000],
                    }
                    # Wait for the user's Apply/Reject. The timeout is generous
                    # (settings.code_review_timeout, default 30 min) so it
                    # effectively waits for you — it won't rush ahead. Only an
                    # explicit "reject" blocks the edit; if the wait is genuinely
                    # exhausted (run abandoned) the fail-safe APPLIES rather than
                    # hanging the task forever or silently rejecting your work.
                    decision = (await request_client_tool(
                        call_id, timeout=float(settings.code_review_timeout)
                    )).strip().lower()
                    if decision.startswith("reject"):
                        print(f"[chat] edit REJECTED by user: {tc['name']} {_paths}")
                        yield tool_chip(tc, f"✗ Rejected by user — {', '.join(_paths) or 'edit'} NOT changed.")
                        msgs.append({
                            "role": "tool",
                            "tool_call_id": tc["id"],
                            "content": (
                                "The user REJECTED this edit; it was NOT applied. Do "
                                "not re-apply the same change. Ask what they'd prefer, "
                                "or revise your approach based on their guidance."
                            ),
                        })
                        continue
                    if not decision.startswith("approve"):
                        # No usable answer — apply anyway so the turn makes progress.
                        print(f"[chat] edit approval timed out/no-response — auto-applying: {tc['name']} {_paths}")
                        yield "\n\n> ⏱️ *No approval response — applying the edit and continuing.*\n\n"

            # Pre-yield a status line for slow tools so the user has something
            # to look at while we wait (image gen can take 30-90s on Dev; the
            # specialist text models can take 10-30s for long synthesis). The
            # line stays in the message but reads naturally as "what happened".
            _slow_tools = {
                "generate_image": "Generating image",
                "edit_image":     "Editing image",
                "consult_coder":  "Consulting coder",
                "deep_research":  "Researching",
            }
            _busy_label = _slow_tools.get(tc["name"])
            if _busy_label:
                yield f"\n\n*⏳ {_busy_label}… (this can take 30–90s)*\n\n"

            tool_result = await dispatch(tc["name"], tc["arguments"], tool_context)
            # Record file activity. changed_files → the ground-truth footer;
            # touched_out → the working set the caller persists, so files this
            # turn opened are pre-loaded next turn instead of re-read.
            _res_lc = (tool_result or "").lstrip().lower()
            _errored = _res_lc.startswith("error")
            if tc["name"] in ("read_file", "write_file", "edit_file") and not _errored:
                _p = (tc["arguments"] or {}).get("path")
                if _p:
                    _p = str(_p)
                    if tc["name"] in ("write_file", "edit_file"):
                        changed_files.append(_p)
                    if touched_out is not None:
                        touched_out.append(_p)
            elif tc["name"] == "apply_patch" and not _errored and "changed nothing" not in _res_lc:
                # apply_patch has no single `path` arg — pull the changed files
                # from the V4A patch headers. Without this a successful patch was
                # invisible to the footer, so it falsely warned "nothing changed"
                # (the footer's most confusing false alarm — observed on real runs).
                _patch = str((tc["arguments"] or {}).get("patch") or "")
                for _p in _PATCH_FILE_RE.findall(_patch):
                    _p = _p.strip()
                    if _p:
                        changed_files.append(_p)
                        if touched_out is not None:
                            touched_out.append(_p)
            models_used.add(
                _specialist_model_for(tc["name"], tool_context, tc.get("arguments")) or model
            )
            yield tool_chip(tc, tool_result)

            # generate_image / edit_image return markdown that contains the
            # rendered image; surface it inline (outside the escaped <details>
            # body) so the browser actually renders the <img>. Then replace
            # the markdown in the tool message Gemma sees with a brief note
            # so Gemma doesn't echo the same image link again.
            tool_msg_for_model = tool_result
            is_image_tool = tc["name"] in ("generate_image", "edit_image")
            has_img_md = "![Generated Image](" in tool_result or "![Edited Image](" in tool_result
            if is_image_tool and has_img_md:
                yield "\n\n" + tool_result + "\n\n"
                verb = "edited" if tc["name"] == "edit_image" else "generated"
                tool_msg_for_model = (
                    f"Image {verb} successfully and already shown to the user. "
                    "Reply with ONLY one short plain sentence acknowledging it "
                    "(e.g. \"Here's your image!\"). Do NOT write image markdown, "
                    "model names, italic text, tool-call syntax, or any "
                    "description of the image."
                )
            elif tc["name"] == "code_interpreter" and tool_result.strip():
                # Render the kernel's output (stdout, tables, math, plots)
                # inline — outside the escaped chip — so the rich
                # representations actually display. The model gets the same
                # text with image links collapsed to a short note.
                yield "\n\n" + tool_result + "\n\n"
                model_view = tool_result
                for im in re.findall(r"!\[[^\]]*\]\(/generated/[^)]+\)", tool_result):
                    model_view = model_view.replace(im, "[plot shown to user]")
                tool_msg_for_model = (
                    model_view.strip()
                    + "\n\n[Output already shown to the user. Briefly interpret "
                    "the result in one or two sentences; do NOT repeat tables, "
                    "image markdown, or tool-call syntax.]"
                )

            if len(tool_msg_for_model) > tool_result_cap:
                # Keep BOTH ends: a build/test log's verdict (error summary,
                # failure count) is at the END, so a head-only clip hands the
                # model a passing-looking log. 60% head + 40% tail.
                _head = (tool_result_cap * 6) // 10
                _tail = tool_result_cap - _head
                _dropped = len(tool_msg_for_model) - tool_result_cap
                tool_msg_for_model = (
                    tool_msg_for_model[:_head]
                    + f"\n… [{_dropped} chars truncated] …\n"
                    + tool_msg_for_model[-_tail:]
                )
            msgs.append({
                "role": "tool",
                "tool_call_id": tc["id"],
                "content": tool_msg_for_model,
            })

    # Max rounds — final synthesis pass without tools. #8: in code mode, hitting
    # the round cap means the task likely ISN'T finished, so don't prompt for a
    # "complete answer" (that invites a fabricated done-claim). Ask for an honest
    # status instead, and warn the user the cap was hit.
    if code_mode:
        synth_prompt = (
            "You've reached the maximum number of tool rounds for this turn, so "
            "you must stop here. Do NOT claim the task is complete. Briefly and "
            "honestly summarize: what you actually changed (which files), what you "
            "verified, and what still remains to be done."
        )
    else:
        synth_prompt = "Based on the information gathered above, provide a clear, complete answer."
    msgs.append({"role": "user", "content": synth_prompt})
    fr_reasoning = ""
    fr_text = ""
    fr_flushed = False
    async for kind, payload in dispatch_stream_round(
        (tool_context or {}).get("user_id"), model, msgs, max_tokens, tools=None,
        temperature=(0.3 if code_mode else None),  # #6
    ):
        if kind == "reasoning":
            fr_reasoning += payload
        elif kind == "text":
            if fr_reasoning and not fr_flushed:
                yield reasoning_block(fr_reasoning)
                fr_flushed = True
            fr_text += payload
            yield payload
        elif kind == "done":
            for k in total_usage:
                total_usage[k] += (payload.get("usage") or {}).get(k, 0)
            models_used.add(payload.get("model") or model)
    if fr_reasoning and not fr_flushed:
        yield reasoning_block(fr_reasoning)

    # Same ground-truth footer on the max-rounds exit, plus an explicit
    # cap-hit warning (#8) so a half-done task doesn't read as finished.
    if code_mode:
        yield ("\n\n> ⚠️ **Hit the max tool-round limit for this turn** — the work "
               "may be incomplete. Send another message to have it continue.")
        _fcf = _file_change_footer(changed_files, _strip_tool_chips(fr_text))
        if _fcf:
            yield _fcf

    print(
        f"[chat] run complete (hit max {rounds} rounds) in "
        f"{time.monotonic() - start:.1f}s | ↑{total_usage['prompt_tokens']} "
        f"↓{total_usage['completion_tokens']}"
    )
    footer = "" if subagent else stats_footer(models_used, total_usage, time.monotonic() - start, summarized=summarized, provider_names=provider_names)
    if footer:
        yield f"\n\n---\n{footer}"
    if stats_out is not None:
        stats_out.update(total_usage)


def _specialist_model_for(
    tool_name: str, tool_context: dict = None, args: dict = None,
) -> str:
    """For stats accounting: deep_research, consult_coder, and image tools
    call out to a different model internally; record that in models_used.
    Honors per-user `tool_models` overrides and the per-call `hd` flag so the
    footer shows the model that actually ran. Image resolution delegates to
    tools.resolve_image_model — the single source of truth for the HD→SD
    precedence chain (no duplicated logic)."""
    tm = ((tool_context or {}).get("tool_models") or {})
    args = args or {}
    if tool_name == "consult_coder":
        return tm.get("code") or settings.model_writer
    if tool_name == "deep_research":
        return tm.get("research") or settings.model_research
    if tool_name in ("generate_image", "edit_image"):
        hd = bool(args.get("hd", False))
        tool_key = "image_edit" if tool_name == "edit_image" else "image"
        return resolve_image_model(tool_context or {}, tool_key, hd)
    return ""
