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
import { parseModelStr, resolveProvider } from "./providers";
import { push, pull, type PushRequest } from "./sync";
import {
  listProviders,
  createProvider,
  updateProvider,
  deleteProvider,
  listModels,
  getDefaultModel,
  setDefaultModel,
  resolveChatModel,
  resolveFallbackModel,
  discoverModels,
  PROVIDER_PRESETS,
} from "./providers_api";

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
          defaultModel: await getDefaultModel(env, who),
          presets: PROVIDER_PRESETS,
        });
      }
      if (path === "/me/default-model" && req.method === "PUT") {
        const b = (await req.json()) as { model?: string };
        await setDefaultModel(env, who, b.model ?? "");
        return json({ ok: true });
      }
      if (path === "/voice") return await voice(req, env, who);
      if (path === "/runs" && req.method === "GET") return await listRuns(url, env, who);
      if (path.startsWith("/run/")) return await run(req, env, who, path);
      if (path === "/home/tools") return await homeTools(env);
      if (path === "/home/call" && req.method === "POST") return await homeCall(req, env);
      return json({ error: "not found" }, 404);
    } catch (e) {
      return json({ error: (e as Error).message }, 500);
    }
  },

  async scheduled(_event: ScheduledController, _env: Env, _ctx: ExecutionContext): Promise<void> {
    // Cron: scheduled prompts + memory decay (spec §9). Not yet implemented —
    // M7. Left as an explicit stub so the trigger binding is wired and the gap
    // is visible rather than silently missing.
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
  };

  // Honours which providers are actually enabled and keyed, so disabling
  // Cloudflare genuinely stops Cloudflare traffic rather than just hiding it
  // from the picker.
  const picked = await resolveChatModel(env, who, body.model);
  if ("error" in picked) return json(picked, 400);

  const { providerId } = parseModelStr(picked.model, env.TIER2_MODEL);
  if (!(await resolveProvider(env, who.userId, providerId))) {
    return json({ error: `unknown provider ${providerId}` }, 400);
  }

  // Stall fallback, skipped entirely when nothing suitable is available.
  const fallbackModel = await resolveFallbackModel(env, who, picked.model);

  const runId = crypto.randomUUID();
  await env.DB.prepare(
    `INSERT INTO runs (id, user_id, conv_id, status, model, started_at)
     VALUES (?, ?, ?, 'running', ?, ?)`,
  )
    .bind(runId, who.userId, body.conversationId, picked.model, Date.now())
    .run();

  const stub = env.RUN.get(env.RUN.idFromName(runId));
  await stub.fetch(
    new Request("https://do/start", {
      method: "POST",
      body: JSON.stringify({
        id: runId,
        userId: who.userId,
        convId: body.conversationId,
        model: picked.model,
        fallbackModel,
        messages: body.messages,
        tools: body.tools,
      }),
    }),
  );

  // Returned straight through: the DO's SSE body streams to the client, and
  // if the client vanishes the DO simply loses one subscriber.
  return stub.fetch(new Request("https://do/attach"));
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
