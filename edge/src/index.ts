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
import { parseModelStr, resolveProvider, chatCompletionsUrl } from "./providers";
import { streamWithFallback } from "./stream";
import { push, pull, type PushRequest } from "./sync";
import {
  listProviders,
  createProvider,
  updateProvider,
  deleteProvider,
  listModels,
  getDefaultModel,
  setDefaultModel,
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

/** POST /chat — streams the text/reasoning/heartbeat/done contract as SSE. */
async function chat(req: Request, env: Env, who: Principal): Promise<Response> {
  const body = (await req.json()) as {
    conversationId: string;
    messages: ChatMessage[];
    model?: string;
    tools?: unknown[];
  };

  // Resolution order: what the client asked for → the user's saved default →
  // the Worker's built-in tier-2. The env var is now only a last-resort
  // bootstrap for an account that has chosen nothing, not the product's
  // answer to "which model am I using".
  const chosen = body.model || (await getDefaultModel(env, who)) || env.TIER2_MODEL;
  const { providerId, modelId } = parseModelStr(chosen, env.TIER2_MODEL);
  const provider = await resolveProvider(env, who.userId, providerId);
  if (!provider) return json({ error: `unknown provider ${providerId}` }, 400);

  // Tier-1 is the fallback for a stalled tier-2/3 model: cheap, fast, and
  // almost never the thing that stalls.
  const fb = parseModelStr(env.TIER1_MODEL, env.TIER1_MODEL);
  const fbProvider = await resolveProvider(env, who.userId, fb.providerId);

  const events = streamWithFallback(
    {
      provider,
      modelId,
      messages: body.messages,
      tools: body.tools,
      fallback: fbProvider ? { provider: fbProvider, modelId: fb.modelId } : undefined,
      ai: env.AI,
    },
    (p) => chatCompletionsUrl(p),
  );

  const encoder = new TextEncoder();
  const stream = new ReadableStream({
    async start(controller) {
      try {
        for await (const ev of events) {
          controller.enqueue(encoder.encode(`data: ${JSON.stringify(ev)}\n\n`));
          if (ev.type === "done") {
            await recordUsage(env, who, providerId, modelId, ev.usage);
          }
        }
      } catch (e) {
        const msg = { type: "text", text: `\n\n[edge error: ${(e as Error).message}]` };
        controller.enqueue(encoder.encode(`data: ${JSON.stringify(msg)}\n\n`));
      } finally {
        controller.enqueue(encoder.encode("data: [DONE]\n\n"));
        controller.close();
      }
    },
  });

  return new Response(stream, {
    headers: {
      "content-type": "text/event-stream",
      "cache-control": "no-cache",
      connection: "keep-alive",
    },
  });
}

async function recordUsage(
  env: Env,
  who: Principal,
  providerId: string,
  modelId: string,
  usage: { in: number; out: number; cache_read: number; cache_write: number },
): Promise<void> {
  const day = new Date().toISOString().slice(0, 10);
  await env.DB.prepare(
    `INSERT INTO usage_daily
       (user_id, day, provider_id, model_id, tokens_in, tokens_out, cache_read, cache_write, requests)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)
     ON CONFLICT(user_id, day, provider_id, model_id) DO UPDATE SET
       tokens_in   = tokens_in   + excluded.tokens_in,
       tokens_out  = tokens_out  + excluded.tokens_out,
       cache_read  = cache_read  + excluded.cache_read,
       cache_write = cache_write + excluded.cache_write,
       requests    = requests    + 1`,
  )
    .bind(who.userId, day, providerId, modelId, usage.in, usage.out, usage.cache_read, usage.cache_write)
    .run();
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

async function run(req: Request, env: Env, who: Principal, path: string): Promise<Response> {
  const runId = path.split("/")[2];
  if (!runId) return json({ error: "missing run id" }, 400);

  // Verify ownership before addressing the DO — run ids are guessable enough
  // that reaching one directly should not be sufficient to read it.
  const owned = await env.DB.prepare(`SELECT 1 FROM runs WHERE id = ? AND user_id = ?`)
    .bind(runId, who.userId)
    .first();
  if (!owned) return json({ error: "not found" }, 404);

  return env.RUN.get(env.RUN.idFromName(runId)).fetch(req);
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
