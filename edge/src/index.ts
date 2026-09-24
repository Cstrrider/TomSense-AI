/**
 * TomSense edge control plane — Worker entry point.
 *
 * Stateless by design: every request authenticates, does its work against D1 /
 * Vectorize / R2 / a Durable Object, and returns. Nothing here holds session
 * state, which is why it can be globally distributed and why home being down
 * doesn't take chat with it.
 */

import type { Env, Principal, ChatMessage } from "./types";
import { authenticate, issueDeviceToken, issueAuthCode, redeemAuthCode } from "./auth";
import { readShared, setShare } from "./share";
import { uploadFile, serveFile, dataUrl } from "./files";
import { serverToolSchemas } from "./server_tools";
import { parseModelStr, resolveProvider } from "./providers";
import { push, pull, type PushRequest } from "./sync";
import {
  listProviders,
  createProvider,
  updateProvider,
  deleteProvider,
  listModels,
  listImageModels,
  getDefaultModel,
  setDefaultModel,
  resolveChatModel,
  resolveFallbackModel,
  discoverModels,
  PROVIDER_PRESETS,
} from "./providers_api";
import { routeChat } from "./routing";
import { buildContext } from "./context";
import { mcpToolSurface } from "./mcp";
import { handleFeatures } from "./features";
import { handleMcpServer } from "./mcp_server";
import { runDueSchedules } from "./schedules";
import { runTaskModel } from "./task_model";
import { getPrefs, setPrefs, setAnalyticsKey, hasAnalyticsKey } from "./prefs";
import { usageToday } from "./usage";
import { synthesize, TTS_VOICES } from "./tts";

export { VoiceSession } from "./do/voice";
export { DetachedRun } from "./do/run";
export { HomeLink } from "./do/homelink";

/**
 * CF Access team domain + application AUD, from config rather than hardcoded.
 *
 * If ACCESS_AUD is unset we FAIL CLOSED — every Access-JWT request is
 * rejected. The tempting alternative (skip the aud check when unconfigured)
 * would mean a token minted for any other application in the account is
 * accepted here, which is a real privilege-escalation path, not a hypothetical.
 */
function accessConfig(env: Env): { teamDomain: string; aud: string } | null {
  if (!env.ACCESS_TEAM_DOMAIN || !env.ACCESS_AUD) return null;
  return { teamDomain: env.ACCESS_TEAM_DOMAIN, aud: env.ACCESS_AUD };
}

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json" },
  });
}

export default {
  async fetch(req: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(req.url);
    const path = url.pathname;

    // The home agent authenticates with its own shared secret, not a user
    // credential — it acts on behalf of the deployment, not a person.
    if (path === "/homelink/agent") {
      const id = env.HOMELINK.idFromName("default");
      return env.HOMELINK.get(id).fetch(req);
    }

    // Health check is the ONLY unauthenticated route, and it deliberately
    // reveals nothing beyond liveness and whether Access is configured.
    if (path === "/health") {
      return json({ ok: true, accessConfigured: accessConfig(env) !== null });
    }

    // Code exchange is UNAUTHENTICATED by necessity — the app has no
    // credential yet; that is the point of the flow. It is protected by the
    // PKCE verifier instead, so an intercepted code alone is not enough.
    if (path === "/auth/exchange" && req.method === "POST") {
      return await authExchange(req, env);
    }

    // Public share links. MUST be resolved before `authenticate`, because the
    // whole point is that a recipient has no credential — the token in the
    // path is the entire authorisation. See share.ts.
    if (path.startsWith("/share/") && req.method === "GET") {
      return await readShared(env, decodeURIComponent(path.slice("/share/".length)));
    }

    // Access config gates ONLY the browser JWT path (enforced inside
    // authenticate). Device-token auth is ours end to end and works without
    // it, which is what makes the native app testable before Access exists.
    const who = await authenticate(req, env, accessConfig(env));
    if (!who) return json({ error: "authentication required" }, 401);

    try {
      if (path === "/chat" && req.method === "POST") return await chat(req, env, who);
      if (path === "/sync/push" && req.method === "POST") return await syncPush(req, env, who);
      if (path === "/sync/pull" && req.method === "GET") return await syncPull(url, env, who);
      if (path === "/auth/device" && req.method === "POST") return await registerDevice(req, env, who);
      if (path === "/auth/mobile") return await authMobile(url, env, who);
      if (path === "/providers") {
        if (req.method === "GET") return json(await listProviders(env, who));
        if (req.method === "POST") {
          const r = await createProvider(env, who, await req.json());
          return "error" in r ? json(r, 400) : json(r);
        }
      }
      if (path === "/providers/discover" && req.method === "POST") {
        return json(await discoverModels(env, who, await req.json()));
      }
      if (path.startsWith("/providers/")) {
        const pid = decodeURIComponent(path.slice("/providers/".length));
        if (req.method === "PATCH") {
          const r = await updateProvider(env, who, pid, await req.json());
          return "error" in r ? json(r, 400) : json(r);
        }
        if (req.method === "DELETE") {
          const r = await deleteProvider(env, who, pid);
          return "error" in r ? json(r, 400) : json(r);
        }
      }
      if (path === "/models" && req.method === "GET") {
        return json({
          models: await listModels(env, who),
          // Image models are a different KIND of thing, not chat models, so
          // they travel in their own list. Mixing them into `models` would
          // put flux in the default-model picker, where choosing it produces
          // a provider error on the next message.
          imageModels: await listImageModels(env),
          ttsVoices: TTS_VOICES,
          defaultModel: await getDefaultModel(env, who),
          presets: PROVIDER_PRESETS,
        });
      }
      if (path === "/voice/tts" && req.method === "POST") {
        const b = (await req.json()) as { text?: string; voice?: string };
        const prefs = await getPrefs(env, who.userId);
        const r = await synthesize(env, b.text ?? "", b.voice || prefs.tts_voice);
        if ("error" in r) return json(r, 400);
        return new Response(r.audio as unknown as BodyInit, {
          headers: {
            "content-type": r.mime,
            // Per-user speech behind an auth check; a shared cache holding it
            // would serve one persons words to another.
            "cache-control": "private, no-store",
          },
        });
      }
      if (path === "/me/usage" && req.method === "GET") {
        return json(await usageToday(env, who.userId));
      }
      if (path === "/me/prefs") {
        if (req.method === "GET") {
          return json({
            ...(await getPrefs(env, who.userId)),
            // Whether a key EXISTS, never the key. A settings screen that can
            // display a credential is one that can leak it.
            hasAnalyticsKey: await hasAnalyticsKey(env, who.userId),
          });
        }
        if (req.method === "PUT") {
          const b = (await req.json()) as {
            tool_models?: Record<string, string>;
            auto_route?: boolean;
            auto_memory?: boolean;
            cfAnalyticsKey?: string;
            cfAccountId?: string;
          };
          if (b.cfAnalyticsKey !== undefined) {
            await setAnalyticsKey(env, who, b.cfAnalyticsKey);
          }
          if (b.cfAccountId !== undefined) {
            await env.DB.prepare(`UPDATE users SET cf_account_id = ? WHERE id = ?`)
              .bind(b.cfAccountId.trim(), who.userId)
              .run();
          }
          // Same shape as the GET, including the key flag. Omitting it made
          // the client default (false) win, so saving an unrelated preference
          // silently reported budget mode as unconfigured.
          return json({
            ...(await setPrefs(env, who, b)),
            hasAnalyticsKey: await hasAnalyticsKey(env, who.userId),
          });
        }
      }
      if (path === "/me/default-model" && req.method === "PUT") {
        const b = (await req.json()) as { model?: string };
        await setDefaultModel(env, who, b.model ?? "");
        return json({ ok: true });
      }
      // Minting and revoking a link is the owner's action, so unlike the
      // public read above this one sits behind authentication.
      if (path.startsWith("/chats/") && path.endsWith("/share") && req.method === "POST") {
        const convId = decodeURIComponent(
          path.slice("/chats/".length, path.length - "/share".length),
        );
        const body = (await req.json()) as { shared?: boolean };
        const r = await setShare(env, who, convId, body.shared !== false);
        return "error" in r ? json(r, 400) : json(r);
      }
      if (path === "/files" && req.method === "POST") {
        const r = await uploadFile(env, who, req);
        return "error" in r ? json(r, 400) : json(r);
      }
      if (path.startsWith("/files/") && req.method === "GET") {
        return await serveFile(env, who, decodeURIComponent(path.slice("/files/".length)));
      }
      if (path === "/voice") return await voice(req, env, who);
      if (path === "/runs" && req.method === "GET") return await listRuns(url, env, who);
      if (path.startsWith("/run/")) return await run(req, env, who, path);
      if (path === "/title" && req.method === "POST") return await title(req, env, who);
      if (path === "/home/tools") return await homeTools(env);
      if (path === "/home/call" && req.method === "POST") return await homeCall(req, env);
      if (path === "/mcp") return await handleMcpServer(req, env, who);
      const feature = await handleFeatures(req, env, who, ctx);
      if (feature) return feature;
      return json({ error: "not found" }, 404);
    } catch (e) {
      return json({ error: (e as Error).message }, 500);
    }
  },

  async scheduled(_event: ScheduledController, env: Env, ctx: ExecutionContext): Promise<void> {
    // Every 15 minutes: run any scheduled prompts that have come due. They go
    // through the same chat handler as a typed message — see schedules.ts.
    ctx.waitUntil(runDueSchedules(env, chat));
  },
} satisfies ExportedHandler<Env>;

/**
 * POST /chat — start a generation and stream it.
 *
 * The Worker no longer produces the stream; it creates a run, hands it to a
 * DetachedRun DO, and attaches to the result. That indirection is what makes
 * stop, reconnect, and the tool round trip possible at all: the request you
 * see here can die at any moment without taking the generation with it.
 *
 * Model resolution stays HERE, before the run exists, so "no usable model"
 * comes back as a 400 the user can act on rather than an error event inside a
 * run that then has to be cleaned up.
 */
async function chat(req: Request, env: Env, who: Principal): Promise<Response> {
  const body = (await req.json()) as {
    conversationId: string;
    messages: ChatMessage[];
    model?: string;
    tools?: unknown[];
    /** Route to the reasoning model and raise the effort — see routing.ts. */
    think?: boolean;
  };

  // A missing conversationId used to surface as an opaque D1_TYPE_ERROR from
  // the runs INSERT several lines below, which reads like a database fault
  // rather than a malformed request.
  if (!body.conversationId) {
    return json({ error: "conversationId is required" }, 400);
  }

  // Attachments become image parts BEFORE routing, so the vision override
  // sees a real image and can claim the turn.
  const expanded = await expandAttachments(env, body.messages);

  // What only the edge knows — persona, project instructions, profile,
  // memories, documents, artifacts — goes right after the device's own
  // system message, so both sit ahead of the conversation.
  const lastUser = [...body.messages].reverse().find((m) => m.role === "user");
  const lastUserText = typeof lastUser?.content === "string" ? lastUser.content : "";
  const context = await buildContext(env, who.userId, body.conversationId, lastUserText).catch(() => "");
  const firstNonSystem = expanded.findIndex((m) => m.role !== "system");
  const messages = context
    ? [
        ...expanded.slice(0, Math.max(firstNonSystem, 0)),
        { role: "system", content: context } as ChatMessage,
        ...expanded.slice(Math.max(firstNonSystem, 0)),
      ]
    : expanded;

  // The full routing stack: explicit pick, think mode, vision override,
  // difficulty escalation, saved default, then the budget cap over the top.
  // Honours which providers are actually enabled and keyed, so disabling
  // Cloudflare genuinely stops Cloudflare traffic rather than just hiding it
  // from the picker.
  const routed = await routeChat(env, who, {
    messages,
    requested: body.model,
    think: body.think,
  });
  if ("error" in routed) return json(routed, 400);

  const { providerId } = parseModelStr(routed.model, env.TIER2_MODEL);
  if (!(await resolveProvider(env, who.userId, providerId))) {
    return json({ error: `unknown provider ${providerId}` }, 400);
  }

  const fallbackModel = routed.fallbackModel;

  // Asked once per turn rather than cached: the agent can come and go, and a
  // stale list would either hide a tool that is available or offer one that is
  // not. It is a Durable Object read, not a network hop home.
  const home = await homeToolSurface(env);
  const mcp = await mcpToolSurface(env, who.userId).catch(() => ({ schemas: [], refs: [] }));
  // deep_research is built from the home agent's web tools; offering it while
  // they are offline would only produce an apology.
  const serverSchemas = serverToolSchemas().filter(
    (t) => home.names.includes("web_search") || (t as { function?: { name?: string } }).function?.name !== "deep_research",
  );

  const runId = crypto.randomUUID();
  await env.DB.prepare(
    `INSERT INTO runs (id, user_id, conv_id, status, model, started_at)
     VALUES (?, ?, ?, 'running', ?, ?)`,
  )
    .bind(runId, who.userId, body.conversationId, routed.model, Date.now())
    .run();

  const stub = env.RUN.get(env.RUN.idFromName(runId));
  await stub.fetch(
    new Request("https://do/start", {
      method: "POST",
      body: JSON.stringify({
        id: runId,
        userId: who.userId,
        convId: body.conversationId,
        model: routed.model,
        fallbackModel,
        messages,
        // Three sources merged here: the device advertises what it can do, the
        // edge adds what IT can do, and the home agent adds whatever it is
        // currently exposing on the LAN. Merging at this point rather than in
        // the client means a new server or home tool needs no app update to
        // exist — and when the agent is offline its tools are simply not
        // offered, so the model never calls something unreachable.
        tools: [...(body.tools ?? []), ...serverSchemas, ...home.schemas, ...mcp.schemas],
        homeTools: home.names,
        mcpTools: mcp.refs,
        attachmentKeys: lastUser?.attachments ?? [],
        lastUserText,
        // The DO receives messages with attachments already expanded into
        // data URLs, so the keys are gone by then — and edit_image needs a
        // key, not a data URL.
        sourceImageKeys: body.messages.flatMap((m) => m.attachments ?? []),
        reasoningEffort: routed.reasoningEffort,
        // Rendered as the first chunks, so a surprising model choice is
        // never silent. That visibility is the point of the override.
        notices: routed.notices,
      }),
    }),
  );

  // Returned straight through: the DO's SSE body streams to the client, and
  // if the client vanishes the DO simply loses one subscriber.
  return stub.fetch(new Request("https://do/attach"));
}

/**
 * Replace attachment keys with inline image parts.
 *
 * A private, authenticated /files URL is not something a model provider can
 * fetch, so the bytes have to travel in the request — an OpenAI-shaped
 * image_url carrying a data: URL is the one form every vision provider
 * accepts. Stable reached the same conclusion (uploads.image_data_url).
 *
 * Only images are inlined. A PDF would be megabytes of base64 that no vision
 * model can read, so non-images are named and left in R2.
 */
async function expandAttachments(
  env: Env,
  messages: ChatMessage[],
): Promise<ChatMessage[]> {
  const out: ChatMessage[] = [];

  for (const m of messages) {
    if (!m.attachments?.length) {
      out.push(m);
      continue;
    }

    const parts: unknown[] = [];
    const text = typeof m.content === "string" ? m.content : "";
    if (text) parts.push({ type: "text", text });

    for (const key of m.attachments) {
      const url = await dataUrl(env, key);
      if (url && url.startsWith("data:image/")) {
        parts.push({ type: "image_url", image_url: { url } });
      } else {
        const name = key.split("/").pop() ?? key;
        // The app also adds attached documents to the user's searchable
        // documents, so point the model at search_docs rather than leaving
        // it with a bare file name it cannot open.
        parts.push({
          type: "text",
          text: "[attached file: " + name + " — its contents are in the user's documents; use search_docs to read it]",
        });
      }
    }

    out.push({ ...m, content: parts.length ? parts : text });
  }
  return out;
}

async function syncPush(req: Request, env: Env, who: Principal): Promise<Response> {
  const body = (await req.json()) as PushRequest;
  return json(await push(env, who, body));
}

async function syncPull(url: URL, env: Env, who: Principal): Promise<Response> {
  const cursor = Number(url.searchParams.get("cursor") ?? 0);
  return json(await pull(env, who, Number.isFinite(cursor) ? cursor : 0));
}

async function registerDevice(req: Request, env: Env, who: Principal): Promise<Response> {
  const body = (await req.json()) as { name: string; platform: string };
  const deviceId = crypto.randomUUID();
  const now = Date.now();
  await env.DB.prepare(
    `INSERT INTO devices (id, user_id, name, platform, created_at, last_seen_at)
     VALUES (?, ?, ?, ?, ?, ?)`,
  )
    .bind(deviceId, who.userId, body.name, body.platform, now, now)
    .run();

  // Returned exactly once — only the hash is retained.
  const token = await issueDeviceToken(env, who.userId, deviceId);
  return json({ deviceId, token });
}

/**
 * GET /auth/mobile — the browser leg of native login.
 *
 * Reached only AFTER Cloudflare Access has authenticated the user, so `who`
 * is already a verified identity. Mints a one-time code and bounces back to
 * the app's custom scheme.
 *
 * The redirect deliberately carries no token — see migrations/0002 for why.
 */
async function authMobile(url: URL, env: Env, who: Principal): Promise<Response> {
  const challenge = url.searchParams.get("challenge") ?? "";
  const state = url.searchParams.get("state") ?? "";
  const name = url.searchParams.get("name") ?? "android device";

  // A missing challenge would silently downgrade the flow to "code alone is
  // sufficient", which is exactly the weakness PKCE exists to close.
  if (challenge.length < 32) {
    return json({ error: "missing or too-short PKCE challenge" }, 400);
  }

  const code = await issueAuthCode(env, who.userId, challenge, name);
  const target = `tomsense://auth?code=${encodeURIComponent(code)}&state=${encodeURIComponent(state)}`;

  // 302 with an HTML fallback: some in-app browsers refuse to follow a
  // redirect to a non-http scheme, and a dead-end blank page is a
  // maddening failure mode to debug on a phone.
  return new Response(
    `<!doctype html><meta charset="utf-8"><title>TomSense</title>
<meta http-equiv="refresh" content="0;url=${target}">
<body style="font-family:system-ui;padding:2rem">
<p>Signing you in…</p>
<p><a href="${target}">Tap here if nothing happens</a></p>`,
    { status: 302, headers: { location: target, "content-type": "text/html; charset=utf-8" } },
  );
}

/**
 * POST /auth/exchange — redeem a one-time code for a device token.
 *
 * Unauthenticated by design. Every failure returns the SAME error, because
 * distinguishing "expired" from "wrong verifier" tells an attacker which
 * half of the exchange they have.
 */
async function authExchange(req: Request, env: Env): Promise<Response> {
  const body = (await req.json().catch(() => null)) as {
    code?: string;
    verifier?: string;
    platform?: string;
  } | null;

  const code = body?.code ?? "";
  const verifier = body?.verifier ?? "";
  if (!code || verifier.length < 32) {
    return json({ error: "invalid code or verifier" }, 400);
  }

  const result = await redeemAuthCode(env, code, verifier, body?.platform ?? "android");
  if (!result) return json({ error: "invalid code or verifier" }, 400);

  return json(result);
}

async function voice(req: Request, env: Env, who: Principal): Promise<Response> {
  // One session per device, so a second device doesn't hijack an active call.
  const id = env.VOICE.idFromName(`${who.userId}:${who.deviceId}`);
  // Forward the authenticated principal. The DO needs it to resolve BYO-key
  // providers, and must not take it from the client's own payload.
  const fwd = new Request(req);
  fwd.headers.set("x-tomsense-user", who.userId);
  return env.VOICE.get(id).fetch(fwd);
}

/**
 * /run/{id}/{attach|cancel|tool_result|state} — reconnect, stop, feed tools.
 *
 * The action is re-derived and the request rebuilt rather than forwarded as-is,
 * so nothing from the caller's URL or headers reaches the DO by accident.
 */
const RUN_ACTIONS = new Set(["attach", "cancel", "tool_result", "state"]);

async function run(req: Request, env: Env, who: Principal, path: string): Promise<Response> {
  const [, , runId, action = "attach"] = path.split("/");
  if (!runId) return json({ error: "missing run id" }, 400);
  if (!RUN_ACTIONS.has(action)) return json({ error: `unknown run action ${action}` }, 404);

  // Verify ownership before addressing the DO — run ids are guessable enough
  // that reaching one directly should not be sufficient to read it.
  const owned = await env.DB.prepare(`SELECT 1 FROM runs WHERE id = ? AND user_id = ?`)
    .bind(runId, who.userId)
    .first();
  if (!owned) return json({ error: "not found" }, 404);

  const init: RequestInit = { method: req.method };
  if (req.method === "POST") init.body = await req.text();
  const fwd = new Request(`https://do/${action}`, init);
  // Preserved so /run/{id}/attach can still be upgraded to a WebSocket.
  const upgrade = req.headers.get("upgrade");
  if (upgrade) fwd.headers.set("upgrade", upgrade);

  return env.RUN.get(env.RUN.idFromName(runId)).fetch(fwd);
}

/** GET /runs?conv={id} — find a reconnectable generation after a cold start. */
async function listRuns(url: URL, env: Env, who: Principal): Promise<Response> {
  const conv = url.searchParams.get("conv");
  const stmt = conv
    ? env.DB.prepare(
        `SELECT id, conv_id, status, model, started_at, ended_at FROM runs
          WHERE user_id = ? AND conv_id = ? ORDER BY started_at DESC LIMIT 20`,
      ).bind(who.userId, conv)
    : env.DB.prepare(
        `SELECT id, conv_id, status, model, started_at, ended_at FROM runs
          WHERE user_id = ? AND status IN ('running','awaiting_tools')
          ORDER BY started_at DESC LIMIT 20`,
      ).bind(who.userId);

  const { results } = await stmt.all();
  return json({ runs: results });
}

/**
 * Name a conversation from its opening exchange.
 *
 * The utility model has had a "title" purpose since task_model.ts was written
 * and nothing ever called it, so every chat outside the assistant overlay
 * stayed "New chat" forever — and the overlay only truncated the first message
 * to 48 characters. This is the call that was missing.
 *
 * Runs on the task model rather than the chat model: it is a four-word
 * classification, and taskSession keeps one warm prefix per user so it is
 * mostly a cache hit. Failure returns null rather than an error, because a
 * chat with no title is a cosmetic problem and must never fail a turn.
 */
async function title(req: Request, env: Env, who: Principal): Promise<Response> {
  const body = (await req.json().catch(() => ({}))) as {
    question?: string;
    answer?: string;
  };
  const question = (body.question ?? "").trim();
  if (!question) return json({ error: "question required" }, 400);

  const prefs = await getPrefs(env, who.userId);
  const text = await runTaskModel(env, who, {
    purpose: "title",
    slots: prefs.tool_models,
    maxTokens: 16,
    prompt:
      "Write a title of at most six words for this conversation. " +
      "Plain words only: no quotes, no punctuation at the end, no prefix like " +
      '"Title:". Reply with the title and nothing else.\n\n' +
      `User: ${question.slice(0, 500)}\n` +
      (body.answer?.trim() ? `Assistant: ${body.answer.trim().slice(0, 500)}\n` : ""),
  });

  // Models add quotes and "Title:" prefixes no matter how firmly they are
  // asked not to, so strip rather than trust — and cap the length, because a
  // model that ignores "six words" should not produce a drawer row that runs
  // off the screen.
  const cleaned = (text ?? "")
    .replace(/^\s*title\s*:\s*/i, "")
    .replace(/^["'“”]+|["'“”.]+$/g, "")
    .trim()
    .slice(0, 60);

  return json({ title: cleaned || null });
}

/**
 * What the home agent is currently offering, as model-ready schemas.
 *
 * Returns empty when the agent is offline or unreachable, which is the
 * designed degradation rather than an error: LAN tools disappear and chat,
 * voice, history and memory carry on. Never throws, because a home agent
 * having a bad day must not be able to fail a chat turn.
 */
async function homeToolSurface(
  env: Env,
): Promise<{ schemas: unknown[]; names: string[] }> {
  try {
    const res = await homeTools(env);
    const body = (await res.json()) as {
      online?: boolean;
      tools?: { name: string; description: string; inputSchema: unknown }[];
    };
    if (!body.online || !body.tools?.length) return { schemas: [], names: [] };

    return {
      // The agent speaks MCP-shaped tool defs; the models here take the
      // OpenAI function shape. Translating at the boundary keeps the agent
      // free of any knowledge of which model is being served.
      schemas: body.tools.map((t) => ({
        type: "function",
        function: {
          name: t.name,
          description: t.description,
          parameters: t.inputSchema,
        },
      })),
      names: body.tools.map((t) => t.name),
    };
  } catch {
    return { schemas: [], names: [] };
  }
}

async function homeTools(env: Env): Promise<Response> {
  const id = env.HOMELINK.idFromName("default");
  return env.HOMELINK.get(id).fetch(new Request("https://do/tools"));
}

async function homeCall(req: Request, env: Env): Promise<Response> {
  const id = env.HOMELINK.idFromName("default");
  return env.HOMELINK.get(id).fetch(
    new Request("https://do/call", { method: "POST", body: await req.text() }),
  );
}
