"""Task-specific hint blocks for code-mode system prompts.

Code mode covers everything from "fix a typo in a Python file" to "deploy a
self-hosted service via docker compose". The base prompt (see
`chat.code_system_prompt`) holds rules that apply to ANY coding task — tools,
sandbox capabilities, git etiquette, working rules. Anything that's
task-specific lives here as a hint.

A hint is appended to the system message ONLY when its `matches()` predicate
returns true for the current conversation. Once applied, the hint's
`<task-rules name="...">` marker stays in the system message, so subsequent
calls to `apply_code_hints` are idempotent — no duplication, no churn.

Why this lives outside the base prompt:
- The deployment hint alone is ~5 KB. Sending it for every "rename a
  function" task is a waste of input tokens and risks behavioral drift
  (model pattern-matches docker keywords on non-docker tasks).
- Adding a new task hint (database migrations, frontend builds, mobile
  scaffolds, …) is one entry in `CODE_HINTS` — no prompt-engineering
  surgery on a monolithic string.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Callable


@dataclass(frozen=True)
class CodeHint:
    """A task-specific block of guidance.

    `matches(messages)` decides whether the hint is relevant given the
    conversation so far (user text, prior assistant text, tool calls, tool
    results). `body` is the prose injected into the system prompt.
    """

    key: str
    description: str
    matches: Callable[[list[dict]], bool]
    body: str


# ─── Matching helpers ────────────────────────────────────────────────────────

def _collect_text(messages: list[dict]) -> str:
    """Concatenate task-signal text from a message list, lowercased.

    Scans only:
      - role=user content (user's stated intent)
      - role=assistant content (model's own narration)
      - role=assistant tool_calls[].function.arguments (the model COMMITTING
        to act on a path/term — e.g. `read_file docker-compose.yml`)

    Explicitly skips:
      - role=tool content (tool RESULTS contain incidental keywords —
        directory listings, log output, README excerpts — that the user
        never intended as task signals; observed 2026-05-26 when a `list_dir
        .` on a CSV-dedup chat returned a workspace containing
        `docker-compose.yml` and `GarminWatchface.monkey`, falsely firing
        both docker_deployment and niche_language hints)
      - role=system content (would self-trigger once a hint is appended)
    """
    parts: list[str] = []
    for m in messages:
        role = m.get("role", "")
        if role not in ("user", "assistant"):
            continue
        content = m.get("content")
        if isinstance(content, str) and content:
            parts.append(content)
        if role == "assistant":
            for tc in (m.get("tool_calls") or []):
                fn = tc.get("function") or {}
                args = fn.get("arguments")
                if isinstance(args, str) and args:
                    parts.append(args)
    return "\n".join(parts).lower()


def _any_keyword(needles: list[str]) -> Callable[[list[dict]], bool]:
    """Build a matcher that fires when any needle is present in the
    conversation text. Needles are lowercased once at build time."""
    needles_lower = [n.lower() for n in needles]

    def matcher(messages: list[dict]) -> bool:
        text = _collect_text(messages)
        return any(n in text for n in needles_lower)

    return matcher


# ─── Hint: Docker / self-hosted service deployment ───────────────────────────

DOCKER_DEPLOYMENT = CodeHint(
    key="docker_deployment",
    description="Docker compose authoring, pre-handoff checklist, and "
                "sandbox-vs-host diagnostic discipline.",
    matches=_any_keyword([
        "docker", "compose.yml", "compose.yaml", "dockerfile", "podman",
    ]),
    body=(
        "Sandbox is a workshop, NOT a deployment target. When the user asks "
        "you to 'set up', 'install', 'host', or 'run' a service on their "
        "server (a database, DNS server, reverse proxy, monitoring stack, "
        "media server, etc.) — your job is to AUTHOR the configuration into "
        "a project mount, then tell the user the host commands to run. The "
        "sandbox is unprivileged, has no host docker access, and is never "
        "the machine the service will live on.\n"
        "  - Write the docker-compose.yml, Dockerfile, env files, and "
        "config into `/workspace/projects/<name>/` (create a subdir for "
        "the new service). Use ABSOLUTE paths in tool calls — relative "
        "paths resolve against the agent's cwd and usually fail. If no "
        "suitable mount exists, stop and ask the user to add one via "
        "Files → Mounts before continuing.\n"
        "  - For any well-known service (AdGuard Home, Pi-hole, Postgres, "
        "nginx/caddy/traefik, Plex, Home Assistant, Grafana, etc.) USE THE "
        "OFFICIAL PREBUILT IMAGE — e.g. `image: adguard/adguardhome:latest`. "
        "Do NOT invent a build-from-source Dockerfile (`FROM golang && go "
        "build`, `pip install` of upstream source) — a hallucinated builder "
        "is always broken (missing source, wrong entry). Custom Dockerfiles "
        "only when the user explicitly asks or no published image exists.\n"
        "  - If the mount already holds files from a prior attempt, "
        "evaluate them critically before patching. Red flags: a language-"
        "toolchain Dockerfile for a service that ships a prebuilt image; "
        "configs whose schema doesn't match the real upstream; references "
        "to source files that don't exist. When you spot this, STOP "
        "patching, tell the user the existing files are incoherent, and "
        "offer to replace them with the standard image-based setup — "
        "layering fixes onto an impossible foundation never converges.\n"
        "  - Do NOT `apt install` the service in the sandbox, do NOT "
        "`curl | bash` install docker, do NOT try sudo/su — the sandbox is "
        "unprivileged by design and every one of those is a dead end.\n"
        "  - Modern compose: no top-level `version:` field (v2 ignores it "
        "and warns) — drop it from files you edit.\n"
        "  - Do NOT set `container_name:` on generic sidecars "
        "(`cloudflared`, `traefik`, `nginx`, `redis`, `postgres`, "
        "`watchtower`, …). The user likely already runs one for another "
        "stack, and fixed names collide across compose projects "
        "('The container name is already in use'). Compose v2 auto-names "
        "`<project>-<service>-N`, which namespaces them per project. "
        "Reserve `container_name:` for the stack's primary service that "
        "must be addressed by a stable hostname from outside its project — "
        "that is rare.\n\n"
        "Pre-handoff checklist — MANDATORY before saying 'ready to "
        "deploy', INCLUDING when the compose file already existed (prior-"
        "attempt files are exactly where validation catches the most "
        "bugs). Reading a plausible-looking file is NOT validation:\n"
        "  1. Fetch the image's upstream docs with `curl` EVERY time — "
        "your training-data memory of volume paths and ports is often a "
        "release or two behind. Docker Hub overview: `curl -sL "
        "https://hub.docker.com/v2/repositories/<org>/<image>/` (the "
        "`full_description` field holds the shipped README), or the "
        "project README: `curl -sL "
        "https://raw.githubusercontent.com/<org>/<repo>/main/README.md` "
        "(or `master`). Save to `/tmp/<svc>-upstream.txt` and grep for "
        "`VOLUME`, `EXPOSE`, 'initial setup', 'first run', 'setup "
        "wizard'.\n"
        "  2. Confirm each `volumes:` target is a directory the image "
        "actually writes to. `/etc/<service>` and `/var/<service>` are "
        "common GUESSES that are often wrong — many modern images use "
        "`/opt/<service>/...` or `/config`.\n"
        "  3. Confirm `ports:` includes any first-boot / setup-wizard "
        "port — many services (AdGuard, Gitea, Vaultwarden, the *arr "
        "stack, …) run initial setup on a port DIFFERENT from the steady-"
        "state UI; miss it and the user gets ERR_CONNECTION_REFUSED on "
        "first try.\n"
        "  4. Validate with `docker compose -f <file> config` and READ "
        "the merged output — missing ports and wrong volume paths show up "
        "plainly; resolve any warnings. This is the ONLY docker command "
        "that works here: `up`, `build`, `run`, `ps`, `pull` etc. all "
        "fail with 'Cannot connect to the Docker daemon', and that is the "
        "EXPECTED sandbox state, not a bug to fix.\n"
        "  5. Only then write a short README (or inline summary) with the "
        "exact host commands: `docker compose up -d`, the first-boot URL "
        "including any setup port, and port-conflict / firewall steps if "
        "relevant.\n\n"
        "Diagnostic locality: every tool call runs in YOUR sandbox — its "
        "results describe the sandbox, never the user's host. When the "
        "user reports a problem on their server ('connection refused', "
        "'container exited', 'port already in use'), do NOT run sandbox "
        "commands (`docker ps`, `docker logs`, `netstat`, `ss`, `curl "
        "http://localhost:...`) and treat the output as evidence about "
        "their machine — and never tell them to start their docker daemon "
        "because YOURS is unreachable. Reason from: (a) the files you "
        "authored — re-read them for missing ports, wrong volume paths, "
        "typos; (b) the user's exact symptom; (c) upstream docs you can "
        "`curl`. If only the user's host can answer (real `docker ps` "
        "output, real container logs, what's on a port), ASK the user to "
        "run a specific command and paste the output back."
    ),
)


# ─── Hint: Niche / poorly-known languages ────────────────────────────────────

NICHE_LANGUAGE = CodeHint(
    key="niche_language",
    description="Languages where training-data priors hallucinate "
                "(Monkey C / Garmin, Solidity, Roblox Lua, embedded toolchains).",
    matches=_any_keyword([
        "monkey c", "monkeyc", "monkey-c", ".mc\"", ".mc ",
        "garmin watch", "garmin connect", "connect iq", "ciq sdk",
        "solidity", ".sol\"", ".sol ",
        "roblox lua", "roblox studio", "luau",
    ]),
    body=(
        "Niche-language workflow: for languages or SDKs you don't fully know "
        "(Monkey C / Garmin, Solidity, Roblox Lua, embedded toolchains, etc.) "
        "— DO NOT generate from scratch using your training-data priors. "
        "Instead:\n"
        "  1. Check `/workspace/starters/` for a working template; if one "
        "exists, modify it rather than write fresh.\n"
        "  2. Call `search_docs` against the user's uploaded reference docs "
        "for the right API names, signatures, and project structure.\n"
        "  3. If a compiler/linter for the language is available "
        "(`monkeyc` for Monkey C, `solc` for Solidity, etc.), run it to "
        "verify — don't claim correctness without compiling.\n"
        "Generating niche-language code from training-data alone is "
        "guaranteed to hallucinate; ground yourself in real files first."
    ),
)


# ─── Hint: Prisma + other interactive-only CLIs in the sandbox ───────────────

PRISMA_NON_INTERACTIVE = CodeHint(
    key="prisma_non_interactive",
    description="Prisma + sandbox: avoid interactive-only commands like "
                "`migrate dev`; use the non-interactive equivalents.",
    matches=_any_keyword([
        "prisma", "schema.prisma", "migrate dev",
    ]),
    body=(
        "Prisma in this sandbox: the `/exec` runner allocates no TTY and "
        "has no stdin, so any CLI that prompts will refuse to run "
        "(\"non-interactive environment is not supported\"). For Prisma "
        "specifically:\n"
        "  - `prisma migrate dev` is INTERACTIVE — it prompts about "
        "applying / naming migrations. It will never work here. Reach "
        "for it and you waste a round on the error.\n"
        "  - For a dev DB (SQLite `dev.db`, throwaway Postgres): use "
        "`npx prisma db push` to sync the schema directly. No migration "
        "history, no prompts.\n"
        "  - For a real migration history: hand-create the SQL via "
        "`npx prisma migrate diff --from-empty --to-schema-datamodel "
        "prisma/schema.prisma --script > prisma/migrations/<timestamp>_<name>/migration.sql`, "
        "then apply with `npx prisma migrate deploy` (non-interactive).\n"
        "  - Seed: `npx prisma db seed` works fine once `package.json` "
        "has a `\"prisma\": { \"seed\": \"tsx prisma/seed.ts\" }` block; "
        "or just `npx tsx prisma/seed.ts` directly.\n"
        "Same principle applies to other interactive-first tools — "
        "`create-next-app` needs `--yes`, `npm init` needs `-y`, "
        "`git rebase -i` is unusable, etc. If a command hangs or errors "
        "with 'non-interactive', look for the flag/subcommand that "
        "skips the prompts rather than retrying."
    ),
)


# ─── Hint: Modern JS/TS stack (Next.js / Zod / Prisma) version drift ─────────

JS_TS_STACK = CodeHint(
    key="js_ts_stack",
    description="Next.js / Zod / Prisma / TypeScript breaking changes the "
                "model's training data predates — and typecheck-before-handoff.",
    matches=_any_keyword([
        "next.js", "nextjs", "next build", "next start", "app router",
        "use client", "zod", "z.object", "z.record", "prisma",
        ".tsx", "tsconfig", "tsc ", "npm run build",
    ]),
    body=(
        "Modern JS/TS stack (Next.js, Zod, Prisma, TypeScript): this "
        "ecosystem moves fast and your training-data priors are a major "
        "version or two BEHIND what's installed. Code that looks right from "
        "memory will fail to compile against the real versions. Three rules "
        "override everything else here:\n"
        "  1. COPY THE REPO, don't recall from memory. Before writing a NEW "
        "file, read a sibling that already works and mirror its imports and "
        "conventions exactly. Adding `src/app/api/foo/route.ts`? Read an "
        "existing `route.ts` first and match how IT imports prisma, returns "
        "responses, and validates input. Most version-drift bugs come from "
        "writing a plausible-but-stale pattern when a correct one was sitting "
        "in the next file over.\n"
        "  2. TYPECHECK BEFORE HANDOFF. After editing any .ts/.tsx file, run "
        "`npx tsc --noEmit` (or `npm run build`) and FIX EVERY error before "
        "telling the user it's done. The build stops at the first error, so "
        "re-run until it's clean — a second and third error often hide behind "
        "the first. Never hand off code you haven't typechecked; 'looks "
        "correct' is not verification.\n"
        "  3. DON'T CLOBBER A FILE TO ADD ONE THING. `write_file` replaces the "
        "ENTIRE file — use it only for brand-new files or a full rewrite you "
        "intend. To ADD to an existing file (a new export, a new route handler, "
        "another function), use `edit_file` to insert it; do NOT regenerate the "
        "whole file from memory, because you WILL silently drop the parts you "
        "didn't think to retype. This bites hardest on API routes: a file with "
        "`GET` + `POST` is easy to rewrite as just the `DELETE` you were asked "
        "for, deleting GET/POST without noticing — the app then 405s on the "
        "methods you dropped. After touching any route file, confirm EVERY HTTP "
        "method it had before is still exported (GET, POST, PUT, PATCH, DELETE), "
        "not just the one you added.\n"
        "Specific traps seen on real projects (all caused failed "
        "builds):\n"
        "  - Zod v4: the error object exposes `.issues`, NOT `.errors` "
        "(`err.issues`, not `err.errors`). And `z.record` now requires BOTH "
        "a key and a value schema: `z.record(z.string(), z.any())`, not "
        "`z.record(z.any())`.\n"
        "  - Prisma client singleton is almost always a DEFAULT export: "
        "`import prisma from '@/lib/prisma'`, NOT `import { prisma } from "
        "'@/lib/prisma'`. Check the export line in the lib file before you "
        "import it.\n"
        "  - Prisma 5 has NO `prisma/config` module and no `prisma.config.ts` "
        "— that's a Prisma 6+ feature. Don't create that file; it only "
        "breaks the build's typecheck. Config lives in `schema.prisma` and "
        "`package.json`.\n"
        "  - Next.js App Router: any component using hooks (`useState`, "
        "`useEffect`), browser APIs, or event handlers (`onClick`) MUST have "
        "`\"use client\";` as the literal first line of the file. Without it "
        "the page renders as dead static HTML — buttons do nothing.\n"
        "If a README or `AGENTS.md` in the project warns about version "
        "specifics, treat it as authoritative over your priors and read the "
        "referenced docs before writing code."
    ),
)


# ─── Registry + application ──────────────────────────────────────────────────

# Order matters only for the order hints appear in the final system message.
# Adding a new hint: define it above, add it to this list. No other changes.
CODE_HINTS: list[CodeHint] = [
    DOCKER_DEPLOYMENT,
    NICHE_LANGUAGE,
    PRISMA_NON_INTERACTIVE,
    JS_TS_STACK,
]


def _hint_marker(key: str) -> str:
    return f'<task-rules name="{key}">'


def apply_code_hints(system_message: str, messages: list[dict]) -> str:
    """Return `system_message` with any newly-matched hints appended.

    Idempotent: a hint already present (detected by its `<task-rules>`
    marker) is never re-added. Safe to call every round; the round loop in
    `chat.run_chat` does exactly that so a hint can fire mid-conversation
    when a tool call first reveals the task type (e.g. the model lists a
    directory and finds a `docker-compose.yml`).
    """
    out = system_message
    for hint in CODE_HINTS:
        marker = _hint_marker(hint.key)
        if marker in out:
            continue
        if hint.matches(messages):
            out += (
                f"\n\n{marker}\n"
                f"{hint.body}\n"
                f"</task-rules>"
            )
    return out


def active_hint_keys(system_message: str) -> list[str]:
    """For diagnostics — which hints are currently embedded in the system
    message. Used by the round-log print so a slow run is traceable to
    the hint set in effect."""
    return [h.key for h in CODE_HINTS if _hint_marker(h.key) in system_message]
